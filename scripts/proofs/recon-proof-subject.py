#!/usr/bin/env python3
"""RI-19: scoped, fresh, named negative control for yu05-recon.sh.

Only the proof changes. The YU18 reconciler persists its checkpoint, so a pod
restart alone cannot reclassify an existing subject. Pause its sole replica,
save/reset the selected checkpoint, then restore it and the subject in finally.
"""
import argparse
import json
import re
import shlex
import signal
import tempfile
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation


ACTIVE_COMMAND = False
PENDING_SIGNAL = None


class Refusal(RuntimeError):
    pass


class CommandFailure(Refusal):
    pass


def require(ok, reason):
    if not ok:
        raise Refusal(reason)


def integer(value, positive=False):
    require(type(value) is int and value >= (1 if positive else 0), 'invalid integer reading')
    return value


def safe(value, pattern):
    require(isinstance(value, str) and re.fullmatch(pattern, value), 'invalid identity reading')
    return value


class Control:
    def __init__(self, args):
        self.a = args
        self.k = ['kubectl', '-n', 'traderx', '--context', args.context]
        self.token = sys.stdin.read().strip()
        require(bool(self.token), 'missing admin token')
        self.forward = None
        self.forward_output = None
        self.borrowed = False
        self.saved = None
        self.subject = None
        self.saved_projection = None
        self.mutated = False
        self.selector = None
        self.scope = None
        self.run = None
        self.pod = None
        self.seen_uids = set()

    def command(self, argv, timeout=330):
        global ACTIVE_COMMAND, PENDING_SIGNAL
        ACTIVE_COMMAND = True
        try:
            p = subprocess.run(argv, text=True, capture_output=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise CommandFailure('command unreadable: ' + argv[0]) from exc
        finally:
            ACTIVE_COMMAND = False
        # Complete the bounded command before restoring. Killing a local kubectl
        # mid-UPDATE does not establish that its remote SQL process has stopped.
        if PENDING_SIGNAL is not None:
            signum, PENDING_SIGNAL = PENDING_SIGNAL, None
            raise Refusal('interrupted by signal ' + str(signum))
        # Do not print command text: it may include an authorization header.
        if p.returncode != 0:
            raise CommandFailure('command failed: ' + argv[0])
        return p.stdout.strip()

    def sql(self, query):
        shell = 'exec mariadb -utraderx -ptraderx traderx -N -B -e ' + shlex.quote(query)
        return self.command(self.k + ['exec', 'deploy/' + self.a.db_deploy, '--', 'sh', '-c', shell])

    def http(self, base, path):
        raw = self.command(['curl', '--fail', '--silent', '--show-error', '--max-time', '10',
                            base.rstrip('/') + path, '-H', 'Authorization: Bearer ' + self.token], 15)
        try:
            return json.loads(raw, parse_float=Decimal)
        except (ValueError, TypeError) as exc:
            raise Refusal('HTTP response is not JSON: ' + path) from exc

    def kjson(self, argv):
        try:
            return json.loads(self.command(self.k + argv + ['-o', 'json']))
        except ValueError as exc:
            raise Refusal('Kubernetes response is not JSON') from exc

    def run_identity(self):
        d = self.http(self.a.om, '/run/status')
        require(isinstance(d, dict), 'missing run descriptor')
        identity = (safe(d.get('projectionScope'), r'[a-z0-9_-]{1,64}'),
                    safe(d.get('clusterEpoch'), r'[a-z0-9_]{1,25}'),
                    safe(d.get('descriptorHash'), r'[0-9a-f]{64}'))
        require(d.get('eventIdScheme') == 'epoch-v1' and d.get('runPhase') in (2, 3),
                'unknown or inactive run; explicit epoch-v1 scope required')
        require(identity[0] != 'legacy-unknown', 'unknown projection scope')
        if self.run is not None:
            require(identity == self.run, 'run descriptor changed during control')
        return identity

    def page(self, since=0):
        rows = self.http(self.a.om, '/recon/trades/blotter?sinceSeq=' + str(since))
        require(isinstance(rows, list), 'unreadable live forward window')
        prev = since
        for row in rows:
            require(isinstance(row, dict), 'invalid live row')
            seq = integer(row.get('tradeSeq'), True)
            require(seq > prev, 'live window is not strictly forward ordered')
            prev = seq
            require(row.get('side') in ('Buy', 'Sell'), 'invalid trade side')
            expected = f"e1-{self.run[1]}-{seq}-" + ('B' if row['side'] == 'Buy' else 'S')
            require(row.get('id') == expected, 'foreign run or inconsistent live trade identity')
            integer(row.get('accountId'), True)
            integer(row.get('quantity'), True)
            integer(row.get('execTimeMillis'), True)
            safe(row.get('security'), r'[A-Za-z0-9_.:-]{1,50}')
            require(Decimal(str(row.get('price'))).is_finite() and Decimal(str(row['price'])) > 0,
                    'invalid live price')
        return rows

    def where(self, row):
        # Values are allowlisted above; full provenance predicates guard the write.
        return (f"id='{row['id']}' AND projectionscope='{self.scope}' "
                f"AND clusterepoch='{self.run[1]}' AND eventidscheme='epoch-v1' "
                f"AND rundescriptorhash='{self.run[2]}'"
                + (f" AND consensussequence={self.saved_projection[10]} AND accountid={row['accountId']} "
                   f"AND security='{row['security']}' AND side='{row['side']}' AND price={row['price']}"
                   if self.saved_projection is not None else ''))

    def projection(self, row):
        raw = self.sql('SELECT id,accountid,security,side,quantity,price,projectionscope,'
                       'clusterepoch,eventidscheme,rundescriptorhash,consensussequence '
                       f"FROM trades WHERE id='{row['id']}';")
        if not raw:
            return None
        lines = raw.splitlines()
        require(len(lines) == 1, 'ambiguous SQL trade identity')
        fields = lines[0].split('\t')
        require(len(fields) == 11, 'unreadable SQL trade')
        expected = [row['id'], str(row['accountId']), row['security'], row['side'],
                    str(row['quantity']), self.scope, self.run[1], 'epoch-v1', self.run[2]]
        require(fields[:5] + fields[6:10] == expected, 'SQL/live identity or scope differs')
        require(Decimal(fields[5]) == Decimal(str(row['price'])), 'SQL/live price differs')
        require(re.fullmatch(r'[1-9][0-9]*', fields[10]), 'missing SQL consensus provenance')
        if self.saved_projection is not None and row['id'] == self.subject['id']:
            require(fields == self.saved_projection, 'selected SQL identity changed during control')
        return fields

    def stop_forward(self):
        if self.forward is not None:
            self.forward.terminate()
            try:
                self.forward.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.forward.kill()
                self.forward.wait(timeout=5)
            self.forward = None
        if self.forward_output is not None:
            self.forward_output.close()
            self.forward_output = None

    def pause(self):
        self.stop_forward()
        self.command(self.k + ['scale', 'deploy/trade-processor', '--replicas=0'])
        self.command(self.k + ['wait', '--for=delete', 'pod', '-l', self.selector, '--timeout=300s'])
        require(self.kjson(['get', 'pods', '-l', self.selector]).get('items') == [],
                'processor still running; checkpoint cannot be reset')

    def checkpoint(self):
        raw = self.sql(f"SELECT cursor_seq,matched,missing,mismatched FROM projection_recon WHERE projection_scope='{self.scope}';")
        require(re.fullmatch(r'[0-9]+\t[0-9]+\t[0-9]+\t[0-9]+', raw),
                'missing or unreadable scoped reconciliation checkpoint')
        return tuple(map(int, raw.split('\t')))

    def write_checkpoint(self, values):
        c, m, missing, mismatched = values
        self.sql(f"UPDATE projection_recon SET cursor_seq={c},matched={m},missing={missing},mismatched={mismatched} WHERE projection_scope='{self.scope}';")
        require(self.checkpoint() == values, 'checkpoint write did not apply')

    def resume(self):
        global ACTIVE_COMMAND, PENDING_SIGNAL
        self.command(self.k + ['scale', 'deploy/trade-processor', '--replicas=1'])
        self.command(self.k + ['rollout', 'status', 'deploy/trade-processor', '--timeout=300s'])
        items = self.kjson(['get', 'pods', '-l', self.selector]).get('items')
        require(isinstance(items, list) and len(items) == 1, 'fresh processor pod ambiguous')
        p = items[0]
        self.pod = safe(p['metadata']['name'], r'[a-z0-9-]+')
        uid = safe(p['metadata']['uid'], r'[a-zA-Z0-9-]+')
        require(uid not in self.seen_uids and not p['metadata'].get('deletionTimestamp'),
                'processor pod was not replaced')
        self.seen_uids.add(uid)
        require(any(x.get('type') == 'Ready' and x.get('status') == 'True'
                    for x in p.get('status', {}).get('conditions', [])), 'fresh processor not ready')
        self.open_forward()
        return uid

    def open_forward(self):
        global ACTIVE_COMMAND, PENDING_SIGNAL
        # Let kubectl allocate its own local port; do not reserve and release a socket
        # with a race before the forward starts. Parse only its documented bind witness.
        self.forward_output = tempfile.TemporaryFile(mode='w+')
        ACTIVE_COMMAND = True
        try:
            self.forward = subprocess.Popen(self.k + ['port-forward', 'pod/' + self.pod, ':18091'],
                                           stdout=self.forward_output, stderr=self.forward_output)
        finally:
            ACTIVE_COMMAND = False
        if PENDING_SIGNAL is not None:
            signum, PENDING_SIGNAL = PENDING_SIGNAL, None
            raise Refusal('interrupted by signal ' + str(signum))
        for _ in range(self.a.attempts):
            require(self.forward.poll() is None, 'owned processor forward exited')
            self.forward_output.seek(0)
            output = self.forward_output.read()
            match = re.search(r'^Forwarding from 127\.0\.0\.1:([0-9]+) -> 18091$', output, re.M)
            if match:
                self.tp = 'http://localhost:' + match[1]
                break
            time.sleep(self.a.poll_seconds)
        else:
            raise Refusal('owned processor forward did not bind')

    def classification(self, mismatch):
        # Target cursor, not quiet counters: a gap between sweep pages is not completion.
        wanted = self.subject['tradeSeq']
        for _ in range(self.a.attempts):
            require(self.forward.poll() is None, 'owned processor forward exited')
            try:
                d = self.http(self.tp, '/recon/status')
            except CommandFailure:
                # A new port-forward may not yet be listening. Bound retries; no value fallback.
                time.sleep(self.a.poll_seconds)
                continue
            require(isinstance(d, dict), 'unreadable fresh classification')
            c, m, absent, bad = [integer(d.get(k)) for k in
                                ('cursor', 'matched', 'missingInProjection', 'fieldMismatch')]
            require(isinstance(d.get('lastSweepAt'), str) and bool(d['lastSweepAt']),
                    'classification has no sweep witness')
            if c >= wanted:
                logs = self.command(self.k + ['logs', 'pod/' + self.pod, '-c', 'trade-processor'])
                ids = re.findall(r'\brecon FIELD_MISMATCH id=([^\s]+)', logs)
                # Exact token comparison, not substring or a cumulative count.
                require((bad > 0 and self.subject['id'] in ids) if mismatch
                        else (bad == 0 and self.subject['id'] not in ids),
                        'fresh classification did not name planted subject' if mismatch
                        else 'restored subject did not classify cleanly')
                require(m + absent + bad > 0, 'empty classification')
                self.run_identity()
                print(f"   → fresh pod {self.pod}: cursor={c} FIELD_MISMATCH "
                      f"subject={self.subject['id']} named={self.subject['id'] in ids}", flush=True)
                return
            time.sleep(self.a.poll_seconds)
        raise Refusal('fresh classification did not reach the selected subject')

    def quantity(self, value, expected):
        self.sql(f"UPDATE trades SET quantity={value} WHERE {self.where(self.subject)} AND quantity={expected};")
        raw = self.sql(f"SELECT quantity FROM trades WHERE {self.where(self.subject)};")
        require(raw == str(value), 'subject quantity write did not apply')

    def restore(self):
        self.stop_forward()
        if not self.borrowed:
            return
        self.pause()
        if self.mutated:
            self.quantity(self.subject['quantity'], self.subject['quantity'] + 1)
            self.mutated = False
            self.projection(self.subject)
        if self.saved is not None:
            self.write_checkpoint(self.saved)
        self.command(self.k + ['scale', 'deploy/trade-processor', '--replicas=1'])
        self.command(self.k + ['rollout', 'status', 'deploy/trade-processor', '--timeout=300s'])
        require(self.kjson(['get', 'deploy/trade-processor'])['spec'].get('replicas') == 1,
                'processor replica restoration did not apply')
        self.borrowed = False
        print('   → restored original subject, scoped checkpoint and processor replica count', flush=True)

    def active_registration(self):
        row = self.sql('SELECT r.projection_scope,r.cluster_epoch,r.event_id_scheme,r.descriptor_hash,r.phase '
                       'FROM projection_active a JOIN projection_runs r ON a.projection_scope=r.projection_scope '
                       'WHERE a.singleton_id=1;')
        require(row == '\t'.join((*self.run[:2], 'epoch-v1', self.run[2], 'ACTIVE')),
                'SQL active scope does not agree with live descriptor')

    def probe_predicate(self):
        # Include every trade field: a changed/replaced row cannot inherit cleanup ownership.
        return (f"id='{self.probe}' AND accountid=42422 AND security='NVDA' AND side='Buy' "
                f"AND quantity=1 AND price=1 AND state='Processing' AND projectionscope='{self.scope}' "
                f"AND clusterepoch='{self.run[1]}' AND eventidscheme='epoch-v1' "
                f"AND rundescriptorhash='{self.run[2]}' AND consensussequence=1 "
                f"AND sourceorderid='{self.probe_owner}' AND created='{self.probe_time}' "
                f"AND updated='{self.probe_time}' AND settlementdate IS NULL AND rejectionreason IS NULL"
                # MariaDB string equality may use a case-insensitive collation. Byte guards
                # prevent altered ownership/provenance text from inheriting cleanup rights.
                + ''.join(f" AND HEX({column})=HEX('{value}')" for column, value in (
                    ('id',self.probe),('security','NVDA'),('side','Buy'),('state','Processing'),
                    ('projectionscope',self.scope),('clusterepoch',self.run[1]),
                    ('eventidscheme','epoch-v1'),('rundescriptorhash',self.run[2]),
                    ('sourceorderid',self.probe_owner))))

    def probe_population(self):
        # Atomic predicate read; distinguish missing, exact attributable contents, and foreign row.
        population = self.sql('SELECT COUNT(*),COALESCE(SUM(CASE WHEN ' + self.probe_predicate() +
                              f" THEN 1 ELSE 0 END),0) FROM trades WHERE id='{self.probe}';")
        require(population in ('0\t0','1\t0','1\t1'), 'unreadable probe ownership')
        return population.startswith('1'), population == '1\t1'

    def cleanup_probe(self):
        if not self.probe_attempted:
            return
        exists, exact = self.probe_population()
        if not exists:
            return
        if not exact:
            # Failed insert did not grant ownership. An externally changed owned row also
            # cannot be deleted. The predicates below still protect the read/delete race.
            require(not self.probe_owned, 'owned orphan probe contents changed; row retained')
            print('   → unattributed orphan row retained; no cleanup ownership', flush=True)
            return
        self.probe_owned = True  # Reconcile committed-then-error using the private invocation nonce.
        self.sql('DELETE FROM trades WHERE ' + self.probe_predicate() + ';')
        exists, _ = self.probe_population()
        require(not exists, 'orphan cleanup did not remove owned row; changed/replaced row retained')
        print('   → attributed orphan probe removed with full row predicates', flush=True)

    def orphan_sweep(self):
        d = self.http_post(self.tp, '/recon/orphan-sweep', 600)
        require(isinstance(d, dict), 'unreadable orphan sweep')
        local, journal, count = [integer(d.get(k)) for k in
                                 ('localTradeCount', 'fullHistoryTradeCount', 'orphanCount')]
        ids = d.get('orphanIds')
        require(isinstance(ids, list) and all(isinstance(i, str) for i in ids)
                and len(set(ids)) == len(ids) and len(ids) <= count, 'invalid orphan identity list')
        require(local > 0 and journal > 0, 'nothing to reconcile in orphan sweep')
        require(count < local, 'orphan sweep called every projection row an orphan')
        self.run_identity()
        self.active_registration()
        return count, ids

    def http_post(self, base, path, seconds):
        raw = self.command(['curl', '--fail', '--silent', '--show-error', '--max-time', str(seconds),
                            '-X', 'POST', base.rstrip('/') + path,
                            '-H', 'Authorization: Bearer ' + self.token], seconds + 15)
        try:
            return json.loads(raw)
        except ValueError as exc:
            raise Refusal('HTTP response is not JSON: ' + path) from exc

    def attach_processor(self):
        # The subject control replaced the processor, invalidating the caller's old tunnel.
        # Own a forward to the current ready pod without changing its replica setting.
        deployment = self.kjson(['get','deploy/trade-processor'])
        require(deployment['spec'].get('replicas') == 1, 'orphan processor replica is ambiguous')
        selector = deployment['spec']['selector']
        require(not selector.get('matchExpressions') and bool(selector.get('matchLabels')), 'unsupported selector')
        label = ','.join(f'{safe(k,r"[a-zA-Z0-9_./-]+") }={safe(v,r"[a-zA-Z0-9_.-]+")}'
                         for k,v in sorted(selector['matchLabels'].items()))
        items = self.kjson(['get','pods','-l',label]).get('items')
        require(isinstance(items,list) and len(items) == 1, 'orphan processor pod is ambiguous')
        pod=items[0]
        require(not pod['metadata'].get('deletionTimestamp') and
                any(c.get('type') == 'Ready' and c.get('status') == 'True'
                    for c in pod.get('status',{}).get('conditions',[])), 'orphan processor is not ready')
        self.pod = safe(pod['metadata']['name'],r'[a-z0-9-]+')
        self.open_forward()

    def orphan_control(self):
        global PENDING_SIGNAL
        try:
            self.attach_processor()
            self.orphan_rows()
        finally:
            PENDING_SIGNAL = None
            for sig in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP):
                signal.signal(sig,signal.SIG_IGN)
            self.stop_forward()

    def orphan_rows(self):
        global PENDING_SIGNAL
        self.run = self.run_identity()
        self.scope = self.run[0]
        self.active_registration()
        nonce = uuid.uuid4().hex
        self.probe = 'orphan-probe-' + nonce + '-B'  # 47 chars; no numeric epoch inference.
        self.probe_owner = 'ri19-' + nonce  # Existing sourceorderid column; unique attribution.
        self.probe_time = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')
        self.probe_attempted = False
        self.probe_owned = False
        baseline, baseline_ids = self.orphan_sweep()
        require(self.probe not in baseline_ids, 'probe already named before insertion')
        require(self.sql(f"SELECT COUNT(*) FROM trades WHERE id='{self.probe}';") == '0',
                'probe id already present before insertion')
        try:
            self.probe_attempted = True  # Even an ambiguous command response needs reconciliation.
            inserted = self.sql('INSERT INTO trades (id,accountid,security,side,quantity,price,state,'
                                'projectionscope,clusterepoch,eventidscheme,rundescriptorhash,consensussequence,'
                                'sourceorderid,created,updated,settlementdate,rejectionreason) '
                                f"SELECT '{self.probe}',42422,'NVDA','Buy',1,1,'Processing',"
                                f"r.projection_scope,r.cluster_epoch,r.event_id_scheme,r.descriptor_hash,1,"
                                f"'{self.probe_owner}','{self.probe_time}','{self.probe_time}',NULL,NULL "
                                'FROM projection_runs r JOIN projection_active a ON r.projection_scope=a.projection_scope '
                                f"WHERE a.singleton_id=1 AND r.projection_scope='{self.scope}' "
                                f"AND r.cluster_epoch='{self.run[1]}' AND r.descriptor_hash='{self.run[2]}' "
                                "AND r.phase='ACTIVE' AND r.event_id_scheme='epoch-v1' "
                                f"AND HEX(r.projection_scope)=HEX('{self.scope}') "
                                f"AND HEX(r.cluster_epoch)=HEX('{self.run[1]}') "
                                f"AND HEX(r.descriptor_hash)=HEX('{self.run[2]}') "
                                "AND HEX(r.event_id_scheme)=HEX('epoch-v1'); SELECT ROW_COUNT();")
            require(inserted == '1', 'orphan INSERT did not establish one owned row')
            self.probe_owned = True
            require(self.probe_population() == (True, True), 'owned orphan probe contents changed; row retained')
            print(f'   planted projection-only row {self.probe}', flush=True)
            count, ids = self.orphan_sweep()
            require(self.probe in ids, 'planted row was NOT detected as ORPHAN_IN_PROJECTION')
            print(f'   → the planted row is named as ORPHAN_IN_PROJECTION (count={count}; baseline={baseline}) ✔', flush=True)
            self.cleanup_probe()
            self.probe_attempted = False
            count, ids = self.orphan_sweep()
            require(self.probe not in ids, 'probe STILL named after attributed deletion')
            print(f'   → after removing the probe: {count} orphans; selected probe absent ✔', flush=True)
        finally:
            PENDING_SIGNAL = None
            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                signal.signal(sig, signal.SIG_IGN)
            try:
                self.cleanup_probe()
            except Exception as exc:
                raise Refusal(f'ORPHAN_RESTORATION_FAILED: probe={self.probe} scope={self.scope}; '
                              'no unqualified deletion; inspect retained row: ' + str(exc)) from exc

    def execute(self):
        global PENDING_SIGNAL
        self.run = self.run_identity()
        self.scope = self.run[0]
        scope_row = self.sql('SELECT r.projection_scope,r.cluster_epoch,r.event_id_scheme,r.descriptor_hash,r.phase '
                             'FROM projection_active a JOIN projection_runs r ON a.projection_scope=r.projection_scope '
                             'WHERE a.singleton_id=1;')
        require(scope_row == '\t'.join((*self.run[:2], 'epoch-v1', self.run[2], 'ACTIVE')),
                'SQL active scope does not agree with live descriptor')
        since = 0
        observed = 0
        for _ in range(90):
            page = self.page(since)
            if not page:
                break
            observed += len(page)
            for row in page:
                projected = self.projection(row)
                if projected is not None:
                    self.subject = row
                    self.saved_projection = projected
                    break
            if self.subject is not None:
                break
            since = page[-1]['tradeSeq']
        require(observed > 0, 'empty live forward window')
        require(self.subject is not None, 'no live-window/SQL intersection within the page budget')
        require(self.subject['quantity'] < 2147483647, 'quantity has no safe mismatch increment')
        deployment = self.kjson(['get', 'deploy/trade-processor'])
        require(deployment['spec'].get('replicas') == 1 and not deployment['spec'].get('paused'),
                'proof requires one unpaused processor replica')
        selector = deployment['spec']['selector']
        require(not selector.get('matchExpressions') and bool(selector.get('matchLabels')), 'unsupported selector')
        self.selector = ','.join(f'{safe(k, r"[a-zA-Z0-9_./-]+" )}={safe(v, r"[a-zA-Z0-9_.-]+")}'
                                 for k, v in sorted(selector['matchLabels'].items()))
        items = self.kjson(['get', 'pods', '-l', self.selector])['items']
        require(len(items) == 1, 'original processor pod ambiguous')
        self.seen_uids.add(items[0]['metadata']['uid'])
        try:
            self.borrowed = True  # Before the first mutation, including an ambiguous scale response.
            self.pause()
            self.saved = self.checkpoint()
            self.run_identity()
            # Re-read both sides after pausing; never mutate a subject evicted during selection.
            require(self.subject in self.page(self.subject['tradeSeq'] - 1), 'selected subject left the live window')
            self.projection(self.subject)
            self.write_checkpoint((0, 0, 0, 0))
            self.mutated = True  # Before UPDATE, for ambiguous mutation failure cleanup.
            self.quantity(self.subject['quantity'] + 1, self.subject['quantity'])
            print(f"   planted field mismatch row {self.subject['id']} qty "
                  f"{self.subject['quantity']} -> {self.subject['quantity'] + 1}", flush=True)
            self.resume()
            self.classification(True)
            self.pause()
            self.quantity(self.subject['quantity'], self.subject['quantity'] + 1)
            self.mutated = False
            self.projection(self.subject)
            self.run_identity()
            require(self.subject in self.page(self.subject['tradeSeq'] - 1), 'restored subject left the live window')
            self.write_checkpoint((0, 0, 0, 0))
            self.resume()
            self.classification(False)
        finally:
            # Repeated catchable signals must not interrupt the bounded cleanup operation.
            PENDING_SIGNAL = None
            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                signal.signal(sig, signal.SIG_IGN)
            try:
                self.restore()
            except Exception as exc:
                expected = (f"subject={self.subject['id']} quantity={self.subject['quantity']} "
                            if self.subject is not None else '')
                raise Refusal('RESTORATION_FAILED: ' + expected +
                              f'scope={self.scope} checkpoint={self.saved} replicas=1; '
                              'inspect before rig reuse: ' + str(exc)) from exc


def interrupted(signum, _frame):
    global PENDING_SIGNAL
    if ACTIVE_COMMAND:
        PENDING_SIGNAL = signum
    else:
        raise Refusal('interrupted by signal ' + str(signum))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mode', choices=('subject','orphan'), default='subject')
    p.add_argument('--context', required=True)
    p.add_argument('--db-deploy', required=True)
    p.add_argument('--om', required=True)
    p.add_argument('--attempts', type=int, default=90)
    p.add_argument('--poll-seconds', type=float, default=2)
    a = p.parse_args()
    require(1 <= a.attempts <= 90 and 0 <= a.poll_seconds <= 10, 'invalid poll budget')
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, interrupted)
    try:
        control = Control(a)
        if a.mode == 'orphan':
            control.orphan_control()
        else:
            control.execute()
        return 0
    except (Refusal, ValueError, KeyError, TypeError, OSError, InvalidOperation) as exc:
        print('   ✘ reconciliation ' + a.mode + ' control refused: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
