"""Strict local intake of Alex cb9b277 W0 outcomes, not a financial-pricing validator."""
import json
from decimal import Decimal
from pathlib import Path
import bundle
from worker_protocol import Deferred, W0_PROFILE

CALCULATIONS = ('npv', 'accruedInterest', 'rateSensitivity', 'rateGamma', 'theta', 'vega', 'varEs')
STATUSES = ('ok', 'unsupported', 'unavailable', 'failed', 'notApplicable')
ENGINE_COMMIT = W0_PROFILE['engineCommit']
MAX_BYTES = 4 * 1024 * 1024


def compact(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode()


def read(path):
    path = Path(path)
    bundle.require(path.is_file() and not path.is_symlink(), 'result must be a regular file')
    with path.open('rb') as stream:
        data = stream.read(MAX_BYTES + 1)
    bundle.require(len(data) <= MAX_BYTES, 'local W0 result exceeds byte limit')
    return data


def text(value):
    return isinstance(value, str) and bool(value.strip())


def number(value):
    bundle.require(type(value) in (int, float), 'result amount must be numeric, not boolean')
    return bundle.decimal(str(value), 'result amount')


def validate(source, data):
    # Reuse the duplicate-key/nonfinite rejecting decoder, not the producer's validator.
    from instrument_terms import decode
    manifest, parsed = bundle.validate(source)
    bundle.require(manifest['schema'] == 'traderx.eod-bundle.v2', 'W0 intake requires bundle v2')
    terms = decode((Path(source) / 'instrument-terms.json').read_bytes())
    bundle.require(terms['schema'] == 'traderx.instrument-terms.v1', 'pinned Alex commit supports terms v1 only')
    result = decode(data)
    fields = {'bundleId', 'clusterEpoch', 'sessionDate', 'valuationTime', 'mappingVersion',
              'engineVersion', 'marketProvenance', 'marketInputs', 'measure', 'items', 'itemOrder', 'coverage', 'warnings'}
    bundle.require(isinstance(result, dict) and set(result) == fields, 'unexpected W0 result fields')
    for key in ('bundleId', 'clusterEpoch', 'valuationTime'):
        bundle.require(result[key] == manifest[key], 'result input mismatch: '+key)
    bundle.require(result['sessionDate'] == manifest['cut']['sessionDate'], 'result business date mismatch')
    bundle.require(result['mappingVersion'] == 'traderx-adapter-v1' and result['engineVersion'] == '0.1.0', 'unsupported result version')
    bundle.require(all(result[k] is None for k in ('marketProvenance', 'marketInputs', 'measure')), 'this intake profile permits no market computation')
    bundle.require(isinstance(result['warnings'], list) and all(text(w) for w in result['warnings']), 'invalid warnings')
    refs = {(e['identity']['source'], e['identity'].get('security', e['identity'].get('contractId'))):e for e in terms['entries']}
    expected = {}
    for source_kind, (_, rows) in parsed.items():
        for row in rows:
            field = 'security' if source_kind == 'positions' else 'contractId'
            identity = {'kind':'position' if source_kind == 'positions' else 'contract',
                        'accountId':row['accountId'], 'clusterEpoch':manifest['clusterEpoch'], field:row[field]}
            key = bundle.digest(compact({'scheme':'traderx-item-v1', **identity}))[:32]
            bundle.require(key not in expected, 'source item ID collision')
            expected[key] = (identity, row, refs[(source_kind, row[field])])
    bundle.require(bool(expected), 'W0 intake requires a nonempty portfolio')
    bundle.require(isinstance(result['items'], list), 'items must be an array')
    seen = []
    counts = {c:{s:0 for s in STATUSES} for c in CALCULATIONS}
    accrual_count = 0
    for item in result['items']:
        bundle.require(isinstance(item, dict), 'invalid item')
        key = item.get('itemId')
        bundle.require(isinstance(key, str) and key in expected and key not in seen, 'unknown or duplicate item ID')
        seen.append(key)
        identity, row, ref = expected[key]
        bundle.require(item.get('sourceIdentity') == identity and item.get('currency') == row['currency'], 'source identity or currency mismatch')
        position = identity['kind'] == 'position'
        product = row['instrumentType'] if position else row['productType']
        bundle.require(product in ('TREASURY','SWAP'), 'unsupported intake instrument')
        missing = ref['missingTerms']
        # Restrict this first acceptance profile to the delivered Treasury/SOFR cases.
        bundle.require((position and not missing) or (not position and bool(missing)), 'outside W0 intake convention profile')
        expected_fields = {'itemId','sourceIdentity','currency','calculations','mappingVersion'} if position else {'itemId','sourceIdentity','currency','calculations','refusal'}
        bundle.require(set(item) == expected_fields, 'unexpected item fields')
        if position:
            bundle.require(item['mappingVersion'] == result['mappingVersion'], 'item mapping version mismatch')
        else:
            refusal = item['refusal']
            bundle.require(isinstance(refusal,dict) and set(refusal)=={'reason','detail','missingTerms','offendingFields'}
                           and refusal['reason']=='CONVENTION_NOT_SUPPORTED' and refusal['missingTerms']==missing
                           and refusal['offendingFields']==[] and text(refusal['detail']), 'incorrect convention refusal')
        outcomes = item['calculations']
        bundle.require(isinstance(outcomes,dict) and set(outcomes)==set(CALCULATIONS), 'missing or extra calculations')
        for calc in CALCULATIONS:
            outcome = outcomes[calc]
            bundle.require(isinstance(outcome,dict), 'invalid calculation outcome')
            if calc in ('vega','varEs'):
                bundle.require(set(outcome)=={'status','detail'} and outcome['status']=='not-applicable'
                               and text(outcome['detail']), 'invalid non-applicability')
                # varEs here is per-item only. It does not account for portfolio-level VaR/ES.
                expected_detail = ('VaR/ES is a portfolio-level statistic, not a per-item one' if calc=='varEs' else
                                   f'{product} has no optionality, so vega is not defined for it')
                bundle.require(outcome['detail']==expected_detail, 'unrecognized non-applicability basis')
            elif calc=='accruedInterest' and position:
                bundle.require(set(outcome)=={'status','value','provenance','currency','observedCleanPrice','signedFaceAmount'}
                               and outcome['status']=='ok', 'invalid exported accrual conversion')
                quantity = bundle.decimal(row['quantity'],'quantity')
                zero = bundle.decimal(row['coupon'],'coupon')==0
                fraction = Decimal(0) if zero else bundle.decimal(row['accruedInterestFraction'],'accrual')
                bundle.require(outcome['currency']==row['currency'] and outcome['provenance']==('structural-zero' if zero else 'converted'), 'accrual provenance mismatch')
                bundle.require(number(outcome['signedFaceAmount'])==quantity
                               and number(outcome['observedCleanPrice'])==bundle.decimal(row['closingMark'],'mark'), 'normalization input mismatch')
                # Only binary-float serialization tolerance, NOT the financial recomputation tolerance.
                bundle.require(abs(number(outcome['value'])-fraction*quantity)<=Decimal('0.00000001'), 'accrual value or sign mismatch')
                accrual_count += 1
            else:
                allowed = {'status','reason','detail'} | ({'missingTerms'} if missing else set())
                bundle.require(set(outcome)==allowed and outcome['status']=='unsupported' and text(outcome['detail']), 'W0 must not claim a price or unsupported outcome shape')
                bundle.require(outcome['reason']==('CONVENTION_NOT_SUPPORTED' if missing else 'NO_PRICER_AT_THIS_STAGE'), 'wrong refusal reason')
                if missing:
                    bundle.require(outcome['missingTerms']==missing, 'missing terms differ from source')
            status = 'notApplicable' if outcome['status']=='not-applicable' else outcome['status']
            counts[calc][status] += 1
    bundle.require(set(seen)==set(expected), 'missing result items')
    order = {'scheme':'traderx-item-v1','itemIds':seen}
    expected_order = {**order,'itemCount':len(seen),'sha256':bundle.digest(compact(order))}
    bundle.require(result['itemOrder']==expected_order and type(result['itemOrder']['itemCount']) is int, 'invalid item order/hash')
    coverage = result['coverage']
    expected_coverage = {'itemCount':len(seen),'byCalculation':counts,'allOutcomesAccountedFor':True,'allApplicableComputed':False}
    bundle.require(coverage==expected_coverage, 'coverage arithmetic mismatch')
    bundle.require(type(coverage['itemCount']) is int and type(coverage['allOutcomesAccountedFor']) is bool
                   and type(coverage['allApplicableComputed']) is bool
                   and all(type(n) is int for d in coverage['byCalculation'].values() for n in d.values()), 'coverage type mismatch')
    return {'pricedItems':0,'accrualConversions':accrual_count,'portfolioRiskAvailable':False,'usableForRisk':False}


class W0FileAdapter:
    profile = W0_PROFILE
    completion_status = 'W0_VALIDATED'
    resumable = True
    pending_label = 'LOCAL_RESULT_PENDING'

    def __init__(self, results):
        self.results = Path(results)

    def execute(self, source, destination):
        manifest, _ = bundle.validate(source)
        path = self.results / (manifest['bundleId']+'.json')
        if not path.exists() and not path.is_symlink():
            raise Deferred('local W0 result not supplied; no external execution was requested')
        data = read(path)
        summary = validate(source,data)
        receipt = {'schema':'traderx.w0-local-receipt.v1','engineCommit':ENGINE_COMMIT,
                   'engineCommitEvidence':'operator-selected compatibility profile; not authenticated producer identity',
                   'bundleId':manifest['bundleId'],'resultSha256':bundle.digest(data),**summary}
        bundle.publish_directory(destination,{'results.json':data,'acceptance.json':bundle.encoded(receipt)})

    def validate_result(self, source, destination):
        from instrument_terms import decode
        destination = Path(destination)
        bundle.require(destination.is_dir() and not destination.is_symlink()
                       and {p.name for p in destination.iterdir()}=={'results.json','acceptance.json'}, 'invalid W0 stored files')
        data = read(destination/'results.json')
        summary = validate(source,data)
        manifest,_ = bundle.validate(source)
        expected = {'schema':'traderx.w0-local-receipt.v1','engineCommit':ENGINE_COMMIT,
                    'engineCommitEvidence':'operator-selected compatibility profile; not authenticated producer identity',
                    'bundleId':manifest['bundleId'],'resultSha256':bundle.digest(data),**summary}
        receipt = read(destination/'acceptance.json')
        bundle.require(decode(receipt)==expected, 'invalid local acceptance receipt')
        return bundle.digest(bundle.encoded({'results.json':bundle.digest(data),'acceptance.json':bundle.digest(receipt)}))
