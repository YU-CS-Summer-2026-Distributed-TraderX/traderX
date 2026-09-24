"""RI-03 closed synthetic export profile over the accepted EOD HTTP API.

No engine imports or pricing logic. Terminal consumer holds need operator review;
GET recovery never treats upstream UNKNOWN_WORKLOAD as permission to POST again.
"""
import http.client
import json
import math
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
from urllib.parse import urlsplit

import bundle
from coordinator import decode_json
from http_adapter import NoRedirect
from worker_protocol import CONTAINER_PROFILE, Deferred, Uncertain
from w0_result import CALCULATIONS, STATUSES

BILL = 'c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d'
MARKET = {'mode': 'assumed-profile', 'assumedProfileId': 'flat-3pct-v1'}
CURVE = {'curveId': 'flat-3pct-v1', 'inputOrigin': 'assumed',
         'construction': 'flat-constant', 'inputHashes': []}
MAX_BYTES = 4 * 1024 * 1024


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def workload(manifest):
    # Wire hash protocol, not financial logic. Verified against actual engine HTTP.
    return 'sha256:' + bundle.digest(canonical({
        'keyVersion': 'workload-key-v1', 'bundleId': manifest['bundleId'],
        'clusterEpoch': manifest['clusterEpoch'], 'sessionDate': manifest['cut']['sessionDate'],
        'marketInputs': MARKET, 'calculations': None, 'reportingCurrency': None,
        'mappingVersion': 'traderx-adapter-v1', 'engineVersion': '0.1.0',
        'resultSchema': CONTAINER_PROFILE['resultSchema'], 'precision': None, 'termsHash': None}))


def inputs(source):
    manifest, parsed = bundle.validate(source)
    bundle.require(manifest['bundleId'] == BILL and manifest['inputOrigin'] == 'synthetic',
                   'unsupported input scope: RI-03 admits only the original exported synthetic bill')
    return manifest, parsed['positions'][1]


def durable_publish(path, files):
    bundle.publish_directory(path, files)
    # publish_directory fsyncs files; durable intent also requires the directory entries.
    for directory in (path, path.parent):
        fd = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def read(path):
    bundle.require(path.is_file() and not path.is_symlink(), 'invalid custody file')
    with path.open('rb') as stream:
        data = stream.read(MAX_BYTES + 1)
    bundle.require(len(data) <= MAX_BYTES, 'custody file too large')
    return data


def schema_validator():
    """Diagnose missing runtime assets before a caller creates or submits a job."""
    try:
        from jsonschema import Draft202012Validator
    except ImportError as exc:
        raise ValueError('RI-03 requires jsonschema: install requirements-container.txt with this Python interpreter') from exc
    schema = decode_json(Path(__file__).with_name('container-result-schema.json').read_bytes())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def finite_numbers(value):
    if isinstance(value, dict):
        for child in value.values():
            finite_numbers(child)
    elif isinstance(value, list):
        for child in value:
            finite_numbers(child)
    elif isinstance(value, float):
        bundle.require(math.isfinite(value), 'non-finite numerical outcome')


def amount_metadata(outcome, fields):
    # Calculation payloads are open in the upstream schema. This closed profile
    # accepts only reviewed fields and optional USD amount annotations, never a
    # currency conversion or an unknown price-per-face/percentage unit extension.
    amounts = {'currency', 'reportingCurrency', 'valueCurrency', 'unit', 'units', 'valueUnit'}
    bundle.require(set(outcome) <= fields | amounts, 'unreviewed calculation fields/units')
    for key in amounts & set(outcome):
        bundle.require(outcome[key] == 'USD', 'contradictory amount currency/unit: ' + key)


def validate_document(source, result):
    """Published schema plus consumer-owned identities, units and invariants; no pricer."""
    validator = schema_validator()
    manifest, rows = inputs(source)
    finite_numbers(result)
    errors = list(validator.iter_errors(result))
    bundle.require(not errors, 'result schema mismatch')
    for key, expected in {'bundleId': manifest['bundleId'], 'clusterEpoch': manifest['clusterEpoch'],
                          'sessionDate': manifest['cut']['sessionDate'], 'valuationTime': manifest['valuationTime'],
                          'mappingVersion': 'traderx-adapter-v1', 'engineVersion': '0.1.0',
                          'resultSchema': CONTAINER_PROFILE['resultSchema'],
                          'marketProvenance': 'assumed', 'measure': 'risk-neutral-pricing',
                          'marketInputs': {**MARKET, 'curveProvenance': CURVE}}.items():
        bundle.require(result.get(key) == expected, 'result identity/provenance mismatch: ' + key)
    bundle.require(len(result['items']) == len(rows), 'result item count mismatch')
    counts = {c: {s: 0 for s in STATUSES} for c in CALCULATIONS}
    ids = []
    for row, item in zip(rows, result['items']):
        identity = {'kind': 'position', 'accountId': row['accountId'],
                    'clusterEpoch': manifest['clusterEpoch'], 'security': row['security']}
        item_id = bundle.digest(canonical({'scheme': 'traderx-item-v1', **identity}))[:32]
        bundle.require(item['sourceIdentity'] == identity and item['itemId'] == item_id
                       and item['currency'] == row['currency'] == 'USD'
                       and item['mappingVersion'] == 'traderx-adapter-v1', 'result item identity mismatch')
        ids.append(item_id)
        outcomes = item['calculations']
        bundle.require(set(outcomes) == set(CALCULATIONS), 'calculation set mismatch')
        for calc, outcome in outcomes.items():
            status = outcome['status']
            counts[calc]['notApplicable' if status == 'not-applicable' else status] += 1
            expected = ('ok' if calc in ('npv', 'accruedInterest') else
                        'not-applicable' if calc in ('vega', 'varEs') else 'unsupported')
            bundle.require(status == expected, 'unsupported or unavailable calculation in closed profile')
        npv, accrued = outcomes['npv'], outcomes['accruedInterest']
        for outcome in (npv, accrued):
            bundle.require(type(outcome.get('value')) in (int, float), 'missing numerical outcome')
            bundle.require(outcome.get('signedFaceAmount') == float(row['quantity']), 'face binding mismatch')
        face = float(row['quantity'])
        bundle.require((npv['value'] > 0) == (face > 0) and npv['value'] != 0,
                       'bill NPV sign inconsistent with signed face')
        bundle.require(accrued['value'] == 0, 'structural-zero accrual must equal zero')
        amount_metadata(npv, {'status', 'value', 'method', 'signedFaceAmount', 'redemptionFraction',
                             'discountFactor', 'yearFraction', 'dayCount', 'maturityDate',
                             'valuationDate', 'curveProvenance'})
        amount_metadata(accrued, {'status', 'value', 'provenance', 'observedCleanPrice',
                                 'signedFaceAmount', 'accrualSource'})
        bundle.require(npv.get('curveProvenance') == CURVE and npv.get('valuationDate') == manifest['cut']['sessionDate']
                       and npv.get('maturityDate') == row['maturityDate'], 'NPV provenance mismatch')
        bundle.require(accrued.get('accrualSource') == accrued.get('provenance') == 'structural-zero'
                       and accrued.get('currency') == 'USD', 'accrual provenance mismatch')
    order = {'scheme': 'traderx-item-v1', 'itemIds': ids}
    bundle.require(result['itemOrder'] == {**order, 'itemCount': len(ids), 'sha256': bundle.digest(canonical(order))},
                   'item order mismatch')
    bundle.require(result['coverage'] == {'itemCount': len(ids), 'byCalculation': counts,
                   'allOutcomesAccountedFor': True, 'allApplicableComputed': False}, 'coverage mismatch')
    return result


class ContainerAdapter:
    profile = CONTAINER_PROFILE
    completion_status = 'CONTAINER_PRICING_VALIDATED'
    resumable = True
    pending_label = 'REMOTE_PENDING'
    result_files = {'http-response.json', 'attempt-response.json', 'request.json', 'results.json', 'acceptance.json'}

    def __init__(self, endpoint=None, staging=None, timeout=15, market_inputs=MARKET,
                 calculations=None, reporting_currency=None):
        bundle.require(market_inputs == MARKET, 'explicit supported assumed market profile required')
        bundle.require(calculations is None and reporting_currency is None,
                       'unsupported request options: calculations and reporting currency must be omitted')
        schema_validator()  # Fail before any staging, intent publication or network request.
        self.endpoint = endpoint.rstrip('/') if endpoint else None
        self.staging = Path(staging).resolve() if staging else None
        bundle.require(0 < timeout <= 60, 'invalid HTTP timeout')
        self.timeout = timeout
        if endpoint:
            parsed = urlsplit(endpoint)
            bundle.require(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port
                           and not parsed.username and not parsed.password and parsed.path in ('', '/')
                           and not parsed.query and not parsed.fragment, 'literal loopback endpoint required')
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, body=None):
        bundle.require(self.endpoint is not None, 'validation-only adapter cannot execute')
        request = urllib.request.Request(self.endpoint + path, method=method,
                  data=canonical(body) if body is not None else None, headers={'Content-Type': 'application/json'})
        try:
            response = self.client.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as exc:
            response = exc
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException) as exc:
            raise Uncertain('HTTP outcome unavailable; do not resubmit') from exc
        try:
            with response:
                data = response.read(MAX_BYTES + 1)
                bundle.require(len(data) <= MAX_BYTES, 'HTTP response too large')
                return response.code, data
        except (OSError, http.client.HTTPException) as exc:
            raise Uncertain('HTTP body incomplete; do not resubmit') from exc

    def request_record(self, source, destination):
        manifest, _ = inputs(source)
        return {'profile': self.profile, 'workloadKey': workload(manifest),
                'consumerAttemptId': destination.name,
                'body': {'bundlePath': '/data/inputs/' + manifest['bundleId'],
                         'submissionId': destination.name, 'reuseExistingResult': False,
                         'marketInputs': MARKET}}

    def execute(self, source, destination):
        source, destination = Path(source), Path(destination)
        record = self.request_record(source, destination)
        intent = destination.with_name('.' + destination.name + '-intent')
        response_store = destination.with_name('.' + destination.name + '-response')
        binding_store = destination.with_name('.' + destination.name + '-binding')
        if not intent.exists():
            bundle.require(self.staging is not None, 'input staging required')
            staged = self.staging / BILL
            files = {p.name: read(p) for p in source.iterdir()}
            if not staged.exists():
                durable_publish(staged, files)
                # Only this explicitly synthetic closed profile may be mounted for UID 10001.
                staged.chmod(0o755)
                for p in staged.iterdir():
                    p.chmod(0o444)
            bundle.require({p.name: read(p) for p in staged.iterdir()} == files, 'staged bytes changed')
            durable_publish(intent, {'request.json': canonical(record)})
            code, raw = self.request('POST', '/eod/price', record['body'])
            durable_publish(response_store, {'body.json': raw, 'status.json': canonical(code)})
        else:
            bundle.require(read(intent/'request.json') == canonical(record), 'stored submission intent mismatch')
        if not response_store.exists():
            code, raw = self.request('GET', '/eod/results/by-workload/' + record['workloadKey'])
            if code == 404:
                raise Uncertain('upstream workload unknown after submitted intent; execution cannot be excluded')
            if code >= 500:
                raise Uncertain('upstream reconciliation unavailable')
            bundle.require(code == 200, 'upstream reconciliation refused')
            state = decode_json(raw).get('state')
            if state == 'running':
                raise Deferred('upstream still running; GET only on resume')
            durable_publish(response_store, {'body.json': raw, 'status.json': canonical(code)})
        raw = read(response_store/'body.json')
        code = decode_json(read(response_store/'status.json'))
        if code >= 500:
            raise Uncertain('upstream server error after submission; publication/execution uncertain')
        bundle.require(code == 200, 'engine submission refused with HTTP ' + str(code))
        envelope = decode_json(raw)
        bundle.require(envelope.get('workloadKey') == record['workloadKey'], 'workload identity mismatch')
        attempt_id = envelope.get('attemptId', '')
        bundle.require(isinstance(attempt_id, str) and re.fullmatch('[0-9a-f-]{36}', attempt_id), 'invalid engine attempt')
        if not binding_store.exists():
            code, binding = self.request('GET', '/eod/attempts/' + attempt_id)
            if code != 200:
                raise Uncertain('attempt binding unavailable; cannot promote result')
            durable_publish(binding_store, {'body.json': binding})
        binding = read(binding_store/'body.json')
        self.validate_binding(envelope, decode_json(binding), record)
        bundle.require(envelope.get('state') == 'completed', 'engine attempt failed')
        result = validate_document(source, envelope['result'])
        result_bytes = canonical(result)
        files = {'http-response.json': raw, 'attempt-response.json': binding,
                 'request.json': canonical(record), 'results.json': result_bytes}
        receipt = {'profile': self.profile, 'hashes': {k: bundle.digest(v) for k, v in files.items()},
                   'workloadKey': record['workloadKey'], 'engineAttemptId': attempt_id,
                   'consumerAttemptId': destination.name, 'usableForRisk': False,
                   'producerAuthentication': 'NOT_ESTABLISHED'}
        durable_publish(destination, {**files, 'acceptance.json': canonical(receipt)})

    def validate_binding(self, envelope, binding, record):
        bundle.require(binding.get('workloadKey') == record['workloadKey']
                       and binding.get('attemptId') == envelope.get('attemptId')
                       and binding.get('submissionId') == record['body']['submissionId'], 'submission/attempt binding mismatch')
        bundle.require(binding.get('state') == envelope.get('state')
                       and binding.get('result') == envelope.get('result'), 'attempt result mismatch')

    def validate_result(self, source, destination):
        destination = Path(destination)
        bundle.require(destination.is_dir() and not destination.is_symlink()
                       and {p.name for p in destination.iterdir()} == self.result_files, 'invalid container custody files')
        files = {k: read(destination/k) for k in self.result_files}
        record = decode_json(files['request.json'])
        bundle.require(record == self.request_record(source, destination), 'request identity mismatch')
        envelope = decode_json(files['http-response.json'])
        binding = decode_json(files['attempt-response.json'])
        self.validate_binding(envelope, binding, record)
        bundle.require(envelope.get('workloadKey') == record['workloadKey'] and envelope.get('state') == 'completed',
                       'invalid completed envelope')
        result = validate_document(source, decode_json(files['results.json']))
        bundle.require(result == envelope['result'], 'extracted result differs from original HTTP bytes')
        expected = {'profile': self.profile, 'hashes': {k: bundle.digest(v) for k, v in files.items() if k != 'acceptance.json'},
                    'workloadKey': record['workloadKey'], 'engineAttemptId': envelope['attemptId'],
                    'consumerAttemptId': destination.name, 'usableForRisk': False,
                    'producerAuthentication': 'NOT_ESTABLISHED'}
        bundle.require(decode_json(files['acceptance.json']) == expected, 'receipt/hash mismatch')
        return bundle.digest(canonical({k: bundle.digest(v) for k, v in files.items()}))
