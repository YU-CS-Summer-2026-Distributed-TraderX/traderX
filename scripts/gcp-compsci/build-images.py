#!/usr/bin/env python3
"""Build fresh amd64 CompSci candidates after full-ancestry context admission.

Local builds only. No registry push, cluster mutation, or risk-engine upgrade.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = 'us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo'
COMPONENTS = ['order-matcher', 'trade-processor', 'position-service',
              'execution-algo-engine', 'trade-service', 'account-service',
              'people-service', 'price-publisher', 'reference-data', 'web-front-end', 'api-explorer']


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def build(output, tag, exception=None):
    output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('derive', ROOT / 'scripts/yu15/generation-derivation.py')
    derive = importlib.util.module_from_spec(spec); spec.loader.exec_module(derive)
    inventory = {'schema': 'traderx-compsci-local-images-v1', 'platform': 'linux/amd64',
                 'sourceCommit': run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'],
                                     capture_output=True, text=True).stdout.strip(),
                 'published': False, 'images': {}}
    # ADR-096 §6: --exception COMPONENT=IMAGE builds that one component, at this checkout's commit, as its own
    # repository (e.g. order-matcher=cluster-node-member on the 00b31d33 backport), for a declared workload exception.
    only, rename = exception if exception else (None, None)
    with derive.validated(ROOT, [only] if only else COMPONENTS, state='YU18-risk-integration') as (contexts, receipt, log):
        (output / 'derivation.json').write_text(json.dumps(receipt, indent=2) + '\n')
        shutil.copy2(log, output / 'generation.log')
        # The console is a root-owned component outside the generated-state tree.
        # Snapshot all its build inputs and compare content before/after the build.
        console = contexts / 'web-front-end-console'
        original = ROOT / 'web-front-end-console'
        derive.provenance(ROOT).copy_context(original, console)
        console_hash = derive.provenance(ROOT).tree_hash(original)
        (output / 'console-source.json').write_text(json.dumps({'sha256': console_hash,
            'path': 'web-front-end-console', 'sourceCommit': inventory['sourceCommit']}, indent=2) + '\n')
        for component in [only] if only else COMPONENTS + ['web-front-end-console']:
            name = rename or {'order-matcher':'cluster-node', 'web-front-end':'web-front-end-angular'}.get(component,component)
            context = contexts / component
            if component == 'web-front-end': context = context / 'angular'
            print(f'[build] {component}', flush=True)
            if (context / 'gradlew').exists():
                run(['./gradlew', '--no-daemon', '--max-workers=2', '-q', 'clean', 'bootJar'], cwd=context)
            dockerfile = ('Dockerfile.cluster' if component == 'order-matcher' else
                          'Dockerfile' if (context / 'Dockerfile').exists() else 'Dockerfile.compose')
            reference = f'{REGISTRY}/{name}:{tag}'
            args = ['docker', 'build', '--platform', 'linux/amd64',
                    '--label', f'dev.traderx.source.commit={inventory["sourceCommit"]}',
                    '--label', f'dev.traderx.derivation.sha256={receipt["sourceInputsSha256"]}',
                    '-f', str(context / dockerfile), '-t', reference]
            if component == 'order-matcher':
                jars = [p for p in (context / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
                if len(jars) != 1:
                    raise ValueError('expected exactly one fresh engine boot jar')
                args += ['--build-arg', f'JAR_FILE={jars[0].relative_to(context)}']
            run(args + [str(context)])
            inspection = json.loads(run(['docker', 'image', 'inspect', reference], capture_output=True, text=True).stdout)[0]
            if inspection['Os'] != 'linux' or inspection['Architecture'] != 'amd64':
                raise ValueError(f'{name}: incorrect image architecture')
            inventory['images'][name] = {'reference': reference, 'imageId': inspection['Id'],
                'component': component, 'inspection': inspection,
                'sourceCommit': inventory['sourceCommit'], 'sourceInputsSha256': receipt['sourceInputsSha256'],
                'contextSha256': console_hash if component == 'web-front-end-console' else receipt['contexts'][component]}
            (output / 'images.json').write_text(json.dumps(inventory, indent=2) + '\n')
            print(f'[ok] {name}: {inspection["Id"]}', flush=True)
        if derive.provenance(ROOT).tree_hash(original) != console_hash:
            raise ValueError('console source changed during build')
        if derive.source_hash(derive.inputs(ROOT)) != receipt['sourceInputsSha256']:
            raise ValueError('source changed during build')
    print(f'[ok] {len(inventory["images"])} local amd64 images; not published', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--exception', help='COMPONENT=IMAGE: build only that component, as that image (ADR-096 §6)')
    args = parser.parse_args()
    import re
    if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}', args.tag):
        parser.error('invalid Docker tag')
    exception = None
    if args.exception:
        component, _, image = args.exception.partition('=')
        if component not in COMPONENTS or not re.fullmatch(r'[a-z][a-z0-9-]{0,62}', image):
            parser.error('--exception COMPONENT=IMAGE')
        exception = (component, image)
    build(args.output.resolve(), args.tag, exception)
