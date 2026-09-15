"""Provisional loopback-only HTTP transport. Never accepts financial results."""
import base64
import http.client
import re
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

import bundle
from coordinator import Deferred, HTTP_PROFILE, MockAdapter, decode_json, load_json

WIRE = 'traderx.http-mock.draft-1'
MAX_BYTES = 4 * 1024 * 1024  # Local test transport ceiling, not a production batch-size claim.


def workload(manifest):
    return bundle.digest(bundle.encoded({'bundleId': manifest['bundleId'], 'profile': HTTP_PROFILE}))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class HttpAdapter(MockAdapter):
    profile = HTTP_PROFILE
    result_files = {'results.json', 'transport.json'}
    resumable = True

    def __init__(self, endpoint, timeout=2, polls=3, interval=0.1):
        parsed = urlsplit(endpoint)
        bundle.require(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1'
                       and parsed.port and not parsed.username and not parsed.password
                       and parsed.path in ('', '/') and not parsed.query and not parsed.fragment,
                       'HTTP test worker must be http://127.0.0.1:PORT')
        bundle.require(0 < timeout <= 10 and type(polls) is int and 1 <= polls <= 10
                       and 0 <= interval <= 1, 'invalid bounded HTTP test settings')
        self.endpoint = endpoint.rstrip('/')
        self.timeout, self.polls, self.interval = timeout, polls, interval
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, body=None):
        data = bundle.encoded(body) if body is not None else None
        bundle.require(data is None or len(data) <= MAX_BYTES, 'request exceeds HTTP test byte limit')
        request = urllib.request.Request(self.endpoint + path, data=data, method=method,
                                          headers={'Content-Type': 'application/json'})
        try:
            with self.client.open(request, timeout=self.timeout) as response:
                payload = response.read(MAX_BYTES + 1)
                bundle.require(len(payload) <= MAX_BYTES, 'response exceeds HTTP test byte limit')
                return response.status, decode_json(payload)
        except urllib.error.HTTPError as exc:
            exc.close()
            if exc.code == 404:
                return 404, None
            if exc.code == 429 or exc.code >= 500:
                raise Deferred(f'worker HTTP {exc.code}; reconcile on next run') from exc
            raise ValueError(f'worker HTTP {exc.code}') from exc
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException) as exc:
            raise Deferred('worker response unavailable; reconcile on next run') from exc

    def envelope(self, data, key):
        bundle.require(isinstance(data, dict) and data.get('schema') == WIRE
                       and data.get('workloadKey') == key and data.get('profile') == self.profile,
                       'worker protocol or workload mismatch')
        bundle.require(isinstance(data.get('workerAttemptId'), str)
                       and re.fullmatch('[0-9a-f]{32}', data['workerAttemptId']), 'invalid worker attempt identity')
        status = data.get('status')
        fields = {'schema', 'workloadKey', 'profile', 'workerAttemptId', 'status'}
        if status == 'MOCK_COMPLETE':
            fields |= {'result', 'resultSha256'}
            bundle.require(bundle.digest(bundle.encoded(data['result'])) == data['resultSha256'],
                           'worker result hash mismatch')
        elif status == 'FAILED':
            fields.add('reason')
        else:
            bundle.require(status in ('QUEUED', 'RUNNING'), 'unknown worker status')
        bundle.require(set(data) == fields, 'unexpected worker envelope fields')
        return status

    def execute(self, source, destination):
        manifest, _ = bundle.validate(source)
        key = workload(manifest)
        path = '/risk/results/by-workload/' + key
        code, data = self.request('GET', path)
        if code == 404:
            code, capabilities = self.request('GET', '/capabilities')
            bundle.require(code == 200 and capabilities == {
                'schema': WIRE, 'profile': self.profile, 'pricing': False}, 'incompatible worker capabilities')
            request = {'schema': WIRE, 'workloadKey': key, 'profile': self.profile,
                       'clientAttemptId': destination.name,
                       'bundle': {'manifest': manifest, **{
                           kind: base64.b64encode((source / f'{kind}.csv').read_bytes()).decode('ascii')
                           for kind in ('positions', 'contracts')}}}
            code, data = self.request('POST', '/risk/jobs', request)
            bundle.require(code in (200, 202), 'unexpected submit status')
        else:
            bundle.require(code == 200, 'unexpected lookup status')
        for poll in range(self.polls + 1):
            status = self.envelope(data, key)
            if status == 'MOCK_COMPLETE':
                result = data['result']
                transport = {k: v for k, v in data.items() if k != 'result'}
                bundle.publish_directory(destination, {'results.json': bundle.encoded(result),
                                                      'transport.json': bundle.encoded(transport)})
                return
            if status == 'FAILED':
                raise ValueError(f'worker reported failure: {data["reason"]}')
            if poll < self.polls:
                time.sleep(self.interval)
                code, data = self.request('GET', path)
                if code == 404:
                    raise Deferred('accepted workload temporarily unavailable; do not resubmit this run')
                bundle.require(code == 200, 'unexpected poll status')
        raise Deferred('worker still running; poll again on next run')

    def validate_result(self, source, result_directory):
        super().validate_result(source, result_directory)
        manifest, _ = bundle.validate(source)
        path = result_directory / 'transport.json'
        bundle.require(path.is_file() and not path.is_symlink(), 'invalid transport provenance file')
        transport = load_json(path)
        result = load_json(result_directory / 'results.json')
        bundle.require(self.envelope({**transport, 'result': result}, workload(manifest)) == 'MOCK_COMPLETE',
                       'stored HTTP result is incomplete')
        return bundle.digest(bundle.encoded({name: bundle.digest((result_directory / name).read_bytes())
                                             for name in sorted(self.result_files)}))
