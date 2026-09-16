"""Provisional, fixture-only e7246e1 acceptance. No producer code is imported."""
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, localcontext
from pathlib import Path
import bundle
from instrument_terms import decode
from worker_protocol import PRICING_PROFILE, Deferred
from w0_result import CALCULATIONS, STATUSES, compact, number, read

ENGINE_COMMIT = PRICING_PROFILE['engineCommit']
PROFILE_ID = PRICING_PROFILE['adapter']
# These identify the exact original synthetic economics, accounts, epoch, cut and valuation.
SUPPORTED_BUNDLES = {
    'c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d': 'bill',
    '1b64bdcb2497423a72ddd7ca70b664a9a1cf6b8b6b595a562b87e6b2ac211dc9': 'note',
}
CURVE = {'curveId':'flat-3pct-v1', 'inputOrigin':'assumed',
         'construction':'flat-constant', 'inputHashes':[]}
MARKET = {'mode':'assumed-profile', 'assumedProfileId':'flat-3pct-v1', 'curveProvenance':CURVE}
WARNING = ("bundle declares marketInputs.status=NOT_SUPPLIED: it carries no observed market data. "
           "Any pricing must therefore name an assumed profile explicitly, and its results will be "
           "labelled marketProvenance='assumed'.")
UNSUPPORTED_DETAIL = ("conventions for this TREASURY are supported, but no pricer for this calculation "
                      "has been delivered yet. Closed by W1 build work; no external decision is required.")
D = Decimal


@dataclass(frozen=True)
class Approx:
    value: Decimal
    tolerance: Decimal = D('0.00000001')  # USD; much tighter than a cent.


def dimensionless(value):
    return Approx(value, D('0.000000000001'))


def match(actual, expected, path='result'):
    """Exact shape/types/labels, with explicit absolute numerical allowances only."""
    if isinstance(expected, Approx):
        bundle.require(abs(number(actual)-expected.value) <= expected.tolerance, path+': numerical mismatch')
    elif isinstance(expected, Decimal):
        bundle.require(number(actual) == expected, path+': exact numeric mismatch')
    elif isinstance(expected, dict):
        bundle.require(isinstance(actual, dict) and set(actual) == set(expected), path+': fields mismatch')
        for key, value in expected.items():
            match(actual[key], value, path+'.'+key)
    elif isinstance(expected, list):
        bundle.require(isinstance(actual, list) and len(actual) == len(expected), path+': item count mismatch')
        for i, value in enumerate(expected):
            match(actual[i], value, path+'.'+str(i))
    else:
        bundle.require(type(actual) is type(expected) and actual == expected, path+': value/type mismatch')


def accrual_bound(face):
    # Preserve v4's UNROUNDED six-decimal fraction bound plus one USD cent.
    return D('0.5') * D('0.000001') * abs(face) + D('0.01')


def inputs(source):
    manifest, parsed = bundle.validate(source)
    bundle.require(manifest['bundleId'] in SUPPORTED_BUNDLES, 'outside exact provisional synthetic fixture scope')
    terms = decode((Path(source)/'instrument-terms.json').read_bytes())
    return manifest, parsed['positions'][1], terms['entries'][0]['terms']


def reference(row, terms, valuation):
    """DCF from explicit regular fixture dates; decimal exp, no JAX/ORE/Alex calls."""
    with localcontext() as ctx:
        ctx.prec = 40
        face = D(row['quantity'])
        coupon = D(terms['couponRatePercent']) / 100
        redemption = D(terms['redemptionFraction'])
        val = date.fromisoformat(valuation)
        def time(day):
            return D((date.fromisoformat(day)-val).days) / 365
        def discount(day, rate=D('0.03')):
            return (-rate*time(day)).exp()
        maturity = terms['maturityDate']
        common = {'status':'ok', 'method':'discounted-cashflow', 'signedFaceAmount':face,
                  'redemptionFraction':redemption, 'maturityDate':maturity,
                  'valuationDate':valuation, 'curveProvenance':CURVE}
        fraction = D(row['accruedInterestFraction']) if coupon else D(0)
        accrued = face*fraction
        conversion = {'status':'ok', 'value':Approx(accrued),
                      'provenance':'converted' if coupon else 'structural-zero', 'currency':row['currency'],
                      'observedCleanPrice':D(row['closingMark']), 'signedFaceAmount':face}
        if not coupon:
            npv = {**common, 'value':Approx(face*redemption*discount(maturity)),
                   'discountFactor':dimensionless(discount(maturity)),
                   'yearFraction':dimensionless(time(maturity)), 'dayCount':'ACT/365 (Fixed)'}
            return npv, conversion, None
        schedule = terms['schedule']
        # The input bundle pin admits only regular semiannual ICMA periods, unadjusted, zero lag.
        coupons = [{**period, 'accrualFraction':D('0.5'), 'amountPerUnitFace':coupon/2,
                    'discountFactor':dimensionless(discount(period['paymentDate'])),
                    'yearFraction':dimensionless(time(period['paymentDate']))} for period in schedule]
        def price(rate):
            return face*(redemption*discount(maturity, rate) +
                         sum(coupon/2*discount(p['paymentDate'], rate) for p in schedule))
        dirty = price(D('0.03'))
        active = next(p for p in schedule if date.fromisoformat(p['startDate']) <= val < date.fromisoformat(p['endDate']))
        elapsed = (val-date.fromisoformat(active['startDate'])).days
        days = (date.fromisoformat(active['endDate'])-date.fromisoformat(active['startDate'])).days
        recomputed = D(elapsed)/D(days)*coupon/2
        difference = face*(fraction-recomputed)
        bound = accrual_bound(face)
        bundle.require(abs(difference) <= bound, 'fixture accrual exceeds unrounded bound')
        npv = {**common, 'value':Approx(dirty), 'priceType':'dirty', 'cleanNpv':Approx(dirty-accrued),
               'accruedInterest':Approx(accrued), 'couponRate':coupon,
               'redemptionPvPerUnitFace':dimensionless(redemption*discount(maturity)),
               'redemptionDiscountFactor':dimensionless(discount(maturity)),
               'accrualDayCount':'ACT/ACT (ICMA)', 'discountDayCount':'ACT/365 (Fixed)',
               'coupons':coupons, 'accrualReconciliation':{'accrualSource':'exported-fraction',
                   'exportedFraction':fraction, 'recomputedFraction':dimensionless(recomputed),
                   'difference':Approx(difference), 'tolerance':Approx(bound, D('0.000000000001'))}}
        sensitivity = {'status':'ok', 'value':Approx(price(D('0.0301'))-dirty),
                       'method':'bumped-revaluation', 'derivative':'dNPV/dZeroRate',
                       'shockedFactor':'zero-curve-parallel', 'bump':D('0.0001'), 'currency':'USD'}
        return npv, conversion, sensitivity


def validate(source, data):
    manifest, rows, terms = inputs(source)
    result = decode(data)  # rejects duplicate keys and all nonfinite numbers
    items = []
    counts = {c:{s:0 for s in STATUSES} for c in CALCULATIONS}
    for row in rows:
        identity = {'kind':'position', 'accountId':row['accountId'],
                    'clusterEpoch':manifest['clusterEpoch'], 'security':row['security']}
        item_id = bundle.digest(compact({'scheme':'traderx-item-v1', **identity}))[:32]
        npv, accrued, sensitivity = reference(row, terms, manifest['cut']['sessionDate'])
        outcomes = {c:{'status':'unsupported', 'reason':'NO_PRICER_AT_THIS_STAGE',
                       'detail':UNSUPPORTED_DETAIL} for c in CALCULATIONS}
        outcomes.update(npv=npv, accruedInterest=accrued)
        if sensitivity is not None:
            outcomes['rateSensitivity'] = sensitivity
        outcomes['vega'] = {'status':'not-applicable', 'detail':'TREASURY has no optionality, so vega is not defined for it'}
        outcomes['varEs'] = {'status':'not-applicable', 'detail':'VaR/ES is a portfolio-level statistic, not a per-item one'}
        for calc, outcome in outcomes.items():
            status = 'notApplicable' if outcome['status']=='not-applicable' else outcome['status']
            counts[calc][status] += 1
        items.append({'itemId':item_id, 'sourceIdentity':identity, 'currency':row['currency'],
                      'mappingVersion':'traderx-adapter-v1', 'calculations':outcomes})
    order = {'scheme':'traderx-item-v1', 'itemIds':[item['itemId'] for item in items]}
    expected = {'bundleId':manifest['bundleId'], 'clusterEpoch':manifest['clusterEpoch'],
                'sessionDate':manifest['cut']['sessionDate'], 'valuationTime':manifest['valuationTime'],
                'mappingVersion':'traderx-adapter-v1', 'engineVersion':'0.1.0',
                'marketProvenance':'assumed', 'marketInputs':MARKET, 'measure':'risk-neutral-pricing',
                'items':items, 'itemOrder':{**order, 'itemCount':len(items), 'sha256':bundle.digest(compact(order))},
                'coverage':{'itemCount':len(items), 'byCalculation':counts,
                            'allOutcomesAccountedFor':True, 'allApplicableComputed':False}, 'warnings':[WARNING]}
    match(result, expected)
    # Reconciliation checks on the actual values, independently of comparison with the reference.
    for item in result['items']:
        calc = item['calculations']; npv = calc['npv']
        if 'cleanNpv' in npv:
            bundle.require(abs(number(npv['value'])-number(npv['cleanNpv'])-number(npv['accruedInterest'])) <= D('0.00000001'), 'dirty/clean/accrued mismatch')
            rec = npv['accrualReconciliation']
            bundle.require(abs(number(rec['difference'])) <= accrual_bound(number(npv['signedFaceAmount'])), 'accrual exceeds unrounded bound')
    return {'pricedItems':len(items), 'accrualConversions':len(items),
            'noteParallelBumpItems':counts['rateSensitivity']['ok'],
            'validationScope':'provisional-dated-synthetic-fixtures',
            'portfolioRiskAvailable':False, 'usableForRisk':False}


class PricingFileAdapter:
    profile = PRICING_PROFILE
    completion_status = 'SYNTHETIC_PRICING_VALIDATED'
    resumable = True
    pending_label = 'LOCAL_RESULT_PENDING'

    def __init__(self, results):
        self.results = Path(results)

    def receipt(self, source, data):
        summary = validate(source, data)
        manifest, _, _ = inputs(source)
        return {'schema':'traderx.provisional-pricing-local-receipt.v1',
                'compatibilityProfile':self.profile, 'engineCommit':ENGINE_COMMIT,
                'engineCommitEvidence':'operator-selected compatibility profile; not authenticated producer identity',
                'producerResultSchema':'absent-at-reviewed-commit', 'bundleId':manifest['bundleId'],
                'resultSha256':bundle.digest(data),
                'units':{'npv':'signed USD', 'accruedInterest':'signed USD',
                         'noteRateSensitivity':'USD price change for +0.0001 parallel zero-rate bump; not divided by bump'},
                **summary}

    def execute(self, source, destination):
        manifest, _, _ = inputs(source)  # fail out-of-scope inputs even if no file arrives
        path = self.results/(manifest['bundleId']+'.json')
        if not path.exists() and not path.is_symlink():
            raise Deferred('local provisional pricing result not supplied; no external execution was requested')
        data = read(path)
        receipt = self.receipt(source, data)
        bundle.publish_directory(destination, {'results.json':data, 'acceptance.json':bundle.encoded(receipt)})

    def validate_result(self, source, destination):
        destination = Path(destination)
        bundle.require(destination.is_dir() and not destination.is_symlink()
                       and {p.name for p in destination.iterdir()} == {'results.json','acceptance.json'}, 'invalid pricing stored files')
        data = read(destination/'results.json')
        receipt = read(destination/'acceptance.json')
        match(decode(receipt), self.receipt(source, data), 'receipt')
        return bundle.digest(bundle.encoded({'results.json':bundle.digest(data), 'acceptance.json':bundle.digest(receipt)}))
