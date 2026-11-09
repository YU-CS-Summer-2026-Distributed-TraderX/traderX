#!/usr/bin/env python3
"""Alex's request, risk-engine half: VaR and ES end to end on each instrument TraderX books.

Input is what the TraderX half (``VarEsEndToEndTest``) wrote: the write-once ready receipt, the
positions and contracts fixtures and the cut. This script

1. packages the receipt with ``eod-risk-bundles`` (``bridge.package``: hashes, counts, stamp,
   schema, write-once inbox), exactly as the EOD path does;
2. maps every position and contract in the bundle to a ``POST /runs`` trade of TraderX's fork of Alex's
   engine (``ENGINE_REVISION`` on branch ``traderx/engine-fork``, off his ``17ac757``), refusing LOUDLY,
   per row, anything the engine has no faithful product for, and never substituting a term (a day count
   the engine does not implement is refused, not defaulted: the engine's own rule);
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

Standard library only. The market is synthetic (labelled so in the report; ``financial_validation`` is
false): a flat curve per currency, each equity's spot its row's own closing mark, a flat volatility and
dividend yield per equity, a flat credit spread per corporate. This proves the PATH, not the numbers.
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
from calendar import monthrange
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / 'eod_contract' if (HERE / 'eod_contract').is_dir() else HERE.parent / 'eod-risk-bundles'))
import bridge   # noqa: E402
import bundle   # noqa: E402

#: TraderX's fork of the engine (Alex's 17ac757 plus R1-R6, ADR in risk-pipeline/spec.md FR-RP-VARES-06).
#: The run's own ``engine.revision`` must be this commit, unmodified, or the proof fails.
ENGINE_REVISION = '0e2c61fa539044e3a94997529a6dcd6094322b8d'
UPSTREAM_REVISION = '17ac757e8ffe9b180fa6e98ca6114c474ab0e6ad'
QUANTILES = (0.99, 0.975)
#: Day counts the engine implements (``engine.market_data.day_counts``); anything else is refused by
#: the engine and therefore here. ``ACT/365F`` is Actual/365 (Fixed), which ORE spells ``ACT/365``:
#: a spelling, not a substitution.
DAY_COUNTS = {'ACT/365': 'ACT/365', 'ACT/365F': 'ACT/365', 'ACT/360': 'ACT/360', 'ACT/ACT (ICMA)': 'ACT/ACT (ICMA)'}
#: TraderX's float indices (SwapConventions.TABLE) are overnight indices; the engine's OVERNIGHT_INDICES
#: carry their calendars and payment lags. Every TraderX swap and swaption is an OIS on one of these.
OVERNIGHT = {'USD-SOFR', 'EUR-ESTR', 'GBP-SONIA', 'JPY-TONA'}
#: Synthetic equity market (``synthetic_market``): a flat Black volatility and dividend yield for every
#: name, 30% annual equity vol in the shocks, 0.5 pairwise correlation between names.
EQUITY_VOL, DIVIDEND_YIELD, EQUITY_CORRELATION = 0.30, 0.005, 0.5
#: Synthetic flat continuous credit spread for every corporate (FR-RP-VARES-06).
CREDIT_SPREAD = 0.012
OCC = __import__('re').compile(r'^([A-Z]+)(\d{2})(\d{2})(\d{2})([CP])(\d{8})$')
FREQUENCY_MONTHS = {'1M': 1, '3M': 3, '6M': 6, '1Y': 12}
PILLARS = [0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0]
FLAT_RATE = {'USD': 0.040, 'GBP': 0.045, 'EUR': 0.025, 'JPY': 0.005}
FX_USD = {'GBP': 1.27, 'EUR': 1.08, 'JPY': 0.0066}
#: Flat ATM normal swaption volatility per currency (synthetic, like the curves).
NORMAL_VOL = {'USD': 0.0090, 'GBP': 0.0085, 'EUR': 0.0070, 'JPY': 0.0040}
VOL_TENORS = ['1Y', '2Y', '3Y', '5Y', '7Y', '10Y']

#: Who has to act on a refusal (FR-RP-VARES-04): 'engine' = the engine has no faithful product (named by
#: request id). R1-R4 are implemented in the fork, so no class is an engine gap today; the map stays for
#: the next one.
ENGINE_GAP = {}


class Refused(Exception):
    """An instrument the engine has no faithful product for; carries the class and reason."""
    def __init__(self, instrument_class, identity, reason):
        super().__init__(f'{instrument_class} {identity}: {reason}')
        self.instrument_class, self.identity, self.reason = instrument_class, identity, reason


# ---- mapping: bundle rows -> engine trades --------------------------------------------------------

def map_position(row):
    kind = row['instrumentType']
    identity = f"{row['accountId']}/{row['security']}"
    trade_id = 'pos-' + hashlib.sha256(identity.encode()).hexdigest()[:16]
    quantity = bundle.decimal(row['quantity'], 'quantity')
    multiplier = bundle.decimal(row['contractMultiplier'], 'contractMultiplier')
    if quantity == 0:
        raise Refused(kind, identity, 'zero quantity')
    if kind == 'EQUITY':
        # FR-RP-VARES-06 (R1): shares x multiplier x the equity's spot, the spot a market-risk factor.
        return {'trade_type': 'equity', 'trade_id': trade_id, 'symbol': row['security'], 'quantity': float(quantity),
                'multiplier': float(multiplier), 'currency': row['currency']}
    if kind == 'OPTION':
        # FR-RP-VARES-06 (R2): the OCC symbol is the contract (optionIdentity in the extract's header).
        occ = OCC.match(row['security'])
        if not occ:
            raise Refused(kind, identity, f"{row['security']!r} is not an OCC option symbol")
        root, yy, mm, dd, cp, strike = occ.groups()
        return {'trade_type': 'equity_option', 'trade_id': trade_id, 'symbol': root,
                'option_type': 'Call' if cp == 'C' else 'Put', 'strike': int(strike) / 1000,
                'expiry_date': f'20{yy}-{mm}-{dd}', 'exercise': 'American', 'quantity': float(quantity),
                'multiplier': float(multiplier), 'currency': row['currency']}
    if kind not in ('TREASURY', 'CORPORATE'):
        raise Refused(kind, identity, 'unknown instrument type')
    coupon = bundle.decimal(row['coupon'], 'coupon')
    schedule = [] if coupon == 0 else treasury_schedule(row, identity)
    # the extract states the basis it used, per class (bondDayCount): ACT/ACT (ICMA) Treasuries, 30/360 corporates
    bond = {'trade_type': 'bond', 'trade_id': trade_id, 'currency': row['currency'],
            'face_amount': float(quantity * multiplier), 'maturity_date': row['maturityDate'],
            'coupon_rate': float(coupon / 100), 'coupon_schedule': schedule, 'redemption_fraction': 1.0,
            'accrual_day_count': 'ACT/ACT (ICMA)'}
    if kind == 'CORPORATE':
        bond.update(accrual_day_count='30/360', credit_spread=CREDIT_SPREAD)
    return bond


def months_before(anchor, months):
    """``LocalDate.minusMonths``: the day clamps to the target month's length."""
    y, m = divmod(anchor.year * 12 + anchor.month - 1 - months, 12)
    return date(y, m + 1, min(anchor.day, monthrange(y, m + 1)[1]))


def treasury_schedule(row, identity):
    """The extract's own coupon convention (RiskExtractCsv.accrual, ADR-061): semiannual, every date
    measured back from maturityDate (never step from step), no irregular first coupon. Periods run from
    the row's lastCouponDate to maturity; a lastCouponDate the walk does not reproduce is refused."""
    maturity = date.fromisoformat(row['maturityDate'])
    last = date.fromisoformat(row['lastCouponDate']) if row.get('lastCouponDate') else None
    dates, k = [maturity], 1
    while dates[-1] > (last or maturity):
        dates.append(months_before(maturity, 6 * k))
        k += 1
    if last is None or dates[-1] != last or last >= maturity:
        raise Refused(row['instrumentType'], identity, f"lastCouponDate {row.get('lastCouponDate')!r} is not on the "
                                             f"semiannual schedule anchored at maturity {maturity}")
    dates.reverse()
    return [{'start_date': a.isoformat(), 'end_date': b.isoformat()} for a, b in zip(dates, dates[1:])]


def map_contract(row):
    identity = row['contractId']
    kind = row['productType']
    if kind not in ('SWAP', 'SWAPTION'):
        raise Refused(kind, identity, 'unknown product type')
    day_count = DAY_COUNTS.get(row['dayCount'])
    if day_count is None:
        raise Refused(kind, identity, f"fixed-leg day count {row['dayCount']!r} is not implemented by the engine "
                                      f"(it implements {sorted(set(DAY_COUNTS.values()))}); refused, not defaulted "
                                      "(engine I-05)")
    months = FREQUENCY_MONTHS.get(row['paymentFrequency'])
    if months is None:
        raise Refused(kind, identity, f"payment frequency {row['paymentFrequency']!r} has no index tenor mapping")
    if row['floatIndex'] not in OVERNIGHT:
        raise Refused(kind, identity, f"float index {row['floatIndex']!r} is not an overnight index the engine implements")
    # FR-RP-VARES-06 (R3): every TraderX convention is an OIS on its overnight index, both legs on its day count
    terms = {'trade_id': 'ctr-' + identity, 'currency': row['currency'],
             'notional': float(bundle.decimal(row['notional'], 'notional')),
             'fixed_rate': float(bundle.decimal(row['fixedRate'], 'fixedRate')),
             'payer': row['payReceive'] == 'PAY_FIXED', 'effective_date': row['effectiveDate'],
             'maturity_date': row['maturityDate'], 'index_tenor_months': months, 'overnight_index': row['floatIndex']}
    if kind == 'SWAP':
        return dict(terms, trade_type='swap', accrual_day_count=day_count)
    return swaption(terms, row, months)


def swaption(terms, row, months):
    """FR-RP-VARES-03. A TraderX swaption row describes the UNDERLYING (fixedRate is the strike, PAY_FIXED is a
    payer) and the booking account is the holder: /swaptions has no short side, so ``long_short`` is Long.
    The underlying is the OIS ``map_contract`` built (its index's day count and calendar). Exercise: EUROPEAN on expiryDate; BERMUDAN on expiryDate and every later underlying period start
    (effectiveDate + k x paymentFrequency, k >= 1) before maturity (co-terminal, annual for every TraderX
    convention); AMERICAN on any date from expiryDate to the last of those period starts."""
    style, expiry = row['exerciseStyle'], row['expiryDate']
    effective, maturity = date.fromisoformat(row['effectiveDate']), date.fromisoformat(row['maturityDate'])
    starts, k = [], 1
    while True:
        d = months_before(effective, -months * k)
        if d >= maturity:
            break
        starts.append(d.isoformat())
        k += 1
    option = dict(terms, settlement='Physical', long_short='Long')
    if style == 'EUROPEAN':
        return dict(option, trade_type='european_swaption', exercise_date=expiry)
    if not starts:
        raise Refused('SWAPTION', row['contractId'], f'{style} swaption with a single-period underlying')
    if style == 'BERMUDAN':
        return dict(option, trade_type='bermudan_swaption', exercise_dates=[expiry] + starts)
    if style == 'AMERICAN':
        return dict(option, trade_type='american_swaption', first_exercise_date=expiry, last_exercise_date=starts[-1])
    raise Refused('SWAPTION', row['contractId'], f'unknown exercise style {style!r}')


def refusal(r):
    """A refusal as reported, with who must act: an engine gap names its request (ENGINE_GAP)."""
    gap = ENGINE_GAP.get('ACT/360' if "'ACT/360'" in r.reason else r.instrument_class)
    return {'class': r.instrument_class, 'identity': r.identity, 'reason': r.reason,
            'owner': 'engine' if gap else 'traderx', 'request': gap}


def equity_spots(rows):
    """Each equity's spot in the synthetic market: its EQUITY rows' closing mark (the published close,
    or the cluster's last trade at N; markSource says which). Rows of one security must agree."""
    spots = {}
    for row in rows:
        if row['instrumentType'] == 'EQUITY':
            mark = float(bundle.decimal(row['closingMark'], 'closingMark'))
            if spots.setdefault(row['security'], mark) != mark:
                raise ValueError(f"{row['security']}: closing marks {spots[row['security']]} and {mark} disagree in one cut")
    return spots


def without_spot(trades, spots):
    """Split off the equity trades whose equity has no spot in this cut (an option on a name nobody holds):
    a market-data gap, TraderX's (no published close reaches the bundle for it)."""
    kept, refused = [], []
    for t in trades:
        if t['trade_type'] in ('equity', 'equity_option') and t['symbol'] not in spots:
            refused.append(Refused(t['trade_type'].upper(), t['trade_id'],
                                   f"no closing mark for {t['symbol']} in this cut, so no spot for the synthetic market"))
        else:
            kept.append(t)
    return kept, refused


def market_for(trades, asof, spots=None):
    currencies = {}
    for t in trades:
        c = t['currency']
        if c not in FLAT_RATE:
            raise Refused(t['trade_type'].upper(), t['trade_id'], f'no synthetic curve for currency {c}')
        cur = currencies.setdefault(c, {'discount_curve': {'times': PILLARS, 'rates': [FLAT_RATE[c]] * len(PILLARS)},
                                        'index_curves': {}})
        if 'index_tenor_months' in t:
            cur['index_curves'][f"{c}-SIMINDEX-{t['index_tenor_months']}M"] = {
                'times': PILLARS, 'rates': [FLAT_RATE[c] + 0.001] * len(PILLARS)}
        if t['trade_type'].endswith('_swaption'):
            cur['swaption_vols'] = {'option_tenors': VOL_TENORS, 'swap_tenors': VOL_TENORS,
                                    'vols': [[NORMAL_VOL[c]] * len(VOL_TENORS) for _ in VOL_TENORS]}
    # the base currency is always a market currency: an FX spot names two (a GBP-only book still needs USD)
    currencies.setdefault('USD', {'discount_curve': {'times': PILLARS, 'rates': [FLAT_RATE['USD']] * len(PILLARS)},
                                  'index_curves': {}})
    market = {'asof': asof, 'currencies': currencies}
    fx = {f'{c}USD': FX_USD[c] for c in currencies if c != 'USD'}
    if fx:
        market['fx_spots'] = fx
    names = sorted({t['symbol'] for t in trades if 'symbol' in t})
    if names:
        flat_q = {'times': [0.0, 1.0], 'rates': [DIVIDEND_YIELD, DIVIDEND_YIELD]}
        currency = {t['symbol']: t['currency'] for t in trades if 'symbol' in t}
        market['equities'] = {n: {'currency': currency[n], 'spot': spots[n], 'volatility': EQUITY_VOL,
                                  'dividend_curve': flat_q} for n in names}
    return market


def factor_labels(market):
    """The engine's factor order (RateRiskFactors.from_market): per currency its curves' pillars, then every
    equity's spot by symbol."""
    labels = []
    for code, data in market['currencies'].items():
        curves = [(f'discount:{code}', data['discount_curve'])]
        curves += [(f'index:{name}', data['index_curves'][name]) for name in sorted(data['index_curves'])]
        labels += [f'{name}/{t:g}y' for name, curve in curves for t in curve['times']]
    return labels + [f'equity:{n}/spot' for n in sorted(market.get('equities', {}))]


def covariance_for(labels, horizon):
    """Rates: 8 bp/day pillar moves, 0.9^distance correlation; equities: EQUITY_VOL lognormal, pairwise
    EQUITY_CORRELATION, independent of rates. Horizon variance, as the engine takes it."""
    rate = [i for i, l in enumerate(labels) if not l.startswith('equity:')]
    position = {i: k for k, i in enumerate(rate)}
    n, daily_rate, daily_equity = len(labels), 0.0008 ** 2, EQUITY_VOL ** 2 / 252
    cov = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i in position and j in position:
                cov[i][j] = daily_rate * horizon * 0.9 ** abs(position[i] - position[j])
            elif i not in position and j not in position:
                cov[i][j] = daily_equity * horizon * (1.0 if i == j else EQUITY_CORRELATION)
    return cov


def run_request(trades, asof, key, scenarios=1024, seed=7, spots=None):
    market = market_for(trades, asof, spots)
    labels = factor_labels(market)
    horizon = 10
    covariance = covariance_for(labels, horizon)
    config = {'base_currency': 'USD',
                       'market_risk': {'scenarios': {'source': 'monte-carlo', 'factors': labels, 'covariance': covariance,
                                                     'horizon_days': horizon, 'num_scenarios': scenarios, 'seed': seed},
                                       'quantiles': list(QUANTILES)}}
    # FR-RP-VARES-03/06: Bermudans and Americans calibrate on their OWN underlying's conventions (the fork's
    # swap_index_from_trade: its index tenor and day count), and an American also exercises on every period
    # start in its window (R5), so it is never below the Bermudan on the same swap.
    if any(t['trade_type'] in ('bermudan_swaption', 'american_swaption') for t in trades):
        config['pricing'] = {'bermudan': {'swap_index_from_trade': True},
                             'american': {'swap_index_from_trade': True, 'exercise_on_period_starts': True}}
    return {'market': market, 'portfolio': trades, 'analytics': ['npv', 'market_risk'], 'config': config,
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


def negative_controls(body, trades):
    """(name, request, text the 400 must name): the run with one input a class needs removed. Each drops the
    factor labels and covariance rows of what it removes, so the refusal is about the trade, not the shape."""
    def without(mutate):
        control = json.loads(json.dumps(body))
        mutate(control)
        labels = factor_labels(control['market'])
        keep = [i for i, l in enumerate(body['config']['market_risk']['scenarios']['factors']) if l in labels]
        scenarios = control['config']['market_risk']['scenarios']
        scenarios['factors'] = labels
        scenarios['covariance'] = [[scenarios['covariance'][i][j] for j in keep] for i in keep]
        return control
    controls = []
    ois = next((t for t in trades if t.get('overnight_index')), None)
    if ois:
        name = f"{ois['currency']}-SIMINDEX-{ois['index_tenor_months']}M"
        controls.append(('ois-index-curve', without(
            lambda c: c['market']['currencies'][ois['currency']]['index_curves'].pop(name)), name))
    equity = next((t for t in trades if t['trade_type'] == 'equity'), None)
    if equity:
        controls.append(('equity-spot', without(lambda c: c['market']['equities'].pop(equity['symbol'])),
                         f"no equity '{equity['symbol']}'"))
    option = next((t for t in trades if t['trade_type'] == 'equity_option'), None)
    if option:
        controls.append(('option-volatility', without(lambda c: c['market']['equities'][option['symbol']].pop('volatility')),
                         'no volatility'))
    corporate = next((t for t in trades if t.get('credit_spread')), None)
    if corporate:
        # a spread bond prices today and under t=0 shocks only: asking for its Greeks must be refused, not
        # answered from a path its spread does not ride on
        def greeks(c):
            c['portfolio'] = [corporate]
            c['analytics'] = ['npv', 'sensitivities']
            c['config'].pop('market_risk')
            c['config'].pop('pricing', None)
            c['outputs'] = {}
        control = json.loads(json.dumps(body))
        greeks(control)
        controls.append(('corporate-greeks', control, 'credit spread'))
    return controls


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
            refused.append(refusal(r))
    for row in parsed['contracts'][1]:
        try:
            t = map_contract(row)
            trades.append(t)
            origin_of[t['trade_id']] = {'class': row['productType'], 'trade_type': t['trade_type'], 'contractId': row['contractId'], 'accountId': row['accountId'],
                                        'conventions': f"{row['floatIndex']}/{row['paymentFrequency']}/{row['dayCount']}"}
        except Refused as r:
            refused.append(refusal(r))
    spots = equity_spots(parsed['positions'][1])
    trades, unspotted = without_spot(trades, spots)
    refused += [refusal(r) for r in unspotted]
    report['refused'] = refused
    report['mapped'] = origin_of
    if not trades:
        report['error'] = 'nothing in the bundle is priceable by the engine'
        return finish(1)

    # 3. one run for the priceable trades
    # the key binds the whole request (market, trades AND config) and the engine it is meant for, so a changed
    # setting or a re-pinned engine is a new run, not a 409 or a replay of another engine's result
    body = run_request(trades, asof, None, scenarios=args.scenarios, spots=spots)
    key = body['idempotency_key'] = 'var-es-e2e-' + hashlib.sha256(
        bundle.encoded({'cut': manifest['cut'], 'request': body, 'engine': ENGINE_REVISION})).hexdigest()[:24]
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
    identity = result.get('engine') or {}
    report['engine'] = identity
    mr = result['market_risk']
    report['run'] = {'run_id': run_id, 'source': mr['source'], 'measure': mr['measure'], 'num_scenarios': mr['num_scenarios'],
                     'horizon_days': mr['horizon_days'], 'risk_factors': len(mr['risk_factors']), 'base_npv': mr['base_npv'],
                     'risk': mr['risk'], 'npv': result.get('npv')}

    # 4. VaR and ES: portfolio figures from the engine, per-trade attribution from the inline P&L
    problems = []
    if identity.get('revision') != ENGINE_REVISION or identity.get('modified') is not False:
        problems.append(f"the run's engine is {identity.get('revision')!r} (modified={identity.get('modified')!r}), "
                        f"not the fork's {ENGINE_REVISION} unmodified")
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

    # 5. engine-side negative controls, one per input a class needs: the engine must refuse each (400 naming it)
    #    before any run exists, never price the trade to 0.
    controls = negative_controls(body, trades)
    report['negative_controls'] = []
    for name, control, expect in controls:
        control['idempotency_key'] = f'{key}-negative-{name}'
        status, detail = http(args.origin, '/runs', control)
        report['negative_controls'].append({'control': name, 'status': status, 'expect': expect, 'detail': detail})
        if status != 400 or expect not in json.dumps(detail):
            problems.append(f'negative control {name}: answered {status} {detail}; expected 400 naming {expect!r} '
                            f'(refused before queueing, never priced to 0)')
    if not controls:
        problems.append('no negative control applies to this book')

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
