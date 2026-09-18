"""Proposed acceptance checks; adapter binding for the current JAX EOD API."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor

args = argparse.ArgumentParser()
args.add_argument('--engine', required=True, type=Path)
options = args.parse_args()
engine = options.engine.resolve()
print('Engine revision:', subprocess.check_output(['git', '-C', str(engine), 'rev-parse', 'HEAD'], text=True).strip(), flush=True)
sys.dont_write_bytecode = True
sys.path.insert(0, str(engine))
store_root = tempfile.TemporaryDirectory(prefix='integration-contract-')
os.environ['JAX_EOD_STORE_ROOT'] = store_root.name
from fastapi.testclient import TestClient
from engine.api.app import create_app
from engine.api import eod_routes as routes
from engine.integration.publication import ResultStore
from engine.integration.workload import AttemptStore

class Acceptance(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='integration-attempt-')
        self.original_store = routes.STORE
        routes.STORE = AttemptStore(store=ResultStore(Path(self.directory.name)))
        self.client = TestClient(create_app(), raise_server_exceptions=False)
        self.pricer = routes.price_bundle

    def tearDown(self):
        routes.price_bundle = self.pricer
        routes.STORE = self.original_store
        self.client.close()
        self.directory.cleanup()

    def submit(self, case='bill', sid='one', **options):
        return self.client.post('/eod/price', json={
            'bundlePath': str(engine / 'tests/fixtures/traderx-eod' / case / 'v2'),
            'marketInputs': {'mode': 'assumed-profile', 'assumedProfileId': 'flat-3pct-v1'},
            'submissionId': sid, **options})

    def test_A01_healthy_assumed_bill(self):
        response = self.submit()
        self.assertEqual(response.status_code, 200, response.text)
        result = response.json()['result']
        self.assertEqual(result['marketProvenance'], 'assumed')
        self.assertEqual(len(result['items']), 2)
        for item in result['items']:
            self.assertEqual(item['calculations']['npv']['status'], 'ok')

    def test_A02_cached_destination_still_checks_submission_binding(self):
        self.assertEqual(self.submit('note', 'note-id').status_code, 200)
        self.assertEqual(self.submit('bill', 'bill-id').status_code, 200)
        response = self.submit('bill', 'note-id')
        self.assertEqual(response.status_code, 409, response.text)
        self.assertEqual(response.json()['detail']['reason'], 'SUBMISSION_ID_CONFLICT')

    def test_A03_overlapping_retry_has_one_execution_owner(self):
        entered = threading.Event()
        duplicate = threading.Event()
        release = threading.Event()
        lock = threading.Lock()
        calls = []
        def observed(*a, **kw):
            with lock:
                calls.append(1)
                if len(calls) > 1:
                    duplicate.set()
            entered.set()
            if not release.wait(10):
                raise RuntimeError('test did not release pricing')
            return self.pricer(*a, **kw)
        routes.price_bundle = observed
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(self.submit, reuseExistingResult=False)
            try:
                self.assertTrue(entered.wait(5), 'first request never entered pricing')
                second = pool.submit(self.submit, reuseExistingResult=False)
                duplicate.wait(1)  # bounded overlap observation, not universal concurrency proof
            finally:
                release.set()
            one = first.result(timeout=15)
            two = second.result(timeout=15)
        self.assertEqual(one.status_code, 200, one.text)
        self.assertIn(two.status_code, (200, 202), two.text)
        self.assertEqual(len(calls), 1, 'same attempt executed more than once')
        self.assertEqual(one.json()['attemptId'], two.json()['attemptId'])

    def test_A04_USD_profile_rejects_EUR(self):
        response = self.submit(reportingCurrency='EUR')
        self.assertIn(response.status_code, (400, 422), response.text)

    def test_A05_unknown_calculation_is_rejected(self):
        response = self.submit(calculations=['not-a-real-calculation'])
        self.assertIn(response.status_code, (400, 422), response.text)

try:
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Acceptance))
finally:
    store_root.cleanup()
sys.exit(0 if result.wasSuccessful() else 1)
