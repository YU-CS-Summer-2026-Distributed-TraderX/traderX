"""Explicit offline admission and read-only verification of a retained Batch result."""
import argparse
import json
import os
import shutil
import tempfile
from pathlib import Path

from batch_artifacts import require, safe_run
from batch_prepare import read, write
from batch_handoff import check, verify_result
from corpus import digest, encoded

SCHEMA = 'traderx.batch-result.v1'


def inventory(root):
    require(root.is_dir() and not root.is_symlink(), 'invalid evidence root')
    files = {}
    total = 0
    for p in sorted(root.rglob('*')):
        require(not p.is_symlink(), 'symlinks are not retained evidence')
        require(p.is_dir() or p.is_file(), 'nonregular evidence')
        if p.is_file():
            total += p.stat().st_size
            require(total <= 256 * 1024 * 1024, 'evidence byte budget exceeded')
            require(p.stat().st_size <= 32 * 1024 * 1024, 'oversized evidence file')
            if p.relative_to(root).as_posix() != 'admission.json':
                files[p.relative_to(root).as_posix()] = digest(p)
    require(0 < len(files) <= 3000, 'invalid evidence inventory')
    return files


def verified_view(root):
    work, evidence = root / 'build', root / 'download'
    completion = read(evidence / 'completion.json')
    accepted = verify_result(work, evidence / 'artifacts', completion, read(evidence / 'job.json'))
    connected = read(evidence / 'artifacts/connected-result.json')
    mapping = connected['mapping']
    require(mapping['synthetic'] is True and mapping['financial_validation'] is False
            and mapping['profile'] == 'traderx-synthetic-usd-bill-v1', 'unsupported imported profile')
    identity = read(work / 'job-identity.json')
    require(read(evidence / 'control.json') == identity, 'downloaded control mismatch')
    require(str(read(evidence / 'control-object.json')['generation']) ==
            str(read(work / 'job-identity-object.json')['generation']), 'control generation mismatch')
    bucket = identity['contract']['input']['bucket']
    meta = read(evidence / 'completion-object.json')
    require(meta['bucket'] == bucket and meta['name'] == 'runs/' + mapping['expected']['run_id'] + '/completion.json'
            and str(meta['generation']).isdigit() and int(meta['generation']) > 0
            and int(meta['size']) == (evidence / 'completion.json').stat().st_size, 'completion object mismatch')
    metadata = read(evidence / 'artifact-objects.json')
    require(len(metadata) == len(completion['files']), 'artifact object inventory mismatch')
    for item, meta in zip(completion['files'], metadata):
        require(meta['bucket'] == bucket and meta['name'] == item['object']
                and str(meta['generation']) == str(item['generation']) and int(meta['size']) == item['size'],
                'artifact object generation mismatch')
    return {'schema': SCHEMA, 'availability': 'AVAILABLE', 'status': 'VERIFIED',
            'usableForRisk': False, 'synthetic': True, 'financialValidation': False,
            'scenarioRiskAvailable': False, 'accountId': mapping['expected']['account_id'],
            'runId': mapping['expected']['run_id'], 'bundleId': mapping['expected']['bundle_id'],
            'cut': mapping['cut'], 'clusterEpoch': mapping['cluster_epoch'],
            'valuationTime': mapping['valuation_time'], 'market': mapping['market'],
            'batchUid': identity['batch_uid'], 'image': identity['contract']['image'],
            'engineJobId': accepted['job_id'], 'tradeCount': accepted['trade_count'],
            'baseNpvUsd': accepted['base_npv_usd'], 'positions': mapping['positions'],
            'currentPortfolioComparison': 'NOT_CHECKED'}


def status(root, pin):
    require(len(pin) == 64 and digest(root / 'admission.json') == pin, 'admission pin mismatch')
    admission = read(root / 'admission.json')
    require(inventory(root) == admission['files'], 'retained evidence changed')
    result = verified_view(root)
    require(result == admission['result'], 'admitted result changed')
    return result


def ingest(work, evidence, store):
    require(not any((p / '.git').exists() for p in (store.resolve(), *store.resolve().parents)),
            'store must be outside Git')
    contract, _ = check(work)
    # Refuse before creating any visible selected result.
    verify_result(work, evidence / 'artifacts', read(evidence / 'completion.json'), read(evidence / 'job.json'))
    package = Path(read(work / 'archive.json')['package_directory'])
    if not package.is_absolute():
        package = work.parent / 'package'
    for source in (package, work, evidence):
        inventory(source)  # refuse symlinks before copytree follows them
    store.mkdir(parents=True, exist_ok=True, mode=0o700)
    target = store / safe_run(contract['run_id'])
    stage = Path(tempfile.mkdtemp(prefix='.import-', dir=store))
    try:
        for source, name in ((package, 'package'), (work, 'build'), (evidence, 'download')):
            shutil.copytree(source, stage / name)
        # Keep original locators as provenance; only local path fields are relocated.
        for name in ('archive.json', 'build-expected.json'):
            p = stage / 'build' / name
            write(stage / 'original-locators' / name, p.read_bytes())
            data = read(p)
            data['package_directory'] = '../package'
            p.write_bytes(encoded(data) + b'\n')
        result = verified_view(stage)
        admission = {'schema': 'traderx.batch-admission.v1', 'files': inventory(stage), 'result': result}
        write(stage / 'admission.json', encoded(admission) + b'\n')
        pin = digest(stage / 'admission.json')
        status(stage, pin)
        if target.exists():
            require(digest(target / 'admission.json') == pin, 'conflicting import for this run')
            status(target, pin)
            disposition = 'ALREADY_IMPORTED'
        else:
            # Publish a complete directory only; never overwrite an existing nonempty run.
            for p in stage.rglob('*'):
                if p.is_file():
                    with p.open('rb') as f:
                        os.fsync(f.fileno())
            try:
                stage.rename(target)
            except OSError:
                require(target.is_dir() and digest(target / 'admission.json') == pin, 'concurrent conflicting import')
                status(target, pin)
            fd = os.open(store, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
            disposition = 'IMPORTED'
        return {'status': disposition, 'directory': str(target.resolve()), 'sha256': pin, 'result': result}
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    a = sub.add_parser('import')
    for name in ('work', 'evidence', 'store'):
        a.add_argument('--' + name, type=Path, required=True)
    a = sub.add_parser('status')
    a.add_argument('--directory', type=Path, required=True)
    a.add_argument('--sha256', required=True)
    a = p.parse_args()
    result = ingest(a.work, a.evidence, a.store) if a.command == 'import' else status(a.directory, a.sha256)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
