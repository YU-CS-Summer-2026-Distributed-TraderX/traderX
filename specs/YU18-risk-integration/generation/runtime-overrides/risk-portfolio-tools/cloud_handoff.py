"""Pinned cloud source/build receipts. Only explicit submit-build performs a POST.

All other commands are offline. Unknown submission outcomes retain their fence;
never retry a POST automatically. Batch identity handoff is a separate gate.
"""
import argparse
import base64
import copy
import gzip
import hashlib
import io
import json
import os
import re
import subprocess
import tarfile
import urllib.request
from pathlib import Path

from batch_prepare import ACCOUNT, PROJECT, REGION, IMAGE_ROOT, read, write, verify_package
from batch_artifacts import require
from corpus import digest, encoded

SOURCE_BUCKET = PROJECT + '-risk-engine-build-source'


def frozen_archive(package):
    manifest = verify_package(package)
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode='wb', mtime=0, filename='') as gz:
        with tarfile.open(fileobj=gz, mode='w', format=tarfile.USTAR_FORMAT) as tar:
            for name in sorted(manifest['context_files']):
                raw = (package / 'context' / name).read_bytes()
                entry = tarfile.TarInfo(name)
                entry.size, entry.mode, entry.mtime = len(raw), 0o644, 0
                tar.addfile(entry, io.BytesIO(raw))
    return output.getvalue()


def archive(package, work):
    require(not any((p / '.git').exists() for p in (work.resolve(), *work.resolve().parents)), 'cloud evidence outside Git required')
    manifest = verify_package(package)
    work.mkdir(mode=0o700, parents=True, exist_ok=False)
    raw = frozen_archive(package)
    write(work / 'source.tar.gz', raw)
    expected = {'package_directory': str(package.resolve()), 'package_sha256': digest(package / 'package.json'),
                'archive_sha256': hashlib.sha256(raw).hexdigest(), 'source_bucket': SOURCE_BUCKET,
                'source_object': 'mapped/' + manifest['run_id'] + '/source.tar.gz'}
    write(work / 'archive.json', encoded(expected) + b'\n')
    return expected


def checked(work):
    expected = read(work / 'archive.json')
    package = Path(expected['package_directory'])
    if not package.is_absolute():
        require(expected['package_directory'] == '../package', 'invalid relative package locator')
        package = work.parent / 'package'
    require(digest(package / 'package.json') == expected['package_sha256'], 'trusted package changed')
    manifest = verify_package(package)
    require(expected['source_bucket'] == SOURCE_BUCKET and
            expected['source_object'] == 'mapped/' + manifest['run_id'] + '/source.tar.gz', 'source target mismatch')
    raw = frozen_archive(package)
    require(raw == (work / 'source.tar.gz').read_bytes() and
            hashlib.sha256(raw).hexdigest() == expected['archive_sha256'], 'source archive differs from package')
    return expected, package, manifest


def pin_source(work, metadata, downloaded):
    expected, package, manifest = checked(work)
    meta = read(metadata)
    uri = 'gs://' + expected['source_bucket'] + '/' + expected['source_object']
    require((meta.get('bucket') == expected['source_bucket'] and meta.get('name') == expected['source_object']) or
            meta.get('storage_url') == uri, 'wrong uploaded source object')
    generation = str(meta.get('generation', ''))
    require(generation.isdigit() and int(generation) > 0 and
            int(meta['size']) == (work / 'source.tar.gz').stat().st_size, 'invalid source generation/size')
    require(digest(downloaded) == expected['archive_sha256'], 'uploaded source bytes mismatch')
    config = read(package / 'context/cloudbuild.json')
    config['source'] = {'storageSource': {'bucket': expected['source_bucket'],
                        'object': expected['source_object'], 'generation': generation}}
    config['options']['sourceProvenanceHash'] = ['SHA256']
    config['tags'] = [manifest['run_id']]
    config['queueTtl'] = '3600s'
    # Specify timeouts so API defaults cannot hide a material difference.
    config['steps'][0]['timeout'] = '1200s'
    config['steps'][1]['timeout'] = '600s'
    write(work / 'build-request.json', encoded(config) + b'\n')
    write(work / 'build-expected.json', encoded(dict(expected, source=config['source'],
          config=config, build_sha256=manifest['build_sha256'])) + b'\n')
    return config


def check_request(work):
    expected, package, manifest = checked(work)
    receipt = read(work / 'build-expected.json')
    for key, value in expected.items():
        require(receipt.get(key) == value, 'build expectation mismatch: ' + key)
    config = read(package / 'context/cloudbuild.json')
    source = receipt['source']
    pin = source['storageSource']
    require(set(source) == {'storageSource'} and set(pin) == {'bucket', 'object', 'generation'} and
            pin['bucket'] == expected['source_bucket'] and pin['object'] == expected['source_object'] and
            str(pin['generation']).isdigit() and int(pin['generation']) > 0, 'source pin mismatch')
    config.update(source=source, tags=[manifest['run_id']], queueTtl='3600s')
    config['options']['sourceProvenanceHash'] = ['SHA256']
    for step, timeout in zip(config['steps'], ('1200s', '600s')):
        step['timeout'] = timeout
    require(receipt['config'] == config and read(work / 'build-request.json') == config and
            receipt['build_sha256'] == manifest['build_sha256'], 'build request differs from trusted package')
    return receipt


def submit_build(work):
    expected = check_request(work)
    # Obtain credentials before the durable submission intent; never print tokens.
    token = subprocess.check_output(['gcloud', '--account=' + ACCOUNT, 'auth', 'print-access-token'], text=True).strip()
    write(work / 'build-submit-intent.json', encoded({'request_sha256': digest(work / 'build-request.json')}) + b'\n')
    with (work / 'build-submit-intent.json').open('rb') as fence:
        os.fsync(fence.fileno())
    directory_fd = os.open(work, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
    request = urllib.request.Request('https://cloudbuild.googleapis.com/v1/projects/' + PROJECT +
          '/locations/' + REGION + '/builds?projectId=' + PROJECT, data=encoded(expected['config']),
          headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        raw = response.read(4 * 1024 * 1024 + 1)
    require(len(raw) <= 4 * 1024 * 1024, 'oversized build operation')
    write(work / 'build-operation.json', raw)
    operation = json.loads(raw)
    build = operation.get('metadata', {}).get('build', {})
    require(bool(re.fullmatch(r'[a-f0-9-]{36}', build.get('id', ''))),
            'missing build ID; reconcile saved operation, do not resubmit')
    write(work / 'build-id.txt', (build['id'] + '\n').encode())
    return {'build_id': build['id'], 'operation': operation['name']}


def normalized_config(actual):
    """Remove only observed output fields and explicit inert server defaults."""
    result = copy.deepcopy(actual)
    outputs = {'id', 'name', 'projectId', 'status', 'statusDetail', 'createTime', 'startTime',
               'finishTime', 'results', 'sourceProvenance', 'logUrl', 'timing'}
    for key in outputs:
        result.pop(key, None)
    artifacts = result.pop('artifacts', None)
    require(artifacts in (None, {'images': result.get('images')}), 'unexpected build artifacts')
    options = result.get('options', {})
    if options.get('pool') == {}:
        options.pop('pool')
    if options.get('workerRelease') == 'legacy':
        options.pop('workerRelease')
    for step in result.get('steps', []):
        require(step.get('status') == 'SUCCESS', 'every build step must succeed')
        for key in ('status', 'timing', 'pullTiming'):
            step.pop(key, None)
        if step.get('exitCode') == 0:
            step.pop('exitCode')
    return result


def admit_build(work, actual, build_id, registry_digest):
    expected = check_request(work)
    require(actual.get('id') == build_id and actual.get('projectId') == PROJECT and
            actual.get('status') == 'SUCCESS', 'wrong/unsuccessful build')
    require(actual.get('name') in ['projects/' + project + '/locations/' + REGION + '/builds/' + build_id
                                  for project in (PROJECT, '918355398369')],
            'wrong build location/name')
    require(normalized_config(actual) == expected['config'], 'actual build configuration differs from expected')
    provenance = actual['sourceProvenance']
    pin = expected['source']['storageSource']
    require(provenance.get('resolvedStorageSource') == pin, 'resolved source generation differs')
    key = 'gs://' + pin['bucket'] + '/' + pin['object'] + '#' + str(pin['generation'])
    hashes = provenance.get('fileHashes', {}).get(key, {}).get('fileHash', [])
    sha = [x['value'] for x in hashes if x.get('type') == 'SHA256']
    require(len(sha) == 1 and base64.urlsafe_b64decode(sha[0]).hex() == expected['archive_sha256'],
            'source provenance SHA256 mismatch')
    images = actual.get('results', {}).get('images', [])
    require(len(images) == 1 and images[0].get('name') == expected['config']['images'][0] and
            bool(re.fullmatch(r'sha256:[0-9a-f]{64}', images[0].get('digest', ''))), 'unexpected built image')
    require(images[0]['digest'] == registry_digest, 'registry/build digest mismatch')
    return {'status': 'BUILD_RECEIPT_VERIFIED', 'build_id': build_id,
            'image_uri': IMAGE_ROOT + '@' + images[0]['digest'],
            'build_sha256': expected['build_sha256'], 'source': pin,
            'archive_sha256': expected['archive_sha256'], 'package_sha256': expected['package_sha256']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    a = sub.add_parser('archive'); a.add_argument('--package', type=Path, required=True)
    a.add_argument('--work', type=Path, required=True)
    a = sub.add_parser('pin-source'); a.add_argument('--work', type=Path, required=True)
    a.add_argument('--metadata', type=Path, required=True); a.add_argument('--downloaded', type=Path, required=True)
    a = sub.add_parser('submit-build'); a.add_argument('--work', type=Path, required=True)
    a = sub.add_parser('admit-build'); a.add_argument('--work', type=Path, required=True)
    a.add_argument('--metadata', type=Path, required=True)
    a.add_argument('--registry-digest', required=True)
    args = p.parse_args()
    if args.command == 'archive': result = archive(args.package, args.work)
    elif args.command == 'pin-source': result = pin_source(args.work, args.metadata, args.downloaded)
    elif args.command == 'submit-build': result = submit_build(args.work)
    else:
        result = admit_build(args.work, read(args.metadata), (args.work / 'build-id.txt').read_text().strip(), args.registry_digest)
        write(args.work / 'image-receipt.json', encoded(result) + b'\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
