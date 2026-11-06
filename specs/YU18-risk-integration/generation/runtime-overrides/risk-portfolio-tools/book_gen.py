#!/usr/bin/env python3
"""Seeded large-book generator (FR-RP-BOOK-01): a deterministic BOOKING PLAN across every instrument
class TraderX books (listed equities and ETFs, OCC equity options, Treasury bills and notes, corporates,
OTC swaps in all five conventions, European/Bermudan/American swaptions) on many accounts grouped into
desks. The plan is booked through the REAL gateway by ``VarEsEndToEndTest.largeBookThroughTheRealIngress``
(``TRADERX_LARGE_BOOK_PLAN``), so the cut and the exporters' fixture are genuine; the plan itself holds
terms only, never a value.

    python3 book_gen.py --seed 7 --crosses 2000 --otc 1000 --out plan.json

Same arguments, same bytes. The plan is refused when it exceeds what one epoch can hold
(``MatchingEngineClusteredService``: 64 accounts, 1,024 securities, 4,096 contracts).
"""
import argparse
import hashlib
import json
import random
from datetime import date, timedelta

#: One epoch's capacity (MatchingEngineClusteredService MAX_ACCOUNTS / MAX_SECURITIES / MAX_CONTRACTS).
MAX_ACCOUNTS, MAX_SECURITIES, MAX_CONTRACTS = 64, 1024, 4096
DESKS = ['EQ-CASH-NY', 'EQ-DERIV-NY', 'RATES-NY', 'RATES-LDN', 'RATES-TKY', 'CREDIT-NY', 'MACRO-LDN', 'TREASURY-NY']
#: SwapConventions.TABLE, with a book weight each (a USD-heavy rates book).
CONVENTIONS = [('USD-SOFR-1Y-ACT360', 'USD', 12, 0.30), ('USD-SOFR-3M-ACT360', 'USD', 3, 0.10),
               ('EUR-ESTR-1Y-ACT360', 'EUR', 12, 0.20), ('GBP-SONIA-1Y-ACT365F', 'GBP', 12, 0.25),
               ('JPY-TONA-1Y-ACT365F', 'JPY', 12, 0.15)]
PAR = {'USD': 0.040, 'EUR': 0.025, 'GBP': 0.045, 'JPY': 0.005}
FX = {'GBP': 1.27, 'EUR': 1.08, 'JPY': 0.0066}
SESSION = date(2026, 10, 26)
SPOT = SESSION + timedelta(days=2)


def add_months(d, months):
    y, m = divmod(d.year * 12 + d.month - 1 + months, 12)
    return date(y, m + 1, min(d.day, [31, 29 if y % 4 == 0 and (y % 100 or y % 400 == 0) else 28,
                                       31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m]))


def letters(prefix, i, width):
    """A synthetic ticker of uppercase letters only (OccSymbol.isOption reads an option's root as A-Z)."""
    return prefix + ''.join(chr(65 + (i // 26 ** k) % 26) for k in reversed(range(width)))


def third_friday(year, month):
    d = date(year, month, 15)
    return d + timedelta(days=(4 - d.weekday()) % 7)


def plan(seed, accounts=16, equities=40, etfs=10, options_per_name=4, bills=6, notes=24, corporates=16,
         crosses=2000, otc=1000, swaption_share=0.35):
    if accounts > MAX_ACCOUNTS:
        raise SystemExit(f'{accounts} accounts > one epoch\'s {MAX_ACCOUNTS}')
    if otc > MAX_CONTRACTS:
        raise SystemExit(f'{otc} OTC contracts > one epoch\'s {MAX_CONTRACTS}')
    rnd = random.Random(seed)
    accts = [{'accountId': 30001 + i, 'desk': DESKS[i % len(DESKS)], 'counterpartyId': f'CP-{30001 + i}',
              'nettingSetId': f'NS-{30001 + i}', 'currency': 'USD'} for i in range(accounts)]
    secs = []
    for i in range(equities):
        secs.append({'ticker': letters('Z', i, 3), 'kind': 'EQUITY', 'price': round(rnd.uniform(20, 400), 2)})
    for i in range(etfs):
        secs.append({'ticker': letters('YX', i, 2), 'kind': 'ETF', 'price': round(rnd.uniform(50, 500), 2)})
    for u in [s for s in secs if s['kind'] == 'EQUITY'][:max(1, equities // 2)]:
        for _ in range(options_per_name):
            expiry = third_friday(2026 + rnd.choice([0, 1, 1]), rnd.choice([12, 3, 6, 9]))
            if expiry <= SESSION:
                expiry = third_friday(2027, 1)
            strike = max(1, round(u['price'] * rnd.uniform(0.8, 1.2)))
            cp = rnd.choice('CP')
            occ = f"{u['ticker']}{expiry:%y%m%d}{cp}{strike * 1000:08d}"
            secs.append({'ticker': occ, 'kind': 'OPTION', 'underlying': u['ticker'],
                         'price': round(max(0.05, rnd.uniform(0.02, 0.12) * u['price']), 2)})
    for i in range(bills):
        m = add_months(SESSION, 1 + i * 2)
        secs.append({'ticker': f'UST-BILL-{m:%Y%m%d}', 'kind': 'TREASURY', 'coupon': '0', 'maturity': m.isoformat(),
                     'price': round(1 - 0.04 * (m - SESSION).days / 365, 3)})
    for i in range(notes):
        m = date(2027 + rnd.randrange(30), rnd.choice([2, 5, 8, 11]), 15)
        c = f'{rnd.randrange(50, 550, 25) / 100:.3f}'
        secs.append({'ticker': f'UST-{c}-{m:%Y%m%d}', 'kind': 'TREASURY', 'coupon': c, 'maturity': m.isoformat(),
                     'price': round(rnd.uniform(0.90, 1.06), 3)})
    for i in range(corporates):
        m = date(2028 + rnd.randrange(15), rnd.randrange(1, 13), rnd.choice([1, 15, 24]))
        c = f'{rnd.randrange(300, 800, 25) / 100:.3f}'
        secs.append({'ticker': f'CORP{i:02d}-{c}-{m:%Y%m%d}', 'kind': 'CORPORATE', 'coupon': c, 'maturity': m.isoformat(),
                     'price': round(rnd.uniform(0.92, 1.08), 3)})
    secs = list({s['ticker']: s for s in secs}.values())   # an OCC collision keeps one
    if len(secs) > MAX_SECURITIES:
        raise SystemExit(f'{len(secs)} securities > one epoch\'s {MAX_SECURITIES}')
    # MatchingEngineClusteredService.MAX_ORDER_QUANTITY is 1,000,000 units an order; debt books FACE DOLLARS, so one
    # Treasury/corporate order tops out at 1mm face (a block is many orders; positions still net per account).
    qty = {'EQUITY': (10, 5000), 'ETF': (10, 5000), 'OPTION': (1, 200), 'TREASURY': (10, 1000), 'CORPORATE': (10, 1000)}
    trades = []
    for i in range(crosses):
        s = rnd.choice(secs)
        seller, buyer = rnd.sample(accts, 2)
        lo, hi = qty[s['kind']]
        q = rnd.randint(lo, hi) * (1000 if s['kind'] in ('TREASURY', 'CORPORATE') else 1)   # debt: face in $1,000s
        trades.append({'seller': seller['accountId'], 'buyer': buyer['accountId'], 'ticker': s['ticker'], 'qty': q,
                       'price': s['price']})
    contracts = []
    for i in range(otc):
        name, ccy, months, _ = rnd.choices(CONVENTIONS, weights=[c[3] for c in CONVENTIONS])[0]
        acct = rnd.choice(accts)['accountId']
        # The contract record holds the notional as an int (FR-OTC: refused past 2,147,483,647 before sequencing),
        # so a JPY booking tops out near 2.1bn JPY (~14mm USD); USD/EUR/GBP run 1mm-100mm.
        notional = rnd.randint(1, 21) * 100_000_000 if ccy == 'JPY' else rnd.randint(1, 100) * 1_000_000
        body = {'clientOrderId': f'lb-{seed}-{i}', 'accountId': acct, 'payReceive': rnd.choice(['Pay', 'Receive']),
                'notional': notional, 'conventions': name}
        swaption = rnd.random() < swaption_share
        if swaption:
            expiry = add_months(SESSION, 12 * rnd.randint(1, 5))
            effective = expiry + timedelta(days=2)
            body.update(expiryDate=expiry.isoformat(),
                        exerciseStyle=rnd.choices(['European', 'Bermudan', 'American'], weights=[6, 3, 1])[0])
        else:
            effective = SPOT if rnd.random() < 0.8 else add_months(SPOT, rnd.choice([3, 6, 12]))
        tenor_years = rnd.choice([2, 3, 5, 7, 10]) if swaption else rnd.choice([1, 2, 3, 5, 7, 10, 15, 20, 30])
        body.update(fixedRate=round(PAR[ccy] + rnd.uniform(-0.01, 0.01), 6), effectiveDate=effective.isoformat(),
                    maturityDate=add_months(effective, 12 * tenor_years).isoformat())
        contracts.append({'route': '/swaptions' if swaption else '/swaps', 'body': body})
    out = {'schema': 'traderx.large-book-plan.v1', 'seed': seed, 'sessionDate': SESSION.isoformat(), 'fx': FX,
           'accounts': accts, 'securities': secs, 'crosses': trades, 'otc': contracts, 'synthetic': True}
    out['planSha256'] = hashlib.sha256(json.dumps(out, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return out


def counts(p):
    c = {}
    for s in p['securities']:
        c[f"securities.{s['kind']}"] = c.get(f"securities.{s['kind']}", 0) + 1
    for o in p['otc']:
        k = 'otc.' + (o['body'].get('exerciseStyle', 'SWAP').upper()) + '.' + o['body']['conventions']
        c[k] = c.get(k, 0) + 1
    return dict(sorted(c.items()), accounts=len(p['accounts']), crosses=len(p['crosses']), otc=len(p['otc']))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--seed', type=int, required=True)
    ap.add_argument('--accounts', type=int, default=16)
    ap.add_argument('--crosses', type=int, default=2000)
    ap.add_argument('--otc', type=int, default=1000)
    ap.add_argument('--notes', type=int, default=24)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    p = plan(a.seed, accounts=a.accounts, crosses=a.crosses, otc=a.otc, notes=a.notes)
    with open(a.out, 'x') as f:
        json.dump(p, f, sort_keys=True, indent=1)
    print(json.dumps({'planSha256': p['planSha256'], **counts(p)}, indent=1))


if __name__ == '__main__':
    main()
