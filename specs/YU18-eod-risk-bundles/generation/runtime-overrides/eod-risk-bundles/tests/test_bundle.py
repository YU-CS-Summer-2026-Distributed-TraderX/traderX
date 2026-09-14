import csv
import io
import json
from pathlib import Path
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle


def fixture(kind, records, **overrides):
    schema, fields, count, title = bundle.KINDS[kind]
    meta = {'consensusSequence': '42', 'sessionDate': '2025-06-02',
            'priceSnapshotVersion': '1', 'cutSha256': 'a' * 64, count: str(len(records)), **overrides}
    out = io.StringIO()
    out.write(f'# {title} schema={schema}\n')
    for k, v in meta.items():
        out.write(f'# {k}={v}\n')
    writer = csv.DictWriter(out, fieldnames=fields, lineterminator='\n')
    writer.writeheader()
    writer.writerows(records)
    return out.getvalue().encode()


EQUITY = dict(accountId='demo-account', security='SYNTH', instrumentType='EQUITY',
              quantity='10', contractMultiplier='1', costBasis='9', closingMark='10',
              markSource='EOD_SNAPSHOT', markQuality='SYNTHETIC', marketValue='100',
              unrealizedPnl='10', currency='USD')
SWAP = dict(contractId='SW-41', accountId='demo-account', payReceive='PAY_FIXED',
            notional='1000000', fixedRate='0.04', floatIndex='USD-SOFR-1Y-ACT360',
            effectiveDate='2025-06-03', maturityDate='2030-06-03', paymentFrequency='ANNUAL',
            dayCount='ACT360', currency='USD', productType='SWAP')


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.positions = self.root / 'source-positions.csv'
        self.contracts = self.root / 'source-contracts.csv'
        self.positions.write_bytes(fixture('positions', [EQUITY]))
        self.contracts.write_bytes(fixture('contracts', [SWAP]))
        self.output = self.root / 'bundle'

    def build(self, output=None, epoch='test-epoch'):
        return bundle.build(self.positions, self.contracts, output or self.output, epoch,
                            '2025-06-02T16:00:00-04:00', 'synthetic')

    def test_round_trip_preserves_bytes_and_identity(self):
        original = self.positions.read_bytes()
        created = self.build()
        checked, parsed = bundle.validate(self.output)
        self.assertEqual(created, checked)
        self.assertEqual(original, (self.output / 'positions.csv').read_bytes())
        result = bundle.mock(self.output, self.root / 'result')
        self.assertEqual(result['bundleId'], created['bundleId'])
        self.assertEqual(result['items'][1]['contractId'], 'SW-41')
        self.assertFalse(result['usableForRisk'])
        self.assertEqual(result['coverage'], {'submitted': 2, 'priced': 0})
        self.assertTrue(all(item['npv'] is None for item in result['items']))

    def test_repeated_inputs_have_same_id(self):
        self.assertEqual(self.build()['bundleId'], self.build(self.root / 'other')['bundleId'])

    def test_epoch_changes_identity(self):
        self.assertNotEqual(self.build()['bundleId'], self.build(self.root / 'other', 'epoch-2')['bundleId'])

    def test_output_is_not_overwritten(self):
        self.build()
        with self.assertRaises(ValueError):
            self.build()

    def test_mixed_cuts_rejected_before_publication(self):
        for key, value in [('consensusSequence', '43'), ('sessionDate', '2025-06-03'),
                           ('priceSnapshotVersion', '2'), ('cutSha256', 'b' * 64)]:
            with self.subTest(key=key):
                self.contracts.write_bytes(fixture('contracts', [SWAP], **{key: value}))
                with self.assertRaises(ValueError):
                    self.build()
                self.assertFalse(self.output.exists())

    def test_corrupted_payload_rejected_by_consumer(self):
        self.build()
        path = self.output / 'positions.csv'
        path.write_bytes(path.read_bytes().replace(b'SYNTH,', b'OTHER,'))
        with self.assertRaises(ValueError):
            bundle.mock(self.output, self.root / 'result')
        self.assertFalse((self.root / 'result').exists())

    def test_manifest_tampering_rejected(self):
        self.build()
        path = self.output / 'manifest.json'
        manifest = json.loads(path.read_text())
        manifest['artifacts']['positions']['path'] = '../source-positions.csv'
        path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            bundle.validate(self.output)

    def test_count_and_duplicate_identity_rejected(self):
        for content in (fixture('positions', [EQUITY], rows='2'), fixture('positions', [EQUITY, EQUITY])):
            self.positions.write_bytes(content)
            with self.assertRaises(ValueError):
                self.build()

    def test_nonfinite_numbers_rejected(self):
        for number in ('NaN', 'Infinity', 'oops'):
            self.positions.write_bytes(fixture('positions', [{**EQUITY, 'quantity': number}]))
            with self.assertRaises(ValueError):
                self.build()

    def test_bad_dates_and_direction_rejected(self):
        for changes in ({'maturityDate': '2025-06-03'}, {'payReceive': 'BUY'},
                        {'productType': 'SWAPTION', 'expiryDate': '2031-01-01', 'exerciseStyle': 'EUROPEAN'}):
            self.contracts.write_bytes(fixture('contracts', [{**SWAP, **changes}]))
            with self.assertRaises(ValueError):
                self.build()

    def test_empty_portfolio_valid(self):
        self.positions.write_bytes(fixture('positions', []))
        self.contracts.write_bytes(fixture('contracts', []))
        self.build()
        self.assertEqual(bundle.mock(self.output, self.root / 'result')['coverage']['submitted'], 0)

    def test_bonds_options_and_swaption_preserve_terms(self):
        option = {**EQUITY, 'security': 'SYNTH250620C00100000', 'instrumentType': 'OPTION', 'contractMultiplier': '100'}
        bond = {**EQUITY, 'security': 'SYNTH-BOND', 'instrumentType': 'TREASURY',
                'coupon': '4.0', 'maturityDate': '2030-06-03', 'lastCouponDate': '2024-12-03',
                'accruedInterestFraction': '0.0199'}
        corporate = {**bond, 'security': 'SYNTH-CORP', 'instrumentType': 'CORPORATE'}
        swaption = {**SWAP, 'contractId': 'SWPT-40', 'productType': 'SWAPTION',
                    'expiryDate': '2025-06-03', 'exerciseStyle': 'EUROPEAN'}
        self.positions.write_bytes(fixture('positions', [option, bond, corporate]))
        self.contracts.write_bytes(fixture('contracts', [SWAP, swaption]))
        self.build()
        _, parsed = bundle.validate(self.output)
        self.assertEqual(parsed['positions'][1][1]['accruedInterestFraction'], '0.0199')
        result = bundle.mock(self.output, self.root / 'result')
        self.assertEqual(result['coverage'], {'submitted': 5, 'priced': 0})

    def test_publish_lock_prevents_concurrent_writer(self):
        lock = self.output.with_name(self.output.name + '.publish-lock')
        lock.touch()
        with self.assertRaises(FileExistsError):
            self.build()
        self.assertTrue(lock.exists())
        self.assertFalse(self.output.exists())

    def test_offset_required(self):
        with self.assertRaises(ValueError):
            bundle.build(self.positions, self.contracts, self.output, 'test', '2025-06-02T16:00:00', 'synthetic')

    def test_symlink_and_extra_file_rejected(self):
        self.build()
        path = self.output / 'positions.csv'
        path.unlink()
        path.symlink_to(self.positions)
        with self.assertRaises(ValueError):
            bundle.validate(self.output)
        path.unlink()
        path.write_bytes(self.positions.read_bytes())
        (self.output / 'extra').touch()
        with self.assertRaises(ValueError):
            bundle.validate(self.output)

    def test_schema_and_missing_provenance_rejected(self):
        valid = self.positions.read_bytes()
        for data in (valid.replace(b'schema=3', b'schema=2'),
                     valid.replace(b'# consensusSequence=42\n', b'')):
            self.positions.write_bytes(data)
            with self.assertRaises(ValueError):
                self.build()


if __name__ == '__main__':
    unittest.main()
