#!/usr/bin/env python3
"""Offline wire fixture: real SQLite projection, synthetic live-window HTTP/K8s."""
import json
import os
from pathlib import Path
import re
import shlex
import signal
import sqlite3
import sys
import time

root = Path(os.environ['RECON_FIXTURE'])
name = Path(sys.argv[0]).name
a = sys.argv[1:]
scenario = (root / 'scenario').read_text().strip()
db = sqlite3.connect(root / 'projection.sqlite')
db.row_factory = sqlite3.Row
row_count = 0
db.create_function('ROW_COUNT', 0, lambda: row_count)
state = json.loads((root / 'runtime.json').read_text())
run = json.loads((root / 'run.json').read_text())
window = json.loads((root / 'window.json').read_text())

def save():
    (root / 'runtime.json').write_text(json.dumps(state))

def fail():
    sys.exit(7)

def classify():
    saved = db.execute("SELECT * FROM projection_recon WHERE projection_scope='scope_a'").fetchone()
    counts = [saved['matched'], saved['missing'], saved['mismatched']]
    cursor = saved['cursor_seq']
    lines = []
    for r in window:
        if r['tradeSeq'] <= cursor:
            continue
        row = db.execute('SELECT * FROM trades WHERE id=?', (r['id'],)).fetchone()
        if row is None:
            counts[1] += 1
        elif tuple(row[k] for k in ('accountid', 'security', 'side', 'quantity', 'price', 'projectionscope')) == (
                r['accountId'], r['security'], r['side'], r['quantity'], float(r['price']), run['projectionScope']):
            counts[0] += 1
        else:
            counts[2] += 1
            lines.append('WARN recon FIELD_MISMATCH id=' + r['id'])
        cursor = r['tradeSeq']
    state['status'] = dict(zip(('matched','missingInProjection','fieldMismatch'), counts), cursor=cursor,
                           lastSweepAt='2026-10-07T19:00:00Z')
    state['logs'] = '\n'.join(lines)
    db.execute("UPDATE projection_recon SET cursor_seq=?,matched=?,missing=?,mismatched=? WHERE projection_scope='scope_a'", (cursor,*counts))
    db.commit()
    if scenario == 'other-mismatch' and counts[2]:
        state['logs'] = 'WARN recon FIELD_MISMATCH id=e1-epoch_a-70-S'
    if scenario == 'prefix-mismatch' and counts[2]:
        state['logs'] = 'WARN recon FIELD_MISMATCH id=e1-epoch_a-7-S-extra'
    if scenario == 'stale-uid':
        state['uid'] = 'original-uid'
    if scenario == 'cursor-short':
        state['status']['cursor'] = 0
    save()

if name in ('sleep','pkill'):
    sys.exit(0)
if name == 'kubectl':
    a = a[4:]  # -n traderx --context offline
    if a[0] == 'exec':
        if a[1].startswith('order-matcher-cluster-'):
            if 'metrics' in a[-1]:
                print('traderx_cluster_trades 2')
            else:
                print(json.dumps(dict(indexedTrades=2,replayedAppliedSeq=10)))
            sys.exit(0)
        query = shlex.split(a[-1])[-1]
        with (root / 'queries.log').open('a') as f:
            f.write(query + '\n')
        if scenario == 'sql-error' and 'accountid,security' in query:
            fail()
        if scenario == 'checkpoint-error' and query.startswith('SELECT cursor_seq'):
            fail()
        if scenario == 'update-error' and query.startswith('UPDATE trades SET quantity=26'):
            # Ambiguous result: write landed before client error.
            db.execute(query); db.commit(); fail()
        if scenario == 'mutation-noop' and query.startswith('UPDATE trades SET quantity=26'):
            sys.exit(0)
        orphan_insert = query.startswith('INSERT INTO trades') and "'orphan-probe-" in query
        probe = re.search(r"'(orphan-probe-[a-f0-9]+-B)'", query)
        if orphan_insert:
            probe_id=probe.group(1)
            (root/'probe-id').write_text(probe_id)
            if scenario == 'orphan-insert-race':
                row=(probe_id,99,'IBM','Buy',777,123.0,'Processing','other_scope','other_epoch','epoch-v1','b'*64,55,'foreign-owner',None,None,None,None)
                db.execute('INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',row);db.commit()
                (root/'foreign-row.json').write_text(json.dumps(row))
                fail()
            if scenario == 'orphan-insert-error':
                fail()
            if scenario == 'orphan-insert-noop':
                sys.exit(0)
            if scenario == 'orphan-scope-before-insert':
                db.execute("UPDATE projection_active SET projection_scope='other_scope'");db.commit()
                (root/'external-active').touch()
        if query.startswith('DELETE FROM trades') and probe and scenario == 'orphan-delete-replace':
            probe_id=probe.group(1)
            row=(probe_id,99,'IBM','Buy',777,123.0,'Processing','other_scope','other_epoch','epoch-v1','b'*64,55,'foreign-owner',None,None,None,None)
            db.execute('DELETE FROM trades WHERE id=?',(probe_id,))
            db.execute('INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',row);db.commit()
            (root/'foreign-row.json').write_text(json.dumps(row))
        for q in query.split(';'):
            if not q.strip():
                continue
            cur = db.execute(q)
            if not cur.description:
                row_count=cur.rowcount
            if cur.description:
                for row in cur:
                    print('\t'.join('NULL' if v is None else str(v) for v in row))
        db.commit()
        if orphan_insert and scenario == 'orphan-insert-ambiguous':
            (root/'ambiguous-committed').touch()
            fail()
        if orphan_insert and scenario in ('orphan-term','orphan-hup','orphan-int','orphan-shell-term','orphan-shell-hup','orphan-shell-int'):
            target=os.getppid()
            if 'shell' in scenario:
                import subprocess
                target=int(subprocess.check_output(['ps','-o','ppid=','-p',str(target)],text=True).strip())
            os.kill(target, {'term':signal.SIGTERM,'hup':signal.SIGHUP,'int':signal.SIGINT}[scenario.rsplit('-',1)[-1]])
            time.sleep(.1)
        if scenario in ('term','int','hup','shell-term','shell-int','shell-hup') and query.startswith('UPDATE trades SET quantity=26'):
            (root / 'mutated').touch()
            # Deliver to the actual helper while it waits on this SQL subprocess.
            target=os.getppid()
            if scenario.startswith('shell-'):
                import subprocess
                target=int(subprocess.check_output(['ps','-o','ppid=','-p',str(target)],text=True).strip())
            os.kill(target, {'term':signal.SIGTERM,'int':signal.SIGINT,'hup':signal.SIGHUP}[scenario.removeprefix('shell-')])
            time.sleep(.1)
        if scenario == 'run-churn' and query.startswith('UPDATE trades SET quantity=26'):
            run['clusterEpoch'] = 'epoch_b'; (root / 'run.json').write_text(json.dumps(run))
        sys.exit(0)
    if a[0] == 'get':
        if a[1].startswith('deploy/'):
            print(json.dumps({'spec':{'replicas':state['replicas'],'selector':{'matchLabels':{'app':'trade-processor'}}}}))
        elif a[1] == 'pods':
            items=[] if not state['replicas'] else [{'metadata':{'name':'tp-' + str(state['generation']), 'uid':state['uid']},
                     'status':{'conditions':[{'type':'Ready','status':'True'}]}}]
            print(json.dumps({'items':items}))
        elif a[1] == 'secret':
            print('')
        sys.exit(0)
    if a[0] == 'scale':
        state['replicas'] = int(a[-1].split('=')[-1])
        if state['replicas']:
            state['generation'] += 1; state['uid'] = 'uid-' + str(state['generation']); classify()
        save(); sys.exit(0)
    if a[0] == 'rollout':
        if a[1] == 'restart':
            classify()
        sys.exit(0)
    if a[0] == 'wait':
        sys.exit(0)
    if a[0] == 'logs':
        if scenario == 'logs-error':
            fail()
        print(state['logs']); sys.exit(0)
    if a[0] == 'port-forward':
        print('Forwarding from 127.0.0.1:21919 -> 18091', flush=True)
        while True:
            time.sleep(1)
    raise SystemExit('unknown fake Kubernetes command ' + repr(a))
if name == 'curl':
    url = next(x for x in a if x.startswith('http://'))
    if 'auth/dev-token' in url:
        print('fixture-token')
    elif 'actuator/health' in url:
        print('200', end='')
    elif 'full-history/reindex' in url:
        print(json.dumps(dict(indexedTrades=2,replayedMessages=10,replayedAppliedSeq=10,
                             liveTradeCounterBefore=2,liveTradeCounterAfter=2)))
        if '-w' in a:
            print('200')
    elif 'run/status' in url:
        if scenario == 'http-error':
            fail()
        print(json.dumps(run))
    elif 'trades/blotter' in url:
        if scenario == 'invalid-json':
            print('<html>'); sys.exit(0)
        since=int(url.rsplit('=',1)[-1]); print(json.dumps([r for r in window if r['tradeSeq'] > since]))
    elif 'recon/status' in url:
        if scenario == 'poll-term' and state['generation'] > 0:
            os.kill(os.getppid(), signal.SIGTERM)
            time.sleep(.1)
        d = state['status']
        if scenario == 'invalid-status' and state['generation'] > 0:
            d = {'matched': 1}
        print(json.dumps(d))
    elif 'orphan-sweep' in url:
        if scenario == 'orphan-stale-shared-forward' and ':18091/' in url:
            fail()
        rows = db.execute("SELECT id FROM trades WHERE projectionscope=(SELECT projection_scope FROM projection_active WHERE singleton_id=1)").fetchall()
        history={r['id'] for r in window}
        ids = [r[0] for r in rows if r[0] not in history]
        local_count=len(rows)
        if scenario == 'orphan-empty':
            local_count=0;ids=[]
        if scenario == 'orphan-all':
            ids=[r[0] for r in rows]
        if ids and scenario in ('orphan-row-scope-change','orphan-row-owner-change','orphan-row-owner-case-change','orphan-row-field-change'):
            column={'orphan-row-scope-change':'projectionscope','orphan-row-owner-change':'sourceorderid','orphan-row-owner-case-change':'sourceorderid','orphan-row-field-change':'quantity'}[scenario]
            value=777 if column=='quantity' else 'foreign-owner'
            if scenario=='orphan-row-owner-case-change':
                value=db.execute('SELECT sourceorderid FROM trades WHERE id=?',(ids[0],)).fetchone()[0].upper()
            db.execute('UPDATE trades SET '+column+'=? WHERE id=?',(value,ids[0]));db.commit()
            row=tuple(db.execute('SELECT * FROM trades WHERE id=?',(ids[0],)).fetchone())
            (root/'foreign-row.json').write_text(json.dumps(row))
        if ids and scenario == 'orphan-scope-after-insert':
            db.execute("UPDATE projection_active SET projection_scope='other_scope'");db.commit()
            (root/'external-active').touch()
        if ids and scenario in ('orphan-sweep-term','orphan-sweep-hup'):
            os.kill(os.getppid(),signal.SIGTERM if scenario.endswith('term') else signal.SIGHUP)
            time.sleep(.1)
        if scenario == 'orphan-wrong-id' and ids:
            ids=[ids[0]+'-foreign']
        if scenario == 'orphan-still-named' and not ids and (root/'probe-id').exists():
            ids=[(root/'probe-id').read_text()]
        print(json.dumps(dict(localTradeCount=local_count,fullHistoryTradeCount=len(window),orphanCount=len(ids),orphanIds=ids)))
    else:
        raise SystemExit('unknown fake URL ' + url)
    sys.exit(0)
raise SystemExit('unknown fake command ' + name)
