"""Contract failures use synthetic exports; real CPU evidence is a separate run."""
import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import export_mapping as m
from batch_cpu import verify_unused_eod
from corpus import encoded

FIXTURE = Path(m.bundle.__file__).parent / 'tests/fixtures/shared/bill/v2'
MARKET = {'schema': 'traderx.synthetic-flat-curve.v1', 'origin': 'synthetic', 'asof': '2025-06-02',
          'rate': '0.03', 'compounding': 'continuous', 'day_count': 'ACT/365F',
          'description': 'Explicit test assumption; not observed market data.'}


class MappingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.source = self.root / 'source'
        shutil.copytree(FIXTURE, self.source)
        self.market = self.root / 'market.json'
        self.market.write_bytes(encoded(MARKET))
        self.expected = {'account_id': '22214', 'run_id': 'risk-cpu-mapping-01',
                         'bundle_id': json.loads((self.source / 'manifest.json').read_bytes())['bundleId']}

    def tearDown(self):
        self.tmp.cleanup()

    def remanifest(self):
        old = json.loads((self.source / 'manifest.json').read_bytes())
        manifest = m.bundle.manifest_for({k: (self.source / (k + '.csv')).read_bytes()
                                         for k in ('positions', 'contracts')}, old['clusterEpoch'],
                                        old['valuationTime'], old['inputOrigin'],
                                        (self.source / 'instrument-terms.json').read_bytes())
        (self.source / 'manifest.json').write_bytes(m.bundle.encoded(manifest))
        self.expected['bundle_id'] = manifest['bundleId']

    def test_long_short_units_and_explicit_account_coverage(self):
        for account, face in [('22214', 100000), ('42422', -100000)]:
            self.expected['account_id'] = account
            raw, receipt = m.mapping(self.source, self.market, self.expected)
            self.assertEqual(json.loads(raw)['trades'][0]['face_amount'], face)
            self.assertEqual(receipt['coverage'], {'selected': 1, 'mapped': 1,
                             'unselected_positions': 1, 'unselected_contracts': 0})
            self.assertEqual(receipt['cut']['consensusSequence'], '8')
            self.assertEqual(receipt['positions'][0]['account_id'], account)

    def test_bound_expected_identity_and_tampered_request_refused(self):
        out = self.root / 'mapped'
        receipt = m.prepare(self.source, self.market, self.expected, out)
        self.assertEqual(m.verify(out, self.expected), receipt)
        for key, value in [('account_id', '42422'), ('run_id', 'risk-cpu-other-01'), ('bundle_id', '0'*64)]:
            bad = dict(self.expected, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                m.verify(out, bad)
        (out / 'request.json').write_bytes(b'{}')
        with self.assertRaises(ValueError):
            m.verify(out, self.expected)

    def test_empty_account_corrupt_export_and_missing_curve_refused(self):
        with self.assertRaises(ValueError):
            m.mapping(self.source, self.market, dict(self.expected, account_id='absent'))
        self.market.unlink()
        with self.assertRaises(OSError):
            m.mapping(self.source, self.market, self.expected)
        self.market.write_bytes(encoded(MARKET))
        p = self.source / 'positions.csv'
        p.write_bytes(p.read_bytes().replace(b'100000', b'200000'))
        with self.assertRaises(ValueError):
            m.mapping(self.source, self.market, self.expected)

    def test_market_date_and_provenance_refused(self):
        for key, value in [('asof','2025-06-03'), ('origin','observed'), ('compounding','simple'),
                           ('day_count','ACT/360'), ('rate','NaN'), ('description','')]:
            self.market.write_bytes(encoded(dict(MARKET, **{key:value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                m.mapping(self.source, self.market, self.expected)

    def test_unsupported_terms_even_with_valid_new_bundle_identity_refused(self):
        original = (self.source / 'instrument-terms.json').read_bytes()
        for key, value in [('quantityUnit', 'units'), ('settlementDays', 2), ('redemptionFraction','0.9')]:
            terms = json.loads(original)
            terms['entries'][0]['terms'][key] = value
            (self.source / 'instrument-terms.json').write_bytes(encoded(terms))
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.remanifest()
                m.mapping(self.source, self.market, self.expected)

    def test_unsupported_selected_position_never_silently_dropped(self):
        p = self.source / 'positions.csv'
        p.write_bytes(p.read_bytes().replace(b',TREASURY,', b',CORPORATE,'))
        with self.assertRaises(ValueError):
            self.remanifest()
            m.mapping(self.source, self.market, self.expected)

    def test_fractional_face_refused_without_rounding(self):
        p = self.source / 'positions.csv'
        p.write_bytes(p.read_bytes().replace(b',100000,', b',100000.01,'))
        self.remanifest()
        with self.assertRaises(ValueError):
            m.mapping(self.source, self.market, self.expected)

    def test_empty_eod_initialization_allowed_but_work_and_symlinks_refused(self):
        root = self.root / 'eod'
        root.mkdir()
        with self.assertRaises(ValueError):
            verify_unused_eod(root)
        for name in ('attempts', 'pointers', 'submissions', 'tmp'):
            (root / name).mkdir()
        verify_unused_eod(root)
        p = root / 'attempts/job.json'
        p.write_text('{}')
        with self.assertRaises(ValueError):
            verify_unused_eod(root)
        p.unlink()
        p.symlink_to(self.market)
        with self.assertRaises(ValueError):
            verify_unused_eod(root)


if __name__ == '__main__':
    unittest.main()
