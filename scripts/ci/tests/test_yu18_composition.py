"""Negative controls: a gate must reject stale sources and absent/skipped typed tests."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
GATE = ROOT/'scripts/ci/check-yu18-composition.py'
SOURCE = ROOT/'specs/YU18-risk-integration/generation/runtime-overrides'


class CompositionGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.gen = Path(self.tmp.name)/'generated'
        shutil.copytree(SOURCE, self.gen, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))

    def check(self, ok, *args):
        p = subprocess.run([sys.executable, str(GATE), '--generated', str(self.gen), *args],
                           capture_output=True, text=True)
        self.assertEqual(p.returncode == 0, ok, p.stdout + p.stderr)

    def test_exact_composition_passes(self):
        self.check(True)

    def test_missing_engine_override_fails(self):
        next(self.gen.rglob('MatchingEngine.java')).unlink()
        self.check(False)

    def test_stale_risk_adapter_fails(self):
        (self.gen/'eod-risk-bundles/container_adapter.py').write_text('stale')
        self.check(False)

    def test_absent_junit_fails(self):
        self.check(False, '--junit')

    def test_skipped_or_zero_test_evidence_fails(self):
        d = self.gen/'order-matcher/build/test-results/test'
        d.mkdir(parents=True)
        f = d/'TEST-finos.traderx.ordermatcher.lmax.OrderTypesEngineTest.xml'
        for xml in ('<testsuite tests="0"/>',
                    '<testsuite tests="1"><testcase name="ignored"><skipped/></testcase></testsuite>'):
            with self.subTest(xml=xml):
                f.write_text(xml)
                p = subprocess.run([sys.executable, str(GATE), '--generated', str(self.gen), '--junit'],
                                   capture_output=True, text=True)
                self.assertNotEqual(p.returncode, 0)
                self.assertIn('incomplete/failing typed suite', p.stderr)


if __name__ == '__main__':
    unittest.main()
