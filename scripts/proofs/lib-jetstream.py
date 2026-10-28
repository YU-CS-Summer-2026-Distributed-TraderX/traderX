#!/usr/bin/env python3
"""lib-jetstream.py — a dependency-free JetStream reader for proofs (the NATS text protocol over a socket).

    count  <host:port> <stream> <subject-filter>               -> stored message count for the filter
    read   <host:port> <stream> <subject-filter> <start-seq>   -> one JSON line per stored message:
                                                                  {"seq":..,"subject":..,"data":<payload>}
    last   <host:port> <stream>                                -> the stream's last sequence
    info   <host:port> <stream>                                -> the STREAM.INFO response (config + state)
    purge  <host:port> <stream>                                -> WRITE: empty the stream (destructive proofs only)
    consumers <host:port> <stream>                             -> one JSON line per consumer: {"name":..,"durable":..,"filters":[..]}
    cdelete <host:port> <stream> <consumer>                    -> WRITE: delete one consumer (destructive proofs only)
    delete <host:port> <stream>                                -> WRITE: delete the stream (destructive proofs only)

Reads, except `purge`/`delete` (the two writes, for DESTRUCTIVE proofs): `count` and `last` use STREAM.INFO; `read` creates an ephemeral, ack-none pull consumer that
the server deletes when it goes inactive. Nothing is published to a stream subject.
"""
import json
import socket
import sys
import time
import uuid


class Nats:
    def __init__(self, addr):
        host, port = addr.rsplit(':', 1)
        self.s = socket.create_connection((host, int(port)), timeout=15)
        self.buf = b''
        self._line()  # INFO
        self.s.sendall(b'CONNECT {"verbose":false,"pedantic":false,"headers":true,"no_responders":true}\r\nPING\r\n')
        while self._line() != b'PONG':
            pass
        self.inbox = '_INBOX.' + uuid.uuid4().hex
        self.s.sendall(f'SUB {self.inbox}.* 1\r\n'.encode())

    def _fill(self):
        chunk = self.s.recv(1 << 20)
        if not chunk:
            raise ConnectionError('nats closed the connection')
        self.buf += chunk

    def _line(self):
        while b'\r\n' not in self.buf:
            self._fill()
        line, self.buf = self.buf.split(b'\r\n', 1)
        return line

    def _take(self, n):
        while len(self.buf) < n + 2:
            self._fill()
        data, self.buf = self.buf[:n], self.buf[n + 2:]
        return data

    def msgs(self, deadline):
        """Yield (subject, headers, payload) for messages on our inbox until the deadline; self.reply holds
        the last message's reply subject (for a JetStream delivery: $JS.ACK.<stream>.<consumer>.<n>.<sseq>...)."""
        self.s.settimeout(max(0.1, deadline - time.time()))
        while time.time() < deadline:
            try:
                line = self._line()
            except socket.timeout:
                return
            if line == b'PING':
                self.s.sendall(b'PONG\r\n'); continue
            parts = line.split()
            if not parts or parts[0] not in (b'MSG', b'HMSG'):
                continue
            if parts[0] == b'MSG':
                self.reply = parts[3].decode() if len(parts) == 5 else ''
                n = int(parts[-1]); yield parts[1].decode(), b'', self._take(n)
            else:
                self.reply = parts[3].decode() if len(parts) == 6 else ''
                hl, tl = int(parts[-2]), int(parts[-1])
                raw = self._take(tl); yield parts[1].decode(), raw[:hl], raw[hl:]

    def request(self, subject, payload, timeout=10):
        reply = f'{self.inbox}.{uuid.uuid4().hex[:8]}'
        body = json.dumps(payload).encode() if payload is not None else b''
        self.s.sendall(f'PUB {subject} {reply} {len(body)}\r\n'.encode() + body + b'\r\n')
        for subj, _h, data in self.msgs(time.time() + timeout):
            if subj == reply:
                return json.loads(data or b'{}')
        raise TimeoutError(subject)


def main():
    cmd, addr, stream = sys.argv[1], sys.argv[2], sys.argv[3]
    n = Nats(addr)
    if cmd == 'last':
        print(n.request(f'$JS.API.STREAM.INFO.{stream}', {})['state']['last_seq']); return
    if cmd == 'consumers':
        r = n.request(f'$JS.API.CONSUMER.LIST.{stream}', {'offset': 0})
        for c in r.get('consumers', []):
            cf = c.get('config', {})
            print(json.dumps({'name': c['name'], 'durable': bool(cf.get('durable_name')), 'filters': cf.get('filter_subjects') or [cf.get('filter_subject', '')]}))
        return
    if cmd == 'cdelete':
        print(json.dumps(n.request(f'$JS.API.CONSUMER.DELETE.{stream}.{sys.argv[4]}', None))); return
    if cmd in ('purge', 'delete'):
        print(json.dumps(n.request(f'$JS.API.STREAM.{cmd.upper()}.{stream}', {}))); return
    if cmd == 'info':
        print(json.dumps(n.request(f'$JS.API.STREAM.INFO.{stream}', {}))); return
    filt = sys.argv[4]
    if cmd == 'count':
        info = n.request(f'$JS.API.STREAM.INFO.{stream}', {'subjects_filter': filt})
        subs = (info.get('state') or {}).get('subjects') or {}
        print(sum(subs.values())); return
    start = int(sys.argv[5])
    name = 'proof' + uuid.uuid4().hex[:10]
    cfg = {'stream_name': stream, 'config': {'name': name, 'deliver_policy': 'by_start_sequence', 'opt_start_seq': start,
           'ack_policy': 'none', 'filter_subject': filt, 'inactive_threshold': 30_000_000_000, 'mem_storage': True}}
    r = n.request(f'$JS.API.CONSUMER.CREATE.{stream}.{name}', cfg)
    if 'error' in r:
        sys.exit(f'consumer create: {r["error"]}')
    pending = r.get('num_pending', 0)
    got = 0
    while got < pending:
        reply = f'{n.inbox}.next'
        body = json.dumps({'batch': min(1000, pending - got), 'no_wait': True}).encode()
        n.s.sendall(f'PUB $JS.API.CONSUMER.MSG.NEXT.{stream}.{name} {reply} {len(body)}\r\n'.encode() + body + b'\r\n')
        progressed = False
        for subj, hdr, data in n.msgs(time.time() + 5):
            if hdr.startswith(b'NATS/1.0 404') or hdr.startswith(b'NATS/1.0 408'):
                break
            got += 1; progressed = True
            ack = n.reply.split('.')
            seq = int(ack[-4]) if len(ack) >= 9 else None   # $JS.ACK.<stream>.<consumer>.<delivered>.<sseq>.<cseq>.<ts>.<pending>
            print(json.dumps({'seq': seq, 'subject': subj, 'data': json.loads(data) if data[:1] in (b'{', b'[') else data.decode(errors='replace')}))
            if got >= pending:
                break
        if not progressed:
            break
    print(json.dumps({'_read': got, '_pending': pending}))


if __name__ == '__main__':
    main()
