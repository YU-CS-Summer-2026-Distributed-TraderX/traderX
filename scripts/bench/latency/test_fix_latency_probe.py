"""FR-OPL-08: fix-latency-probe.mjs against testdata/mock-fix-acceptor.mjs. Offline; needs node."""
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

HERE = Path(__file__).parent
PROBE, MOCK = HERE / 'fix-latency-probe.mjs', HERE / 'testdata/mock-fix-acceptor.mjs'
CREDS = {'CLIENT2': {'account': '9900002', 'password': 'not-printed-pw'}, 'CLIENT3': {'account': '9900003', 'password': 'p3'}}


def run(*mock_args, secs='3'):
    mock = subprocess.Popen(['node', str(MOCK), *mock_args], stdout=subprocess.PIPE, text=True)
    try:
        port = mock.stdout.readline().split()[1]
        with tempfile.TemporaryDirectory() as d:
            creds = Path(d, 'creds.json'); creds.write_text(json.dumps(CREDS))
            r = subprocess.run(['node', str(PROBE), '--creds', str(creds), '--host', '127.0.0.1', '--port', port, '--ticker', 'T',
                                '--px', '50', '--rate', '200', '--secs', secs, '--warmup', '1', '--window', '1', '--drain', '2'],
                               capture_output=True, text=True, timeout=60)
    finally:
        mock.kill(); mock.stdout.close(); mock.wait()
    total = next((l for l in r.stdout.splitlines() if l.startswith('PROBE-TOTAL')), '')
    ack, _, fill, _ = (dict(re.findall(r'(\S+?)=(\d+)(?:us)?(?=\s|$)', part)) for part in total.split('|'))
    tail = dict(re.findall(r'(\w+)=(\d+)', total.split('|')[-1]))
    return r, ack, fill, tail


class Stages(unittest.TestCase):
    def test_the_injected_delays_land_on_their_stage(self):
        r, ack, fill, tail = run('--ack-ms', '2', '--fill-ms', '5')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn('not-printed-pw', r.stdout + r.stderr)
        self.assertTrue(r.stdout.count('PROBE-WINDOW') >= 3, r.stdout)
        self.assertLessEqual(abs(int(ack['count']) - 2 * int(fill['count'])), 1)   # two orders per pair (one pair spans the warm-up)
        self.assertTrue(500 <= int(ack['count']) <= 620, ack)                 # 200/s for 3 s after the warm-up
        self.assertTrue(2000 <= int(ack['p50']) < 4500, ack)                  # 2 ms ack (+ the mock's timer slack)
        self.assertTrue(5000 <= int(fill['p50']) < 7500, fill)                # 5 ms fill
        self.assertEqual({k: tail[k] for k in ('unanswered', 'unended', 'skipped', 'lostSessions', 'uncrossed')},
                         dict.fromkeys(('unanswered', 'unended', 'skipped', 'lostSessions', 'uncrossed'), '0'))

    def test_an_aggressor_answered_filled_is_both_stages(self):
        # the gateway answers an order that fills on arrival with Filled, no New (FR-FFR-01)
        r, ack, fill, _ = run('--ack-ms', '2', '--fill-ms', '5', '--filled-ack')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertTrue(int(ack['p90']) >= 5000, ack)                         # the upper half: sells' first answer, the 5 ms fill
        self.assertTrue(5000 <= int(fill['p50']) < 7500, fill)


class Refusals(unittest.TestCase):
    def test_a_dropped_session_fails_the_run(self):
        r, _, _, tail = run('--drop-after', '100')
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertEqual(tail['lostSessions'], '1')
        self.assertIn('[probe] LOST', r.stdout)
        self.assertGreater(int(tail['skipped']), 0)
        self.assertGreater(int(tail['unanswered']), 0)                        # the order that went with the session

    def test_without_accounts_the_sessions_must_pair(self):
        with tempfile.TemporaryDirectory() as d:
            creds = Path(d, 'creds.json'); creds.write_text(json.dumps({'CLIENT2': CREDS['CLIENT2']}))
            r = subprocess.run(['node', str(PROBE), '--creds', str(creds), '--ticker', 'T', '--px', '50'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertIn('refused: without --accounts', r.stdout)


if __name__ == '__main__':
    unittest.main()
