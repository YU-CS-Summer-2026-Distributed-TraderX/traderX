"""FR-PRG-16: binary-load.mjs against a scripted gateway (lookup-only /resolve + a one-price book). Offline; needs node."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import unittest

LOAD = Path(__file__).with_name('binary-load.mjs')
SETS = ({9900010, 9900012, 9900014, 9900016}, {9900011, 9900013, 9900015, 9900017})
import collections


class Gateway:
    """reject_every: refuse every Nth NEW (a risk reject); drop: never answer the Nth; dup: answer the Nth twice.
    sequenced (FR-XOD-11): every NEW must be flagged, each (account, streamId) counting from 1 with no gap; receipt:
    answer the Nth REJECTED/DUPLICATE with no ref (FR-XOD-03); oos: answer the Nth REJECTED/OUT_OF_SEQUENCE (FR-XOD-04)."""
    def __init__(self, test, reject_every=0, drop=0, dup=0, sequenced=True, receipt=0, oos=0):
        self.book = collections.deque()   # resting (side, account), all at one price, FIFO
        self.self_trades, self.buyers, self.sides = 0, set(), collections.defaultdict(set)
        self.orders, self.errors, self.n, self.lock = 0, [], 0, threading.Lock()
        self.reject_every, self.drop, self.dup = reject_every, drop, dup
        self.sequenced, self.receipt, self.oos, self.streams = sequenced, receipt, oos, {}   # (account, streamId) -> last seq
        self.srv = socket.create_server(('127.0.0.1', 0)); test.addCleanup(self.srv.close)
        class Resolve(BaseHTTPRequestHandler):
            def do_POST(h):
                body = json.loads(h.rfile.read(int(h.headers['content-length'])))
                ok = body.get('ticker') == 'MSFT'
                h.send_response(200 if ok else 404); h.end_headers()
                h.wfile.write(json.dumps({'ticker': 'MSFT', 'securityId': 7} if ok else {'error': 'unresolvable'}).encode())
            def log_message(h, *a): pass
        self.http = HTTPServer(('127.0.0.1', 0), Resolve); test.addCleanup(self.http.server_close)
        threading.Thread(target=self.http.serve_forever, daemon=True).start(); test.addCleanup(self.http.shutdown)
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while True:
            try: conn, _ = self.srv.accept()
            except OSError: return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        buf = b''
        while data := conn.recv(65536):
            buf += data
            while len(buf) >= 34:
                n, typ, side, flags = struct.unpack_from('<HBBB', buf)
                acct, sec, qty, px, cid = struct.unpack_from('<IIiqQ', buf, 6); buf = buf[34:]
                if (n, typ, flags, sec, qty, px) != (32, 1, int(self.sequenced), 7, 1, 412_500000) or acct not in SETS[0] | SETS[1]:
                    self.errors.append((n, typ, side, flags, acct, sec, qty, px))
                with self.lock:
                    self.n += 1; i = self.n
                    status = 0 if self.reject_every and i % self.reject_every == 0 else 1
                    kind = risk = ref = 0
                    if self.sequenced:
                        key, q = (acct, cid >> 32), cid & 0xffffffff
                        if cid >> 32 == 0 or q != self.streams.get(key, 0) + 1: self.errors.append(('sequence', acct, cid >> 32, q))
                        self.streams[key] = q
                        if i == self.receipt: status, risk = 0, 17
                        if i == self.oos: status, risk, ref = 0, 34, q
                    if status == 1:
                        if side == 0: self.buyers.add(acct)
                        self.sides[acct].add(side)
                        if self.book and self.book[0][0] != side:
                            self.self_trades += self.book.popleft()[1] == acct
                        else:
                            self.book.append((side, acct))
                if i == self.drop: continue
                ack = struct.pack('<HBBBBIQ', 16, 0x81, status, kind, risk, ref if risk else i, cid)
                conn.sendall(ack * (2 if i == self.dup else 1))
        conn.close()

    def run(self, *extra, ticker='MSFT'):
        out = Path(tempfile.mkdtemp()) / 'r.json'
        p = subprocess.run(['node', str(LOAD), '--resolve', f'http://127.0.0.1:{self.http.server_port}', '--ticker', ticker,
                            '--px', '412.5', '--host', '127.0.0.1', '--port', str(self.srv.getsockname()[1]), '--sessions', '4',
                            '--rate', '500', '--secs', '3', '--phase-secs', '1', '--pause-ms', '300', '--out', str(out),
                            *(() if self.sequenced else ('--unsequenced',)), *extra],
                           capture_output=True, text=True, timeout=60)
        return p, json.loads(out.read_text()) if out.exists() else None


class BinaryLoad(unittest.TestCase):
    def test_rejects_are_balanced_so_the_book_ends_empty(self):
        g = Gateway(self, reject_every=7)
        p, r = g.run()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(g.errors, [])
        self.assertGreater(r['sent'], 1000); self.assertGreater(r['rejected'], 100)
        self.assertEqual((r['unanswered'], r['duplicates'], r['imbalance']), (0, 0, 0))
        self.assertEqual(len(g.book), 0); self.assertEqual(g.self_trades, 0)
        self.assertTrue(g.buyers & SETS[0] and g.buyers & SETS[1], 'the account sets swap roles each phase')
        # Every account buys in one role and sells in the other, so none drifts toward the position limit (production
        # 2026-10-29: seq % n gave each side half the accounts, and those reached POSITION_LIMIT in minutes).
        self.assertEqual({a: s for a, s in g.sides.items()}, {a: {0, 1} for a in SETS[0] | SETS[1]})
        self.assertEqual(r['acceptedBuys'], r['acceptedSells'])
        self.assertEqual(r['securityId'], 7)

    def test_an_unknown_ticker_is_refused_before_any_order(self):
        g = Gateway(self)
        p, r = g.run(ticker='NOPE')
        self.assertEqual(p.returncode, 2); self.assertIsNone(r); self.assertEqual(g.n, 0)

    def test_an_unanswered_order_fails_the_run(self):
        g = Gateway(self, drop=50)
        p, r = g.run()
        self.assertEqual(p.returncode, 1); self.assertEqual(r['unanswered'], 1)

    def test_an_order_answered_twice_fails_the_run(self):
        g = Gateway(self, dup=50)
        p, r = g.run()
        self.assertEqual(p.returncode, 1); self.assertEqual(r['duplicates'], 1)


    def test_sequenced_frames_give_each_session_account_its_own_fresh_stream(self):
        # FR-XOD-11: the engine keys a stream by (account, streamId), and a session trades 8 accounts.
        g = Gateway(self)
        p, r = g.run()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(g.errors, [])
        self.assertTrue(r['sequenced']); self.assertGreater(r['sent'], 1000); self.assertEqual(r['streams'], 4 * 8)
        first = set(g.streams)
        self.assertEqual(len({s for _, s in first}), 4 * 8)   # 4 sessions x 8 accounts, one stream each
        g.run()                                                # a second run starts new streams at 1, never a reused one
        self.assertEqual(g.errors, []); self.assertFalse(first & (set(g.streams) - first))
        self.assertEqual(len(g.streams), 2 * len(first))

    def test_a_sequenced_duplicate_fails_the_run(self):
        # FR-XOD-03's DUPLICATE with no ref needs a resend, and this load never resends: a frame reached the engine twice
        g = Gateway(self, receipt=50)
        p, r = g.run()
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertEqual((r['sequencedDuplicates'], r['rejected'], r['outOfSequence']), (1, 0, 0))

    def test_unsequenced_sends_the_old_frame(self):
        # --unsequenced, for members older than ADR-090 (BINARY_SEQUENCED=refuse): flags 0, the run's own clOrdIds
        g = Gateway(self, sequenced=False, reject_every=7)
        p, r = g.run()
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(g.errors, []); self.assertFalse(r['sequenced']); self.assertEqual(r['streams'], 0)

    def test_out_of_sequence_fails_the_run(self):
        g = Gateway(self, oos=50)
        p, r = g.run()
        self.assertEqual(p.returncode, 1); self.assertEqual((r['outOfSequence'], r['rejected']), (1, 0))

    def test_the_pods_run_on_loadgen_pool_once_against_the_lookup_only_resolve(self):
        p = subprocess.run([str(LOAD.with_name('binary-load.sh')), '3', 'node:20.18.0-alpine', '--', '--ticker', 'MSFT', '--px', '1'],
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr)
        for want in ('completions: 3', 'backoffLimit: 0', 'restartPolicy: Never', 'gke-nodepool: loadgen-pool',
                     '"--resolve", "http://order-matcher:18110", "--ticker", "MSFT"', 'binary-load.mjs: |'):
            self.assertIn(want, p.stdout)
        self.assertNotIn('--apply', p.stdout)

    def test_a_ramp_step_scales_the_rate(self):
        full = Gateway(self).run()[1]['sent']
        p, r = Gateway(self).run('--ramp', '0.25', '--step-secs', '60')
        self.assertEqual(p.returncode, 0, p.stdout)
        self.assertLess(r['sent'], 0.45 * full); self.assertGreater(r['sent'], 0.1 * full); self.assertEqual(r['ramp'], 0.25)

    def test_both_clients_refuse_a_ramp_fraction_outside_0_to_1(self):
        creds = Path(tempfile.mkdtemp()) / 'c.json'; creds.write_text('{}')
        for cmd in (['node', str(LOAD), '--resolve', 'http://127.0.0.1:9', '--ticker', 'X', '--px', '1'],
                    ['node', str(LOAD.with_name('fix-rehearsal-load.mjs')), '--creds', str(creds), '--px', '1']):
            p = subprocess.run(cmd + ['--ramp', '0.5,2'], capture_output=True, text=True, timeout=30)
            self.assertEqual(p.returncode, 2, cmd[1] + p.stdout + p.stderr); self.assertIn('--ramp', p.stdout)

if __name__ == '__main__':
    unittest.main()
