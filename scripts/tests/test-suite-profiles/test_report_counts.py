"""Independent terminal report fixtures: setup success and all-skipped are not execution."""
import importlib.util
from pathlib import Path
import unittest
import subprocess
import tempfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('ri31_runner', ROOT / 'scripts/ci/local-suite.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class ReportCounts(unittest.TestCase):
    def test_zero_or_setup_only_output_has_no_test_execution(self):
        for text in ['BUILD SUCCESSFUL\n: test NO-SOURCE\n', 'added 25 packages\n', 'Ran 0 tests\nOK']:
            self.assertEqual((0, 0), runner.output_test_counts(text))

    def test_unittest_counts_and_all_skipped(self):
        self.assertEqual((7, 0), runner.output_test_counts('Ran 7 tests in 1.0s\nOK'))
        total, skipped = runner.output_test_counts('Ran 3 tests in 1.0s\nOK (skipped=3)')
        self.assertEqual((3, 3), (total, skipped))
        self.assertEqual(0, total - skipped)

    def test_node_summary_does_not_count_nested_log_examples(self):
        text = '# tests 99\n# skipped 0\n# tests 15\n# skipped 2\n'
        self.assertEqual((15, 2), runner.output_test_counts(text))
        self.assertEqual((4, 4), runner.output_test_counts('ℹ tests 4\nℹ skipped 4'))

    def test_karma_terminal_total(self):
        self.assertEqual((46, 0), runner.output_test_counts('Chrome: Executed 46 of 46 SUCCESS\nTOTAL: 46 SUCCESS'))

    def test_failed_karma_summary_preserves_executed_and_skipped_cases(self):
        text = 'Executed 31 of 40 (16 FAILED) (skipped 9)\nTOTAL: 16 FAILED, 15 SUCCESS'
        self.assertEqual((40, 9), runner.output_test_counts(text))

    def test_pytest_summary_preserves_skips(self):
        self.assertEqual((7, 2), runner.output_test_counts('===== 4 passed, 1 failed, 2 skipped in 1.25s ====='))
        self.assertEqual((3, 3), runner.output_test_counts('3 skipped in 0.12s'))

class SourceIdentity(unittest.TestCase):
    def test_new_source_and_tracked_output_named_paths_are_bound_but_disposable_outputs_are_not(self):
        with tempfile.TemporaryDirectory(prefix='ri31-identity-') as tmp:
            root = Path(tmp)
            subprocess.run(['git', 'init', '-q', tmp], check=True)
            tracked = root / 'fixtures/dist/kept.js'
            tracked.parent.mkdir(parents=True); tracked.write_text('versioned fixture')
            subprocess.run(['git', '-C', tmp, 'add', '-f', 'fixtures/dist/kept.js'], check=True)
            source = root / 'new.py'; source.write_text('new source')
            for folder in ['dist', '.test-results']:
                (root / folder).mkdir(); (root / folder / 'generated.txt').write_text('first output')
            with patch.object(runner, 'ROOT', root):
                first = runner.source_identity()
                self.assertEqual({'fixtures/dist/kept.js', 'new.py'}, {x[0] for x in first['files']})
                (root / 'dist/generated.txt').write_text('second output')
                (root / '.test-results/generated.txt').write_text('second output')
                self.assertEqual(first['sha256'], runner.source_identity()['sha256'])
                source.write_text('changed source')
                second = runner.source_identity()['sha256']; self.assertNotEqual(first['sha256'], second)
                tracked.write_text('changed versioned fixture')
                self.assertNotEqual(second, runner.source_identity()['sha256'])

if __name__ == '__main__':
    unittest.main(verbosity=2)
