#!/usr/bin/env python3
"""Build external reviewed packaging and smoke its CPU HTTP boundary, without pricing.

Explicit local inputs only. No checkout, registry push, engine edits or cluster access.
This does not qualify portfolio/price, financial risk, or the RI-03 pinned-image profile.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*cmd, **kwargs):
    return subprocess.check_output([str(c) for c in cmd], **kwargs)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--engine-source', type=Path, required=True)
    p.add_argument('--revision', required=True, help='reviewed full 40-character commit SHA')
    p.add_argument('--evidence', type=Path, required=True, help='new directory outside either checkout')
    a = p.parse_args()
    engine, evidence = a.engine_source.resolve(), a.evidence.resolve()
    require(re.fullmatch(r'[0-9a-f]{40}', a.revision), 'revision must be a full lowercase commit SHA')
    require(run('git', '-C', engine, 'rev-parse', 'HEAD').decode().strip() == a.revision,
            'external checkout revision mismatch')
    require(not run('git', '-C', engine, 'status', '--porcelain', '--untracked-files=no').strip(),
            'external tracked source must be clean')
    require(not any(evidence.is_relative_to(repo) for repo in (ROOT, engine)),
            'evidence must stay outside source checkouts')
    require(not evidence.exists(), 'use a new evidence directory')
    for file in ('container/build.py', 'container/inspect_image.py', 'Dockerfile'):
        require((engine/file).is_file(), f'missing reviewed packaging: {file}')
    evidence.mkdir(parents=True, mode=0o700)
    tag = 'traderx-ri08-smoke:' + uuid.uuid4().hex
    name = 'traderx-ri08-' + uuid.uuid4().hex
    # Use the owner's allowlisted, committed-source build instead of a second Dockerfile.
    subprocess.run([sys.executable, str(engine/'container/build.py'), '--tag', tag,
                    '--evidence', str(evidence/'build')], check=True)
    inspection = json.loads(run('docker', 'image', 'inspect', tag))[0]
    require(inspection['Os'] == 'linux' and inspection['Architecture'] == 'amd64', 'wrong platform')
    require(inspection['Config']['Labels']['org.opencontainers.image.revision'] == a.revision,
            'image provenance mismatch')
    image = inspection['Id']
    subprocess.run([sys.executable, str(engine/'container/inspect_image.py'), '--image', image,
                    '--output', str(evidence/'layers.json')], check=True)
    created = False
    try:
        # No published ports, no network, no user state mounts. Only this container is removed.
        run('docker', 'create', '--name', name, '--platform', 'linux/amd64', '--network', 'none',
            '--cpus', '2', '--memory', '2g', '--pids-limit', '256', '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
            '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=268435456,mode=1777',
            '--tmpfs', '/data/inputs:ro,nosuid,nodev,noexec,size=1048576,mode=755',
            '--tmpfs', '/data/eod-store:rw,nosuid,nodev,noexec,size=16777216,uid=10001,gid=10001,mode=700', image)
        created = True
        run('docker', 'start', name)
        deadline = time.monotonic() + 120
        while True:
            state = json.loads(run('docker', 'inspect', name))[0]['State']
            require(state['Running'], 'container exited before readiness')
            if state.get('Health', {}).get('Status') == 'healthy':
                break
            require(time.monotonic() < deadline, 'container readiness timeout')
            time.sleep(1)
        probe = '''import json, urllib.request, urllib.error
from pathlib import Path
with urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=5) as r:
    assert r.status == 200 and json.load(r) == {'status': 'ok'}
with urllib.request.urlopen('http://127.0.0.1:8000/openapi.json', timeout=5) as r:
    assert {'/eod/price', '/portfolio/price'} <= set(json.load(r)['paths'])
with urllib.request.urlopen('http://127.0.0.1:8000/eod/schemas/result', timeout=5) as r:
    schema = r.read()
req = urllib.request.Request('http://127.0.0.1:8000/eod/price',
    data=json.dumps({'bundlePath': '/data/inputs/absent'}).encode(), headers={'Content-Type': 'application/json'})
try:
    urllib.request.urlopen(req, timeout=10)
except urllib.error.HTTPError as e:
    assert e.code == 422, e.code
else:
    raise AssertionError('missing bundle was accepted')
import hashlib
print(json.dumps({'schema_sha256': hashlib.sha256(schema).hexdigest(),
                  'build': json.loads(Path('/opt/risk/build.json').read_text()), 'missing_bundle_http': 422}))
'''
        result = json.loads(run('docker', 'exec', name, 'python', '-c', probe))
        schema = ROOT/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/container-result-schema.json'
        require(result['schema_sha256'] == hashlib.sha256(schema.read_bytes()).hexdigest(),
                'served result schema differs from TraderX consumer pin')
        require(result['build']['source_revision'] == a.revision, 'in-image build revision mismatch')
        result.update(image=image, source_revision=a.revision, scope='packaging and HTTP refusal only',
                      usableForRisk=False, portfolioRiskAvailable=False)
        (evidence/'smoke.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    finally:
        if created:
            try:
                (evidence/'container.log').write_bytes(run('docker', 'logs', name, stderr=subprocess.STDOUT))
            finally:
                run('docker', 'rm', '-f', name)


if __name__ == '__main__':
    main()
