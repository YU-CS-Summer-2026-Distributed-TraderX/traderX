"""Explicit Batch startup source, hash-bound by the submitted job (not the image)."""
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def require(ok, message):
    if not ok:
        raise ValueError(message)


def validate_identity(identity, expected):
    require(set(identity) == {'contract', 'job_name', 'batch_uid'}, 'unexpected identity fields')
    require(identity['contract'] == expected, 'job identity contract mismatch')
    names = ['projects/' + p + '/locations/' + expected['region'] + '/jobs/' + expected['run_id']
             for p in (expected['project_id'], expected['project_number'])]
    require(identity['job_name'] in names, 'job name/project/location mismatch')
    require(isinstance(identity['batch_uid'], str) and
            re.fullmatch(r'[A-Za-z0-9._:-]{8,200}', identity['batch_uid']), 'invalid actual Batch UID')
    return identity['batch_uid']


def load_identity(expected, token, get=None, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + 180
    name = 'control/' + expected['run_id'] + '/job-identity.json'
    bucket = expected['input']['bucket']
    url = 'https://storage.googleapis.com/storage/v1/b/' + bucket + '/o/' + urllib.parse.quote(name, safe='')
    def request(target):
        remaining = deadline - clock()
        require(remaining > 0, 'job identity handoff timed out')
        req = urllib.request.Request(target, headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(req, timeout=min(10, remaining)) as response:
            raw = response.read(16385)
        require(len(raw) <= 16384, 'oversized identity response')
        return json.loads(raw)
    get = get or request
    while True:
        require(clock() < deadline, 'job identity handoff timed out')
        try:
            meta = get(url)
            break
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise
            sleep(min(2, max(0, deadline-clock())))
    generation = str(meta.get('generation', ''))
    require(meta.get('bucket') == bucket and meta.get('name') == name and generation.isdigit() and
            int(generation) > 0 and 0 < int(meta['size']) <= 16384, 'invalid identity object metadata')
    identity = get(url + '?alt=media&generation=' + generation)
    validate_identity(identity, expected)
    return {'identity': identity, 'object': name, 'generation': generation}


def main():
    from batch_artifacts import GCS
    from verify_installed import verify
    expected = json.loads(os.environ['PILOT_HANDOFF_CONTRACT'])
    source = sys.orig_argv[sys.orig_argv.index('-c') + 1]
    require(hashlib.sha256(source.encode()).hexdigest() == expected['bootstrap_sha256'], 'startup source mismatch')
    manifest = verify(Path('/opt/pilot/build.json'))
    require(hashlib.sha256(Path('/opt/pilot/build.json').read_bytes()).hexdigest() == expected['build_sha256'],
            'installed build mismatch')
    require(manifest['request_sha256'] == expected['input']['sha256'] and
            manifest['engine_revision'] == expected['engine_revision'] and
            manifest['numerical_revision'] == expected['numerical_revision'], 'installed input/engine mismatch')
    require(os.environ.get('BATCH_TASK_INDEX') == '0', 'one task required')
    required_env = {'PILOT_RUN_ID': expected['run_id'], 'PILOT_IMAGE': expected['image'],
                    'PILOT_BUCKET': expected['input']['bucket'], 'PILOT_INPUT_GENERATION': expected['input']['generation']}
    require(all(os.environ.get(k) == v for k,v in required_env.items()), 'runtime environment mismatch')
    record = load_identity(expected, GCS(expected['input']['bucket']).token)
    os.environ['BATCH_JOB_UID'] = record['identity']['batch_uid']
    import batch_cpu
    original = batch_cpu.run_local
    def with_identity(raw, root, manifest, runtime):
        try:
            return original(raw, root, manifest, runtime)
        finally:
            if root.is_dir():
                with (root / 'operator-job-identity.json').open('x') as f:
                    json.dump(record, f, sort_keys=True)
    batch_cpu.run_local = with_identity
    sys.argv = ['batch_cpu.py']
    return batch_cpu.main()


if __name__ == '__main__':
    raise SystemExit(main())
