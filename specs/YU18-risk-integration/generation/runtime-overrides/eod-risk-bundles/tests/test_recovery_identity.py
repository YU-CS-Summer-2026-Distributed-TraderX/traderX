"""RI-06 SC-RI01..08: disposable local custody, process recovery and epoch separation."""
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import unittest

import test_bridge
import bridge
import bundle
import coordinator
import receipt_scope


class RecoveryIdentityTests(unittest.TestCase):
    setUp = test_bridge.BridgeTests.setUp
    write = test_bridge.BridgeTests.write
    scan = test_bridge.BridgeTests.scan
    # Reuse synthetic production-shaped receipts, not a new approximation of the format.
    def package(self, epoch='old', directory=None):
        return bridge.package((directory or self.receipts) / 'one.ready.json', self.root,
                              self.inbox, epoch, '2025-06-02T20:00:00Z',
                              'synthetic', '2025-06-02')

    def child(self, code, *args):
        return subprocess.run([sys.executable, '-c', code,
                               str(Path(bridge.__file__).parent), *map(str, args)],
                              capture_output=True, text=True, timeout=20)

    def test_relabel_refused_without_changing_accepted_bytes(self):
        first = self.package()
        accepted = Path(first['path']) / 'manifest.json'
        original = accepted.read_bytes()
        with self.assertRaisesRegex(ValueError, 'different epoch'):
            self.package('fresh')
        self.assertEqual(original, accepted.read_bytes())
        self.assertTrue(self.package()['duplicate'])
        self.assertEqual(len(list(self.inbox.glob('*/manifest.json'))), 1)

    def test_invalid_input_does_not_adopt_epoch(self):
        self.write({**self.event, 'quiesceWitnessSequence': 44})
        with self.assertRaisesRegex(ValueError, 'non-adjacent'):
            self.package('wrong')
        self.assertFalse((self.inbox / receipt_scope.STORE).exists())
        self.write(self.event)
        self.assertFalse(self.package('right')['duplicate'])

    def test_new_process_retains_binding_and_duplicate_job(self):
        first = self.package()
        with coordinator.Coordinator(self.root / 'state') as ctl:
            ctl.discover(self.inbox)
            before = ctl.run()['jobs'][0]
        code = '''import sys,json
sys.path.insert(0,sys.argv[1])
import bridge
print(json.dumps(bridge.package(*sys.argv[2:])))
'''
        args = [self.receipt, self.root, self.inbox, 'old',
                '2025-06-02T20:00:00Z', 'synthetic', '2025-06-02']
        child = self.child(code, *args)
        self.assertEqual(child.returncode, 0, child.stderr)
        self.assertTrue(json.loads(child.stdout)['duplicate'])
        args[3] = 'fresh'
        child = self.child(code, *args)
        self.assertNotEqual(child.returncode, 0)
        self.assertIn('different epoch', child.stderr)
        with coordinator.Coordinator(self.root / 'state') as ctl:
            self.assertEqual(len(ctl.discover(self.inbox)['duplicates']), 1)
            after = ctl.run()['jobs'][0]
        self.assertEqual(before['job_id'], after['job_id'])
        self.assertEqual(before['attempts'], after['attempts'])
        self.assertEqual(first['bundleId'], after['bundle_id'])

    def test_new_directory_new_epoch_preserves_history_and_rejects_stale_result(self):
        old = self.package()
        fresh_dir = self.root / 'fresh-receipts'
        fresh_dir.mkdir()
        (fresh_dir / 'one.ready.json').write_bytes(self.receipt.read_bytes())
        fresh = self.package('fresh', fresh_dir)
        self.assertNotEqual(old['bundleId'], fresh['bundleId'])
        with coordinator.Coordinator(self.root / 'state') as ctl:
            self.assertEqual(len(ctl.discover(self.inbox)['queued']), 2)
            jobs = ctl.run()['jobs']
            self.assertEqual({j['clusterEpoch'] for j in jobs}, {'old', 'fresh'})
            self.assertTrue(all(j['selectedMockResult'] for j in jobs))
            self.assertTrue(all(j['resultIntegrity'] == 'VERIFIED' for j in jobs))
            old_job = next(j for j in jobs if j['clusterEpoch'] == 'old')
            fresh_job = next(j for j in jobs if j['clusterEpoch'] == 'fresh')
            old_result = self.root / 'state' / old_job['result_path'] / 'results.json'
            fresh_result = self.root / 'state' / fresh_job['result_path'] / 'results.json'
            fresh_result.write_bytes(old_result.read_bytes())
            status = ctl.status()['jobs']
            self.assertFalse(next(j for j in status if j['clusterEpoch'] == 'fresh')['selectedMockResult'])
            self.assertTrue(next(j for j in status if j['clusterEpoch'] == 'old')['selectedMockResult'])
        # Late original delivery dedups; it is never relabelled to the fresh epoch.
        self.assertTrue(self.package()['duplicate'])
        with self.assertRaisesRegex(ValueError, 'different epoch'):
            self.package('fresh')

    def test_process_death_after_binding_before_publication(self):
        code = '''import sys,os
sys.path.insert(0,sys.argv[1])
import bridge,bundle
bundle.publish_directory=lambda *args: os._exit(71)
bridge.package(*sys.argv[2:])
'''
        child = self.child(code, self.receipt, self.root, self.inbox, 'old',
                           '2025-06-02T20:00:00Z', 'synthetic', '2025-06-02')
        self.assertEqual(child.returncode, 71, child.stderr)
        self.assertTrue((self.inbox / receipt_scope.STORE).is_file())
        self.assertEqual(list(self.inbox.glob('*/manifest.json')), [])
        with self.assertRaisesRegex(ValueError, 'different epoch'):
            self.package('fresh')
        self.assertFalse(self.package()['duplicate'])

    def test_process_death_before_binding_commit_rolls_back(self):
        coordinator.private_directory(self.inbox)
        code = '''import sys,os,sqlite3
sys.path.insert(0,sys.argv[1])
import receipt_scope
class Crash(sqlite3.Connection):
 def commit(self): os._exit(72)
connect=sqlite3.connect
receipt_scope.sqlite3.connect=lambda *a,**kw: connect(*a,**kw,factory=Crash)
receipt_scope.bind(*sys.argv[2:])
'''
        child = self.child(code, self.receipts, self.inbox, 'uncommitted')
        self.assertEqual(child.returncode, 72, child.stderr)
        self.assertFalse(self.package('committed')['duplicate'])
        with self.assertRaisesRegex(ValueError, 'different epoch'):
            self.package('uncommitted')

    def test_concurrent_conflicting_adopters_and_repeated_ingestion(self):
        coordinator.private_directory(self.inbox)
        code = '''import sys,time
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import receipt_scope
while not Path(sys.argv[5]).exists(): time.sleep(.01)
try: receipt_scope.bind(*sys.argv[2:5])
except ValueError as e:
 print(e);sys.exit(3)
'''
        gate = self.root / 'go'
        processes = [subprocess.Popen([sys.executable, '-c', code,
                     str(Path(bridge.__file__).parent), str(self.receipts), str(self.inbox),
                     epoch, str(gate)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                     for epoch in ('a', 'b')]
        try:
            gate.touch()
            outputs = [p.communicate(timeout=20) for p in processes]
            self.assertEqual(sorted(p.returncode for p in processes), [0, 3], outputs)
            winner = ('a', 'b')[next(i for i,p in enumerate(processes) if p.returncode == 0)]
            self.assertFalse(self.package(winner)['duplicate'])
            self.assertTrue(self.package(winner)['duplicate'])
            self.assertIn('different epoch', ''.join(o[0] for o in outputs))
        finally:
            for p in processes:
                if p.poll() is None:
                    p.kill()
                    p.communicate()

    def test_corrupt_unsupported_and_symlink_stores_fail_closed(self):
        coordinator.private_directory(self.inbox)
        db = self.inbox / receipt_scope.STORE
        db.write_bytes(b'not sqlite')
        with self.assertRaisesRegex(ValueError, 'database failure'):
            self.package()
        db.unlink()  # Test-owned invalid fixture only.
        with sqlite3.connect(db) as conn:
            conn.execute('PRAGMA user_version=999')
        with self.assertRaisesRegex(ValueError, 'unsupported'):
            self.package()
        db.unlink()
        target = self.root / 'untouched'
        target.write_bytes(b'preserve')
        db.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.package()
        self.assertEqual(target.read_bytes(), b'preserve')
        self.assertEqual(list(self.inbox.glob('*/manifest.json')), [])

    def test_scope_conflict_is_reported_by_scan(self):
        self.package('old')
        result = self.scan()  # inherited helper supplies test-epoch
        self.assertEqual(result['packaged'], [])
        self.assertEqual(len(result['invalid']), 1)
        self.assertIn('different epoch', result['invalid'][0]['reason'])


if __name__ == '__main__':
    unittest.main()
