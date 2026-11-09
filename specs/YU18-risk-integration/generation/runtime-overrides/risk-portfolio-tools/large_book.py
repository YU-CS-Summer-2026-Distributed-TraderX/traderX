#!/usr/bin/env python3
"""Large-book pricing measurement (FR-RP-BOOK-03).

Input is the fixture ``VarEsEndToEndTest.largeBookThroughTheRealIngress`` wrote from a ``book_gen.py``
plan (a genuine three-member cut and the production exporters' files). This script packages it with
``eod-risk-bundles`` exactly as the proof does, maps every row with ``var_es_e2e``'s mapping (refusals by
class and owner), optionally TILES the priced trades ``--tile`` times (each copy keeps the genuine terms
and takes a ``~k`` identity suffix; a tiled run is labelled so, it is not a cut), and submits ONE run
(a portfolio's VaR is never split across runs). While it runs, the engine's process tree is sampled
for resident memory. Writes ``measure.json`` (and request/reply bytes) to a new evidence directory.

    python3 large_book.py --artifacts OUT --origin http://127.0.0.1:8766 --evidence EVID \
        [--tile 4] [--scenarios 256] [--analytics npv,market_risk] [--only bond,swap,...] \
        [--precision '{"profile": "float32"}']
"""
import argparse
import hashlib
import json
import math
import os
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import var_es_e2e as m
from var_es_e2e import bridge, bundle


def tree_rss_kib(root_pid):
    """Resident KiB of root_pid and its descendants (ps, by parent pid; never a pattern match)."""
    out = subprocess.run(['ps', '-axo', 'pid=,ppid=,rss='], capture_output=True, text=True, check=True).stdout
    rows = [tuple(int(x) for x in line.split()) for line in out.splitlines() if line.strip()]
    kids, rss = {}, {}
    for pid, ppid, r in rows:
        kids.setdefault(ppid, []).append(pid)
        rss[pid] = r
    total, stack = 0, [root_pid]
    while stack:
        p = stack.pop()
        total += rss.get(p, 0)
        stack += kids.get(p, [])
    return total


def listener_pid(origin):
    port = origin.rsplit(':', 1)[1].split('/')[0]
    out = subprocess.run(['lsof', '-nP', f'-iTCP:{port}', '-sTCP:LISTEN', '-t'], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--artifacts', required=True)
    ap.add_argument('--origin', required=True)
    ap.add_argument('--evidence', required=True)
    ap.add_argument('--tile', type=int, default=1)
    ap.add_argument('--scenarios', type=int, default=256)
    ap.add_argument('--analytics', default='npv,market_risk')
    ap.add_argument('--only', default='', help='comma list of engine trade types to keep (default: every priced one)')
    ap.add_argument('--limit', type=int, default=0, help='keep the first N mapped trades (after --only)')
    ap.add_argument('--deadline', type=float, default=6 * 3600)
    ap.add_argument('--precision', default='', help="JSON for the request's config.precision (default: the engine's float64)")
    ap.add_argument('--nonce', default='', help='mixed into the idempotency key, to re-measure an identical request')
    a = ap.parse_args()
    os.umask(0o077)
    evidence = Path(a.evidence)
    evidence.mkdir(parents=True)
    artifacts = Path(a.artifacts).resolve()
    receipt = sorted((artifacts / 'receipts').glob('*.ready.json'))[0]
    asof = json.loads(receipt.read_bytes())['sessionDate']
    t0 = time.monotonic()
    packaged = bridge.package(receipt, artifacts, evidence / 'inbox', 'large-book',
                              datetime.now(timezone.utc).isoformat(timespec='seconds'), 'export', asof)
    manifest, parsed = bundle.validate(Path(packaged['path']))
    t_bundle = time.monotonic() - t0
    trades, refused = [], {}
    for row in parsed['positions'][1]:
        try:
            trades.append(m.map_position(row))
        except m.Refused as r:
            x = m.refusal(r)
            refused.setdefault(f"{x['class']}/{x['owner']}/{x['request']}", 0)
            refused[f"{x['class']}/{x['owner']}/{x['request']}"] += 1
    for row in parsed['contracts'][1]:
        try:
            trades.append(m.map_contract(row))
        except m.Refused as r:
            x = m.refusal(r)
            refused.setdefault(f"{x['class']}/{x['owner']}/{x['request']}", 0)
            refused[f"{x['class']}/{x['owner']}/{x['request']}"] += 1
    spots = m.equity_spots(parsed['positions'][1])
    trades, unspotted = m.without_spot(trades, spots)
    for r in unspotted:
        x = m.refusal(r)
        refused[f"{x['class']}/{x['owner']}/{x['request']}"] = refused.get(f"{x['class']}/{x['owner']}/{x['request']}", 0) + 1
    only = set(filter(None, a.only.split(',')))
    if only:
        trades = [t for t in trades if t['trade_type'] in only]
    if a.limit:
        trades = trades[:a.limit]
    if a.tile > 1:
        trades = [dict(t, trade_id=f"{t['trade_id']}~{k}") for k in range(a.tile) for t in trades]
    mix = {}
    for t in trades:
        mix[t['trade_type']] = mix.get(t['trade_type'], 0) + 1
    analytics = a.analytics.split(',')
    body = m.run_request(trades, asof, None, scenarios=a.scenarios, spots=spots)
    body['analytics'] = analytics
    body['outputs'] = {'pnl': 'none'}
    if a.precision:
        body['config']['precision'] = json.loads(a.precision)
    if 'market_risk' not in analytics:
        body['config'].pop('market_risk')
        body['outputs'] = {}
    body['idempotency_key'] = 'large-book-' + hashlib.sha256(bundle.encoded({'cut': manifest['cut'], 'request': body, 'engine': m.ENGINE_REVISION, 'nonce': a.nonce})).hexdigest()[:24]
    request_bytes = bundle.encoded(body)
    (evidence / 'request.json').write_bytes(request_bytes)
    pid = listener_pid(a.origin)
    base_rss = tree_rss_kib(pid) if pid else None
    peak = {'kib': base_rss or 0}
    stop = threading.Event()

    def sample():
        while not stop.is_set():
            try:
                peak['kib'] = max(peak['kib'], tree_rss_kib(pid))
            except Exception:
                pass
            stop.wait(0.5)
    if pid:
        threading.Thread(target=sample, daemon=True).start()
    result = {'artifacts': str(artifacts), 'cut': manifest['cut'], 'bundle_seconds': round(t_bundle, 3),
              'precision': body['config'].get('precision'), 'tile': a.tile, 'tiled': a.tile > 1, 'scenarios': a.scenarios, 'analytics': analytics,
              'priced_trades': len(trades), 'mix': mix, 'refused': refused,
              'request_bytes': len(request_bytes), 'engine_rss_before_mib': base_rss and round(base_rss / 1024, 1)}
    t1 = time.monotonic()
    try:
        run_id, doc = m.submit_and_wait(a.origin, body, a.deadline)
        result['status'] = 'done'
    except RuntimeError as e:
        result['status'], result['error'] = 'failed', str(e)[:4000]
        doc = None
    result['wall_seconds'] = round(time.monotonic() - t1, 2)
    stop.set()
    result['engine_rss_peak_mib'] = round(peak['kib'] / 1024, 1)
    if doc:
        reply = bundle.encoded(doc)
        (evidence / 'reply.json').write_bytes(reply)
        r = doc['result']
        result['reply_bytes'] = len(reply)
        result['portfolio_npv'] = r.get('npv')
        if r.get('market_risk'):
            risk = r['market_risk']['risk']
            result['risk'] = {k: risk[k] for k in ('VaR_99', 'ES_99', 'VaR_97.5', 'ES_97.5')}
            result['risk_finite_nonzero'] = all(m.finite_nonzero(v) for v in result['risk'].values())
        npvs = [t['npv'] for t in r['trades']]
        result['trade_npv_finite'] = all(isinstance(x, (int, float)) and math.isfinite(x) for x in npvs)
        result['run_id'] = run_id
        result['engine'] = r.get('engine')
        result['engine_timings'] = {k: doc[k] for k in ('submitted', 'started', 'finished') if k in doc}
        if doc.get('started') and doc.get('finished'):
            result['engine_seconds'] = round((datetime.fromisoformat(doc['finished']) -
                                              datetime.fromisoformat(doc['started'])).total_seconds(), 2)
    (evidence / 'measure.json').write_text(json.dumps(result, indent=1, sort_keys=True))
    print(json.dumps(result, indent=1, sort_keys=True))
    return 0 if result['status'] == 'done' else 1


if __name__ == '__main__':
    raise SystemExit(main())
