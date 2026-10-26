#!/usr/bin/env python3
"""Alex's request, risk-engine half: VaR and ES end to end on each instrument TraderX books.

Input is what the TraderX half (``VarEsEndToEndTest``) wrote: the write-once ready receipt, the
positions and contracts fixtures and the cut. This script

1. packages the receipt with ``eod-risk-bundles`` (``bridge.package``: hashes, counts, stamp,
   schema, write-once inbox), exactly as the EOD path does;
2. maps every position and contract in the bundle to a ``POST /runs`` trade of Alex's upstream
   engine (``f9cc4c1``), refusing LOUDLY, per row, anything the engine has no faithful product
   for, and never substituting a term (a day count the engine does not implement is refused, not
   defaulted: the engine's own rule);
3. submits ONE run with ``analytics ["npv", "market_risk"]`` (Monte Carlo shocks of every curve
   pillar, ``outputs.pnl = inline``) for the priceable trades, polls it to ``done``;
4. asserts VaR and ES (the configured quantiles) are finite and non-zero for the portfolio, and
   attributes them to every trade from the inline P&L ``[scenario, trade]`` with the engine's
   own rank rule (``var_es.py``: VaR = -sorted[ceil(N(1-p)) - 1], ES = -mean(tail below -VaR));
5. runs the engine-side negative control: the same portfolio with a market that lacks one curve
   must be refused (400) before any run exists, never priced to 0;
6. writes ``report.json`` and exits 0 only when EVERY instrument in the bundle has finite,
   non-zero VaR and ES attributed to it. A refused instrument class is a failure of the request,
   reported by class and reason, so Alex sees exactly which product the engine still needs.

Standard library only. The market is a synthetic flat curve per currency (labelled synthetic in the
report; ``financial_validation`` is false): this proves the PATH, not the numbers.
"""
import argparse
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / 'eod_contract' if (HERE / 'eod_contract').is_dir() else HERE.parent / 'eod-risk-bundles'))
import bridge   # noqa: E402
import bundle   # noqa: E402

ENGINE_REVISION = 'f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b'
QUANTILES = (0.99, 0.975)
#: Day counts the engine implements (``engine.instruments.schedules``); anything else is refused by
#: the engine and therefore here. ``ACT/365F`` is Actual/365 (Fixed), which ORE spells ``ACT/365``:
#: a spelling, not a substitution.
DAY_COUNTS = {'ACT/365': 'ACT/365', 'ACT/365F': 'ACT/365', 'ACT/ACT (ICMA)': 'ACT/ACT (ICMA)'}
FREQUENCY_MONTHS = {'1M': 1, '3M': 3, '6M': 6, '1Y': 12}
PILLARS = [0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0]
FLAT_RATE = {'USD': 0.040, 'GBP': 0.045, 'EUR': 0.025, 'JPY': 0.005}
FX_USD = {'GBP': 1.27, 'EUR': 1.08, 'JPY': 0.0066}


class Refused(Exception):
    """An instrument the engine has no faithful product for; carries the class and reason."""
    def __init__(self, instrument_class, identity, reason):
        super().__init__(f'{instrument_class} {identity}: {reason}')
        self.instrument_class, self.identity, self.reason = instrument_class, identity, reason


# ---- mapping: bundle rows -> engine trades --------------------------------------------------------

def map_position(row):
    kind = row['instrumentType']
    identity = f"{row['accountId']}/{row['security']}"
    if kind in ('EQUITY', 'OPTION'):
        # engine.instruments has treasury, swap and swaptions only; engine.traderx.equity refuses a
        # cash equity (SPOT_SOURCE_NOT_SUPPLIED) and no module prices a listed option.
        raise Refused(kind, identity, f"engine {ENGINE_REVISION[:7]} has no {kind.lower()} product "
                                      "(engine.instruments: treasury, swap, swaptions)")
    if kind == 'CORPORATE':
        raise Refused(kind, identity, 'corporate bonds are refused by the engine (I-07)')
    if kind != 'TREASURY':
        raise Refused(kind, identity, 'unknown instrument type')
    coupon = bundle.decimal(row['coupon'], 'coupon')
    if coupon != 0:
        raise Refused(kind, identity, 'coupon-bearing Treasury: the fixture carries no coupon schedule; '
                                      'only bills are mapped here')
    face = bundle.decimal(row['quantity'], 'quantity') * bundle.decimal(row['contractMultiplier'], 'contractMultiplier')
    if face == 0:
        raise Refused(kind, identity, 'zero face')
    return {'trade_type': 'bond', 'trade_id': 'pos-' + hashlib.sha256(identity.encode()).hexdigest()[:16],
            'currency': row['currency'], 'face_amount': float(face), 'maturity_date': row['maturityDate'],
            'coupon_rate': 0.0, 'coupon_schedule': [], 'redemption_fraction': 1.0,
            'accrual_day_count': 'ACT/ACT (ICMA)'}


def map_contract(row):
    identity = row['contractId']
    kind = row['productType']
    if kind != 'SWAP':
        raise Refused(kind, identity, 'swaptions are not mapped here (strike/expiry conventions unverified)')
    day_count = DAY_COUNTS.get(row['dayCount'])
    if day_count is None:
        raise Refused('SWAP', identity, f"fixed-leg day count {row['dayCount']!r} is not implemented by the engine "
                                        f"(it implements {sorted(set(DAY_COUNTS.values()))}); refused, not defaulted")
    months = FREQUENCY_MONTHS.get(row['paymentFrequency'])
    if months is None:
        raise Refused('SWAP', identity, f"payment frequency {row['paymentFrequency']!r} has no index tenor mapping")
    return {'trade_type': 'swap', 'trade_id': 'ctr-' + identity, 'currency': row['currency'],
            'notional': float(bundle.decimal(row['notional'], 'notional')),
            'fixed_rate': float(bundle.decimal(row['fixedRate'], 'fixedRate')),
            'payer': row['payReceive'] == 'PAY_FIXED', 'effective_date': row['effectiveDate'],
            'maturity_date': row['maturityDate'], 'index_tenor_months': months, 'accrual_day_count': day_count}


def market_for(trades, asof):
    currencies = {}
    for t in trades:
        c = t['currency']
        if c not in FLAT_RATE:
            raise Refused(t['trade_type'].upper(), t['trade_id'], f'no synthetic curve for currency {c}')
        cur = currencies.setdefault(c, {'discount_curve': {'times': PILLARS, 'rates': [FLAT_RATE[c]] * len(PILLARS)},
                                        'index_curves': {}})
        if t['trade_type'] == 'swap':
            cur['index_curves'][f"{c}-SIMINDEX-{t['index_tenor_months']}M"] = {
                'times': PILLARS, 'rates': [FLAT_RATE[c] + 0.001] * len(PILLARS)}
    market = {'asof': asof, 'currencies': currencies}
    fx = {f'{c}USD': FX_USD[c] for c in currencies if c != 'USD'}
    if fx:
        market['fx_spots'] = fx
    return market


def factor_labels(market):
    labels = []
    for code, data in market['currencies'].items():
        curves = [(f'discount:{code}', data['discount_curve'])]
        curves += [(f'index:{name}', data['index_curves'][name]) for name in sorted(data['index_curves'])]
        labels += [f'{name}/{t:g}y' for name, curve in curves for t in curve['times']]
    return labels


def run_request(trades, asof, key, scenarios=1024, seed=7):
    market = market_for(trades, asof)
    labels = factor_labels(market)
    n = len(labels)
    daily_vol, horizon = 0.0008, 10   # 8 bp/day rate moves, 10-day horizon, 0.9^distance correlation
    covariance = [[daily_vol ** 2 * horizon * 0.9 ** abs(i - j) for j in range(n)] for i in range(n)]
    return {'market': market, 'portfolio': trades, 'analytics': ['npv', 'market_risk'],
            'config': {'base_currency': 'USD',
                       'market_risk': {'scenarios': {'source': 'monte-carlo', 'factors': labels, 'covariance': covariance,
                                                     'horizon_days': horizon, 'num_scenarios': scenarios, 'seed': seed},
                                       'quantiles': list(QUANTILES)}},
            'outputs': {'pnl': 'inline'}, 'idempotency_key': key, 'keep': True}


# ---- engine transport ----------------------------------------------------------------------------

def http(origin, path, body=None, timeout=60):
    req = urllib.request.Request(origin + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'}, method='POST' if body is not None else 'GET')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


def submit_and_wait(origin, body, deadline_s):
    status, acc = http(origin, '/runs', body)
    if status != 202:
        raise RuntimeError(f'POST /runs refused: {status} {acc}')
    run_id = acc['run_id']
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        status, doc = http(origin, f'/runs/{run_id}')
        if status != 200:
            raise RuntimeError(f'GET /runs/{run_id}: {status} {doc}')
        if doc['status'] in ('done', 'failed', 'interrupted', 'cancelled'):
            if doc['status'] != 'done':
                raise RuntimeError(f"run {run_id} {doc['status']}: {doc.get('error')}")
            return run_id, doc
        time.sleep(0.5)
    raise RuntimeError(f'run {run_id} did not finish within {deadline_s}s')


# ---- statistics, the engine's rule -----------------------------------------------------------------

def var_es(pnl, p):
    """ORE/engine rank rule on one P&L sample: VaR = max(-sorted[ceil(N(1-p)) - 1], 0); ES = -mean of the
    sample strictly below -VaR (NaN if that tail is empty)."""
    s = sorted(pnl)
    n = len(s)
    idx = min(max(math.ceil((1 - p) * n) - 1, 0), n - 1)
    var = max(-s[idx], 0.0)
    tail = [x for x in s if x < -var]
    es = -sum(tail) / len(tail) if tail else float('nan')
    return var, es


def finite_nonzero(x):
    return x is not None and isinstance(x, (int, float)) and math.isfinite(x) and x != 0.0


# ---- the proof --------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--artifacts', required=True, help='the TraderX half\'s output directory (cut.txt, positions.csv, contracts.csv, receipts/)')
    ap.add_argument('--origin', required=True, help='risk engine origin, e.g. http://127.0.0.1:8765')
    ap.add_argument('--evidence', required=True, help='NEW private directory for the bundle inbox, request/reply bytes and report.json')
    ap.add_argument('--epoch', default='var-es-e2e')
    ap.add_argument('--deadline', type=float, default=600.0)
    ap.add_argument('--scenarios', type=int, default=1024)
    args = ap.parse_args()
    os.umask(0o077)
    artifacts, evidence = Path(args.artifacts).resolve(), Path(args.evidence)
    if evidence.exists():
        ap.exit(2, 'evidence directory must be new\n')
    evidence.mkdir(parents=True)
    report = {'status': 'FAILED', 'engine_revision_expected': ENGINE_REVISION, 'synthetic_market': True,
              'financial_validation': False, 'quantiles': list(QUANTILES)}

    def finish(code):
        (evidence / 'report.json').write_bytes(bundle.encoded(report))
        print(json.dumps(report, indent=2, sort_keys=True))
        return code

    # 1. package the receipt the way the EOD path does
    receipts = sorted((artifacts / 'receipts').glob('*.ready.json'))
    if len(receipts) != 1:
        report['error'] = f'expected one ready receipt under {artifacts}/receipts, found {len(receipts)}'
        return finish(2)
    event = json.loads(receipts[0].read_bytes())
    asof = event['sessionDate']
    packaged = bridge.package(receipts[0], artifacts, evidence / 'inbox', args.epoch,
                              datetime.now(timezone.utc).isoformat(timespec='seconds'), 'export', asof)
    manifest, parsed = bundle.validate(Path(packaged['path']))
    report['bundle'] = {'bundleId': packaged['bundleId'], 'cut': manifest['cut'],
                        'positions': len(parsed['positions'][1]), 'contracts': len(parsed['contracts'][1])}

    # 2. map every row; collect refusals per instrument class
    trades, origin_of, refused = [], {}, []
    for row in parsed['positions'][1]:
        try:
            t = map_position(row)
            trades.append(t)
            origin_of[t['trade_id']] = {'class': row['instrumentType'], 'accountId': row['accountId'], 'security': row['security']}
        except Refused as r:
            refused.append({'class': r.instrument_class, 'identity': r.identity, 'reason': r.reason})
    for row in parsed['contracts'][1]:
        try:
            t = map_contract(row)
            trades.append(t)
            origin_of[t['trade_id']] = {'class': 'SWAP', 'contractId': row['contractId'], 'accountId': row['accountId'],
                                        'conventions': f"{row['floatIndex']}/{row['paymentFrequency']}/{row['dayCount']}"}
        except Refused as r:
            refused.append({'class': r.instrument_class, 'identity': r.identity, 'reason': r.reason})
    report['refused'] = refused
    report['mapped'] = origin_of
    if not trades:
        report['error'] = 'nothing in the bundle is priceable by the engine'
        return finish(1)

    # 3. one run for the priceable trades
    key = 'var-es-e2e-' + hashlib.sha256(bundle.encoded({'cut': manifest['cut'], 'trades': trades})).hexdigest()[:24]
    body = run_request(trades, asof, key, scenarios=args.scenarios)
    (evidence / 'request.json').write_bytes(bundle.encoded(body))
    status, caps = http(args.origin, '/health')
    report['engine_health'] = caps
    try:
        run_id, doc = submit_and_wait(args.origin, body, args.deadline)
    except RuntimeError as e:
        report['error'] = str(e)
        return finish(1)
    (evidence / 'reply.json').write_bytes(bundle.encoded(doc))
    result = doc['result']
    mr = result['market_risk']
    report['run'] = {'run_id': run_id, 'source': mr['source'], 'measure': mr['measure'], 'num_scenarios': mr['num_scenarios'],
                     'horizon_days': mr['horizon_days'], 'risk_factors': len(mr['risk_factors']), 'base_npv': mr['base_npv'],
                     'risk': mr['risk'], 'npv': result.get('npv')}

    # 4. VaR and ES: portfolio figures from the engine, per-trade attribution from the inline P&L
    problems = []
    for q in QUANTILES:
        label = f"{q * 100:g}"
        for name in (f'VaR_{label}', f'ES_{label}'):
            if not finite_nonzero(mr['risk'].get(name)):
                problems.append(f"portfolio {name} is {mr['risk'].get(name)!r}, not finite non-zero")
    pnl = mr['pnl']
    if not pnl or pnl.get('form') != 'inline' or pnl['axes'] != ['scenario', 'trade']:
        problems.append(f'P&L array not inline [scenario, trade]: {None if not pnl else {k: pnl[k] for k in pnl if k != "values"}}')
        attribution = {}
    else:
        ids = pnl['items']['ids']
        values = pnl['values']
        expected_sha = hashlib.sha256(json.dumps(values, separators=(',', ':')).encode()).hexdigest()
        attribution = {}
        for j, trade_id in enumerate(ids):
            column = [row[j] for row in values]
            figures = {}
            for q in QUANTILES:
                v, e = var_es(column, q)
                figures[f'VaR_{q * 100:g}'], figures[f'ES_{q * 100:g}'] = v, e
                for name, x in ((f'VaR_{q * 100:g}', v), (f'ES_{q * 100:g}', e)):
                    if not finite_nonzero(x):
                        problems.append(f'trade {trade_id} ({origin_of.get(trade_id)}): {name} = {x!r}')
            attribution[trade_id] = dict(origin_of.get(trade_id, {}), **figures,
                                         base_npv=next(t['base_npv'] for t in mr['trades'] if t['trade_id'] == trade_id))
        missing = [t['trade_id'] for t in trades if t['trade_id'] not in ids]
        if missing:
            problems.append(f'trades without a P&L column: {missing}')
        # the sum of the columns is the portfolio P&L the engine's figures use: check VaR at the first quantile
        portfolio = [sum(row) for row in values]
        v0, _ = var_es(portfolio, QUANTILES[0])
        if not math.isclose(v0, mr['risk'][f'VaR_{QUANTILES[0] * 100:g}'], rel_tol=1e-9, abs_tol=1e-6):
            problems.append(f"portfolio VaR recomputed from the P&L ({v0}) != engine ({mr['risk'][f'VaR_{QUANTILES[0] * 100:g}']})")
    report['attribution'] = attribution

    # 5. engine-side negative control: drop one curve the portfolio needs; the engine must refuse
    #    before any run exists (400), never price the trade to 0.
    control = json.loads(json.dumps(body))
    swap = next((t for t in trades if t['trade_type'] == 'swap'), None)
    if swap is not None:
        currency = swap['currency']
        removed = f"{currency}-SIMINDEX-{swap['index_tenor_months']}M index curve (the swap's floating index)"
        control['market']['currencies'][currency]['index_curves'].clear()
    else:
        currency = trades[0]['currency']
        removed = f'the whole {currency} market'
        del control['market']['currencies'][currency]
        control['market']['fx_spots'] = {k: v for k, v in control['market'].get('fx_spots', {}).items() if currency not in k} or None
        if control['market']['fx_spots'] is None:
            del control['market']['fx_spots']
    control['config']['market_risk']['scenarios']['factors'] = factor_labels(control['market'])
    n = len(control['config']['market_risk']['scenarios']['factors'])
    control['config']['market_risk']['scenarios']['covariance'] = [r[:n] for r in control['config']['market_risk']['scenarios']['covariance'][:n]]
    control['idempotency_key'] = key + '-negative-control'
    status, detail = http(args.origin, '/runs', control)
    report['negative_control'] = {'removed': removed, 'status': status, 'detail': detail}
    if status != 400 or currency not in json.dumps(detail):
        problems.append(f'negative control: a market missing {removed} answered {status} {detail}; '
                        f'expected 400 naming {currency} (refused before queueing, never priced to 0)')

    # 6. verdict: every instrument in the bundle, finite non-zero VaR and ES, refusals are failures
    for r in refused:
        problems.append(f"{r['class']} {r['identity']}: NOT priced: {r['reason']}")
    report['problems'] = problems
    classes = sorted({o['class'] for o in origin_of.values()} | {r['class'] for r in refused})
    report['coverage'] = {c: {'priced': sum(1 for o in attribution.values() if o.get('class') == c),
                              'refused': sum(1 for r in refused if r['class'] == c)} for c in classes}
    report['status'] = 'PASSED' if not problems else 'FAILED'
    return finish(0 if not problems else 1)


if __name__ == '__main__':
    raise SystemExit(main())
