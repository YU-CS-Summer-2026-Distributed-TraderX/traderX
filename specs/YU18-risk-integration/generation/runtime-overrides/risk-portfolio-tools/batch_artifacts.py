"""Bounded GCS custody using attached VM credentials; offline receipt checks."""
import hashlib
import json
import re
import sqlite3
import urllib.parse
import urllib.request
from contextlib import closing
from pathlib import Path

from corpus import digest, encoded
from intake import snapshot

MAX_FILE = 32 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024


def require(ok, message):
    if not ok:
        raise ValueError(message)


def safe_run(value):
    require(bool(re.fullmatch(r'risk-cpu-[a-z0-9-]{1,45}[a-z0-9]', value)), 'invalid pilot run/job ID')
    return value


class GCS:
    """No upload retries: ambiguous create-only writes require manual reconciliation."""
    def __init__(self, bucket):
        require(bool(re.fullmatch(r'[a-z0-9][a-z0-9.-]{2,220}', bucket)), 'invalid bucket')
        self.bucket = bucket
        req = urllib.request.Request(
            'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token',
            headers={'Metadata-Flavor': 'Google'})
        # Bypass user proxy settings for the link-local Google metadata endpoint.
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=10) as response:
            self.token = json.loads(response.read(16384))['access_token']

    def request(self, url, data=None):
        req = urllib.request.Request(url, data=data, headers={
            'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/octet-stream'})
        with urllib.request.urlopen(req, timeout=60) as response:
            raw = response.read(MAX_FILE + 1)
        require(len(raw) <= MAX_FILE, 'GCS response exceeds byte budget')
        return raw

    def get(self, name, generation):
        require(str(generation).isdigit() and int(generation) > 0, 'input generation must be positive')
        return self.request('https://storage.googleapis.com/storage/v1/b/' + self.bucket + '/o/' +
                            urllib.parse.quote(name, safe='') + '?alt=media&generation=' + str(generation))

    def put(self, name, raw):
        require(0 < len(raw) <= MAX_FILE, 'empty/oversized artifact')
        query = urllib.parse.urlencode({'uploadType': 'media', 'name': name, 'ifGenerationMatch': '0'})
        result = json.loads(self.request('https://storage.googleapis.com/upload/storage/v1/b/' +
                                         self.bucket + '/o?' + query, raw))
        require(result.get('name') == name and int(result['size']) == len(raw) and
                int(result['generation']) > 0, 'upload receipt mismatch')
        return {'object': name, 'generation': str(result['generation']), 'size': len(raw),
                'sha256': hashlib.sha256(raw).hexdigest()}


def verify_queue(root, accepted):
    with closing(sqlite3.connect('file:' + str(root / 'jobs.sqlite') + '?mode=ro', uri=True)) as con:
        rows = con.execute('SELECT id,kind,status,idempotency_key,keep,request,result FROM jobs').fetchall()
    require(len(rows) == 1, 'queue must contain exactly one job')
    job, kind, status, key, keep, request, result = rows[0]
    require((job, kind, status, key, keep) == (accepted['job_id'], 'run', 'done', accepted['submission_id'], 1),
            'queue completion/identity mismatch')
    # The queue keeps the HTTP body as received.
    require(request == (root / 'job/request.json').read_bytes(), 'queue request content mismatch')
    require(json.loads(result) == json.loads((root / 'job/result.json').read_bytes()), 'queue result mismatch')


def verify_payload(root, manifest=None):
    root = Path(root)
    accepted = snapshot(root / 'job')
    count = manifest['trade_count'] if manifest is not None else 4
    require(accepted['trade_count'] == count and count > 0 and accepted['warnings'] == [], 'position count/warnings mismatch')
    if manifest is not None:
        require(digest(root / 'job/request.json') == manifest['request_sha256'], 'submitted input mismatch')
        if manifest.get('profile') == 'traderx-synthetic-usd-bill-v1':
            from export_mapping import verify
            mapping = manifest['mapping']
            require(verify(root / 'mapped', mapping['expected']) == mapping, 'mapped source mismatch')
            require(accepted['submission_id'] == mapping['expected']['run_id'], 'result run identity mismatch')
    require(json.loads((root / 'job/receipt.json').read_bytes())['post_attempts'] == 1,
            'exactly one submission required')
    verify_queue(root, accepted)
    return accepted


def connected_record(root, manifest, accepted):
    return {'schema': 'traderx.connected-portfolio-result.v1', 'mapping': manifest['mapping'],
            'verification': accepted, 'build_sha256': digest(root / 'build.json'),
            'financial_validation': False}


def verify_local(root, build_sha256):
    """Trusted package fingerprint selects account/run/cut; artifacts cannot select themselves."""
    root = Path(root)
    require(digest(root / 'build.json') == build_sha256, 'expected build identity mismatch')
    manifest = json.loads((root / 'build.json').read_bytes())
    accepted = verify_payload(root, manifest)
    if manifest.get('profile') == 'traderx-synthetic-usd-bill-v1':
        require(json.loads((root / 'connected-result.json').read_bytes()) ==
                connected_record(root, manifest, accepted), 'connected result identity mismatch')
    return accepted


def validate_completion(receipt, expected):
    for name in ('run_id', 'input', 'image', 'build_sha256', 'engine_revision', 'numerical_revision'):
        require(receipt.get(name) == expected[name], 'completion identity mismatch: ' + name)
    require(receipt.get('version') == 1 and receipt.get('status') == 'VERIFIED' and
            receipt.get('financial_validation') is False, 'invalid completion status/scope')
    require(bool(receipt.get('batch_uid')), 'missing Batch UID')
    prefix = 'runs/' + safe_run(receipt['run_id']) + '/attempts/'
    attempt = receipt.get('attempt_prefix', '')
    require(attempt.startswith(prefix) and bool(re.fullmatch(r'[a-f0-9]{32}/', attempt[len(prefix):])),
            'invalid attempt prefix')
    files = receipt.get('files', [])
    require(1 <= len(files) <= 1010, 'missing/oversized artifact list')
    names, total = set(), 0
    for item in files:
        name = item['path']
        require(name not in names and not Path(name).is_absolute() and '..' not in Path(name).parts and
                item['object'] == attempt + name and not name.startswith('.'), 'invalid artifact path')
        require(re.fullmatch(r'[a-f0-9]{64}', item['sha256']) and str(item['generation']).isdigit() and
                int(item['generation']) > 0 and type(item['size']) is int and 0 < item['size'] <= MAX_FILE,
                'invalid artifact checksum/generation/size')
        names.add(name)
        total += item['size']
    require(total <= MAX_TOTAL, 'artifact budget exceeded')
    require({'build.json', 'runtime.json', 'jobs.sqlite', 'job/request.json', 'job/result.json',
             'job/acceptance.json', 'job/receipt.json'} <= names, 'incomplete artifact list')
    return files


def verify_download(root, receipt, expected):
    files = validate_completion(receipt, expected)
    for item in files:
        path = Path(root) / item['path']
        require(not path.is_symlink() and path.is_file() and path.stat().st_size == item['size'] and
                digest(path) == item['sha256'], 'artifact integrity mismatch: ' + item['path'])
    require(digest(Path(root) / 'build.json') == expected['build_sha256'], 'build fingerprint mismatch')
    require(digest(Path(root) / 'job/request.json') == expected['input']['sha256'], 'input checksum mismatch')
    runtime = json.loads((Path(root) / 'runtime.json').read_bytes())
    require(runtime['batch_uid'] == receipt['batch_uid'] and runtime['image'] == expected['image'],
            'runtime/Batch identity mismatch')
    manifest = json.loads((Path(root) / 'build.json').read_bytes())
    accepted = (verify_local(root, expected['build_sha256']) if 'trade_count' in manifest
                else verify_payload(Path(root)))
    require(receipt.get('verification') == accepted, 'completion verification mismatch')
    return accepted
