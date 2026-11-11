"""l1-report.py on a two-edge fixture: throttling, cycles and NET_RX are deltas, a max shows only when it rose, CPUs split own/other, SMT pairs found."""
import pathlib, subprocess, sys, unittest

HERE = pathlib.Path(__file__).parent


class L1Report(unittest.TestCase):
    def test_fixture_row(self):
        out = subprocess.run([sys.executable, HERE / 'l1-report.py', HERE / 'testdata/l1-report'],
                             capture_output=True, text=True, check=True).stdout.splitlines()
        self.assertEqual(out[2], '| blk | 0 | 30 / 4.0 | sender 7000 | sender 3 | 7 | 70/70 | '
                                 'sender 2, receiver 10, consensus-modul 3, clustered-servi 4 (receiver+sender) |')


if __name__ == '__main__':
    unittest.main()
