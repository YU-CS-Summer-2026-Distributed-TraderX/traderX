#!/usr/bin/env python3
"""Derive selected build contexts independently, before compilation/image effects.

No receipt/cache supplied by a caller is trusted. Full generation runs in an owned
source snapshot; the checkout's generated tree and source documents are untouched.
"""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

OUTPUT_DIRS = {'.git', 'generated', 'node_modules', '.gradle', 'build', '__pycache__', '.parent-src', '.run'}


def provenance(root):
    spec = importlib.util.spec_from_file_location('derivation_hash_contract', root / 'scripts/yu15/stp-image-provenance.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def inputs(root):
    """Conservative current source inventory, including dirty/new/deleted files.
    Exclusions are local administration, generated roots and build/cache outputs.
    They cannot be generation inputs: the reference starts without those folders.
    """
    result = []
    def walk(folder):
        for path in sorted(folder.iterdir()):
            if path.name in OUTPUT_DIRS:
                continue
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                raise ValueError(f'unbounded symlink generation input: {relative}')
            if path.is_dir():
                result.append([relative, 'directory']); walk(path)
            elif path.is_file():
                result.append([relative, 'file', bool(path.stat().st_mode & 0o111), hashlib.sha256(path.read_bytes()).hexdigest()])
            else:
                raise ValueError(f'unsupported generation input: {relative}')
    walk(root)
    return result


def source_hash(records):
    return hashlib.sha256(json.dumps(records, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def copy_inputs(root, target, records):
    target.mkdir()
    for record in records:
        source = root / record[0]; dest = target / record[0]
        if record[1] == 'directory':
            dest.mkdir(parents=True, exist_ok=True)
        else:
            dest.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(source, dest)
    if inputs(target) != records:
        raise ValueError('source changed while capturing private generation inputs')


def selected_state(root, state):
    if state == 'YU18-eod-risk-bundles':
        state = 'YU18-risk-integration'
    states = {x['id']: x for x in json.loads((root / 'catalog/state-catalog.json').read_text())['states']}
    if state not in states:
        raise ValueError(f'unknown explicit build state: {state}')
    ancestry = []
    active = set()
    def visit(value):
        if value in active or value not in states:
            raise ValueError('invalid/cyclic build-state ancestry')
        if value in ancestry:
            return
        active.add(value)
        for parent in states[value].get('previous', []):
            visit(parent)
        active.remove(value); ancestry.append(value)
    visit(state)
    return state, ancestry


def default_state(root):
    # Keep the existing build entrypoint default; explicit BUILD_STATE can select
    # an inherited state. The resulting bytes are still independently derived.
    packs = [p.name for p in (root / 'specs').glob('YU*') if p.is_dir()]
    if not packs:
        raise ValueError('no build state found; select BUILD_STATE explicitly')
    return max(packs, key=lambda x: int(x.split('-', 1)[0][2:]))


@contextmanager
def validated(root, components, *, state=None, candidate=None, role='fix'):
    """Yield immutable private build contexts only after current derivation parity.
    candidate/role support order-matcher's intentional STP transform. No arbitrary
    custom transform or supplied prepared.json can waive independent generation.
    """
    root = root.resolve()
    p = provenance(root)
    state, ancestry = selected_state(root, state or os.environ.get('BUILD_STATE') or default_state(root))
    if not components or len(set(components)) != len(components):
        raise ValueError('nonempty unique component selection required')
    import re
    if any(not re.fullmatch(r'[a-z0-9][a-z0-9-]*', component) for component in components):
        raise ValueError('invalid selected component')
    if role not in ('fix', 'pre') or (candidate is not None and components != ['order-matcher']) or (role == 'pre' and components != ['order-matcher']):
        raise ValueError('custom context/transform is supported only for explicit order-matcher fix/pre')
    actual = {name: Path(candidate).resolve() if candidate is not None else root / 'generated/code/target-generated' / name for name in components}
    for name, path in actual.items():
        if not path.is_dir():
            raise ValueError(f'missing generated build context {name}: generate the full selected state first')
    hashes = {name: p.tree_hash(path) for name, path in actual.items()}
    before = inputs(root)
    # origin is a generation input for UI lineage links, not a fetch source.
    # Preserve it in independent Git administration instead of silently emitting
    # empty links. Never copy credential-bearing URLs into build metadata.
    origin_result = subprocess.run(['git','-C',str(root),'config','--get','remote.origin.url'],
                                   capture_output=True,text=True,check=False)
    origin = origin_result.stdout.strip() if origin_result.returncode == 0 else ''
    from urllib.parse import urlsplit
    if '://' in origin and (urlsplit(origin).username or urlsplit(origin).password):
        raise ValueError('credential-bearing origin URL cannot be a build metadata input')
    with tempfile.TemporaryDirectory(prefix='traderx-derivation-') as temp:
        base = Path(temp).resolve(); reference = base / 'source'
        copy_inputs(root, reference, before)
        # Preserve the patch pipeline's repository-relative scope, without sharing
        # any Git administration/index or relying on a clean HEAD's older bytes.
        subprocess.run(['git', 'init', '-q', str(reference)], check=True, capture_output=True, stdin=subprocess.DEVNULL)
        # Generation records Git revision metadata. Copy only the selected HEAD
        # objects locally into independent Git administration; never share an
        # index/object alternate, reset the caller or replace dirty source bytes.
        subprocess.run(['git', '-C', str(reference), 'fetch', '--quiet', '--no-tags', '--depth=1', str(root), 'HEAD'], check=True, capture_output=True, stdin=subprocess.DEVNULL)
        subprocess.run(['git', '-C', str(reference), 'reset', '--soft', 'FETCH_HEAD'], check=True, capture_output=True, stdin=subprocess.DEVNULL)
        subprocess.run(['git', '-C', str(reference), 'read-tree', 'HEAD'], check=True, capture_output=True, stdin=subprocess.DEVNULL)
        if origin:
            subprocess.run(['git','-C',str(reference),'config','remote.origin.url',origin],
                           check=True,capture_output=True,stdin=subprocess.DEVNULL)
        env = {k:v for k,v in os.environ.items() if not k.startswith('TRADERX_') and k not in ('BUILD_STATE', 'OM_DIR', 'OM_ROLE')}
        env.update(TRADERX_GENERATED_ROOT=str(reference / 'generated'), TRADERX_SKIP_LOCKFILE_REFRESH='1',
                   npm_config_offline='true', NPM_CONFIG_OFFLINE='true')
        log = base / 'generation.log'
        with log.open('w') as stream:
            result = subprocess.run(['bash', str(reference / 'pipeline/generate-state.sh'), state], cwd=reference,
                                    env=env, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            # Only a bounded diagnostic tail; full log remains available while the
            # context is owned. No successful build may follow a generation failure.
            raise ValueError('independent full generation failed:\n' + '\n'.join(log.read_text().splitlines()[-16:]))
        contexts = base / 'contexts'; contexts.mkdir()
        expected = {}
        for name in components:
            fresh = reference / 'generated/code/target-generated' / name
            if not fresh.is_dir():
                raise ValueError(f'{name} absent from independently generated state {state}')
            target = contexts / name
            p.copy_context(fresh, target)
            if not any(record[1] == 'file' for record in p.files(target, p.EXCLUDED)):
                raise ValueError(f'{name}: independent component has no build inputs')
            if role == 'pre':
                p.transform(reference, target)
            expected[name] = p.tree_hash(target)
            if hashes[name] != expected[name] or p.tree_hash(actual[name]) != hashes[name]:
                raise ValueError(f'{name}: generated build context does not derive from current full ancestry ({state}, role={role}); regenerate the selected state/validated transform before rebuilding')
        if inputs(root) != before:
            raise ValueError('current source changed during derivation admission')
        receipt = dict(schema='traderx-generation-derivation-v1', state=state, ancestry=ancestry,
                       sourceInputsSha256=source_hash(before), sourceInputFiles=sum(x[1]=='file' for x in before),
                       role=role, contexts=expected, generationLogSha256=hashlib.sha256(log.read_bytes()).hexdigest())
        receipt['generationMetadata'] = {'origin':origin,'sourceDateEpoch':os.environ.get('SOURCE_DATE_EPOCH')}
        print('[ok] current full-ancestry derivation: ' + json.dumps(receipt, sort_keys=True), flush=True)
        yield contexts, receipt, log


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('check', 'execute'))
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--state')
    parser.add_argument('--component', action='append', required=True)
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--role', choices=('fix','pre'), default='fix')
    parser.add_argument('--evidence', type=Path)
    argv = sys.argv[1:]; command = []
    if '--' in argv:
        split = argv.index('--'); argv, command = argv[:split], argv[split+1:]
    args = parser.parse_args(argv)
    if args.action == 'execute' and not command:
        parser.error('execute requires a command after --')
    with validated(args.root, args.component, state=args.state, candidate=args.candidate, role=args.role) as (contexts, receipt, log):
        if args.evidence:
            args.evidence.mkdir(parents=True, exist_ok=True)
            (args.evidence / 'derivation.json').write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
            shutil.copy2(log, args.evidence / 'generation.log')
        if args.action == 'execute':
            env = dict(os.environ, DERIVED_BUILD_ROOT=str(contexts))
            result = subprocess.run(command, env=env)
            if result.returncode:
                raise subprocess.CalledProcessError(result.returncode, command)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f'[FAIL] generation derivation: {error}', file=sys.stderr)
        sys.exit(1)
