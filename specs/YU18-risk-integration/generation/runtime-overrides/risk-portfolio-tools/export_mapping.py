"""Closed synthetic Treasury-bill mapping; reuses the sequenced bundle validator.

The caller supplies account/run/bundle identity. These are custody bindings, not
an authorization service. No real-market fallback, account netting or EOD API.
"""
import argparse
import hashlib
import json
import shutil
import sys
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

from batch_artifacts import require, safe_run
from corpus import encoded, digest, validate

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / 'eod_contract' if (HERE / 'eod_contract').is_dir()
                       else HERE.parent / 'eod-risk-bundles'))
import bundle

PROFILE = 'traderx-synthetic-usd-bill-v1'


def mapping(source, market_path, expected):
    require(set(expected) == {'account_id', 'run_id', 'bundle_id'}, 'explicit account/run/bundle identity required')
    safe_run(expected['run_id'])
    require(isinstance(expected['account_id'], str) and expected['account_id'], 'account is required')
    manifest, parsed = bundle.validate(source)
    require(manifest['schema'] == 'traderx.eod-bundle.v2' and manifest['inputOrigin'] == 'synthetic',
            'only explicit synthetic v2 exports admitted')
    require(manifest['bundleId'] == expected['bundle_id'], 'expected bundle/cut mismatch')
    require(manifest['marketInputs'] == {'status': 'NOT_SUPPLIED'}, 'unexpected bundled market profile')
    asof = manifest['cut']['sessionDate']
    require(datetime.fromisoformat(manifest['valuationTime']).date().isoformat() == asof,
            'valuation date differs from cut session date')
    market = json.loads(market_path.read_bytes())
    require(set(market) == {'schema', 'origin', 'asof', 'rate', 'compounding', 'day_count', 'description'} and
            market['schema'] == 'traderx.synthetic-flat-curve.v1' and market['origin'] == 'synthetic' and
            market['asof'] == asof and market['compounding'] == 'continuous' and
            market['day_count'] == 'ACT/365F' and bool(market['description'].strip()),
            'explicit matching synthetic continuous ACT/365F curve required')
    rate = bundle.decimal(str(market['rate']), 'rate')
    require(Decimal('-.05') <= rate <= Decimal('.20'), 'unsupported flat rate')
    rows = parsed['positions'][1]
    selected = [r for r in rows if r['accountId'] == expected['account_id']]
    require(selected, 'selected account has no positions')
    require(not any(r['accountId'] == expected['account_id'] for r in parsed['contracts'][1]),
            'selected account has unsupported OTC contracts')
    terms = json.loads((source / 'instrument-terms.json').read_bytes())['entries']
    trades, identities = [], []
    for row in selected:
        require(row['instrumentType'] == 'TREASURY' and row['currency'] == 'USD' and
                bundle.decimal(row['coupon'], 'coupon') == 0 and
                bundle.decimal(row['contractMultiplier'], 'multiplier') == 1,
                'selected position is outside the USD Treasury bill profile')
        matches = [e for e in terms if e['identity'] == {'source': 'positions', 'security': row['security']}]
        require(len(matches) == 1 and not matches[0]['missingTerms'] and
                matches[0]['provenance']['origin'] == 'synthetic', 'missing explicit synthetic bill terms')
        t = matches[0]['terms']
        required = {'currency': 'USD', 'couponFrequency': 'NONE', 'dayCount': 'NOT_APPLICABLE',
                    'calendar': 'NONE', 'businessDayAdjustment': 'UNADJUSTED', 'paymentLagBusinessDays': 0,
                    'settlementDays': 0, 'firstCouponDate': None, 'penultimateCouponDate': None,
                    'stubConvention': 'NONE', 'schedule': [], 'priceBasis': 'clean-fraction-of-par',
                    'quantityUnit': 'signed-currency-face', 'maturityDate': row['maturityDate']}
        require(all(t.get(k) == v for k, v in required.items()) and
                bundle.decimal(t['couponRatePercent'], 'coupon') == 0 and
                bundle.decimal(t['redemptionFraction'], 'redemption') == 1 and
                date.fromisoformat(t['issueDate']) <= date.fromisoformat(asof), 'unsupported bill terms')
        face = bundle.decimal(row['quantity'], 'signed face')
        # Integer USD face keeps exact transport units; no implicit rounding or denomination multiplier.
        require(face == face.to_integral_value() and 0 < abs(face) <= 2**53 - 1,
                'finite nonzero exactly representable integer USD face required')
        identity = {'account_id': row['accountId'], 'security': row['security']}
        trade_id = 'tx-' + hashlib.sha256(encoded(identity)).hexdigest()
        trades.append({'trade_type': 'bond', 'trade_id': trade_id, 'currency': 'USD', 'face_amount': int(face),
                       'maturity_date': t['maturityDate'], 'redemption_fraction': 1.0, 'coupon_rate': 0.0,
                       'coupon_schedule': [], 'accrual_day_count': 'ACT/ACT (ICMA)'})
        identities.append(dict(identity, trade_id=trade_id, quantity=row['quantity'], quantity_unit=t['quantityUnit']))
    request = {'analytics': ['npv'], 'config': {'base_currency': 'USD'},
               'idempotency_key': expected['run_id'], 'keep': True,
               'market': {'asof': asof, 'currencies': {'USD': {'discount_curve': {
                   'times': [0.0, 1.0, 5.0, 10.0], 'rates': [float(rate)] * 4}}}}, 'portfolio': trades}
    validate(request)
    raw = encoded(request) + b'\n'
    receipt = {'profile': PROFILE, 'expected': expected, 'cluster_epoch': manifest['clusterEpoch'],
               'cut': manifest['cut'], 'valuation_time': manifest['valuationTime'],
               'market': market, 'market_sha256': digest(market_path),
               'bundle_files': {p.name: digest(p) for p in sorted(source.iterdir())},
               'request_sha256': hashlib.sha256(raw).hexdigest(), 'positions': identities,
               'coverage': {'selected': len(selected), 'mapped': len(trades),
                            'unselected_positions': len(rows) - len(selected),
                            'unselected_contracts': len(parsed['contracts'][1])},
               'synthetic': True, 'financial_validation': False}
    return raw, receipt


def prepare(source, market, expected, output):
    raw, receipt = mapping(source, market, expected)
    require(not any((p / '.git').exists() for p in (output.resolve(), *output.resolve().parents)),
            'mapped input must be outside Git')
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    shutil.copytree(source, output / 'bundle')
    shutil.copyfile(market, output / 'market.json')
    (output / 'request.json').write_bytes(raw)
    (output / 'mapping.json').write_bytes(encoded(receipt) + b'\n')
    verify(output, expected)
    return receipt


def verify(root, expected):
    require(not root.is_symlink(), 'symlink mapping root')
    require({p.name for p in root.iterdir()} == {'bundle', 'market.json', 'request.json', 'mapping.json'},
            'unexpected mapped input files')
    require(all(not p.is_symlink() for p in root.rglob('*')), 'symlink mapped input')
    raw, receipt = mapping(root / 'bundle', root / 'market.json', expected)
    require(raw == (root / 'request.json').read_bytes() and
            receipt == json.loads((root / 'mapping.json').read_bytes()), 'export mapping custody mismatch')
    return receipt


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--market', type=Path, required=True)
    p.add_argument('--account', required=True)
    p.add_argument('--run-id', required=True)
    p.add_argument('--expected-bundle-id', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    print(json.dumps(prepare(a.bundle, a.market, {'account_id': a.account, 'run_id': a.run_id,
                     'bundle_id': a.expected_bundle_id}, a.output), sort_keys=True))


if __name__ == '__main__':
    main()
