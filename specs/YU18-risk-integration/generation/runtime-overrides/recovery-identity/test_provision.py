import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import provision as p


class ProvisionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / 'storage'

    def fresh(self, epoch='fresh'):
        return json.dumps(dict(schema='traderx.run.v1', epoch=epoch, eventIdScheme='epoch-v1',
                               storageLineage='storage-a', projectionScope='scope-a',
                               adoptionEvidenceSha256=None)).encode()

    def legacy(self, trade='historic-long-epoch-unchanged', order=None):
        order = trade if order is None else order
        self.store.mkdir()
        (self.store / 'log.rec').write_bytes(b'nonempty retained storage witness')
        config = self.root / 'archived-config.json'
        config.write_text(json.dumps(dict(tradePublisherEpoch=trade, orderPublisherEpoch=order,
                                         eventIdScheme='legacy-v0')))
        evidence = json.dumps(dict(schema='traderx.legacy-evidence.v1', epoch=trade,
                                   sourceConfiguration=dict(path=str(config), sha256=p.sha(config.read_bytes())),
                                   storageFiles={'log.rec': p.sha((self.store / 'log.rec').read_bytes())})).encode()
        d = json.loads(self.fresh())
        d.update(epoch=trade, eventIdScheme='legacy-v0', projectionScope='legacy-unknown',
                 adoptionEvidenceSha256=p.sha(evidence))
        return json.dumps(d).encode(), evidence

    def test_fresh_requires_empty_storage_and_offline_acknowledgement(self):
        with self.assertRaises(ValueError):
            p.provision(self.store, self.fresh())
        self.store.mkdir()
        (self.store / 'retained.log').write_bytes(b'history')
        with self.assertRaisesRegex(ValueError, 'NOT_EMPTY'):
            p.provision(self.store, self.fresh(), offline=True)
        self.assertEqual(b'history', (self.store / 'retained.log').read_bytes())
        self.assertFalse((self.store / p.FILE).exists())

    def test_restart_retry_never_replaces_descriptor_or_history(self):
        raw = self.fresh()
        p.provision(self.store, raw, offline=True)
        (self.store / 'new.log').write_bytes(b'new committed tail')
        self.assertEqual(p.sha(raw), p.provision(self.store, raw, offline=True))
        with self.assertRaisesRegex(ValueError, 'DESCRIPTOR_MISMATCH'):
            p.provision(self.store, self.fresh('another'), offline=True)
        self.assertEqual(raw, (self.store / p.FILE).read_bytes())
        self.assertEqual(b'new committed tail', (self.store / 'new.log').read_bytes())

    def test_evidenced_legacy_keeps_arbitrary_historical_epoch_and_bytes(self):
        raw, evidence = self.legacy()
        before = (self.store / 'log.rec').read_bytes()
        p.provision(self.store, raw, offline=True, evidence=evidence)
        self.assertEqual(before, (self.store / 'log.rec').read_bytes())
        self.assertEqual(raw, (self.store / p.FILE).read_bytes())
        self.assertEqual(evidence, (self.store / p.EVIDENCE).read_bytes())
        (self.store / 'log.rec').write_bytes(before + b'tail after adoption')
        self.assertEqual(p.sha(raw), p.provision(self.store, raw, offline=True))

    def test_fallback_epoch_disagreement_is_not_guessed_into_adoption(self):
        raw, evidence = self.legacy(trade='0', order='1')
        with self.assertRaisesRegex(ValueError, 'AMBIGUOUS'):
            p.provision(self.store, raw, offline=True, evidence=evidence)
        self.assertFalse((self.store / p.FILE).exists())

    def test_changed_retained_storage_refuses_evidence(self):
        raw, evidence = self.legacy()
        (self.store / 'log.rec').write_bytes(b'changed after evidence')
        with self.assertRaisesRegex(ValueError, 'STORAGE_EVIDENCE_MISMATCH'):
            p.provision(self.store, raw, offline=True, evidence=evidence)
        self.assertFalse((self.store / p.FILE).exists())

    def test_interruption_after_evidence_before_descriptor_is_retryable(self):
        raw, evidence = self.legacy()
        install = p.install
        def interrupt(directory, name, data):
            if name == p.FILE:
                raise OSError('simulated interruption')
            install(directory, name, data)
        with patch.object(p, 'install', side_effect=interrupt):
            with self.assertRaises(OSError):
                p.provision(self.store, raw, offline=True, evidence=evidence)
        self.assertTrue((self.store / p.EVIDENCE).exists())
        self.assertFalse((self.store / p.FILE).exists())
        p.provision(self.store, raw, offline=True, evidence=evidence)
        self.assertEqual(raw, (self.store / p.FILE).read_bytes())

    def test_existing_descriptor_symlink_is_refused(self):
        self.store.mkdir()
        external = self.root / 'outside.json'
        external.write_bytes(self.fresh())
        (self.store / p.FILE).symlink_to(external)
        with self.assertRaisesRegex(ValueError, 'non-symlink'):
            p.provision(self.store, self.fresh(), offline=True)

    def test_distinct_retained_members_share_one_immutable_descriptor(self):
        raw, evidence = self.legacy()
        another = self.root / 'member-two'
        another.mkdir(); (another / 'log.rec').write_bytes(b'different physical replica bytes')
        e = p.parse(evidence)
        one = e.pop('storageFiles')
        e['storageMembers'] = {'member-one': one, 'member-two': {'log.rec': p.sha((another / 'log.rec').read_bytes())}}
        evidence = json.dumps(e).encode()
        d = p.parse(raw); d['adoptionEvidenceSha256'] = p.sha(evidence); raw = json.dumps(d).encode()
        with self.assertRaisesRegex(ValueError, 'MEMBER_EVIDENCE_REQUIRED'):
            p.provision(self.store, raw, offline=True, evidence=evidence)
        with self.assertRaisesRegex(ValueError, 'STORAGE_EVIDENCE_MISMATCH'):
            p.provision(another, raw, offline=True, evidence=evidence, storage_member='member-one')
        p.provision(self.store, raw, offline=True, evidence=evidence, storage_member='member-one')
        p.provision(another, raw, offline=True, evidence=evidence, storage_member='member-two')
        self.assertEqual((self.store / p.FILE).read_bytes(), (another / p.FILE).read_bytes())
        self.assertEqual((self.store / p.EVIDENCE).read_bytes(), (another / p.EVIDENCE).read_bytes())
        self.assertEqual(b'different physical replica bytes', (another / 'log.rec').read_bytes())

    def test_duplicate_json_and_unknown_fields_refuse(self):
        with self.assertRaises(ValueError):
            p.descriptor(b'{"epoch":"a","epoch":"b"}')
        d = json.loads(self.fresh()); d['unrecognized'] = True
        with self.assertRaises(ValueError):
            p.descriptor(json.dumps(d).encode())


if __name__ == '__main__':
    unittest.main()
