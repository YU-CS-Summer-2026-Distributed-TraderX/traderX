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
BUYS, SELLS = {9900002, 9900004, 9900006, 9900008}, {9900003, 9900005, 9900007}


class Gateway:
    """reject_every: refuse every Nth NEW (a risk reject); drop: never answer the Nth; dup: answer the Nth twice."""
    def __init__(self, test, reject_every=0, drop=0, dup=0):
        self.book = 0                     # >0 resting buys, <0 resting sells, all at one price
        self.orders, self.errors, self.n, self.lock = 0, [], 0, threading.Lock()
        self.reject_every, self.drop, self.dup = reject_every, drop, dup
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
                if (n, typ, flags, sec, qty, px) != (32, 1, 0, 7, 1, 412_500000) or acct not in (BUYS if side == 0 else SELLS):
                    self.errors.append((n, typ, side, flags, acct, sec, qty, px))
                with self.lock:
                    self.n += 1; i = self.n
                    status = 0 if self.reject_every and i % self.reject_every == 0 else 1
                    kind = 0
                    if status == 1:
                        crosses = self.book < 0 if side == 0 else self.book > 0
                        self.book += 1 if side == 0 else -1
                        kind = 4 if crosses else 0
                if i == self.drop: continue
                ack = struct.pack('<HBBBBIQ', 16, 0x81, status, kind, 0, i, cid)
                conn.sendall(ack * (2 if i == self.dup else 1))
        conn.close()

    def run(self, *extra, ticker='MSFT'):
        out = Path(tempfile.mkdtemp()) / 'r.json'
        p = subprocess.run(['node', str(LOAD), '--resolve', f'http://127.0.0.1:{self.http.server_port}', '--ticker', ticker,
                            '--px', '412.5', '--host', '127.0.0.1', '--port', str(self.srv.getsockname()[1]), '--sessions', '4',
                            '--rate', '500', '--secs', '2', '--drain-secs', '5', '--out', str(out), *extra],
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
        self.assertEqual(g.book, 0)
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


    def test_the_pods_run_on_loadgen_pool_once_against_the_lookup_only_resolve(self):
        p = subprocess.run([str(LOAD.with_name('binary-load.sh')), '3', 'node:20.18.0-alpine', '--', '--ticker', 'MSFT', '--px', '1'],
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr)
        for want in ('completions: 3', 'backoffLimit: 0', 'restartPolicy: Never', 'gke-nodepool: loadgen-pool',
                     '"--resolve", "http://order-matcher:18110", "--ticker", "MSFT"', 'binary-load.mjs: |'):
            self.assertIn(want, p.stdout)
        self.assertNotIn('--apply', p.stdout)

if __name__ == '__main__':
    unittest.main()
