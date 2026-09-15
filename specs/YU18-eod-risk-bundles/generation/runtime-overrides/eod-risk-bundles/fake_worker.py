#!/usr/bin/env python3
"""Local fake worker for provisional HTTP recovery tests. No pricing or remote file reads."""
import argparse
import base64
import fcntl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import threading
import uuid

import bundle
from coordinator import HTTP_PROFILE, MockAdapter, decode_json, load_json, private_directory
from http_adapter import MAX_BYTES, WIRE, workload


class Store:
    def __init__(self, root):
        self.root = Path(root).absolute()
        bundle.require(not any(p.is_symlink() for p in (self.root, *self.root.parents)), 'symlink worker root')
        bundle.require(not any((p / '.git').exists() for p in (self.root, *self.root.parents)),
                       'worker state must be outside Git')
        private_directory(self.root)
        fd = os.open(self.root / 'worker.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        self.lockfile = os.fdopen(fd, 'w')
        try:
            fcntl.flock(self.lockfile, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BaseException:
            self.lockfile.close()
            raise
        self.mutex = threading.Lock()
        self.auto_complete = True
        self.executions = 0

    def close(self):
        self.lockfile.close()

    def submit(self, request):
        bundle.require(isinstance(request, dict) and set(request) == {
            'schema', 'workloadKey', 'profile', 'clientAttemptId', 'bundle'}, 'invalid submit fields')
        bundle.require(request['schema'] == WIRE and request['profile'] == HTTP_PROFILE,
                       'unsupported fake-worker contract')
        bundle.require(isinstance(request['clientAttemptId'], str)
                       and re.fullmatch('[0-9a-f]{32}', request['clientAttemptId']), 'invalid client attempt')
        value = request['bundle']
        bundle.require(isinstance(value, dict) and set(value) == {'manifest', 'positions', 'contracts'},
                       'invalid inline bundle')
        payloads = {k: base64.b64decode(value[k], validate=True) for k in ('positions', 'contracts')}
        m = value['manifest']
        expected = bundle.manifest_for(payloads, m['clusterEpoch'], m['valuationTime'], m['inputOrigin'])
        bundle.require(m == expected and request['workloadKey'] == workload(expected), 'input identity mismatch')
        key = request['workloadKey']
        path = self.root / key
        # This record is sufficient to recover accepted work; a repeated client attempt is not new work.
        normalized = {k: v for k, v in request.items() if k != 'clientAttemptId'}
        with self.mutex:
            if path.exists():
                bundle.require(load_json(path / 'request.json') == normalized, 'workload collision')
            else:
                bundle.publish_directory(path, {'request.json': bundle.encoded(normalized),
                                                'worker.json': bundle.encoded({'workerAttemptId': uuid.uuid4().hex})})
            return self._lookup(key)

    def lookup(self, key):
        bundle.require(re.fullmatch('[0-9a-f]{64}', key), 'invalid workload key')
        with self.mutex:
            return self._lookup(key)

    def _lookup(self, key):
        path = self.root / key
        if not path.exists():
            return None
        bundle.require(path.is_dir() and not path.is_symlink(), 'invalid worker workload directory')
        request = load_json(path / 'request.json')
        identity = load_json(path / 'worker.json')
        response = {'schema': WIRE, 'workloadKey': key, 'profile': HTTP_PROFILE,
                    'workerAttemptId': identity['workerAttemptId'], 'status': 'RUNNING'}
        source, result = path / 'input', path / 'result'
        if self.auto_complete and not result.exists():
            value = request['bundle']
            if not source.exists():
                bundle.publish_directory(source, {'manifest.json': bundle.encoded(value['manifest']), **{
                    k+'.csv': base64.b64decode(value[k], validate=True) for k in ('positions', 'contracts')}})
            manifest, _ = bundle.validate(source)
            bundle.require(workload(manifest) == key, 'stored workload mismatch')
            bundle.mock(source, result)
            self.executions += 1
        if result.exists():
            MockAdapter().validate_result(source, result)
            data = load_json(result / 'results.json')
            response.update(status='MOCK_COMPLETE', result=data,
                            resultSha256=bundle.digest(bundle.encoded(data)))
        return response


def handler(store):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *args):
            pass  # Do not print portfolio requests or response bodies.

        def reply(self, code, value):
            data = bundle.encoded(value)
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            try:
                if self.path == '/capabilities':
                    self.reply(200, {'schema': WIRE, 'profile': HTTP_PROFILE, 'pricing': False})
                elif self.path.startswith('/risk/results/by-workload/'):
                    data = store.lookup(self.path.rsplit('/', 1)[1])
                    self.reply(404 if data is None else 200, data or {'error': 'unknown workload'})
                else:
                    self.reply(404, {'error': 'unknown route'})
            except (ValueError, OSError, KeyError, TypeError):
                self.reply(500, {'error': 'worker state invalid'})

        def do_POST(self):
            try:
                if self.path != '/risk/jobs':
                    self.reply(404, {'error': 'unknown route'})
                    return
                length = int(self.headers.get('Content-Length', '-1'))
                bundle.require(0 < length <= MAX_BYTES and not self.headers.get('Transfer-Encoding'),
                               'invalid request length')
                raw = self.rfile.read(length)
                bundle.require(len(raw) == length, 'truncated request')
                self.reply(202, store.submit(decode_json(raw)))
            except (ValueError, OSError, KeyError, TypeError):
                self.reply(400, {'error': 'invalid fake-worker submission'})
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', required=True)
    parser.add_argument('--port', type=int, default=0)
    args = parser.parse_args()
    os.umask(0o077)
    store = Store(args.state)
    try:
        with ThreadingHTTPServer(('127.0.0.1', args.port), handler(store)) as server:
            print(json.dumps({'url': f'http://127.0.0.1:{server.server_port}', 'pricing': False}), flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
    finally:
        store.close()


if __name__ == '__main__':
    main()
