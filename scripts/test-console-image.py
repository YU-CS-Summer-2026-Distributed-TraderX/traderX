#!/usr/bin/env python3
"""Check the installed console image against its source; no bind mounts or credentials.

Requires Docker and local Node (vm.SourceTextModule). Exercises static ESM dependency
closure and the image's default command. Does not prove Kubernetes or bridge access.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid

GRAPH = r"""
const fs = require('node:fs'), path = require('node:path');
const {SourceTextModule} = require('node:vm');
const {createHash} = require('node:crypto');
const root = path.resolve(process.argv[1]), found = {};
function visit(name) {
  const full = path.resolve(root, name);
  if (!full.startsWith(root + path.sep)) throw Error('Import outside runtime: ' + name);
  const rel = path.relative(root, full);
  if (Object.hasOwn(found, rel)) return;
  const bytes = fs.readFileSync(full);
  found[rel] = createHash('sha256').update(bytes).digest('hex');
  for (const spec of new SourceTextModule(bytes.toString(), {identifier: full}).dependencySpecifiers) {
    if (spec.startsWith('node:')) continue;
    if (!spec.startsWith('.')) throw Error('Unexpected package import: ' + spec);
    visit(path.relative(root, path.resolve(path.dirname(full), spec)));
  }
}
visit('server.mjs');
console.log(JSON.stringify(found));
"""


def run(*args, timeout=30):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=timeout).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image')
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1] / 'web-front-end-console')
    parser.add_argument('--evidence', type=Path, required=True, help='New evidence directory (must not exist)')
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = {'requested_image': args.image, 'checks': [], 'profile': 'owned ephemeral container; read-only; 256 MiB; 1 CPU; loopback HTTP; LOCAL_ONLY=1; no mounts/credentials'}
    container = None
    passed = False
    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        report['checks'].append(name)
    try:
        source = json.loads(run('node', '--experimental-vm-modules', '-e', GRAPH, str(args.source.resolve())))
        report['source_modules'] = source
        meta = json.loads(run('docker', 'image', 'inspect', args.image))[0]
        identity = meta['Id']
        report.update(image_id=identity, architecture=meta['Architecture'], command=meta['Config']['Cmd'])
        check('default server command', meta['Config']['Cmd'] == ['node', 'server.mjs'])
        container = run('docker', 'create', '--name', 'lr05-console-test-' + uuid.uuid4().hex[:12],
                        '--read-only', '--memory', '256m', '--cpus', '1', '--pids-limit', '64',
                        '-p', '127.0.0.1::8080', '-e', 'LOCAL_ONLY=1', '-e', 'EDGE_PROXY=127.0.0.1:1',
                        '-e', 'FIX_HOST=127.0.0.1', '-e', 'FIX_PORT=1', identity)
        report['container_id'] = container
        run('docker', 'start', container)
        mapping = run('docker', 'port', container, '8080/tcp')
        check('owned loopback port', mapping.startswith('127.0.0.1:') and '\n' not in mapping)
        report['loopback'] = mapping
        # Disable host HTTP proxy settings for loopback requests.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def get(route):
            try:
                response = opener.open('http://' + mapping + route, timeout=2)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, response.headers, response.read()
        deadline = time.monotonic() + 30
        while True:
            state = json.loads(run('docker', 'inspect', container))[0]['State']
            if not state['Running']:
                raise AssertionError('default command exited: ' + json.dumps(state))
            try:
                status, _, body = get('/healthz')
                if status == 200 and json.loads(body) == {'ok': True}:
                    break
            except (OSError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise AssertionError('health endpoint did not become ready in 30 seconds')
            time.sleep(0.25)
        check('installed server health', True)
        installed = json.loads(run('docker', 'exec', container, 'node', '--experimental-vm-modules', '-e', GRAPH, '/app'))
        report['installed_modules'] = installed
        check('complete installed static ESM graph matches source bytes', installed == source)
        status, headers, body = get('/')
        check('built Angular index served', status == 200 and 'text/html' in headers.get('Content-Type', '') and b'<app-root' in body)
        scripts = re.findall(rb'<script\b[^>]*\bsrc="([^"]+\.js)"', body)
        check('Angular script references present', bool(scripts))
        for raw in scripts:
            asset = raw.decode()
            check('local asset path ' + asset, not asset.startswith('//') and ':' not in asset and '..' not in asset)
            status, headers, content = get('/' + asset.lstrip('/'))
            check('installed JavaScript served ' + asset, status == 200 and 'javascript' in headers.get('Content-Type', '') and len(content) > 100 and b'<!doctype html' not in content.lower())
        status, _, body = get('/auth/me')
        check('server signed-out endpoint', status == 401 and json.loads(body).get('code') == 'signed_out')
        status, _, body = get('/gcs/list')
        check('local cloud reads disabled', status == 503 and 'disabled' in json.loads(body).get('error', ''))
        for tool in ('gcloud', 'kubectl'):
            run('docker', 'exec', container, 'sh', '-c', 'command -v ' + tool)
            check(tool + ' bridge executable retained', True)
        passed = True
    except Exception as error:
        report['error'] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            report['command_stderr'] = error.stderr
    finally:
        if container:
            try:
                logs = subprocess.run(['docker', 'logs', container], text=True, capture_output=True, timeout=15)
                (args.evidence / 'container.log').write_text(logs.stdout + logs.stderr)
                run('docker', 'rm', '-f', container)
                report['cleanup'] = 'owned container removed'
            except Exception as error:
                report['cleanup_error'] = str(error)
                passed = False
        report['passed'] = passed
        report['check_count'] = len(report['checks'])
        (args.evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
