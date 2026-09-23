import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bridge
import bundle
import gcs_stage
from coordinator import Coordinator
from test_bundle import fixture, EQUITY, SWAP


PREFIX = 'gs://test-exports/2025-06-02/'
POSITIONS = PREFIX + 'v1/seq-42.csv'
CONTRACTS = PREFIX + 'v1/seq-42-contracts.csv'
CUT = PREFIX + 'v1/seq-42.cut'


class Store:
    def __init__(self, objects):
        self.objects = objects
        self.reads = []

    def describe(self, uri):
        base = uri.split('#')[0]
        data = self.objects[base]
        return dict(bucket='test-exports', name=base.split('/', 3)[3],
                    generation='1234567890123456789', size=str(len(data)),
                    md5Hash=base64.b64encode(hashlib.md5(data).digest()).decode('ascii'))

    def read(self, uri, size):
        self.reads.append(uri)
        base, generation = uri.split('#')
        assert generation == '1234567890123456789'
        return self.objects[base]


class StageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.output = self.root / 'staged'
        self.cut = b'synthetic archived cut\n'
        self.cut_hash = bundle.digest(self.cut)
        self.positions = fixture('positions', [EQUITY]).replace(b'a'*64, self.cut_hash.encode())
        self.contracts = fixture('contracts', [SWAP]).replace(b'a'*64, self.cut_hash.encode())
        self.store = Store({POSITIONS: self.positions, CONTRACTS: self.contracts, CUT: self.cut})
        self.receipt = self.root / 'receipt.json'
        self.event = dict(schema=3, uri=POSITIONS, consensusSequence=42, sessionDate='2025-06-02',
                          priceSnapshotVersion=1, rows=1, sha256=bundle.digest(self.positions),
                          cutSha256=self.cut_hash, quiesceWitnessSequence=43, contractsSchema=2,
                          contractsUri=CONTRACTS, contracts=1, contractsSha256=bundle.digest(self.contracts))
        self.receipt.write_bytes(bundle.encoded(self.event))

    def stage(self, **kwargs):
        return gcs_stage.stage(self.output, PREFIX, 100000, store=self.store, **kwargs)

    def test_receipt_to_coordinator_and_duplicate(self):
        result = self.stage(receipt=self.receipt)
        self.assertEqual(result['evidence'], 'PRODUCER_RECEIPT_VERIFIED')
        self.assertTrue(self.stage(receipt=self.receipt)['duplicate'])
        self.assertEqual((self.output / 'positions.csv').read_bytes(), self.positions)
        self.assertEqual((self.output / 'source-receipt.json').read_bytes(), self.receipt.read_bytes())
        self.assertTrue(all('#1234567890123456789' in uri for uri in self.store.reads))
        scanned = bridge.scan(self.output, self.output, self.root / 'inbox',
                              'synthetic-test-epoch', '2025-06-02T20:00:00Z', 'synthetic', '2025-06-02')
        self.assertEqual(scanned['invalid'], [])
        self.assertEqual(len(scanned['packaged']), 1)
        packaged = scanned['packaged'][0]
        with Coordinator(self.root / 'state') as ctl:
            self.assertEqual(len(ctl.discover(self.root / 'inbox')['queued']), 1)
            self.assertEqual(ctl.run()['jobs'][0]['status'], 'MOCK_COMPLETE')
            self.assertEqual(len(ctl.discover(self.root / 'inbox')['duplicates']), 1)
            self.assertEqual(ctl.run()['processed'], [])
        self.assertTrue(packaged['bundleId'])

    def test_archive_has_no_completion_receipt_or_asserted_epoch(self):
        result = self.stage(archive_positions=POSITIONS)
        self.assertEqual(result['evidence'], 'ARCHIVE_ONLY_NO_COMPLETION_RECEIPT')
        self.assertIsNone(result['clusterEpoch'])
        self.assertEqual(list(self.output.glob('*.ready.json')), [])
        self.assertEqual(set(result['sources']), {'positions', 'contracts', 'cut'})
        self.assertTrue(self.stage(archive_positions=POSITIONS)['duplicate'])

    def test_no_partial_stage_on_download_failure(self):
        del self.store.objects[CONTRACTS]
        with self.assertRaises(KeyError):
            self.stage(receipt=self.receipt)
        self.assertFalse(self.output.exists())

    def test_duplicate_receipt_keys_and_empty_contracts(self):
        self.receipt.write_text('{"schema":3,"schema":3}')
        with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
            self.stage(receipt=self.receipt)
        self.assertEqual(self.store.reads, [])
        empty = fixture('contracts', []).replace(b'a'*64, self.cut_hash.encode())
        self.store.objects[CONTRACTS] = empty
        self.receipt.write_bytes(bundle.encoded({**self.event, 'contracts': 0,
                                                'contractsSha256': bundle.digest(empty)}))
        result = self.stage(receipt=self.receipt)
        self.assertEqual(result['rows']['contracts'], 0)

    def test_archive_name_must_match_embedded_cut(self):
        wrong = PREFIX + 'v1/seq-43'
        self.store.objects.update({wrong+'.csv': self.positions,
                                   wrong+'-contracts.csv': self.contracts, wrong+'.cut': self.cut})
        with self.assertRaisesRegex(ValueError, 'path/cut mismatch'):
            self.stage(archive_positions=wrong+'.csv')
        self.assertFalse(self.output.exists())

    def test_download_failure_and_changed_generation_are_not_accepted(self):
        with patch.object(self.store, 'read', side_effect=subprocess.TimeoutExpired('gcloud', 60)):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.stage(receipt=self.receipt)
        self.assertFalse(self.output.exists())
        self.stage(receipt=self.receipt)
        old = (self.output / 'source.json').read_bytes()
        describe = self.store.describe
        with patch.object(self.store, 'describe', side_effect=lambda uri: {**describe(uri), 'generation': '999'}), \
             patch.object(self.store, 'read', side_effect=lambda uri, size: self.store.objects[uri.split('#')[0]]):
            with self.assertRaisesRegex(ValueError, 'content mismatch'):
                self.stage(receipt=self.receipt)
        self.assertEqual((self.output / 'source.json').read_bytes(), old)

    def test_receipt_tampering_and_nonadjacent_witness_rejected(self):
        for change in ({'rows': 9}, {'sha256': 'b'*64}, {'quiesceWitnessSequence': 44}):
            self.receipt.write_bytes(bundle.encoded({**self.event, **change}))
            with self.assertRaises(ValueError):
                self.stage(receipt=self.receipt)
            self.assertFalse(self.output.exists())

    def test_archive_mixed_cut_and_source_cut_corruption_rejected(self):
        for key, data in ((CONTRACTS, self.contracts.replace(b'consensusSequence=42', b'consensusSequence=43')),
                          (CUT, b'changed')):
            old = self.store.objects[key]
            self.store.objects[key] = data
            with self.assertRaises(ValueError):
                self.stage(archive_positions=POSITIONS)
            self.assertFalse(self.output.exists())
            self.store.objects[key] = old

    def test_size_and_metadata_identity_checks_before_download(self):
        with self.assertRaisesRegex(ValueError, 'byte limit'):
            gcs_stage.fetch(self.store, POSITIONS, PREFIX, 1)
        self.assertEqual(self.store.reads, [])
        describe = self.store.describe
        for override in ({'bucket': 'foreign'}, {'generation': '1'}, {'size': True}):
            with patch.object(self.store, 'describe', return_value={**describe(POSITIONS), **override}):
                with self.assertRaises(ValueError):
                    gcs_stage.fetch(self.store, POSITIONS+'#1234567890123456789', PREFIX, 100000)
        self.assertEqual(self.store.reads, [])

    def test_size_mismatch_and_pinned_generation_loss_do_not_fallback(self):
        with patch.object(self.store, 'read', return_value=b'truncated'):
            with self.assertRaisesRegex(ValueError, 'size mismatch'):
                self.stage(receipt=self.receipt)
        with patch.object(self.store, 'read', side_effect=FileNotFoundError('generation gone')) as read:
            with self.assertRaises(FileNotFoundError):
                self.stage(receipt=self.receipt)
            self.assertEqual(read.call_count, 1)
        self.assertFalse(self.output.exists())

    def test_same_length_corruption_fails_cloud_metadata_checksum(self):
        with patch.object(self.store, 'read', return_value=b'x' * len(self.positions)):
            with self.assertRaisesRegex(ValueError, 'metadata checksum mismatch'):
                self.stage(receipt=self.receipt)
        self.assertFalse(self.output.exists())

    def test_uri_scope_and_wildcards(self):
        for uri in ('gs://test-exports-other/2025-06-02/v1/seq-42.csv',
                    'gs://test-exports/2025-06-03/v1/seq-42.csv',
                    POSITIONS+'?x=1', POSITIONS+'#latest', POSITIONS+'*',
                    PREFIX+'../other.csv', PREFIX+'%2A.csv', 'file:///tmp/x'):
            with self.subTest(uri=uri), self.assertRaises(ValueError):
                gcs_stage.fetch(self.store, uri, PREFIX, 100000)
        self.assertEqual(self.store.reads, [])

    def test_existing_corruption_and_private_output(self):
        self.stage(receipt=self.receipt)
        (self.output / 'positions.csv').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'content mismatch'):
            self.stage(receipt=self.receipt)
        self.assertEqual(self.output.stat().st_mode & 0o077, 0)
        self.assertTrue(all(p.stat().st_mode & 0o077 == 0 for p in self.output.iterdir()))

    def test_output_in_git_or_symlink_refused_before_download(self):
        (self.root / '.git').mkdir()
        with self.assertRaisesRegex(ValueError, 'Git checkout'):
            self.stage(receipt=self.receipt)
        (self.root / '.git').rmdir()
        self.output.symlink_to(self.root / 'elsewhere')
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.stage(receipt=self.receipt)
        self.assertEqual(self.store.reads, [])

    def test_gcloud_uses_argument_arrays_pinned_uri_and_bounded_range(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            self.assertEqual(kwargs['timeout'], 60)
            self.assertTrue(kwargs['check'])
            if 'describe' in args:
                return subprocess.CompletedProcess(args, 0, json.dumps(self.store.describe(POSITIONS)).encode())
            kwargs['stdout'].write(self.positions)
            return subprocess.CompletedProcess(args, 0)
        with patch('gcs_stage.subprocess.run', side_effect=run):
            data, source = gcs_stage.fetch(gcs_stage.GcloudStore(), POSITIONS, PREFIX, 100000)
        self.assertEqual(data, self.positions)
        self.assertIn(POSITIONS+'#'+source['generation'], calls[1])
        self.assertIn(f'--range=0-{len(self.positions) - 1}', calls[1])


if __name__ == '__main__':
    unittest.main()
