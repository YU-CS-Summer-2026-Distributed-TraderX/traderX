"""FR-PRG-12: fix-rehearsal-load.mjs through a gateway drain, against a scripted acceptor. Offline; needs node."""
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import unittest

LOAD = Path(__file__).with_name('fix-rehearsal-load.mjs')
SOH = '\x01'


def frame(fields):
    body = SOH.join(f'{k}={v}' for k, v in fields) + SOH
    head = f'8=FIX.4.4{SOH}9={len(body)}{SOH}'
    return (head + body + f'10={sum((head + body).encode()) % 256:03d}{SOH}').encode()


def messages(conn):
    buf = b''
    while True:
        data = conn.recv(65536)
        if not data:
            return
        buf += data
        while (e := buf.find(b'\x0110=')) >= 0 and (cut := buf.find(b'\x01', e + 1)) >= 0:
            m, buf = buf[:cut + 1].decode(), buf[cut + 1:]
            yield dict(kv.split('=', 1) for kv in m.split(SOH) if kv)


class Drain(unittest.TestCase):
    def test_a_drain_loses_nothing_and_books_nothing_twice(self):
        srv = socket.create_server(('127.0.0.1', 0)); self.addCleanup(srv.close)
        seen = {'logons': [], 'conn2': [], 'answered': set(), 'errors': []}
        out = [1]
        def reply(conn, fields):
            conn.sendall(frame([('35', fields[0][1]), ('34', out[0]), ('52', '20261029-18:00:00.000')] + fields[1:])); out[0] += 1
        def acceptor():
            # Connection 1: answers the first 20 orders, leaves the next ones in flight, then drains (Logout).
            conn, _ = srv.accept(); last = 0; orders = 0
            for f in messages(conn):
                if not re.fullmatch(r'\d{8}-\d\d:\d\d:\d\d\.\d{3}', f['52']): seen['errors'].append('52 ' + f['52'])
                last = int(f['34'])
                if f['35'] == 'A': seen['logons'].append(f); reply(conn, [('35', 'A')]); continue
                if f['35'] != 'D': continue
                orders += 1
                if orders <= 20:
                    seen['answered'].add(f['11']); reply(conn, [('35', '8'), ('11', f['11']), ('39', '0'), ('150', '0')])
                if orders == 30:
                    reply(conn, [('35', '5')]); conn.close(); break
            # Connection 2: the resumed session. It asks for everything after the last message it read.
            conn, _ = srv.accept()
            for f in messages(conn):
                seen['conn2'].append(f)
                if f['35'] == 'A':
                    seen['logons'].append(f)
                    # Resumed, not reset; at or past the last number read (what the drain cut off is asked for again).
                    if f.get('141') == 'Y' or int(f['34']) <= last: seen['errors'].append(f"resume logon seq {f['34']} after {last}")
                    reply(conn, [('35', 'A')]); reply(conn, [('35', '2'), ('7', last + 1), ('16', 0)]); continue
                if f['35'] == 'F':   # the end-of-run sweep cancels what still rests; the first is already gone
                    seen.setdefault('cancels', []).append(f['41'])
                    if len(seen['cancels']) == 1:   # production 2026-10-29: an order cancelled unseen answers 35=9
                        reply(conn, [('35', '9'), ('11', f['11']), ('41', f['41']), ('39', '4'), ('434', '1'), ('102', '1')])
                    else:
                        reply(conn, [('35', '8'), ('11', f['11']), ('41', f['41']), ('39', '4'), ('150', '4')])
                if f['35'] == 'D':   # a resend of a key the engine already holds is restated (150=I)
                    restate = f['11'] in seen['answered']
                    if not restate: seen['answered'].add(f['11'])
                    reply(conn, [('35', '8'), ('11', f['11']), ('39', '0'), ('150', 'I' if restate else '0')])
                if f['35'] == '5': reply(conn, [('35', '5')]); conn.close(); return
        threading.Thread(target=acceptor, daemon=True).start()
        with tempfile.TemporaryDirectory() as d:
            creds = Path(d, 'creds.json'); creds.write_text(json.dumps({'CLIENT2': {'account': '9900002', 'password': 'not-printed-pw'}}))
            r = subprocess.run(['node', str(LOAD), '--creds', str(creds), '--px', '100.00', '--rate', '400', '--secs', '2',
                                '--host', '127.0.0.1', '--port', str(srv.getsockname()[1]), '--out', str(Path(d, 'r.json'))],
                               capture_output=True, text=True, timeout=60)
            result = json.loads(Path(d, 'r.json').read_text())
        self.assertEqual(seen['errors'], [])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn('not-printed-pw', r.stdout + r.stderr)
        self.assertEqual((result['unanswered'], result['duplicateAnswers'], result['reconnects'], result['restingAtEnd']), (0, 0, 1, 0))
        self.assertEqual(result['cancelledAtEnd'], result['answered'] - 1)   # every order rests; one was gone already
        self.assertEqual(len(seen['cancels']), len(set(seen['cancels'])))
        self.assertEqual(result['sent'], result['answered'])
        self.assertGreaterEqual(result['sessions'][0]['resent'], 10)
        self.assertEqual([l.get('141') for l in seen['logons']], ['Y', None])
        types = [f['35'] for f in seen['conn2']]
        gapfill = next(i for i, f in enumerate(seen['conn2']) if f['35'] == '4' and f.get('123') == 'Y')
        first_resend = next(i for i, f in enumerate(seen['conn2']) if f['35'] == 'D' and f.get('97') == 'Y')
        self.assertLess(gapfill, first_resend, types[:12])

    def test_the_rate_is_per_session_per_second(self):
        # Production 2026-10-29: --rate 5 sent 100/s per session (one order per 10 ms tick at least).
        srv = socket.create_server(('127.0.0.1', 0)); self.addCleanup(srv.close)
        def acceptor():
            conn, _ = srv.accept(); n = [1]
            for f in messages(conn):
                body = {'A': [('35', 'A')], 'D': [('35', '8'), ('11', f.get('11')), ('39', '2')], '5': [('35', '5')]}.get(f['35'])
                if body: conn.sendall(frame([body[0], ('34', n[0]), ('52', '20261029-18:00:00.000')] + body[1:])); n[0] += 1
                if f['35'] == '5': conn.close(); return
        threading.Thread(target=acceptor, daemon=True).start()
        with tempfile.TemporaryDirectory() as d:
            creds = Path(d, 'creds.json'); creds.write_text(json.dumps({'CLIENT2': {'account': '9900002', 'password': 'p'}}))
            subprocess.run(['node', str(LOAD), '--creds', str(creds), '--px', '1.00', '--rate', '5', '--secs', '2', '--host',
                            '127.0.0.1', '--port', str(srv.getsockname()[1]), '--out', str(Path(d, 'r.json'))], capture_output=True, timeout=60)
            sent = json.loads(Path(d, 'r.json').read_text())['sent']
        self.assertTrue(8 <= sent <= 11, sent)
    def test_an_unanswered_order_does_not_leave_the_others_resting(self):
        # Production 2026-10-29: 3 orders refused at the session layer kept the sweep from firing; one order rested on.
        srv = socket.create_server(('127.0.0.1', 0)); self.addCleanup(srv.close)
        seen = {'cancels': 0}
        def acceptor():
            conn, _ = srv.accept(); n = [1]; orders = [0]
            def reply(fields):
                conn.sendall(frame([fields[0], ('34', n[0]), ('52', '20261029-18:00:00.000')] + fields[1:])); n[0] += 1
            for f in messages(conn):
                if f['35'] == 'A': reply([('35', 'A')])
                if f['35'] == 'D':
                    orders[0] += 1
                    if orders[0] > 1: reply([('35', '8'), ('11', f['11']), ('39', '0')])   # the first is never answered
                if f['35'] == 'F': seen['cancels'] += 1; reply([('35', '8'), ('11', f['11']), ('41', f['41']), ('39', '4')])
                if f['35'] == '5': reply([('35', '5')]); conn.close(); return
        threading.Thread(target=acceptor, daemon=True).start()
        with tempfile.TemporaryDirectory() as d:
            creds = Path(d, 'creds.json'); creds.write_text(json.dumps({'CLIENT2': {'account': '9900002', 'password': 'p'}}))
            r = subprocess.run(['node', str(LOAD), '--creds', str(creds), '--px', '1.00', '--rate', '20', '--secs', '1',
                                '--drain-secs', '4', '--host', '127.0.0.1', '--port', str(srv.getsockname()[1]),
                                '--out', str(Path(d, 'r.json'))], capture_output=True, text=True, timeout=60)
            result = json.loads(Path(d, 'r.json').read_text())
        self.assertEqual(r.returncode, 1, r.stdout)   # an unanswered order still fails the run
        self.assertEqual((result['unanswered'], result['restingAtEnd']), (1, 0))
        self.assertEqual(seen['cancels'], result['sent'] - 1)

if __name__ == '__main__':
    unittest.main()


class Fills(unittest.TestCase):
    def test_a_fill_reported_twice_is_counted_and_fails_the_run(self):
        # Acceptance 450K (FR-FFR-14 routed fills): every order is answered once AND filled once; a second Filled(39=2)
        # for one ClOrdID is a duplicate fill report, which an hour at rate must show as 0.
        srv = socket.create_server(('127.0.0.1', 0)); self.addCleanup(srv.close)
        def acceptor():
            conn, _ = srv.accept(); n = [1]; orders = [0]
            def reply(fields):
                conn.sendall(frame([fields[0], ('34', n[0]), ('52', '20261110-18:00:00.000')] + fields[1:])); n[0] += 1
            for f in messages(conn):
                if f['35'] == 'A': reply([('35', 'A')])
                if f['35'] == 'D':
                    orders[0] += 1
                    reply([('35', '8'), ('11', f['11']), ('39', '0'), ('150', '0')])
                    reply([('35', '8'), ('11', f['11']), ('39', '2'), ('150', 'F')])
                    if orders[0] == 3: reply([('35', '8'), ('11', f['11']), ('39', '2'), ('150', 'F')])   # the duplicate
                if f['35'] == '5': reply([('35', '5')]); conn.close(); return
        threading.Thread(target=acceptor, daemon=True).start()
        with tempfile.TemporaryDirectory() as d:
            creds = Path(d, 'creds.json'); creds.write_text(json.dumps({'CLIENT2': {'account': '9900002', 'password': 'p'}}))
            r = subprocess.run(['node', str(LOAD), '--creds', str(creds), '--px', '1.00', '--rate', '20', '--secs', '1',
                                '--host', '127.0.0.1', '--port', str(srv.getsockname()[1]), '--out', str(Path(d, 'r.json'))],
                               capture_output=True, text=True, timeout=60)
            result = json.loads(Path(d, 'r.json').read_text())
        self.assertEqual((result['filled'], result['duplicateFills'], result['duplicateAnswers']), (result['sent'], 1, 0))
        self.assertEqual(r.returncode, 1, r.stdout)
