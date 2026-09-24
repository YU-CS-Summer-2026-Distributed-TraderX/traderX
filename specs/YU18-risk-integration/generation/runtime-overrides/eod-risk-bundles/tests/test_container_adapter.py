"""SC-RP02/03/04/06: binding, tamper, refusals and conservative recovery."""
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle
import container_adapter as ca
from coordinator import Coordinator
import job_status
from worker_protocol import Uncertain

FIX = Path(__file__).parent/'fixtures'


class Transport(ca.ContainerAdapter):
    def __init__(self, stage):
        super().__init__('http://127.0.0.1:18303', stage)
        self.calls = []
        self.response = json.loads((FIX/'container/bill-http.json').read_bytes())
        self.binding = None
        self.lose = False
        self.unknown = False
        self.mutate = lambda x: None

    def request(self, method, path, body=None):
        self.calls.append((method, path))
        if method == 'POST':
            self.binding = {**copy.deepcopy(self.response), 'submissionId': body['submissionId']}
            self.binding.pop('reused')
            if self.lose:
                raise Uncertain('test lost response')
            self.mutate(self.response)
            return 200, ca.canonical(self.response)
        if '/by-workload/' in path:
            return (404, b'{}') if self.unknown else (200, ca.canonical(self.response))
        return 200, ca.canonical(self.binding)


class ContainerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root/'inbox/cut'
        shutil.copytree(FIX/'shared/bill/v2', self.source)
        self.stage = self.root/'stage'; self.stage.mkdir()
        self.adapter = Transport(self.stage)
        self.state = self.root/'state'

    def run_job(self):
        with Coordinator(self.state, self.adapter) as c:
            c.discover(self.source.parent)
            return c.run()['jobs'][0]

    def test_success_original_bytes_and_offline_read(self):
        job = self.run_job()
        self.assertEqual(job['status'], 'CONTAINER_PRICING_VALIDATED')
        self.assertEqual(job['resultIntegrity'], 'VERIFIED')
        result = self.state/job['result_path']
        self.assertEqual((result/'http-response.json').read_bytes(), ca.canonical(self.adapter.response))
        snap = job_status.snapshot(self.state)['jobs'][0]
        self.assertTrue(snap['pricingAvailable'])
        self.assertEqual(len(snap['containerPricing']['positions']), 2)
        self.assertFalse(snap['usableForRisk'])
        self.assertEqual(len(self.adapter.calls), 2)
        again = self.run_job()
        self.assertEqual(len(again['attempts']), 1)
        self.assertEqual(len(self.adapter.calls), 2)

    def test_unknown_after_durable_intent_never_posts(self):
        with Coordinator(self.state, self.adapter) as c:
            c.discover(self.source.parent)
            # Model abrupt consumer death before/after uncertain network send: durable intent,
            # RUNNING database and no local response. Recovery must only GET.
            self.adapter.execute = lambda source, target: ca.durable_publish(
                target.with_name('.'+target.name+'-intent'),
                {'request.json': ca.canonical(self.adapter.request_record(source, target))}) or (_ for _ in ()).throw(KeyboardInterrupt())
            with self.assertRaises(KeyboardInterrupt): c.run()
        del self.adapter.execute
        self.adapter.unknown = True
        job = self.run_job()
        self.assertEqual(job['status'], 'UNCERTAIN')
        self.assertEqual([m for m, _ in self.adapter.calls], ['GET'])
        self.run_job()
        self.assertEqual(len(self.adapter.calls), 1)
        with Coordinator(self.state, self.adapter) as c:
            with self.assertRaisesRegex(ValueError, 'retry disabled'): c.retry(job['job_id'])
        self.assertEqual(job_status.snapshot(self.state)['jobs'][0]['errorCode'], 'SUBMISSION_UNCERTAIN')

    def test_recovery_after_response_before_publication(self):
        original = self.adapter.validate_binding
        self.adapter.validate_binding = lambda *args: (_ for _ in ()).throw(KeyboardInterrupt())
        with self.assertRaises(KeyboardInterrupt): self.run_job()
        self.adapter.validate_binding = original
        job = self.run_job()
        self.assertEqual(job['status'], 'CONTAINER_PRICING_VALIDATED')
        self.assertEqual(len(job['attempts']), 1)
        self.assertEqual(sum(m == 'POST' for m, _ in self.adapter.calls), 1)

    def test_lost_response_held_without_retry(self):
        self.adapter.lose = True
        job = self.run_job()
        self.assertEqual(job['status'], 'UNCERTAIN')
        self.assertEqual(self.run_job()['status'], 'UNCERTAIN')
        self.assertEqual(sum(m == 'POST' for m, _ in self.adapter.calls), 1)

    def test_wrong_submission_is_rejected(self):
        original = self.adapter.request
        def request(method, path, body=None):
            code, data = original(method, path, body)
            if '/attempts/' in path:
                value = json.loads(data); value['submissionId'] = 'someone-else'; data = ca.canonical(value)
            return code, data
        self.adapter.request = request
        self.assertEqual(self.run_job()['status'], 'FAILED')
        self.assertFalse(job_status.snapshot(self.state)['jobs'][0]['pricingAvailable'])

    def test_corruption_clears_prices(self):
        job = self.run_job()
        (self.state/job['result_path']/'http-response.json').write_bytes(b'{}')
        snap = job_status.snapshot(self.state)['jobs'][0]
        self.assertEqual(snap['resultIntegrity'], 'INVALID')
        self.assertNotIn('containerPricing', snap)
        self.assertFalse(snap['pricingAvailable'])

    def test_semantic_mutations_rejected(self):
        baseline = copy.deepcopy(self.adapter.response['result'])
        mutations = [lambda r: r.update(bundleId='wrong'), lambda r: r.update(resultSchema='next'),
            lambda r: r.update(marketProvenance='observed'),
            lambda r: r['coverage'].update(allApplicableComputed=True),
            lambda r: r['items'][0]['sourceIdentity'].update(accountId='wrong'),
            lambda r: r['items'][0]['calculations']['npv'].update(signedFaceAmount=1),
            lambda r: r['items'].reverse(), lambda r: r['items'].pop(),
            lambda r: r['marketInputs'].update(assumedProfileId='other'),
            lambda r: r['items'][0]['calculations'].pop('theta')]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                value = copy.deepcopy(baseline); mutate(value)
                with self.assertRaises(ValueError): ca.validate_document(self.source, value)

    def test_missing_and_unsupported_inputs(self):
        with self.assertRaises(ValueError): ca.ContainerAdapter(market_inputs=None)
        with self.assertRaises(ValueError): ca.ContainerAdapter(reporting_currency='EUR')
        with self.assertRaises(ValueError): ca.ContainerAdapter(calculations=['magic'])
        with self.assertRaises(ValueError): ca.ContainerAdapter('http://example.com:80')
        with self.assertRaises(ValueError): ca.inputs(FIX/'shared/sofr/v2')
        (self.source/'instrument-terms.json').unlink()
        with self.assertRaises(ValueError): ca.inputs(self.source)

    def test_staging_change_refused_before_submission(self):
        shutil.copytree(self.source, self.stage/ca.BILL)
        (self.stage/ca.BILL/'positions.csv').write_text('corrupt')
        self.assertEqual(self.run_job()['status'], 'FAILED')
        self.assertEqual(self.adapter.calls, [])

    def test_consistently_bound_contradictory_amounts_never_promote(self):
        mutations = {
            'long-negative': (lambda r: r['items'][0]['calculations']['npv'].update(value=-1), 'NPV sign'),
            'short-positive': (lambda r: r['items'][1]['calculations']['npv'].update(value=1), 'NPV sign'),
            'zero-npv': (lambda r: r['items'][0]['calculations']['npv'].update(value=0), 'NPV sign'),
            'nonzero-accrual': (lambda r: r['items'][0]['calculations']['accruedInterest'].update(value=123), 'structural-zero'),
            'npv-currency': (lambda r: r['items'][0]['calculations']['npv'].update(currency='EUR'), 'currency/unit'),
            'accrual-currency': (lambda r: r['items'][0]['calculations']['accruedInterest'].update(currency='EUR'), 'currency/unit'),
            'unit': (lambda r: r['items'][0]['calculations']['npv'].update(unit='percent-of-par'), 'currency/unit'),
            'units': (lambda r: r['items'][0]['calculations']['npv'].update(units='EUR'), 'currency/unit'),
            'value-unit': (lambda r: r['items'][0]['calculations']['npv'].update(valueUnit='USD-per-face'), 'currency/unit'),
            'reporting-currency': (lambda r: r['items'][0]['calculations']['npv'].update(reportingCurrency='EUR'), 'currency/unit'),
            'unknown-unit-field': (lambda r: r['items'][0]['calculations']['npv'].update(amountUnit='percent'), 'unreviewed'),
        }
        for name, (mutate, reason) in mutations.items():
            with self.subTest(name=name):
                self.state = self.root/('state-'+name)
                self.adapter = Transport(self.stage)
                # Mutate BEFORE POST, so Transport builds an equally mutated, consistent binding.
                mutate(self.adapter.response['result'])
                job = self.run_job()
                self.assertEqual(job['status'], 'FAILED')
                self.assertIn(reason, job['error'])
                self.assertNotIn('binding mismatch', job['error'])
                public = job_status.snapshot(self.state)['jobs'][0]
                self.assertFalse(public['pricingAvailable'])
                self.assertNotIn('containerPricing', public)
                attempt = job['attempts'][0]
                destination = self.state/attempt['result_path']
                raw = destination.with_name('.'+destination.name+'-response')/'body.json'
                self.assertEqual(raw.read_bytes(), ca.canonical(self.adapter.response))
                self.assertFalse(destination.exists())

    def test_finite_overflow_response_rejected_with_raw_evidence(self):
        original = self.adapter.request
        received = []
        def request(method, path, body=None):
            code, raw = original(method, path, body)
            # Valid JSON numeric literal overflows binary float; both bindings remain equal.
            raw = raw.replace(b'98507.14563826029', b'1e999')
            received.append(raw)
            return code, raw
        self.adapter.request = request
        job = self.run_job()
        self.assertEqual(job['status'], 'FAILED')
        self.assertIn('non-finite', job['error'])
        self.assertFalse(job_status.snapshot(self.state)['jobs'][0]['pricingAvailable'])
        destination = self.state/job['attempts'][0]['result_path']
        self.assertEqual((destination.with_name('.'+destination.name+'-response')/'body.json').read_bytes(), received[0])
        for value in (float('inf'), float('-inf'), float('nan')):
            with self.assertRaisesRegex(ValueError, 'non-finite'):
                ca.finite_numbers({'items': [{'value': value}]})

    def test_consistent_explicit_usd_amount_units(self):
        for item in self.adapter.response['result']['items']:
            for key in ('npv', 'accruedInterest'):
                item['calculations'][key].update(unit='USD', currency='USD')
        self.assertEqual(self.run_job()['status'], 'CONTAINER_PRICING_VALIDATED')

    def test_missing_dependency_fails_before_submission(self):
        from unittest.mock import patch
        with patch.dict(sys.modules, {'jsonschema': None}):
            with self.assertRaisesRegex(ValueError, 'install requirements-container.txt'):
                ca.ContainerAdapter('http://127.0.0.1:18303', self.stage)
        self.assertFalse(self.state.exists())
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_workload_matches_measured_engine(self):
        manifest, _ = ca.inputs(self.source)
        self.assertEqual(ca.workload(manifest), self.adapter.response['workloadKey'])


if __name__ == '__main__': unittest.main()
