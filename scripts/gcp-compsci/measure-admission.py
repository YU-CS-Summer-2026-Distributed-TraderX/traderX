#!/usr/bin/env python3
"""Measure, package and admit the exact CompSci image population a rendered candidate pins.

The scripted form of the October 11 hand measurements (coordination/.../image-admission-final-20261011
/measurements/*.py), so every rollout can rerun it against the digests it actually deployed:

  1. preflight   HEAD is admit.REVIEWED_IMAGE_COMMIT; the generated tree is rendered at it;
                 SOURCE_DATE_EPOCH is that commit's time (generated UI metadata is a context input).
  2. population  the 12 private + 9 upstream references are read FROM THE CANDIDATE, never typed in;
                 optionally (--live-context) every running traderx container must run one of them.
  3. derivation  generation-derivation.validated(): independent full-ancestry contexts + receipt.
  4. images      each private image is pulled by digest; Id, linux/amd64 and the labels
                 dev.traderx.source.commit / dev.traderx.derivation.sha256 are read off the image.
  5. build       --build-records (a build-images.py output folder) when the build host is still around;
                 otherwise the records are reconstructed from the image labels + the fresh contexts and
                 marked so. The payload comparison below is what binds the images to the source.
  6. payloads    the packaged payload of each image vs an INDEPENDENT reference build of the fresh
                 context (Gradle bootJar, the image recipe's build stage, or the whole node recipe).
  7. OCI         registry manifests/configs for private and upstream images, bound to the local pull.
  8. runtime     each image is run with --network none and must report amd64/x64.
  9. admit       prepare-admission.py packages it, admit.py admits it, admission-controls.py proves
                 the refusals still fire against this real population.

Needs: docker with buildx, a registry login for the private repository, JDK 21 on PATH (or JAVA_HOME),
PyYAML. Writes only under --work. Exit status: 0 admitted, 1 refused or failed (see summary.json).
"""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
REGISTRY = 'us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/'
STATE = 'YU18-risk-integration'
# component (generated context) -> private image name, as build-images.py maps them
COMPONENTS = {'order-matcher': 'cluster-node', 'trade-processor': 'trade-processor',
              'position-service': 'position-service', 'execution-algo-engine': 'execution-algo-engine',
              'trade-service': 'trade-service', 'account-service': 'account-service',
              'people-service': 'people-service', 'price-publisher': 'price-publisher',
              'reference-data': 'reference-data', 'web-front-end': 'web-front-end-angular',
              'api-explorer': 'api-explorer'}
JAVA = ['cluster-node', 'trade-processor', 'position-service', 'execution-algo-engine', 'trade-service',
        'account-service']
NODE = ['price-publisher', 'reference-data', 'web-front-end-angular', 'web-front-end-console']


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


admit = module('measure_admit', HERE / 'admit.py')
derive = admit.derive


def run(args, **kw):
    return subprocess.run(args, check=True, **kw)


def out(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw).stdout


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(folder):
    rows = {}
    for f in sorted(Path(folder).rglob('*')):
        if f.is_symlink():
            rows[f.relative_to(folder).as_posix()] = ['symlink', str(f.readlink())]
        elif f.is_file():
            rows[f.relative_to(folder).as_posix()] = ['file', sha(f.read_bytes())]
    if not rows:
        raise SystemExit(f'empty payload tree: {folder}')
    return rows


def zip_entries(path):
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith('/')]
        if len(names) != len(set(names)):
            raise SystemExit(f'duplicate zip entries in {path}')
        return {n: ['file', sha(z.read(n))] for n in names}


def preflight(root):
    head = out(['git', '-C', str(root), 'rev-parse', 'HEAD']).strip()
    if head != admit.REVIEWED_IMAGE_COMMIT:
        raise SystemExit(f'HEAD is {head}, not the reviewed image commit {admit.REVIEWED_IMAGE_COMMIT}. The '
                         'generated contexts record HEAD, so measure on a checkout of exactly that commit '
                         '(these scripts may be uncommitted there).')
    epoch = out(['git', '-C', str(root), 'show', '-s', '--format=%ct', 'HEAD']).strip()
    if os.environ.get('SOURCE_DATE_EPOCH') != epoch:
        raise SystemExit(f'export SOURCE_DATE_EPOCH={epoch} (the reviewed commit time) and render with it')
    if not (root / 'generated/code/target-generated/order-matcher').is_dir():
        raise SystemExit('render first: TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh ' + STATE)


def population(rendered):
    private, upstream = {}, set()
    for doc in yaml.safe_load_all(Path(rendered).read_text()):
        if not doc or doc.get('kind') not in ('Deployment', 'StatefulSet', 'DaemonSet', 'Job'):
            continue
        spec = doc['spec']['template']['spec']
        for c in spec.get('containers', []) + spec.get('initContainers', []):
            ref = c['image']
            if '@sha256:' not in ref:
                raise SystemExit(f'unpinned image in candidate: {ref}')
            if ref.startswith(REGISTRY):
                name = ref[len(REGISTRY):].split('@')[0]
                if private.setdefault(name, ref) != ref:
                    raise SystemExit(f'two digests for {name} in one candidate')
            else:
                upstream.add(ref)
    names = set(COMPONENTS.values()) | {'web-front-end-console'}
    if set(private) != names or len(upstream) != admit.UPSTREAM_IMAGES:
        raise SystemExit(f'candidate population differs: private {sorted(private)}, upstream {len(upstream)}')
    return private, sorted(upstream)


def live_check(context, private, upstream):
    """Every running traderx container runs a candidate digest (the desk is rendered separately)."""
    pods = json.loads(out(['kubectl', '--context', context, '-n', 'traderx', 'get', 'pods', '-o', 'json']))
    admitted = {r.split('@')[1] for r in list(private.values()) + upstream}
    seen, stray = 0, []
    for p in pods['items']:
        for st in p['status'].get('containerStatuses', []) + p['status'].get('initContainerStatuses', []):
            seen += 1
            digest = st['imageID'].split('@', 1)[1]
            if digest not in admitted and 'web-front-end-desk' not in st['image']:
                stray.append((p['metadata']['name'], st['name'], digest))
    if stray:
        raise SystemExit(f'live traderx runs images outside the candidate: {stray}')
    return seen


def pull(private):
    images = {}
    def one(item):
        name, ref = item
        run(['docker', 'pull', '-q', '--platform', 'linux/amd64', ref], capture_output=True)
        i = json.loads(out(['docker', 'image', 'inspect', ref]))[0]
        if (i['Os'], i['Architecture']) != ('linux', 'amd64') or ref not in i['RepoDigests']:
            raise SystemExit(f'{name}: not the linux/amd64 image at {ref}')
        labels = i['Config'].get('Labels') or {}
        return name, {'reference': ref, 'imageId': i['Id'], 'inspection': i,
                      'sourceCommit': labels.get('dev.traderx.source.commit'),
                      'sourceInputsSha256': labels.get('dev.traderx.derivation.sha256')}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        for name, row in ex.map(one, private.items()):
            images[name] = row
    commits = {r['sourceCommit'] for r in images.values()}; inputs = {r['sourceInputsSha256'] for r in images.values()}
    if commits != {admit.REVIEWED_IMAGE_COMMIT} or len(inputs) != 1 or not admit.ri20.sha(next(iter(inputs))):
        raise SystemExit(f'images do not carry one reviewed build identity: commits {commits}, inputs {inputs}')
    return images


def extract(work, images):
    target = work / 'payloads'; target.mkdir()
    def one(item):
        name, row = item
        (target / (name + '-inspection.json')).write_text(json.dumps(row['inspection'], indent=2))
        src = ('/opt/app' if name == 'cluster-node' else '/opt/app/app.jar' if name in JAVA else
               '/usr/share/nginx/html/api/docs' if name == 'api-explorer' else '/app')
        cid = out(['docker', 'create', '--platform', 'linux/amd64', row['reference']]).strip()
        try:
            run(['docker', 'cp', cid + ':' + src, str(target / name)], capture_output=True)
        finally:
            run(['docker', 'rm', cid], capture_output=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        list(ex.map(one, images.items()))


def reference_builds(work, logs):
    contexts = work / 'contexts'; ref = work / 'reference'; ref.mkdir()
    def gradle(component):
        ctx = contexts / component
        with (logs / (component + '-compile.log')).open('w') as s:
            r = subprocess.run(['./gradlew', '--no-daemon', '--max-workers=2', '-q', 'clean', 'bootJar'], cwd=ctx,
                               stdout=s, stderr=subprocess.STDOUT)
        if r.returncode:
            raise SystemExit(f'{component}: reference compilation failed, see {logs}')
        jars = [x for x in (ctx / 'build/libs').glob('*.jar') if not x.name.endswith('-plain.jar')]
        if len(jars) != 1:
            raise SystemExit(f'{component}: expected one boot jar, got {jars}')
        return component, jars[0]
    def docker_payload(name, context, dockerfile, src, stage=None):
        tag = 'traderx-measure-' + name + ':reference'
        cmd = ['docker', 'build', '--platform', 'linux/amd64', '--no-cache', '-f', str(context / dockerfile), '-t', tag]
        if stage:
            cmd += ['--target', stage]
        with (logs / (name + '-reference-build.log')).open('w') as s:
            r = subprocess.run(cmd + [str(context)], stdout=s, stderr=subprocess.STDOUT,
                               env=dict(os.environ, DOCKER_BUILDKIT='1'))
        if r.returncode:
            raise SystemExit(f'{name}: reference build failed, see {logs}')
        cid = out(['docker', 'create', '--platform', 'linux/amd64', tag]).strip()
        try:
            run(['docker', 'cp', cid + ':' + src, str(ref / name)], capture_output=True)
        finally:
            run(['docker', 'rm', cid], capture_output=True)
        return name
    jars = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
        java = [ex.submit(gradle, c) for c in ['order-matcher', 'trade-processor', 'position-service',
                                                'execution-algo-engine', 'trade-service', 'account-service']]
        other = [ex.submit(docker_payload, 'people-service', contexts / 'people-service', 'Dockerfile.compose',
                           '/app/publish', 'build'),
                 ex.submit(docker_payload, 'web-front-end-console', contexts / 'web-front-end-console', 'Dockerfile',
                           '/src/dist', 'build')]
        for name, relative in [('price-publisher', 'price-publisher'), ('reference-data', 'reference-data'),
                               ('web-front-end-angular', 'web-front-end/angular')]:
            ctx = contexts / relative
            other.append(ex.submit(docker_payload, name, ctx,
                                   'Dockerfile' if (ctx / 'Dockerfile').exists() else 'Dockerfile.compose', '/app'))
        for f in java:
            component, jar = f.result(); jars[component] = jar
        for f in other:
            f.result()
    return jars


def compare_payloads(work, jars):
    measured = work / 'measurements'; payloads = work / 'payloads'; ref = work / 'reference'; summary = []
    def compare(name, expected, actual):
        missing = sorted(set(expected) - set(actual)); extra = sorted(set(actual) - set(expected))
        different = sorted(x for x in set(expected) & set(actual) if expected[x] != actual[x])
        report = {'name': name, 'expectedFiles': len(expected), 'actualFiles': len(actual), 'missing': missing,
                  'extra': extra, 'different': different, 'expectedEntries': expected, 'actualEntries': actual,
                  'passed': not (missing or extra or different)}
        (measured / (name + '-payload-comparison.json')).write_text(json.dumps(report, indent=2))
        summary.append({k: (v[:20] if isinstance(v, list) else v) for k, v in report.items()
                        if k not in ('expectedEntries', 'actualEntries')})
    for component, name in COMPONENTS.items():
        if name not in JAVA:
            continue
        expected = zip_entries(jars[component])
        if name == 'cluster-node':   # Dockerfile.cluster unpacks BOOT-INF/{classes,lib} to /opt/app/{classes,lib}
            expected = {('classes/' + k[len('BOOT-INF/classes/'):] if k.startswith('BOOT-INF/classes/')
                         else 'lib/' + k[len('BOOT-INF/lib/'):]): v
                        for k, v in expected.items() if k.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/'))}
            compare(name, expected, tree(payloads / name))
        else:
            compare(name, expected, zip_entries(payloads / name))
    compare('people-service', tree(ref / 'people-service'), tree(payloads / 'people-service'))
    console = work / 'contexts/web-front-end-console'
    recipe = (console / 'Dockerfile').read_text().splitlines()
    expected = {'dist/' + k: v for k, v in tree(ref / 'web-front-end-console').items()}
    for f in console.glob('*.mjs'):   # exactly the runtime COPY lines, not tests or build helpers
        if any(line.startswith('COPY ') and f.name in line.split() for line in recipe):
            expected[f.name] = ['file', sha(f.read_bytes())]
    compare('web-front-end-console', expected, tree(payloads / 'web-front-end-console'))
    compare('api-explorer', tree(work / 'contexts/api-explorer'), tree(payloads / 'api-explorer'))
    for name in ['price-publisher', 'reference-data', 'web-front-end-angular']:
        compare(name, tree(ref / name), tree(payloads / name))
    (measured / 'payload-summary.json').write_text(json.dumps(summary, indent=2))
    return summary


def oci(work, images, upstream):
    measured = work / 'measurements'; (measured / 'private-oci').mkdir(); (measured / 'upstream').mkdir()
    def inspect_raw(ref):
        return subprocess.run(['docker', 'buildx', 'imagetools', 'inspect', ref, '--raw'], check=True,
                              capture_output=True).stdout
    def config_of(ref):
        return json.loads(out(['docker', 'buildx', 'imagetools', 'inspect', ref, '--format', '{{json .Image}}']))
    def private(item):
        name, row = item; ref = row['reference']; raw = inspect_raw(ref)
        if sha(raw) != ref.split('@sha256:')[1]:
            raise SystemExit(f'{name}: registry manifest does not hash to its pinned digest')
        manifest = json.loads(raw); childref = ref
        if 'manifests' in manifest:
            d = [m for m in manifest['manifests'] if m.get('platform') == {'architecture': 'amd64', 'os': 'linux'}]
            if len(d) != 1:
                raise SystemExit(f'{name}: ambiguous amd64 descriptor')
            childref = ref.split('@')[0] + '@' + d[0]['digest']; child = inspect_raw(childref)
            (measured / 'private-oci' / (name + '-platform-manifest.json')).write_bytes(child)
        config = config_of(childref)
        local = row['inspection']
        if (config['architecture'], config['os']) != ('amd64', 'linux') or \
                config['rootfs']['diff_ids'] != local['RootFS']['Layers'] or \
                config['config'].get('Labels') != local['Config'].get('Labels'):
            raise SystemExit(f'{name}: registry config differs from the pulled image')
        (measured / 'private-oci' / (name + '-manifest.json')).write_bytes(raw)
        (measured / 'private-oci' / (name + '-config.json')).write_text(json.dumps(config, indent=2))
    def public(ref):
        raw = inspect_raw(ref)
        if sha(raw) != ref.split('@sha256:')[1]:
            raise SystemExit(f'{ref}: manifest does not hash to its pinned digest')
        manifest = json.loads(raw); image = config_of(ref)
        if (image['architecture'], image['os']) != ('amd64', 'linux') or not manifest.get('layers') or \
                len(image['rootfs']['diff_ids']) != len(manifest['layers']):
            raise SystemExit(f'{ref}: not a complete linux/amd64 image')
        return ref, raw, image
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        list(ex.map(private, images.items()))
        return list(ex.map(public, upstream))


def runtime(work, images):
    records = []
    def run_in(ref, entrypoint, *args):
        r = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--memory', '512m', '--platform',
                            'linux/amd64', '--entrypoint', entrypoint, ref, *args], capture_output=True, text=True,
                           timeout=120)
        return (r.stdout + r.stderr), ['docker', 'run', '--network', 'none', '--entrypoint', entrypoint, *args]
    for name, row in images.items():
        ref = row['reference']; record = {'image': name, 'imageId': row['imageId']}
        if name in JAVA:
            output, cmd = run_in(ref, 'java', '-XshowSettings:properties', '-version')
            record.update(output=output, runtimeCommand=cmd)
        elif name == 'people-service':
            output, cmd = run_in(ref, 'dotnet', '--info'); record.update(output=output, runtimeCommand=cmd)
        elif name in NODE:
            output, cmd = run_in(ref, 'node', '-p', 'process.arch'); record.update(output=output, runtimeCommand=cmd)
        elif name == 'api-explorer':   # nginx has no arch query: read the ELF header of the binary itself
            cid = out(['docker', 'create', '--platform', 'linux/amd64', ref]).strip()
            binary = work / 'api-explorer-nginx.elf'
            try:
                run(['docker', 'cp', '-L', cid + ':/usr/sbin/nginx', str(binary)], capture_output=True)
            finally:
                run(['docker', 'rm', cid], capture_output=True)
            head = binary.read_bytes()[:20]
            elf64 = head[:4] == b'\x7fELF' and head[4] == 2; machine = struct.unpack('<H', head[18:20])[0]
            record.update(actualExecutable='ELF64 x86-64' if elf64 and machine == 0x3E
                          else f'ELF class {head[4]} machine {machine:#x}', elfSha256=sha(binary.read_bytes()))
        if not admit.executable_architecture(record):
            raise SystemExit(f'{name}: no amd64/x64 proof from its own executable: {str(record)[:300]}')
        record['verified'] = 'actual executable architecture amd64/x64; application behavior not tested'
        records.append(record)
    console = next(r for r in records if r['image'] == 'web-front-end-console')
    (work / 'console-native-proof.json').write_text(json.dumps(console, indent=2))
    (work / 'runtime-architecture.json').write_text(json.dumps([r for r in records if r is not console], indent=2))
    (work / 'remaining-runtime-architecture.json').write_text('[]\n')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--root', type=Path, default=ROOT)
    p.add_argument('--rendered', type=Path, required=True, help='the candidate.yaml prepare.py rendered and applied')
    p.add_argument('--public-lock', type=Path, default=HERE / 'public-image-lock.json')
    p.add_argument('--work', type=Path, required=True, help='new folder; everything is written here')
    p.add_argument('--build-records', type=Path, help='build-images.py --output folder of the deployed build')
    p.add_argument('--live-context', help='kubectl context whose traderx namespace must run exactly these digests')
    a = p.parse_args()
    root = a.root.resolve(); work = a.work.resolve()
    preflight(root)
    work.mkdir(parents=True, exist_ok=False); logs = work / 'logs'; logs.mkdir(); (work / 'measurements').mkdir()
    shutil.copy2(a.rendered, work / 'candidate.yaml')
    private, upstream = population(a.rendered)
    lock = json.loads(a.public_lock.read_text())
    if sorted(x['reference'] for x in lock.values()) != upstream:
        raise SystemExit("the public lock does not pin exactly the candidate's upstream images")
    summary = {'reviewedImageCommit': admit.REVIEWED_IMAGE_COMMIT, 'candidateSha256': sha(Path(a.rendered).read_bytes())}
    if a.live_context:
        summary['liveContainersChecked'] = live_check(a.live_context, private, upstream)
    print('[1/7] pulling', len(private), 'private images by digest', flush=True)
    images = pull(private)
    print('[2/7] independent full-ancestry derivation', flush=True)
    prov = derive.provenance(root)
    with derive.validated(root, list(COMPONENTS), state=STATE) as (contexts, receipt, log):
        shutil.copytree(contexts, work / 'contexts', symlinks=True); shutil.copy2(log, logs / 'generation.log')
    prov.copy_context(root / 'web-front-end-console', work / 'contexts/web-front-end-console')
    console_hash = prov.tree_hash(root / 'web-front-end-console')
    (work / 'derivation.json').write_text(json.dumps(receipt, indent=2) + '\n')
    build = work / 'build'
    if a.build_records:
        shutil.copytree(a.build_records, build)
        summary['buildRecords'] = 'build-images.py output of the deployed build'
    else:
        build.mkdir()
        inputs = next(iter(images.values()))['sourceInputsSha256']
        records = {'schema': 'traderx-compsci-local-images-v1', 'platform': 'linux/amd64',
                   'sourceCommit': admit.REVIEWED_IMAGE_COMMIT, 'published': True, 'images': {},
                   'reconstructedFrom': 'image labels (source commit, derivation input hash) + this fresh derivation; '
                                        'the build host is gone. The payload comparison binds image to source.'}
        for component, name in list(COMPONENTS.items()) + [('web-front-end-console', 'web-front-end-console')]:
            records['images'][name] = {'reference': images[name]['reference'], 'imageId': images[name]['imageId'],
                                       'component': component, 'sourceCommit': admit.REVIEWED_IMAGE_COMMIT,
                                       'sourceInputsSha256': inputs,
                                       'contextSha256': console_hash if name == 'web-front-end-console'
                                       else receipt['contexts'][component]}
        (build / 'images.json').write_text(json.dumps(records, indent=2) + '\n')
        (build / 'derivation.json').write_text(json.dumps(dict(receipt, sourceInputsSha256=inputs,
                                                               reconstructed=True), indent=2) + '\n')
        (build / 'console-source.json').write_text(json.dumps({'sha256': console_hash,
                                                              'sourceCommit': admit.REVIEWED_IMAGE_COMMIT}) + '\n')
        summary['buildRecords'] = 'reconstructed from image labels + fresh derivation'
    original = json.loads((build / 'images.json').read_text())
    published = {'sourceCommit': admit.REVIEWED_IMAGE_COMMIT, 'images': {
        name: {'reference': row['reference'], 'imageId': row['imageId'],
               'sourceContextSha256': original['images'][name]['contextSha256'],
               'sourceCommit': row['sourceCommit'], 'sourceInputsSha256': row['sourceInputsSha256']}
        for name, row in images.items()}}
    (work / 'published.json').write_text(json.dumps(published, indent=2) + '\n')
    print('[3/7] extracting packaged payloads', flush=True)
    extract(work, images)
    print('[4/7] independent reference builds', flush=True)
    jars = reference_builds(work, logs)
    print('[5/7] payload comparison', flush=True)
    payloads = compare_payloads(work, jars)
    summary['payloads'] = payloads
    print('[6/7] OCI evidence and runtime architecture', flush=True)
    for ref, raw, image in oci(work, images, upstream):
        tag = next(t for t, x in lock.items() if x['reference'] == ref); name = tag.split(':')[0].replace('/', '-')
        (work / 'measurements/upstream' / (name + '-manifest.json')).write_bytes(raw)
        (work / 'measurements/upstream' / (name + '-config.json')).write_text(json.dumps(image, indent=2))
    runtime(work, images)
    print('[7/7] package, admit, refusal controls', flush=True)
    prep = module('measure_prepare', HERE / 'prepare-admission.py')
    package = work / 'package'
    verdict = {}
    try:
        prep.package(work / 'measurements', work / 'published.json', a.public_lock, build, work / 'derivation.json',
                     work / 'candidate.yaml', package)
        target = []
        for key, value in admit.TARGET.items():
            target += ['--' + key, value]
        for name, cmd in [('admit', [sys.executable, str(HERE / 'admit.py'), '--root', str(root),
                                     '--rendered', str(package / 'candidate.yaml'), '--expected',
                                     str(package / 'expected.json'), '--artifacts', str(package / 'artifacts.json'),
                                     '--derivation', str(package / 'derivation.json'), '--output',
                                     str(work / 'admitted')] + target),
                          ('controls', [sys.executable, str(HERE / 'admission-controls.py'), '--root', str(root),
                                        '--evidence', str(package)])]:
            r = subprocess.run(cmd, capture_output=True, text=True)
            (logs / (name + '.log')).write_text(r.stdout + r.stderr)
            verdict[name] = {'exit': r.returncode, 'tail': (r.stdout + r.stderr).strip().splitlines()[-3:]}
    except BaseException as error:   # a refusal is the finding: keep everything measured so far
        verdict['package'] = {'refused': repr(error)}
    summary['verdict'] = verdict
    (work / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({k: v for k, v in summary.items() if k != 'payloads'}, indent=2))
    for row in summary['payloads']:
        print(f"  payload {row['name']:24} passed={row['passed']} files={row['expectedFiles']}/{row['actualFiles']} "
              f"missing={row['missing'][:3]} extra={row['extra'][:3]} different={row['different'][:3]}")
    ok = verdict.get('admit', {}).get('exit') == 0 and verdict.get('controls', {}).get('exit') == 0
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
