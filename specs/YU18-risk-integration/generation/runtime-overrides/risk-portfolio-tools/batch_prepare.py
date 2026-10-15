"""Prepare immutable CPU image inputs and verify a local installed result.

No cloud commands run here. Cloud job generation and build-receipt admission
require a separate qualified handoff; historical pilot scripts are not exposed.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from batch_artifacts import require, safe_run, verify_local
from corpus import digest, encoded
from profiles import CURRENT_ENGINE

PROJECT = 'project-compsci-484414'
ACCOUNT = 'aseif@mail.yu.edu'
REGION = 'us-east1'
BUCKET = PROJECT + '-risk-engine-pilot'
IMAGE_ROOT = REGION + '-docker.pkg.dev/' + PROJECT + '/risk-engine/cpu'
BASE_SERVICE = '270a907a313bc0184ddcc7cda2e50904d103fce3'
ROOT = Path(__file__).resolve().parents[5]
TOOLS = 'specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools'


def git(root, *args, raw=False):
    data = subprocess.check_output(['git', '-C', str(root), *args])
    return data if raw else data.decode().strip()


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(data)


def read(path):
    return json.loads(Path(path).read_bytes())


def clean(root):
    require(not git(root, 'status', '--porcelain'), 'prepare requires clean committed checkout: ' + str(root))
    return git(root, 'rev-parse', 'HEAD')


def prepare(engine, output, run, upstream, mapped=None):
    safe_run(run)
    require(upstream == CURRENT_ENGINE, 'Alex upstream changed; reconcile and rerun proofs first')
    revision, traderx = clean(engine), clean(ROOT)
    require(revision == BASE_SERVICE, 'exact accepted owned service revision required')
    allowed = {'engine/api/' + n for n in ('job_queue.py', 'routes.py', 'results.py', 'worker.py', 'service_identity.py')}
    changed = set(git(engine, 'diff', '--name-only', CURRENT_ENGINE, revision, '--', 'engine').splitlines())
    require(changed <= allowed, 'numerical source changed outside accepted API integration')
    output = output.resolve()
    require(not any((p / '.git').exists() for p in (output, *output.parents)), 'package must be outside Git')
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    context = output / 'context'
    manifest = {'version': 1, 'numerical_revision': CURRENT_ENGINE, 'engine_revision': revision,
                'service_base_revision': BASE_SERVICE, 'traderx_revision': traderx,
                'engine_files': {}, 'engine_source_files': {}, 'consumer_files': {}, 'dependencies': {}}
    paths = git(engine, 'ls-tree', '-r', '--name-only', revision, '--',
                'engine', 'pyproject.toml', 'constraints.txt').splitlines()
    for name in paths:
        data = git(engine, 'show', revision + ':' + name, raw=True)
        write(context / 'engine-source' / name, data)
        manifest['engine_source_files'][name] = hashlib.sha256(data).hexdigest()
        if name.startswith('engine/') and name.endswith(('.py', '.json')):
            manifest['engine_files'][name] = hashlib.sha256(data).hexdigest()
    for path in git(ROOT, 'ls-tree', '-r', '--name-only', traderx, '--', TOOLS + '/packaging').splitlines():
        write(context / 'platform' / Path(path).name, git(ROOT, 'show', traderx + ':' + path, raw=True))
    lock = (context / 'platform/requirements-linux-amd64.lock').read_text()
    manifest['dependencies'] = dict(re.findall(r'^([A-Za-z0-9_.-]+)==([^\s]+)', lock, re.M))
    require(len(manifest['dependencies']) == 31, 'unexpected CPU dependency lock')
    constraints = dict(re.findall(r'^([A-Za-z0-9_.-]+)==([^\s]+)$',
                                (context / 'engine-source/constraints.txt').read_text(), re.M))
    require(all(constraints.get(k, v) == v for k, v in manifest['dependencies'].items()), 'dependency constraint drift')
    for path in git(ROOT, 'ls-tree', '-r', '--name-only', traderx, '--', TOOLS).splitlines():
        name = Path(path).relative_to(TOOLS).as_posix()
        if name in {'client.py', 'corpus.py', 'intake.py', 'profiles.py', 'reference.py', 'request_identity.py',
                    'batch_cpu.py', 'batch_artifacts.py', 'export_mapping.py', 'fixtures/usd-treasury-current.json'}:
            data = git(ROOT, 'show', traderx + ':' + path, raw=True)
            write(context / 'consumer' / name, data)
            manifest['consumer_files'][name] = hashlib.sha256(data).hexdigest()
    require({'batch_cpu.py', 'batch_artifacts.py'} <= manifest['consumer_files'].keys(), 'missing Batch runtime')
    for name in ('bundle.py', 'instrument_terms.py'):
        data = git(ROOT, 'show', traderx + ':' + TOOLS.rsplit('/', 1)[0] + '/eod-risk-bundles/' + name, raw=True)
        write(context / 'consumer/eod_contract' / name, data)
        manifest['consumer_files']['eod_contract/' + name] = hashlib.sha256(data).hexdigest()
    manifest['profile'] = 'frozen-golden-v1'
    request = (context / 'consumer/fixtures/usd-treasury-current.json').read_bytes()
    if mapped is not None:
        from export_mapping import verify, PROFILE
        expected = read(mapped / 'mapping.json')['expected']
        require(expected['run_id'] == run, 'mapped run ID differs from package run ID')
        receipt = verify(mapped, expected)
        manifest.update(profile=PROFILE, mapping=receipt)
        for path in sorted(mapped.rglob('*')):
            if path.is_file():
                name = 'mapped/' + path.relative_to(mapped).as_posix()
                data = path.read_bytes()
                write(context / 'consumer' / name, data)
                manifest['consumer_files'][name] = hashlib.sha256(data).hexdigest()
        request = (mapped / 'request.json').read_bytes()
    manifest['request_sha256'] = hashlib.sha256(request).hexdigest()
    require(len(json.loads(request)['trades']) > 0, 'nonempty request required')
    manifest['trade_count'] = len(json.loads(request)['trades'])
    manifest['platform_files'] = {p.name: digest(p) for p in (context / 'platform').iterdir()}
    write(output / 'request.json', request)
    write(context / 'build.json', encoded(manifest) + b'\n')
    write(context / 'Dockerfile', (context / 'platform/Dockerfile').read_bytes())
    write(context / '.dockerignore', b'.git\n**/__pycache__\n')
    write(context / '.gcloudignore', b'.git\n**/__pycache__\n')
    image = IMAGE_ROOT + ':' + run
    config = {'steps': [
        {'name': 'gcr.io/cloud-builders/docker@sha256:f772ab459889c67835bd959c2b9e9351f2c0752c4f989710ecda20c229f0733e', 'args': ['build', '--platform=linux/amd64', '-t', image, '.']},
        {'name': 'gcr.io/cloud-builders/docker@sha256:f772ab459889c67835bd959c2b9e9351f2c0752c4f989710ecda20c229f0733e', 'args': ['run', '--rm', '--network=none', '--cpus=2',
          '--memory=6g', '--pids-limit=256', '-e', 'PILOT_RUN_ID=' + run, image, '--local-input', '/opt/pilot/mapped/request.json' if mapped is not None else '/opt/pilot/fixtures/usd-treasury-current.json']}],
        'images': [image], 'timeout': '1800s',
        'serviceAccount': 'projects/' + PROJECT + '/serviceAccounts/risk-image-builder@' + PROJECT + '.iam.gserviceaccount.com',
        'options': {'logging': 'CLOUD_LOGGING_ONLY', 'machineType': 'E2_STANDARD_2', 'diskSizeGb': '30'}}
    write(context / 'cloudbuild.json', encoded(config) + b'\n')
    package = {'version': 1, 'run_id': run, 'project': PROJECT, 'region': REGION, 'bucket': BUCKET,
               'image_tag': image, 'input_sha256': manifest['request_sha256'],
               'build_sha256': digest(context / 'build.json'),
               'engine_revision': revision, 'numerical_revision': CURRENT_ENGINE, 'traderx_revision': traderx,
               'context_files': {p.relative_to(context).as_posix(): digest(p) for p in context.rglob('*') if p.is_file()}}
    write(output / 'package.json', encoded(package) + b'\n')
    return package


def verify_package(directory):
    package = read(directory / 'package.json')
    require(package['project'] == PROJECT and package['bucket'] == BUCKET and
            package['region'] == REGION, 'unknown pilot target')
    safe_run(package['run_id'])
    for name, sha in package['context_files'].items():
        require(not Path(name).is_absolute() and '..' not in Path(name).parts, 'invalid context path')
        require(not (directory / 'context' / name).is_symlink() and
                digest(directory / 'context' / name) == sha, 'context changed after preparation: ' + name)
    actual = {p.relative_to(directory / 'context').as_posix() for p in (directory / 'context').rglob('*') if p.is_file()}
    require(actual == set(package['context_files']), 'extra/missing build context files')
    require(digest(directory / 'request.json') == package['input_sha256'], 'input changed')
    require(digest(directory / 'context/build.json') == package['build_sha256'], 'build manifest changed')
    return package


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    p = subs.add_parser('prepare')
    p.add_argument('--engine-root', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--run-id', required=True)
    p.add_argument('--upstream-sha', required=True)
    p.add_argument('--mapped-input', type=Path)
    p = subs.add_parser('check')
    p.add_argument('--package', type=Path, required=True)
    p = subs.add_parser('local-verify')
    p.add_argument('--package', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'prepare':
        result = prepare(args.engine_root.resolve(), args.output, args.run_id, args.upstream_sha, args.mapped_input)
    elif args.command == 'check':
        result = verify_package(args.package)
    elif args.command == 'local-verify':
        package = verify_package(args.package)
        result = verify_local(args.evidence, package['build_sha256'])
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
