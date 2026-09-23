import base64
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle
import coordinator
import fake_worker
import http_adapter
from test_bundle import fixture, EQUITY, SWAP


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.inbox = self.root / 'inbox'
        self.inbox.mkdir()
        p, c = self.root / 'p.csv', self.root / 'c.csv'
        p.write_bytes(fixture('positions', [EQUITY]))
        c.write_bytes(fixture('contracts', [SWAP]))
        self.manifest = bundle.build(p, c, self.inbox / 'one', 'synthetic-http-test',
                                     '2025-06-02T20:00:00Z', 'synthetic')
        self.server = self.store = None
        self.fault = None
        self.posts = 0
        self.release_response = threading.Event()
        self.start()
        self.addCleanup(self.stop)

    def start(self):
        self.store = fake_worker.Store(self.root / 'worker')
        owner = self
        class Handler(fake_worker.handler(self.store)):
            def do_POST(self):
                owner.posts += 1
                super().do_POST()

            def reply(self, code, value):
                if owner.fault == 'timeout':
                    owner.release_response.wait(2)
                    return
                if owner.fault == 'drop-post' and self.command == 'POST' and code == 202:
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                if owner.fault == 'unavailable':
                    code, value = 503, {'error': 'temporary'}
                if owner.fault == 'redirect':
                    self.send_response(302)
                    self.send_header('Location', 'http://example.invalid/forbidden')
                    self.end_headers()
                    return
                if isinstance(value, dict) and value.get('status') == 'MOCK_COMPLETE':
                    if owner.fault == 'failed':
                        value = {k: v for k, v in value.items() if k not in ('result', 'resultSha256')}
                        value.update(status='FAILED', reason='FAKE_COMPUTE_FAILURE')
                    if owner.fault == 'wrong-workload': value['workloadKey'] = 'a'*64
                    if owner.fault == 'wrong-hash': value['resultSha256'] = 'b'*64
                    if owner.fault == 'financial':
                        value['result']['items'][0]['npv'] = 100
                        value['resultSha256'] = bundle.digest(bundle.encoded(value['result']))
                    if owner.fault == 'missing-row':
                        value['result']['items'].pop()
                        value['resultSha256'] = bundle.digest(bundle.encoded(value['result']))
                super().reply(code, value)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': 0.01})
        self.thread.start()
        self.adapter = http_adapter.HttpAdapter(f'http://127.0.0.1:{self.server.server_port}', polls=1, interval=0)

    def stop(self):
        self.release_response.set()
        if self.server:
            self.server.shutdown()
            self.thread.join(timeout=5)
            self.server.server_close()
            self.store.close()
            self.server = None

    def run_job(self, discover=True):
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            if discover: ctl.discover(self.inbox)
            return ctl.run()['jobs'][0]

    def test_real_http_success_dedup_and_offline_status(self):
        with patch.dict(os.environ, {'HTTP_PROXY': 'http://127.0.0.1:1', 'NO_PROXY': ''}):
            job = self.run_job()
        self.assertEqual(job['status'], 'MOCK_COMPLETE')
        self.assertTrue(job['selectedMockResult'])
        self.assertFalse(job['usableForRisk'])
        self.assertEqual(self.store.executions, 1)
        self.assertEqual(len(self.run_job()['attempts']), 1)
        self.assertEqual(self.posts, 1)
        self.stop()
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            self.assertEqual(ctl.status()['jobs'][0]['resultIntegrity'], 'VERIFIED')

    def test_lost_submit_reply_recovers_same_attempt_by_workload(self):
        self.fault = 'drop-post'
        first = self.run_job()
        self.assertEqual(first['status'], 'RUNNING')
        self.assertIn('REMOTE_PENDING', first['error'])
        self.assertEqual(self.store.executions, 1)
        self.fault = None
        final = self.run_job(False)
        self.assertEqual(final['status'], 'MOCK_COMPLETE')
        self.assertEqual(final['attempts'][0]['attempt_id'], first['attempts'][0]['attempt_id'])
        self.assertEqual(len(final['attempts']), 1)
        self.assertEqual(self.posts, 1)
        self.assertEqual(self.store.executions, 1)

    def test_running_job_survives_worker_restart_before_result(self):
        self.store.auto_complete = False
        first = self.run_job()
        key = http_adapter.workload(self.manifest)
        identity = json.loads((self.root / 'worker' / key / 'worker.json').read_text())
        self.assertEqual(first['status'], 'RUNNING')
        self.assertEqual(self.store.executions, 0)
        self.stop()
        self.start()
        final = self.run_job(False)
        self.assertEqual(final['status'], 'MOCK_COMPLETE')
        self.assertEqual(len(final['attempts']), 1)
        self.assertEqual(self.posts, 1)
        transport = json.loads((self.root / 'state' / final['result_path'] / 'transport.json').read_text())
        self.assertEqual(transport['workerAttemptId'], identity['workerAttemptId'])

    def test_worker_restart_after_publication_does_not_recompute(self):
        self.fault = 'drop-post'
        self.run_job()
        self.assertEqual(self.store.executions, 1)
        self.stop()
        self.fault = None
        self.start()
        self.assertEqual(self.run_job(False)['status'], 'MOCK_COMPLETE')
        self.assertEqual(self.store.executions, 0)
        self.assertEqual(self.posts, 1)

    def crash(self, after_local):
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            ctl.discover(self.inbox)
        code = '''
import os, sys
sys.path.insert(0, sys.argv[1])
import coordinator, http_adapter
class Crash(http_adapter.HttpAdapter):
    def request(self, method, path, body=None):
        result = super().request(method,path,body)
        if method == 'POST' and sys.argv[4] == 'remote': os._exit(17)
        return result
    def execute(self, source, destination):
        super().execute(source,destination)
        os._exit(17)
with coordinator.Coordinator(sys.argv[2], Crash(sys.argv[3])) as ctl: ctl.run()
'''
        result = subprocess.run([sys.executable, '-c', code, str(Path(coordinator.__file__).parent),
                                 str(self.root / 'state'), self.adapter.endpoint,
                                 'local' if after_local else 'remote'], capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 17, result.stderr)

    def test_coordinator_process_exit_after_remote_publication(self):
        self.crash(False)
        final = self.run_job(False)
        self.assertEqual(final['status'], 'MOCK_COMPLETE')
        self.assertEqual(len(final['attempts']), 1)
        self.assertEqual(self.posts, 1)
        self.assertEqual(self.store.executions, 1)

    def test_coordinator_process_exit_after_local_publication_worker_offline(self):
        self.crash(True)
        self.stop()
        final = self.run_job(False)
        self.assertEqual(final['status'], 'MOCK_COMPLETE')
        self.assertEqual(len(final['attempts']), 1)

    def test_unavailable_worker_does_not_submit_or_claim_failure(self):
        self.fault = 'unavailable'
        first = self.run_job()
        self.assertEqual(first['status'], 'RUNNING')
        self.assertFalse(first['selectedMockResult'])
        self.assertEqual(self.posts, 0)
        self.fault = None
        self.assertEqual(self.run_job(False)['status'], 'MOCK_COMPLETE')

    def test_redirects_are_refused(self):
        self.fault = 'redirect'
        job = self.run_job()
        self.assertEqual(job['status'], 'FAILED')
        self.assertIn('HTTP 302', job['error'])
        self.assertEqual(self.posts, 0)

    def test_socket_timeout_keeps_attempt_recoverable(self):
        self.fault = 'timeout'
        self.adapter.timeout = 0.02
        job = self.run_job()
        self.assertEqual(job['status'], 'RUNNING')
        self.assertEqual(self.posts, 0)
        self.release_response.set()
        self.fault = None
        self.adapter.timeout = 2
        self.assertEqual(self.run_job(False)['status'], 'MOCK_COMPLETE')

    def test_worker_declared_failure_requires_explicit_retry(self):
        self.fault = 'failed'
        job = self.run_job()
        self.assertEqual(job['status'], 'FAILED')
        self.assertIn('FAKE_COMPUTE_FAILURE', job['error'])
        self.fault = None
        self.assertEqual(self.run_job(False)['status'], 'FAILED')
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            ctl.retry(job['job_id'])
            final = ctl.run()['jobs'][0]
        self.assertEqual(final['status'], 'MOCK_COMPLETE')
        self.assertEqual(len(final['attempts']), 2)
        self.assertEqual(self.posts, 1)

    def test_remote_endpoint_refused(self):
        for endpoint in ('https://127.0.0.1:1', 'http://example.com:80', 'http://localhost:80',
                         'http://127.0.0.1:80/path', 'http://user:pass@127.0.0.1:80'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                http_adapter.HttpAdapter(endpoint)

    def test_wrong_identity_hash_or_financial_claim_never_accepted(self):
        for fault in ('wrong-workload', 'wrong-hash', 'financial', 'missing-row'):
            with self.subTest(fault=fault):
                self.fault = fault
                with coordinator.Coordinator(self.root / ('state-' + fault), self.adapter) as ctl:
                    ctl.discover(self.inbox)
                    job = ctl.run()['jobs'][0]
                    self.assertEqual(job['status'], 'FAILED')
                    self.assertFalse(job['selectedMockResult'])
                    self.assertFalse(job['usableForRisk'])

    def test_profile_is_distinct_and_existing_state_cannot_switch(self):
        with coordinator.Coordinator(self.root / 'state') as ctl:
            local_key = ctl.discover(self.inbox)['queued'][0]
        self.assertNotEqual(local_key, http_adapter.workload(self.manifest))
        with self.assertRaisesRegex(ValueError, 'another adapter profile'):
            with coordinator.Coordinator(self.root / 'state', self.adapter): pass
        self.assertEqual(self.posts, 0)

    def test_transport_provenance_tampering_invalidates_status(self):
        job = self.run_job()
        path = self.root / 'state' / job['result_path'] / 'transport.json'
        data = json.loads(path.read_text())
        data['workerAttemptId'] = 'f'*32
        path.write_bytes(bundle.encoded(data))
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            job = ctl.status()['jobs'][0]
            self.assertEqual(job['resultIntegrity'], 'INVALID')
            self.assertFalse(job['selectedMockResult'])

    def test_response_limit_prevents_submission(self):
        with patch.object(http_adapter, 'MAX_BYTES', 32):
            job = self.run_job()
        self.assertEqual(job['status'], 'FAILED')
        self.assertIn('byte limit', job['error'])
        self.assertEqual(self.posts, 0)

    def test_transport_symlink_rejected_even_with_identical_bytes(self):
        job = self.run_job()
        path = self.root / 'state' / job['result_path'] / 'transport.json'
        other = self.root / 'other.json'
        path.rename(other)
        path.symlink_to(other)
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            self.assertEqual(ctl.status()['jobs'][0]['resultIntegrity'], 'INVALID')

    def test_cli_pending_exit_code_and_resume(self):
        self.store.auto_complete = False
        with coordinator.Coordinator(self.root / 'state', self.adapter) as ctl:
            ctl.discover(self.inbox)
        args = [sys.executable, str(Path(coordinator.__file__)), '--state', str(self.root / 'state'),
                '--http-worker', self.adapter.endpoint, 'run']
        pending = subprocess.run(args, capture_output=True, timeout=10)
        self.assertEqual(pending.returncode, 2, (pending.stderr, pending.stdout))
        self.assertEqual(json.loads(pending.stdout)['jobs'][0]['status'], 'RUNNING')
        self.store.auto_complete = True
        done = subprocess.run(args, capture_output=True, timeout=10)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(len(json.loads(done.stdout)['jobs'][0]['attempts']), 1)

    def test_worker_refuses_changed_source_bytes(self):
        request = {'schema': http_adapter.WIRE, 'profile': coordinator.HTTP_PROFILE,
                   'workloadKey': http_adapter.workload(self.manifest), 'clientAttemptId': 'a'*32,
                   'bundle': {'manifest': self.manifest, **{
                       k: base64.b64encode((self.inbox / 'one' / (k+'.csv')).read_bytes()).decode()
                       for k in ('positions', 'contracts')}}}
        request['bundle']['positions'] = base64.b64encode(b'changed').decode()
        with self.assertRaisesRegex(ValueError, 'HTTP 400'):
            self.adapter.request('POST', '/risk/jobs', request)
        self.assertEqual(self.store.executions, 0)


if __name__ == '__main__':
    unittest.main()
