"""Versioned reference terms for the initial Treasury/SWAP exchange; no curve or pricing model."""
import json
from datetime import date
from calendar import monthrange
import bundle

SCHEMA = 'traderx.instrument-terms.v1'
BOND = {'currency', 'issueDate', 'maturityDate', 'couponRatePercent', 'couponFrequency',
        'dayCount', 'calendar', 'businessDayAdjustment', 'paymentLagBusinessDays',
        'settlementDays', 'firstCouponDate', 'penultimateCouponDate', 'stubConvention',
        'schedule', 'faceDenomination', 'redemptionFraction', 'priceBasis', 'quantityUnit'}
SWAP = {'currency', 'payReceive', 'notional', 'fixedRate', 'floatIndex', 'effectiveDate',
        'maturityDate', 'fixedPaymentFrequency', 'fixedDayCount', 'fixedSchedule',
        'floatingSchedule', 'floatingDayCount', 'calendar', 'businessDayAdjustment',
        'fixedPaymentLagBusinessDays', 'floatingPaymentLagBusinessDays',
        'overnightCompounding', 'lookbackBusinessDays', 'lockoutBusinessDays',
        'observationShift', 'fixingCalendar', 'fixingHistoryReference'}
REQUIRED = {'TREASURY': BOND, 'SWAP': SWAP}


def decode(data):
    def pairs(items):
        out = {}
        for k, v in items:
            bundle.require(k not in out, 'duplicate terms JSON key: '+k)
            out[k] = v
        return out
    def invalid(value):
        raise ValueError('non-finite JSON number: '+value)
    return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid)


def schedule(periods, start, end):
    bundle.require(isinstance(periods, list) and bool(periods), 'nonempty explicit schedule required')
    previous = date.fromisoformat(start)
    for period in periods:
        bundle.require(isinstance(period, dict) and set(period) == {'startDate', 'endDate', 'paymentDate'},
                       'invalid schedule period')
        a, b, payment = [date.fromisoformat(period[k]) for k in ('startDate', 'endDate', 'paymentDate')]
        bundle.require(a == previous and a < b <= payment, 'schedule gap, overlap or invalid payment date')
        previous = b
    bundle.require(previous == date.fromisoformat(end), 'schedule does not end at maturity')


def validate(data, parsed, epoch, origin):
    doc = decode(data)
    bundle.require(isinstance(doc, dict) and set(doc) == {'schema', 'entries'} and doc['schema'] == SCHEMA,
                   'invalid terms artifact schema or fields')
    bundle.require(isinstance(doc['entries'], list), 'terms entries must be an array')
    expected = {}
    for kind, (_, rows) in parsed.items():
        for row in rows:
            key = (kind, row['security'] if kind == 'positions' else row['contractId'])
            expected.setdefault(key, []).append(row)
    seen = set()
    for entry in doc['entries']:
        bundle.require(isinstance(entry, dict) and set(entry) == {
            'identity', 'instrumentType', 'provenance', 'terms', 'missingTerms'}, 'invalid terms entry fields')
        identity = entry['identity']
        bundle.require(isinstance(identity, dict) and identity.get('source') in bundle.KINDS, 'invalid terms identity')
        kind = identity['source']
        fields = {'source', 'security'} if kind == 'positions' else {'source', 'contractId', 'clusterEpoch'}
        bundle.require(set(identity) == fields, 'invalid identity fields')
        if kind == 'contracts':
            bundle.require(identity['clusterEpoch'] == epoch, 'terms contract epoch mismatch')
        key = (kind, identity['security' if kind == 'positions' else 'contractId'])
        bundle.require(key in expected and key not in seen, 'extra or duplicate terms identity')
        seen.add(key)
        product = entry['instrumentType']
        bundle.require(product in REQUIRED, 'initial terms profile supports only Treasury and swap')
        provenance = entry['provenance']
        bundle.require(isinstance(provenance, dict) and set(provenance) == {'origin', 'description'}
                       and provenance['origin'] in ('synthetic', 'supplied')
                       and isinstance(provenance['description'], str) and provenance['description'].strip(),
                       'invalid terms provenance')
        bundle.require(origin == 'synthetic' or provenance['origin'] == 'supplied',
                       'synthetic terms cannot accompany real export origin')
        terms = entry['terms']
        bundle.require(isinstance(terms, dict) and set(terms) <= REQUIRED[product], 'unknown pricing term')
        missing = sorted(REQUIRED[product] - set(terms))
        bundle.require(entry['missingTerms'] == missing, 'missingTerms must enumerate absent fields exactly')
        # Missing is absence, not an unexplained null. Stub dates and nonapplicable fixing history
        # may explicitly be null; a complete artifact is not a claim of engine support.
        nullable = {'firstCouponDate', 'penultimateCouponDate', 'fixingHistoryReference'}
        for name, value in terms.items():
            bundle.require(value is not None or name in nullable, 'unexplained null term: '+name)
            if name in ('observationShift',):
                bundle.require(type(value) is bool, 'boolean term required: '+name)
            elif name not in nullable and name not in ('schedule', 'fixedSchedule', 'floatingSchedule') and not name.endswith('BusinessDays') and name != 'settlementDays':
                bundle.require(isinstance(value, str) and value.strip(), 'nonempty string term required: '+name)
            if name.endswith('Date') and value is not None:
                date.fromisoformat(value)
            if name.endswith('BusinessDays') or name == 'settlementDays':
                bundle.require(type(value) is int and value >= 0, 'invalid day lag: '+name)
        for row in expected[key]:
            bundle.require(product == row['instrumentType' if kind == 'positions' else 'productType'], 'terms product mismatch')
            mapping = ({'currency': 'currency', 'maturityDate': 'maturityDate', 'couponRatePercent': 'coupon'}
                       if kind == 'positions' else {'currency':'currency', 'payReceive':'payReceive',
                       'notional':'notional', 'fixedRate':'fixedRate', 'floatIndex':'floatIndex',
                       'effectiveDate':'effectiveDate', 'maturityDate':'maturityDate',
                       'fixedPaymentFrequency':'paymentFrequency', 'fixedDayCount':'dayCount'})
            for name, field in mapping.items():
                bundle.require(name in terms, 'terms must preserve exported field: '+name)
                if name in ('couponRatePercent', 'notional', 'fixedRate'):
                    bundle.require(isinstance(terms[name], str) and bundle.decimal(terms[name],name) == bundle.decimal(row[field],field),
                                   'terms disagree with export: '+name)
                else:
                    bundle.require(terms[name] == row[field], 'terms disagree with export: '+name)
        for name in ('faceDenomination', 'redemptionFraction'):
            if name in terms:
                bundle.require(isinstance(terms[name], str) and bundle.decimal(terms[name],name) > 0, 'invalid '+name)
        if product == 'TREASURY':
            if 'issueDate' in terms:
                bundle.require(date.fromisoformat(terms['issueDate']) < date.fromisoformat(terms['maturityDate']), 'invalid bond dates')
            if 'priceBasis' in terms:
                bundle.require(terms['priceBasis'] == 'clean-fraction-of-par', 'invalid price basis')
            if 'quantityUnit' in terms:
                bundle.require(terms['quantityUnit'] == 'signed-currency-face', 'invalid quantity unit')
            if 'schedule' in terms:
                if bundle.decimal(terms['couponRatePercent'], 'coupon') == 0:
                    bundle.require(terms['schedule'] == [] and terms.get('couponFrequency') == 'NONE'
                                   and terms.get('firstCouponDate') is None and terms.get('penultimateCouponDate') is None,
                                   'zero coupon must have no coupon schedule')
                else:
                    schedule(terms['schedule'], terms['issueDate'], terms['maturityDate'])
                    bundle.require(terms.get('couponFrequency') == '6M' and terms.get('dayCount') == 'ACT/ACT (ICMA)'
                                   and terms.get('stubConvention') == 'NONE',
                                   'initial note terms must match exporter semiannual ICMA no-stub assumption')
                    maturity = date.fromisoformat(terms['maturityDate'])
                    periods = terms['schedule']
                    for i, period in enumerate(reversed(periods), 1):
                        absolute = maturity.year * 12 + maturity.month - 1 - 6*i
                        year, month0 = divmod(absolute, 12)
                        expected_start = date(year, month0+1, min(maturity.day,monthrange(year,month0+1)[1]))
                        bundle.require(period['startDate'] == expected_start.isoformat(), 'schedule differs from exporter maturity anchor')
                    bundle.require(terms.get('firstCouponDate') == periods[0]['endDate']
                                   and terms.get('penultimateCouponDate') == (periods[-2]['endDate'] if len(periods)>1 else None),
                                   'coupon date metadata disagrees with explicit schedule')
        else:
            for name in ('fixedSchedule', 'floatingSchedule'):
                if name in terms:
                    schedule(terms[name], terms['effectiveDate'], terms['maturityDate'])
    bundle.require(seen == set(expected), 'missing instrument terms identity')
    return doc
