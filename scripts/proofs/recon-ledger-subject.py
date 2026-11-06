#!/usr/bin/env python3
"""Named negative controls for yu05-recon.sh on the ledger recon (trade-history-tiers FR-THT-01, 2026-11-06).

Rows mode was retired, so the forward recon classifies the members' journal against the capture stream, not SQL;
recon-proof-subject.py's SQL UPDATE is invisible to it (and that helper only ran on a managed epoch-v1 run). These
controls work in any scope, legacy-unknown (production's) included.

  --mode mismatch  With trade-processor at 0: delete the subject's message from its partition stream and publish a
                   copy with quantity+1 (no headers, so no dedup). The ledger reader keeps the last copy of an id;
                   the trade store refuses the copy as a duplicate tradeSeq, so SQL is untouched. Reset the scoped
                   recon checkpoint, resume, and require a fresh pod to log `recon FIELD_MISMATCH (ledger) id=<id>`.
                   Restore: delete the copy, republish the original, require a clean fresh classification.
  --mode orphan    Insert one uniquely named row for idle seed account 17017 (the snapshot store rewrites only
                   accounts that trade), require a fresh orphan sweep to name it, delete it, require it gone.

Each restores what it changed in `finally`. Side effect on the rig: two duplicate sides counted by the trade store's
accounting per mismatch run. Kind only (yu05-recon passes the context). Admin token on stdin.
"""
import argparse, base64, json, re, subprocess, sys, time, uuid

class Refusal(RuntimeError): pass

def require(ok, why):
    if not ok: raise Refusal(why)

class Rig:
    def __init__(self, a):
        self.a, self.token = a, sys.stdin.read().strip()
        require(self.token, 'missing admin token')
        self.k = ['kubectl', '--context', a.context, '-n', a.ns]

    def run(self, argv, timeout=330, stdin=None):
        p = subprocess.run(argv, text=True, capture_output=True, timeout=timeout, input=stdin)
        if p.returncode != 0: raise Refusal('command failed: ' + ' '.join(argv[:6]) + ' ... ' + p.stderr.strip()[-200:])
        return p.stdout.strip()

    def sql(self, q):
        return self.run(self.k + ['exec', 'deploy/' + self.a.db_deploy, '--', 'sh', '-c',
                                  'mariadb -u"${MARIADB_USER:-traderx}" -p"${MARIADB_PASSWORD:-traderx}" traderx -sN -e "$1"', 'sh', q])

    def nats(self, *args):
        return self.run(self.k + ['exec', self.a.nats_box, '--', 'nats', '-s', self.a.nats_url, *args])

    def tp(self, method, path, timeout=600):   # from inside the pod: no port-forward to own or lose
        pod = self.pod()
        return json.loads(self.run(self.k + ['exec', pod, '--', 'curl', '-sf', '-m', str(timeout), '-X', method,
                                             'localhost:18091' + path, '-H', 'Authorization: Bearer ' + self.token], timeout + 30))

    def om(self, path):
        return json.loads(self.run(['curl', '-sf', '-m', '30', self.a.om.rstrip('/') + path, '-H', 'Authorization: Bearer ' + self.token]))

    def pod(self):
        items = json.loads(self.run(self.k + ['get', 'pods', '-l', 'app=trade-processor', '-o', 'json']))['items']
        live = [p for p in items if not p['metadata'].get('deletionTimestamp')]
        require(len(live) == 1, 'trade-processor pod ambiguous: %d' % len(live))
        return 'pod/' + live[0]['metadata']['name']

    def scale(self, n):
        self.run(self.k + ['scale', 'deploy/trade-processor', '--replicas=%d' % n])
        if n == 0:
            self.run(self.k + ['wait', '--for=delete', 'pod', '-l', 'app=trade-processor', '--timeout=300s'])
        else:
            self.run(self.k + ['rollout', 'status', 'deploy/trade-processor', '--timeout=300s'])

    def checkpoint(self, values=None):
        if values is not None:
            self.sql("UPDATE projection_recon SET cursor_seq=%d,matched=%d,missing=%d,mismatched=%d WHERE projection_scope='%s';"
                     % (*values, self.scope))
        raw = self.sql("SELECT cursor_seq,matched,missing,mismatched FROM projection_recon WHERE projection_scope='%s';" % self.scope)
        require(re.fullmatch(r'\d+\t\d+\t\d+\t\d+', raw), 'unreadable recon checkpoint for ' + self.scope)
        return tuple(map(int, raw.split('\t')))

    def last(self, stream, subject):
        m = json.loads(self.nats('stream', 'get', stream, '--last-for', subject, '--json'))
        return m['seq'], base64.b64decode(m['data']).decode()

    def classified(self, tseq, sid, mismatch):
        for _ in range(self.a.attempts):
            try: s = self.tp('GET', '/recon/status', 15)
            except (Refusal, ValueError): time.sleep(self.a.poll); continue
            if int(s.get('cursor', 0)) >= tseq:
                ids = re.findall(r'recon FIELD_MISMATCH \(ledger\) id=(\S+)', self.run(self.k + ['logs', self.pod()]))
                named, bad = sid in ids, int(s['fieldMismatch'])
                require((named and bad > 0) if mismatch else (not named and bad == 0),
                        ('the planted copy of %s was not named FIELD_MISMATCH' if mismatch
                         else '%s did not classify cleanly after the restore') % sid)
                require(int(s['matched']) > 0, 'empty classification')
                print('   → fresh pod: cursor=%s fieldMismatch=%d subject %s named=%s' % (s['cursor'], bad, sid, named), flush=True)
                return
            time.sleep(self.a.poll)
        raise Refusal('the fresh classification never reached tradeSeq %d' % tseq)

    def mismatch(self):
        parts = int(self.tp('GET', '/v2/projection-feed/accounting', 30)['partitions'])
        found = None
        for p in range(parts):
            stream, subject = ('TRADERX_FEED_P%d' % p, '/trades.%d' % p) if parts > 1 else ('TRADERX_FEED', '/trades')
            try: seq, data = self.last(stream, subject)
            except Refusal: continue
            sid = json.loads(data)['payload']['id']
            if re.fullmatch(r'\d+-[BS]', sid): found = (stream, subject, seq, data, sid, int(sid[:-2])); break
        require(found, 'no trade side on any partition stream')
        stream, subject, seq, data, sid, tseq = found
        page = self.om('/recon/trades/blotter?sinceSeq=%d' % (tseq - 1))
        require(page and page[0]['id'] == sid, 'subject %s is not the journal blotter entry at tradeSeq %d' % (sid, tseq))
        self.scope = self.sql('SELECT projection_scope FROM projection_recon;')
        require(re.fullmatch(r'[a-z0-9_-]{1,64}', self.scope), 'recon checkpoint scope ambiguous')
        tampered = json.loads(data); tampered['payload']['quantity'] += 1
        tampered = json.dumps(tampered, separators=(',', ':'))
        saved, planted = None, None
        try:
            self.scale(0)
            saved = self.checkpoint()
            self.nats('stream', 'rmm', stream, str(seq), '-f')
            planted = True
            self.nats('pub', subject, tampered)
            tseq_copy, _ = self.last(stream, subject)
            print('   planted %s on %s: stream seq %d deleted, copy qty+1 at %d' % (sid, stream, seq, tseq_copy), flush=True)
            self.checkpoint((0, 0, 0, 0)); self.scale(1)
            self.classified(tseq, sid, True)
            self.scale(0)
            self.nats('stream', 'rmm', stream, str(tseq_copy), '-f'); self.nats('pub', subject, data); planted = False
            self.checkpoint((0, 0, 0, 0)); self.scale(1)
            self.classified(tseq, sid, False)
        finally:
            if planted:   # a failure between plant and restore: put the original back last
                self.scale(0)
                c, _ = self.last(stream, subject)
                if c != seq: self.nats('stream', 'rmm', stream, str(c), '-f')
                self.nats('pub', subject, data)
            if saved is not None:
                if planted is not None: self.scale(0)
                self.checkpoint(saved)
            self.scale(1)
            print('   → restored the subject on the stream, the recon checkpoint and one processor', flush=True)

    def orphan(self):
        probe = 'orphan-probe-%s-B' % uuid.uuid4().hex[:12]
        def sweep():
            d = self.tp('POST', '/recon/orphan-sweep')
            require(int(d['localTradeCount']) > 0 and int(d['fullHistoryTradeCount']) > 0, 'nothing reconciled')
            return set(d['orphanIds']), d
        before, _ = sweep()
        require(probe not in before, 'probe id already present')
        try:
            self.sql("INSERT INTO trades (id,accountid,security,side,quantity,price,state,created,updated,projectionscope)"
                     " VALUES ('%s',17017,'ORPHAN','Buy',1,1,'Settled',NOW(),NOW(),'legacy-unknown');" % probe)
            ids, d = sweep()
            require(probe in ids, 'the orphan sweep did not name the planted row %s' % probe)
            print('   → orphan sweep named %s (%s orphans of %s local rows)' % (probe, d['orphanCount'], d['localTradeCount']), flush=True)
        finally:
            self.sql("DELETE FROM trades WHERE id='%s';" % probe)
        ids, _ = sweep()
        require(probe not in ids and ids <= before, 'the restored projection still reports the probe or new orphans')
        print('   → removed %s; the sweep is back to its %d baseline orphan(s)' % (probe, len(ids)), flush=True)

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--mode', choices=('mismatch', 'orphan'), required=True)
    p.add_argument('--context', required=True); p.add_argument('--ns', default='traderx')
    p.add_argument('--om', required=True); p.add_argument('--db-deploy', default='eod-price-db')
    p.add_argument('--nats-box', default='pod/nats-box'); p.add_argument('--nats-url', default='nats://nats:4222')
    p.add_argument('--attempts', type=int, default=90); p.add_argument('--poll', type=float, default=2)
    a = p.parse_args()
    require(a.context.startswith('kind-'), 'kind only: this deletes stream messages and plants SQL rows')
    try:
        r = Rig(a); getattr(r, a.mode)(); return 0
    except (Refusal, ValueError, KeyError, subprocess.TimeoutExpired) as x:
        print('   ✘ ledger recon %s control refused: %s' % (a.mode, x), file=sys.stderr); return 1

if __name__ == '__main__':
    sys.exit(main())
