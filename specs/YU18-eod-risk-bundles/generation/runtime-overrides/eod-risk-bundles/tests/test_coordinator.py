import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle
import coordinator
from test_bundle import fixture, EQUITY, SWAP


class BadWorker(coordinator.MockAdapter):
    def execute(self, source, destination):
        super().execute(source, destination)
        path = destination / 'results.json'
        data = json.loads(path.read_text())
        data['items'].pop()
        path.write_bytes(bundle.encoded(data))


class Unavailable(coordinator.MockAdapter):
    def execute(self, source, destination):
        raise OSError('worker unavailable')


class CoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inbox = self.root / 'inbox'
        self.inbox.mkdir()
        self.state = self.root / 'state'
        self.input('one')

    def input(self, name, sequence='42', version='1', equities=None, swaps=None, epoch='epoch'):
        p, c = self.root / 'positions.csv', self.root / 'contracts.csv'
        p.write_bytes(fixture('positions', [EQUITY] if equities is None else equities,
                             consensusSequence=sequence, priceSnapshotVersion=version))
        c.write_bytes(fixture('contracts', [SWAP] if swaps is None else swaps,
                             consensusSequence=sequence, priceSnapshotVersion=version))
        return bundle.build(p, c, self.inbox / name, epoch, '2025-06-02T16:00:00-04:00', 'synthetic')

    def test_discovery_deduplicates_and_snapshots_bytes(self):
        self.input('duplicate')
        with coordinator.Coordinator(self.state) as ctl:
            report = ctl.discover(self.inbox)
            self.assertEqual(len(report['queued']), 1)
            self.assertEqual(len(report['duplicates']), 1)
            (self.inbox / 'one' / 'positions.csv').unlink()
            result = ctl.run()['jobs'][0]
            self.assertEqual(result['status'], 'MOCK_COMPLETE')
            self.assertTrue(result['selectedMockResult'])
            self.assertFalse(result['usableForRisk'])
            self.assertEqual(len(ctl.run()['jobs'][0]['attempts']), 1)
        with coordinator.Coordinator(self.state) as ctl:
            self.assertEqual(ctl.status()['jobs'][0]['resultIntegrity'], 'VERIFIED')

    def test_discovery_missing_corrupt_and_staging(self):
        (self.inbox / 'one' / 'contracts.csv').unlink()
        (self.inbox / 'unfinished').mkdir()
        (self.inbox / '.eod-stage-ignore').mkdir()
        with coordinator.Coordinator(self.state) as ctl:
            r = ctl.discover(self.inbox)
            self.assertEqual(len(r['invalid']), 1)
            self.assertEqual(len(r['incomplete']), 1)
            self.assertEqual(r['queued'], [])
            self.assertEqual(ctl.run()['processed'], [])

    def test_wrong_result_fails_and_retry_keeps_attempts(self):
        with coordinator.Coordinator(self.state, BadWorker()) as ctl:
            ctl.discover(self.inbox)
            job = ctl.run()['jobs'][0]
            self.assertEqual(job['status'], 'FAILED')
            self.assertIn('RESULT_INVALID', job['error'])
            self.assertFalse(job['selectedMockResult'])
            ctl.retry(job['job_id'])
        with coordinator.Coordinator(self.state) as ctl:
            job = ctl.run()['jobs'][0]
            self.assertEqual(job['status'], 'MOCK_COMPLETE')
            self.assertEqual([a['status'] for a in job['attempts']], ['FAILED', 'MOCK_COMPLETE'])
            with self.assertRaises(ValueError):
                ctl.retry(job['job_id'])

    def test_worker_failure_is_not_financial_failure_or_auto_retry(self):
        with coordinator.Coordinator(self.state, Unavailable()) as ctl:
            ctl.discover(self.inbox)
            job = ctl.run()['jobs'][0]
            self.assertIn('WORKER_FAILURE', job['error'])
            self.assertEqual(ctl.run()['processed'], [])

    def crash(self, publish):
        module = Path(coordinator.__file__).parent
        code = '''
import os, sys
sys.path.insert(0, sys.argv[1])
import coordinator
class Crash(coordinator.MockAdapter):
    def execute(self, source, destination):
        if sys.argv[4] == 'yes':
            super().execute(source, destination)
        os._exit(17)
with coordinator.Coordinator(sys.argv[2], Crash()) as ctl:
    ctl.discover(sys.argv[3])
    ctl.run()
'''
        proc = subprocess.run([sys.executable, '-c', code, str(module), str(self.state), str(self.inbox),
                               'yes' if publish else 'no'], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 17, proc.stderr)

    def test_process_death_before_publication_requeues(self):
        self.crash(False)
        with coordinator.Coordinator(self.state) as ctl:
            self.assertEqual(ctl.status()['jobs'][0]['status'], 'RUNNING')
            result = ctl.run()
            self.assertEqual(len(result['recovered']), 1)
            self.assertEqual([a['status'] for a in result['jobs'][0]['attempts']],
                             ['INTERRUPTED', 'MOCK_COMPLETE'])

    def test_process_death_after_publication_ingests_without_recompute(self):
        self.crash(True)
        with coordinator.Coordinator(self.state, Unavailable()) as ctl:
            result = ctl.run()
            self.assertEqual(result['processed'], [])
            self.assertEqual(result['jobs'][0]['status'], 'MOCK_COMPLETE')
            self.assertEqual(len(result['jobs'][0]['attempts']), 1)

    def test_lock_prevents_reclaiming_active_worker(self):
        with coordinator.Coordinator(self.state):
            with self.assertRaisesRegex(ValueError, 'already in use'):
                with coordinator.Coordinator(self.state):
                    self.fail('second coordinator acquired lock')

    def test_result_validation_rejects_identity_financial_and_coverage_mutations(self):
        source = self.inbox / 'one'
        destination = self.root / 'result'
        original = bundle.mock(source, destination)
        changes = [lambda d: d.update(bundleId='f' * 64),
                   lambda d: d.update(clusterEpoch='other'),
                   lambda d: d.update(usableForRisk=True),
                   lambda d: d['items'].append(d['items'][0]),
                   lambda d: d['items'][0].update(accountId='wrong-account'),
                   lambda d: d['items'][0].update(npv=0),
                   lambda d: d['coverage'].update(priced=1),
                   lambda d: d['coverage'].update(priced=False)]
        for mutate in changes:
            with self.subTest(mutation=mutate):
                data = json.loads(json.dumps(original))
                mutate(data)
                (destination / 'results.json').write_bytes(bundle.encoded(data))
                with self.assertRaises(ValueError):
                    coordinator.MockAdapter().validate_result(source, destination)
        (destination / 'results.json').write_bytes(bundle.encoded(original))
        self.assertEqual(len(coordinator.MockAdapter().validate_result(source, destination)), 64)

    def test_tampered_accepted_result_is_not_selected(self):
        with coordinator.Coordinator(self.state) as ctl:
            ctl.discover(self.inbox)
            job = ctl.run()['jobs'][0]
            path = self.state / job['result_path'] / 'results.json'
            path.write_bytes(path.read_bytes() + b'\n')
            checked = ctl.status()['jobs'][0]
            self.assertEqual(checked['resultIntegrity'], 'INVALID')
            self.assertFalse(checked['selectedMockResult'])

    def test_late_old_completion_never_replaces_newer_cut(self):
        # Queue the new cut first, so the old cut completes last.
        self.input('new', sequence='9007199254740994')
        with coordinator.Coordinator(self.state) as ctl:
            old = self.inbox / 'one'
            old.rename(self.root / 'parked')
            ctl.discover(self.inbox)
            (self.root / 'parked').rename(old)
            ctl.discover(self.inbox)
            result = ctl.run()
            selected = [j for j in result['jobs'] if j['selectedMockResult']]
            self.assertEqual(len(selected), 1)
            self.assertEqual(selected[0]['cut']['consensusSequence'], '9007199254740994')
            self.assertEqual(result['jobs'][-1]['cut']['consensusSequence'], '42')

    def test_same_version_conflict_requires_explicit_selection(self):
        self.input('conflict', equities=[dict(EQUITY, quantity='20')])
        with coordinator.Coordinator(self.state) as ctl:
            ctl.discover(self.inbox)
            jobs = ctl.run()['jobs']
            self.assertTrue(all(j['selectionAmbiguous'] for j in jobs))
            self.assertFalse(any(j['selectedMockResult'] for j in jobs))

    def test_empty_portfolio_and_same_security_multiple_accounts(self):
        self.input('empty', sequence='43', equities=[], swaps=[])
        self.input('accounts', sequence='44', equities=[EQUITY, dict(EQUITY, accountId='short', quantity='-10')])
        with coordinator.Coordinator(self.state) as ctl:
            ctl.discover(self.inbox)
            jobs = ctl.run()['jobs']
            self.assertEqual(len(jobs), 3)
            self.assertTrue(all(j['status'] == 'MOCK_COMPLETE' for j in jobs))

    def test_newer_pending_cut_blocks_older_selection(self):
        with coordinator.Coordinator(self.state) as ctl:
            ctl.discover(self.inbox)
            self.assertTrue(ctl.run()['jobs'][0]['selectedMockResult'])
            self.input('newer', sequence='43')
            ctl.discover(self.inbox)
            self.assertFalse(any(j['selectedMockResult'] for j in ctl.status()['jobs']))

    def test_corrupt_snapshot_fails_before_worker_execution(self):
        with coordinator.Coordinator(self.state) as ctl:
            ctl.discover(self.inbox)
            job = ctl.status()['jobs'][0]
            (self.state / 'inputs' / job['bundle_id'] / 'contracts.csv').unlink()
            job = ctl.run()['jobs'][0]
            self.assertEqual(job['status'], 'FAILED')
            self.assertIn('INPUT_INVALID', job['error'])
            self.assertFalse((self.state / job['attempts'][0]['result_path']).exists())

    def test_private_store_and_published_files(self):
        with coordinator.Coordinator(self.state) as ctl:
            ctl.discover(self.inbox)
            job = ctl.run()['jobs'][0]
            paths = [self.state, self.state / 'jobs.sqlite3',
                     self.state / job['result_path'], self.state / job['result_path'] / 'results.json']
            self.assertTrue(all(path.stat().st_mode & 0o077 == 0 for path in paths))
        bad = self.root / 'public-state'
        bad.mkdir(mode=0o755)
        bad.chmod(0o755)
        with self.assertRaisesRegex(ValueError, '0700'):
            with coordinator.Coordinator(bad):
                self.fail('accepted public state')

    def test_cli_returns_nonzero_on_invalid_input(self):
        (self.inbox / 'one' / 'contracts.csv').unlink()
        proc = subprocess.run([sys.executable, coordinator.__file__, '--state', str(self.state),
                               'discover', str(self.inbox)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1, proc.stderr)
        self.assertEqual(len(json.loads(proc.stdout)['invalid']), 1)


if __name__ == '__main__':
    unittest.main()
