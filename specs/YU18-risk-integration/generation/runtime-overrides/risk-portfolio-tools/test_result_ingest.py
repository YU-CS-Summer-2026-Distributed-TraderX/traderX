"""Offline custody primitives; complete real-artifact tests use test-risk-result-ingest.py."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import cloud_handoff as c
import result_ingest as r
from corpus import encoded, digest
import test_cloud_handoff as fixture

class CustodyTests(unittest.TestCase):
    def test_inventory_refuses_symlinks_and_special_files(self):
        import os
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'a').write_text('evidence')
            (root/'link').symlink_to(root/'a')
            with self.assertRaises(ValueError):r.inventory(root)
            (root/'link').unlink();os.mkfifo(root/'pipe')
            with self.assertRaises(ValueError):r.inventory(root)

    def test_wrong_pin_never_calls_result_verifier(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'admission.json').write_text('{}')
            with patch.object(r,'verified_view') as verify:
                with self.assertRaises(ValueError):r.status(root,'0'*64)
                verify.assert_not_called()

class RelativePackageTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.ReceiptTests()
        self.fixture.setUp()

    def tearDown(self):
        self.fixture.tearDown()

    def test_relative_locator_is_portable_and_confined(self):
        t = self.fixture
        for name in ('archive.json','build-expected.json'):
            p=t.work/name;obj=c.read(p);obj['package_directory']='../package';p.write_bytes(encoded(obj))
        self.assertEqual(c.admit_build(t.work,t.actual,t.id,t.digest)['status'],'BUILD_RECEIPT_VERIFIED')
        p=t.work/'archive.json';obj=c.read(p);obj['package_directory']='../../package';p.write_bytes(encoded(obj))
        with self.assertRaises(ValueError):c.checked(t.work)
