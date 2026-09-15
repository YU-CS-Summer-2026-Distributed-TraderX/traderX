import copy
import json
from pathlib import Path
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bridge
import bundle
from coordinator import Coordinator
from test_bundle import fixture, EQUITY, SWAP


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.receipts = self.root / 'receipts'
        self.receipts.mkdir()
        self.inbox = self.root / 'inbox'
        self.positions = self.root / 'positions.csv'
        self.contracts = self.root / 'contracts.csv'
        self.positions.write_bytes(fixture('positions', [EQUITY]))
        self.contracts.write_bytes(fixture('contracts', [SWAP]))
        self.event = dict(schema=3, uri=self.positions.as_uri(), consensusSequence=42,
            sessionDate='2025-06-02', priceSnapshotVersion=1, rows=1,
            sha256=bundle.digest(self.positions.read_bytes()), cutSha256='a'*64,
            quiesceWitnessSequence=43, contractsSchema=2, contractsUri=self.contracts.as_uri(),
            contracts=1, contractsSha256=bundle.digest(self.contracts.read_bytes()))
        self.receipt = self.receipts / 'one.ready.json'
        self.write(self.event)

    def write(self, event):
        self.receipt.write_bytes(bundle.encoded(event))

    def scan(self):
        return bridge.scan(self.receipts, self.root, self.inbox, 'test-epoch',
                           '2025-06-02T16:00:00-04:00', 'synthetic', '2025-06-02')

    def test_ready_to_bundle_to_coordinator_and_repeat(self):
        result = self.scan()
        self.assertEqual(result['invalid'], [])
        self.assertEqual(len(result['packaged']), 1)
        output = Path(result['packaged'][0]['path'])
        self.assertEqual((output / 'positions.csv').read_bytes(), self.positions.read_bytes())
        self.assertTrue(self.scan()['packaged'][0]['duplicate'])
        with Coordinator(self.root / 'state') as ctl:
            self.assertEqual(len(ctl.discover(self.inbox)['queued']), 1)
            job = ctl.run()['jobs'][0]
            self.assertEqual(job['status'], 'MOCK_COMPLETE')
            self.assertFalse(job['usableForRisk'])

    def test_bad_metadata_never_packages(self):
        mutations = [('sha256', 'b'*64), ('contractsSha256', 'b'*64), ('cutSha256', 'b'*64),
                     ('rows', 2), ('contracts', 0), ('quiesceWitnessSequence', 44),
                     ('schema', 2), ('contractsSchema', 3),
                     ('rows', True), ('priceSnapshotVersion', 2)]
        for key, value in mutations:
            with self.subTest(field=key):
                self.write({**self.event, key: value})
                result = self.scan()
                self.assertEqual(result['packaged'], [])
                self.assertEqual(len(result['invalid']), 1)
        self.assertFalse(self.inbox.exists())

    def test_remote_and_escaping_paths_rejected(self):
        for uri in ('gs://bucket/positions.csv', 'file://host/positions.csv',
                    'file:///outside/positions.csv', self.positions.as_uri()+'?other'):
            self.write({**self.event, 'uri': uri})
            self.assertEqual(len(self.scan()['invalid']), 1)
        link = self.root / 'link.csv'
        link.symlink_to(self.positions)
        self.write({**self.event, 'uri': link.as_uri()})
        self.assertEqual(len(self.scan()['invalid']), 1)

    def test_missing_file_is_not_empty_contracts(self):
        self.contracts.unlink()
        self.assertEqual(len(self.scan()['invalid']), 1)
        self.contracts.write_bytes(fixture('contracts', []))
        self.event.update(contracts=0, contractsSha256=bundle.digest(self.contracts.read_bytes()))
        self.write(self.event)
        self.assertEqual(len(self.scan()['packaged']), 1)

    def test_unfinished_files_without_receipt_not_discovered(self):
        self.receipt.rename(self.receipts / '.ready-stage-test.tmp')
        self.assertEqual(self.scan(), {'packaged': [], 'invalid': [], 'skippedOtherDates': []})

    def test_decimal_strings_and_duplicate_json_keys(self):
        for key in ('schema', 'consensusSequence', 'priceSnapshotVersion', 'rows',
                    'quiesceWitnessSequence', 'contractsSchema', 'contracts'):
            self.event[key] = str(self.event[key])
        self.write(self.event)
        self.assertEqual(len(self.scan()['packaged']), 1)
        text = self.receipt.read_text().replace('"schema": "3"', '"schema": "3", "schema": "3"')
        self.receipt.write_text(text)
        self.assertEqual(len(self.scan()['invalid']), 1)

    def test_other_business_dates_are_not_relabelled(self):
        self.write({**self.event, 'sessionDate': '2025-06-03'})
        result = self.scan()
        self.assertEqual(result['packaged'], [])
        self.assertEqual(len(result['skippedOtherDates']), 1)
        with self.assertRaisesRegex(ValueError, 'different business date'):
            bridge.package(self.receipt, self.root, self.inbox, 'epoch',
                           '2025-06-02T20:00:00Z', 'synthetic', '2025-06-02')

    def test_actual_zero_coupon_shape(self):
        bill = dict(EQUITY, security='UST-BILL-20251202', instrumentType='TREASURY',
                    coupon='0', maturityDate='2025-12-02', lastCouponDate='', accruedInterestFraction='')
        self.positions.write_bytes(fixture('positions', [bill]))
        self.event['sha256'] = bundle.digest(self.positions.read_bytes())
        self.write(self.event)
        self.assertEqual(len(self.scan()['packaged']), 1)
        bill['lastCouponDate'] = '2025-06-02'
        bill['accruedInterestFraction'] = '0'
        with self.assertRaisesRegex(ValueError, 'zero-coupon'):
            bundle.read_extract(fixture('positions', [bill]), 'positions')


if __name__ == '__main__':
    unittest.main()
