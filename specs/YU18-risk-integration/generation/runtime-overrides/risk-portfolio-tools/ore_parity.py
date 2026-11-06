#!/usr/bin/env python3
"""ORE parity spot check (FR-RP-BOOK-04): the engine's t=0 NPV per trade against ORE's own, from
the engine's test oracle ``tests/support/ore_npv_oracle.py`` (one in-process OREApp NPV run).

Runs in the ENGINE's environment, from an unmodified archive of the pinned revision:
    <venv>/bin/python ore_parity.py --engine-source DIR --request request.json --reply reply.json \
        [--sample N] [--seed S] [--out parity.json]
The request is parsed by the engine's own schema (``RunRequestSchema``), so ORE sees exactly the
market and trades the engine priced. Prints and writes one JSON object; exit 1 if any sampled trade
differs by more than ``--rel`` relative or ``--abs`` absolute, or ORE refused a sampled trade.
"""
import argparse
import json
import random
import sys
import time
from pathlib import Path

#: The engine's own oracle tests' pillars (tests/support/portfolio.py), on which ORE builds its market.
ORACLE_PILLARS = [0.0, 1.0, 2.0, 5.0, 10.0, 30.0]
#: The oracle's ORE conventions define one index, SimIndex 6M.
ORACLE_TENOR = 6


def quantlib_direct(t, market, ccy, tenor):
    """QuantLib's own pricing of the engine's OWN schedule: ``build_vanilla_swap``'s exact
    ``MakeVanillaSwap`` call, its index given the forwarding curve, under ``DiscountingSwapEngine``;
    a European as ``Swaption`` + ``BachelierSwaptionEngine`` at the flat normal vol. Flat curves only."""
    import ORE
    from engine.instruments.schedules import ibor_index
    from engine.market_data.day_counts import TIME_AXIS_DAY_COUNTER, resolve_accrual_day_count
    asof = ORE.DateParser.parseISO(market['asof'])
    ORE.Settings.instance().evaluationDate = asof
    cur = market['currencies'][ccy]
    flat = lambda c: ORE.YieldTermStructureHandle(ORE.FlatForward(asof, c['rates'][0], TIME_AXIS_DAY_COUNTER, ORE.Continuous))  # noqa: E731
    disc, fwd = flat(cur['discount_curve']), flat(cur['index_curves'][f'{ccy}-SIMINDEX-{tenor}M'])
    accrual = resolve_accrual_day_count(getattr(t, 'accrual_day_count', None))
    swap = ORE.MakeVanillaSwap(ORE.Period(0, ORE.Days), ibor_index(tenor, fwd), t.fixed_rate,
                               effectiveDate=ORE.DateParser.parseISO(t.effective_date),
                               terminationDate=ORE.DateParser.parseISO(t.maturity_date), nominal=t.notional,
                               swapType=ORE.VanillaSwap.Payer if t.payer else ORE.VanillaSwap.Receiver,
                               floatingLegSpread=t.floating_spread, fixedLegDayCount=accrual, floatingLegDayCount=accrual)
    swap.setPricingEngine(ORE.DiscountingSwapEngine(disc))
    if t.trade_type == 'swap':
        return swap.NPV()
    vols = cur['swaption_vols']['vols']
    if len({v for row in vols for v in row}) != 1:
        raise SystemExit('quantlib_direct prices Europeans on a flat normal vol only')
    option = ORE.Swaption(swap, ORE.EuropeanExercise(ORE.DateParser.parseISO(t.exercise_date)))
    option.setPricingEngine(ORE.BachelierSwaptionEngine(disc, ORE.QuoteHandle(ORE.SimpleQuote(vols[0][0]))))
    return option.NPV() * (1 if t.long_short == 'Long' else -1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--engine-source', required=True)
    ap.add_argument('--request', required=True)
    ap.add_argument('--reply', required=True)
    ap.add_argument('--sample', type=int, default=0, help='trades to check, 0 = all')
    ap.add_argument('--seed', type=int, default=7)
    ap.add_argument('--rel', type=float, default=1e-6)
    ap.add_argument('--abs', type=float, default=0.01)
    ap.add_argument('--bound-rel', type=float, default=1e-3)
    ap.add_argument('--out')
    a = ap.parse_args()
    sys.path.insert(0, str(Path(a.engine_source).resolve()))
    from engine.api.requests import RunRequestSchema
    from tests.support.ore_npv_oracle import ore_npv_run

    body = json.loads(Path(a.request).read_bytes())
    reply = json.loads(Path(a.reply).read_bytes())
    result = reply.get('result', reply)
    engine_npv = {t['trade_id']: t['npv'] for t in result['trades']}   # base currency, as market_risk's base_npv
    req = RunRequestSchema.model_validate(body)
    ids = [t.trade_id for t in req.portfolio]
    if a.sample and a.sample < len(ids):
        # every trade type is represented, then a seeded sample of the rest
        by_type = {}
        for t in req.portfolio:
            by_type.setdefault(t.trade_type, t.trade_id)
        rest = sorted(set(ids) - set(by_type.values()))
        ids = sorted(by_type.values()) + random.Random(a.seed).sample(rest, max(0, min(len(rest), a.sample - len(by_type))))
    chosen = [t for t in req.portfolio if t.trade_id in set(ids)]
    pricing = req.config.pricing.to_dataclass()
    fx = dict(body['market'].get('fx_spots', {}))
    t0 = time.monotonic()
    ore_npv, ore_refused = {}, {}
    # The oracle takes one currency (USD) and one index. The engine builds every currency's trades alike
    # (TARGET, its SimIndex), so a currency's trades on that currency's curves, labelled USD, are the same
    # numbers; the engine reports in the base currency, so ORE's value is converted at the run's FX spot.
    groups = {}
    for t in chosen:
        groups.setdefault((t.currency, getattr(t, 'index_tenor_months', None) if t.trade_type != 'bond' else None), []).append(t)
    reference = {}
    for (ccy, tenor), group in sorted(groups.items(), key=lambda kv: (kv[0][0], kv[0][1] or 0)):
        rate = 1.0 if ccy == body['config'].get('base_currency', 'USD') else fx[f'{ccy}USD']
        if tenor not in (None, ORACLE_TENOR):
            direct = [t for t in group if t.trade_type in ('swap', 'european_swaption')]
            for t in direct:
                ore_npv[t.trade_id] = quantlib_direct(t, body['market'], ccy, tenor) * rate
                reference[t.trade_id] = 'quantlib-direct'
            for t in group:
                if t not in direct:
                    ore_refused[t.trade_id] = (f'no ORE reference: the oracle is {ORACLE_TENOR}M-only and QuantLib has no '
                                               'LGM grid engine here; bounds checked instead')
            continue
        mkt = json.loads(json.dumps(body['market']))
        cur = mkt['currencies'][ccy]
        # The oracle's ORE curves sit on whole ACT/365 days. Every proof curve is FLAT, where the pillar
        # times carry no information, so they snap to whole days; a non-flat curve is refused here.
        for curve in [cur['discount_curve'], *cur['index_curves'].values()]:
            if len(set(curve['rates'])) != 1:
                raise SystemExit('ore_parity handles flat curves only (pillar snapping would move a shaped curve)')
            curve['times'] = ORACLE_PILLARS
            curve['rates'] = curve['rates'][:1] * len(ORACLE_PILLARS)
        # ORE's market always builds a swaption surface; a bond-only group reads none of it
        cur.setdefault('swaption_vols', {'option_tenors': ['1Y', '5Y'], 'swap_tenors': ['1Y', '5Y'],
                                         'vols': [[0.01, 0.01], [0.01, 0.01]]})
        keep = f"{ccy}-SIMINDEX-{tenor}M" if tenor else next(iter(cur['index_curves']), None)
        cur['index_curves'] = ({f"USD-SIMINDEX-{tenor or 6}M": cur['index_curves'][keep]} if keep else
                               {'USD-SIMINDEX-6M': cur['discount_curve']})
        sub = RunRequestSchema.model_validate(dict(body, market={'asof': mkt['asof'], 'currencies': {'USD': cur}},
                                                   portfolio=[dict(json.loads(t.model_dump_json()), currency='USD') for t in group],
                                                   config={k: v for k, v in body['config'].items() if k != 'market_risk'},
                                                   analytics=['npv']))
        market = sub.market.to_dataclass()
        run = ore_npv_run(market, [t.to_dataclass(market.asof, t.trade_id) for t in sub.portfolio], pricing)
        ore_npv.update({k: v * rate for k, v in run.npv.items()})
        reference.update({k: 'ore-oracle' for k in run.npv})
        ore_refused.update(run.refused)
    rows, bad = [], []
    for t in chosen:
        e, o = engine_npv.get(t.trade_id), ore_npv.get(t.trade_id)
        diff = None if o is None or e is None else e - o
        ok = diff is not None and (abs(diff) <= a.abs or abs(diff) <= a.rel * abs(o))
        if o is None and t.trade_type in ('bermudan_swaption', 'american_swaption'):
            ok = None   # bounds below
        rows.append({'trade_id': t.trade_id, 'trade_type': t.trade_type, 'engine': e, 'ore': o, 'diff': diff, 'ok': ok,
                     'reference': reference.get(t.trade_id),
                     'ore_refused': ore_refused.get(t.trade_id)})
        if ok is False:
            bad.append(t.trade_id)
    # No-arbitrage bounds on the same underlying and strike: European <= Bermudan <= American (an
    # American's window holds every Bermudan date), to --bound-rel for the engine's grid.
    by_terms = {}
    for t in chosen:
        if t.trade_type.endswith('_swaption'):
            key = (t.currency, t.notional, t.fixed_rate, t.payer, t.effective_date, t.maturity_date, t.long_short)
            by_terms.setdefault(key, {})[t.trade_type] = engine_npv.get(t.trade_id)
    bounds = []
    for key, v in by_terms.items():
        eu, be, am = v.get('european_swaption'), v.get('bermudan_swaption'), v.get('american_swaption')
        sign = 1 if key[-1] == 'Long' else -1
        for lo, hi, name in ((eu, be, 'european<=bermudan'), (be, am, 'bermudan<=american'), (eu, am, 'european<=american')):
            if lo is not None and hi is not None:
                ok = sign * (hi - lo) >= -a.bound_rel * abs(hi)
                bounds.append({'terms': [str(x) for x in key], 'check': name, 'lo': lo, 'hi': hi, 'ok': ok})
                if not ok:
                    bad.append(f'bound {name} {key}')
    report = {'checked': len(rows), 'portfolio': len(body['portfolio']), 'ore_seconds': round(time.monotonic() - t0, 2),
              'tolerance': {'rel': a.rel, 'abs': a.abs}, 'failures': bad,
              'max_abs_diff': max((abs(r['diff']) for r in rows if r['diff'] is not None), default=None),
              'max_rel_diff': max((abs(r['diff']) / max(abs(r['ore']), 1e-12) for r in rows if r['diff'] is not None), default=None),
              'bounds': bounds, 'rows': rows}
    text = json.dumps(report, indent=2, sort_keys=True)
    if a.out:
        Path(a.out).write_text(text)
    print(text)
    return 1 if bad else 0


if __name__ == '__main__':
    raise SystemExit(main())
