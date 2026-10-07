#!/usr/bin/env python3
"""RI-18 local STP build provenance. Timestamps and Git cleanliness are not identity.

The selected context is copied before compilation. Only top-level build/.gradle/.git
are excluded (outputs/local administration); every other file, mode and path counts.
Generation inputs conservatively include whole patchsets and pipeline programs since
those are executable transformations, including patches spanning several components.
Labels are a local builder attestation, not an authenticated remote producer identity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile

LABEL = 'dev.traderx.stp.provenance'
SCHEMA = 'traderx-stp-image-v1'
EXCLUDED = {'build', '.gradle', '.git'}
SHA = re.compile(r'[0-9a-f]{64}\Z')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def files(root, exclude=()):
    """Exact relative names plus bytes and executable bit; no symlink escape."""
    if root.is_symlink():
        raise ValueError(f'symlink is not a bounded build input: {root}')
    result = []
    def walk(folder):
        for path in sorted(folder.iterdir()):
            rel = path.relative_to(root)
            if rel.parts[0] in exclude:
                continue
            if path.is_symlink():
                raise ValueError(f'symlink is not a bounded build input: {path}')
            if path.is_dir():
                result.append([rel.as_posix(), 'directory'])
                walk(path)
            elif path.is_file():
                result.append([rel.as_posix(), 'file', bool(path.stat().st_mode & 0o111),
                               digest(path.read_bytes())])
            else:
                raise ValueError(f'unsupported build input: {path}')
    walk(root)
    return result


def tree_hash(root):
    return digest(canonical(files(root, EXCLUDED)))


def composition(root):
    # Pipeline programs can select layers and apply mixed-component patchsets.
    # Keep those atomic inputs, but exclude component docs, issues, proof runner,
    # generated outputs, website, deployment manifests and unrelated runtime trees.
    sources = [root / 'pipeline', root / 'templates/gradle-wrapper',
               root / 'catalog/dependency-version-targets.json']
    for pack in sorted((root / 'specs').iterdir()):
        sources.extend([pack / 'generation/patches',
                        pack / 'generation/runtime-overrides/order-matcher'])
    entries = []
    for source in sources:
        if not source.exists():
            continue
        if source.is_dir():
            for record in files(source, {'.parent-src', '.gradle', 'build', '__pycache__'}):
                entries.append([source.relative_to(root).as_posix() + '/' + record[0], *record[1:]])
        else:
            entries.append([source.relative_to(root).as_posix(), 'file',
                            bool(source.stat().st_mode & 0o111), digest(source.read_bytes())])
    if not entries:
        raise ValueError('generation composition inputs missing')
    return digest(canonical(sorted(entries)))


def selected_patch(root):
    engine = root / 'generated/code/target-generated/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/MatchingEngine.java'
    # Select from the generated decision being removed, not branch or image tag.
    group_decision = 'if (sameSelfMatchGroup(r.accountId, a.accountId))'
    name = 'stp-boundary-revert-yu18.patch' if group_decision in engine.read_text() else 'stp-boundary-revert.patch'
    return root / 'scripts/yu15' / name


def selected(root):
    context = root / 'generated/code/target-generated/order-matcher'
    for name in ('Dockerfile.cluster', 'build.gradle', 'gradlew'):
        if not (context / name).is_file():
            raise ValueError(f'missing generated build input: {context / name}')
    recipe = []
    for name in ('build-stp-boundary-images.sh', 'stp-image-provenance.py'):
        path = root / 'scripts/yu15' / name
        recipe.append([name, digest(path.read_bytes())])
    patch = selected_patch(root)
    return dict(sourceSha256=tree_hash(context), compositionSha256=composition(root),
                recipeSha256=digest(canonical(recipe)), patchSha256=digest(patch.read_bytes()),
                patchPath=patch.relative_to(root).as_posix())


def copy_context(source, target):
    shutil.copytree(source, target, ignore=lambda folder, names:
                    EXCLUDED.intersection(names) if Path(folder) == source else ())


def transform(root, context):
    patch = selected_patch(root)
    for dry in (True, False):
        args = ['patch', '--batch', '--forward', '--fuzz=0', '--no-backup-if-mismatch', '-p1', '-d', str(context)]
        if dry:
            args.append('--dry-run')
        with patch.open('rb') as stream:
            run(args, stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def prepare(root, work):
    before = selected(root)
    context = root / 'generated/code/target-generated/order-matcher'
    copy_context(context, work / 'fix')
    copy_context(context, work / 'pre')
    transform(root, work / 'pre')
    if before != selected(root) or tree_hash(work / 'fix') != before['sourceSha256']:
        raise ValueError('build inputs changed while preparing the private snapshot')
    revision = run(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                   capture_output=True, text=True).stdout.strip()
    if not re.fullmatch(r'[0-9a-f]{40,64}', revision):
        raise ValueError('source revision unavailable')
    manifest = dict(schema=SCHEMA, selected=before, sourceRevision=revision,
                    roles={role: tree_hash(work / role) for role in ('pre', 'fix')})
    if manifest['roles']['pre'] == manifest['roles']['fix']:
        raise ValueError('pre transformation did not change the source')
    (work / 'prepared.json').write_bytes(canonical(manifest))
    print('[ok] prepared immutable pre/fix input snapshots')


def inspect(image):
    args = ['docker', 'image', 'inspect', image]
    if os.environ.get('YU15_PLATFORM'):
        args += ['--platform', os.environ['YU15_PLATFORM']]
    value = json.loads(run(args, capture_output=True, text=True).stdout)
    if not isinstance(value, list) or len(value) != 1 or not isinstance(value[0], dict):
        raise ValueError(f'malformed docker inspection for {image}')
    value = value[0]
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', value.get('Id', '')):
        raise ValueError(f'malformed immutable image ID for {image}')
    return value


def base_inputs(context):
    bases = []
    stages = set()
    for line in (context / 'Dockerfile.cluster').read_text().splitlines():
        if not re.match(r'^\s*FROM\s', line, re.I):
            continue
        words = line.split()
        if len(words) not in (2, 4) or (len(words) == 4 and words[2].lower() != 'as'):
            raise ValueError('unsupported FROM syntax; provenance requires literal base references')
        ref = words[1]
        if '$' in ref:
            raise ValueError('dynamic FROM is not a verified base input')
        if ref.lower() != 'scratch' and ref.lower() not in stages:
            data = inspect(ref)
            repo_digests = data.get('RepoDigests')
            if not isinstance(repo_digests, list) or not repo_digests:
                raise ValueError(f'base {ref} has no immutable repository digest; pull it explicitly')
            pinned = next((x for x in repo_digests if re.fullmatch(r'.+@sha256:[0-9a-f]{64}', x)), None)
            if not pinned:
                raise ValueError(f'base {ref} has malformed immutable repository digest')
            bases.append(dict(reference=ref, imageId=data['Id'], pinned=pinned))
        if len(words) == 4:
            stages.add(words[3].lower())
    if not re.search(r'^\s*FROM\s', (context / 'Dockerfile.cluster').read_text(), re.M | re.I):
        raise ValueError('Dockerfile has no FROM')
    return bases


def host_toolchain():
    # Java controls host clean bootJar; wrapper/configuration bytes are in the source hash.
    # Refuse ambient Gradle/JVM injection rather than attest an unbounded host program.
    for key in ('GRADLE_OPTS', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'DOCKER_DEFAULT_PLATFORM'):
        if os.environ.get(key):
            raise ValueError(f'{key} is an unbounded build override; unset it for the STP pair')
    if any(key.startswith('ORG_GRADLE_PROJECT_') for key in os.environ):
        raise ValueError('ambient Gradle project properties are an unbounded build override')
    home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    if (home / 'init.gradle').exists() or (home / 'init.gradle.kts').exists() or ((home / 'init.d').exists() and any((home / 'init.d').iterdir())):
        raise ValueError('ambient Gradle init scripts are not verified STP build inputs')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    version = run([java, '-version'], capture_output=True)
    props = home / 'gradle.properties'
    return digest(canonical(dict(javaVersion=digest(version.stdout + version.stderr),
                                 gradleProperties=digest(props.read_bytes()) if props.exists() else None)))


def payload_from_jar(jar):
    entries = []
    with zipfile.ZipFile(jar) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith('/'):
                continue
            if name.startswith('BOOT-INF/classes/'):
                relative = 'classes/' + name[len('BOOT-INF/classes/'):]
            elif name.startswith('BOOT-INF/lib/'):
                relative = 'lib/' + name[len('BOOT-INF/lib/'):]
            else:
                continue
            if '..' in Path(relative).parts or relative.startswith('/'):
                raise ValueError('unsafe jar payload name')
            entries.append([relative, digest(archive.read(name))])
    if not any(p.startswith('classes/') for p, _ in entries) or not any(p.startswith('lib/') for p, _ in entries):
        raise ValueError('boot jar has no class/resource or dependency payload')
    if len({p for p, _ in entries}) != len(entries):
        raise ValueError('duplicate jar payload name')
    return digest(canonical(entries))


def artifact_payload(image):
    # Copy without starting the image or running its entrypoint. Only our container
    # is removed. No shared image, node or retained rig cleanup is performed.
    container = 'ri18-provenance-' + uuid.uuid4().hex
    created = False
    try:
        run(['docker', 'create', '--name', container, '--network', 'none',
             '--entrypoint', '/bin/true', image], capture_output=True)
        created = True
        with tempfile.TemporaryDirectory(prefix='ri18-payload-') as folder:
            root = Path(folder)
            for part in ('classes', 'lib'):
                (root / part).mkdir()
                run(['docker', 'cp', f'{container}:/opt/app/{part}/.', str(root / part)],
                    capture_output=True)
            entries = [[r[0], r[3]] for r in files(root) if r[1] == 'file']
            return digest(canonical(entries))
    finally:
        if created:
            run(['docker', 'rm', container], capture_output=True)


def build(root, work, role, tag):
    prepared = json.loads((work / 'prepared.json').read_text())
    context = work / role
    if prepared['selected'] != selected(root) or prepared['roles'][role] != tree_hash(context):
        raise ValueError('selected inputs or prepared role changed before build')
    toolchain = host_toolchain()
    bases = base_inputs(context)
    print(f'[build] {role}: clean bootJar from private content snapshot', flush=True)
    run(['./gradlew', '--no-daemon', '-q', 'clean', 'bootJar'], cwd=context)
    if prepared['roles'][role] != tree_hash(context) or prepared['selected'] != selected(root):
        raise ValueError('build inputs changed during compilation')
    jars = [p for p in (context / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
    if len(jars) != 1:
        raise ValueError(f'expected exactly one boot jar, found {len(jars)}')
    jar = jars[0]
    provenance = dict(schema=SCHEMA, role=role, **prepared['selected'],
                      roleSha256=prepared['roles'][role], sourceRevision=prepared['sourceRevision'],
                      toolchainSha256=toolchain, bases=bases,
                      jarSha256=digest(jar.read_bytes()), payloadSha256=payload_from_jar(jar),
                      platform=os.environ.get('YU15_PLATFORM', 'native'))
    # Pin every external FROM to the inspected immutable repository digest. Do not
    # change the authoritative Dockerfile; this deterministic transformation is
    # part of the recipe fingerprint. Temporary file lies outside the context.
    dockerfile = (context / 'Dockerfile.cluster').read_text()
    for base in bases:
        dockerfile = re.sub(r'(?im)^(\s*FROM\s+)' + re.escape(base['reference']) + r'(?=\s|$)',
                            lambda m: m[1] + base['pinned'], dockerfile)
    with tempfile.NamedTemporaryFile(prefix='ri18-dockerfile-', mode='w') as file:
        file.write(dockerfile); file.flush()
        args = ['docker', 'build', '-f', file.name, '--build-arg',
                'JAR_FILE=' + jar.relative_to(context).as_posix(), '--label',
                LABEL + '=' + canonical(provenance).decode(), '-t', tag]
        if provenance['platform'] != 'native':
            args += ['--platform', provenance['platform']]
        run(args + [str(context)])
    verify(root, tag, role, prepared=prepared)
    print(f'[ok] {tag}: {role} provenance and packaged payload verified')


def verify(root, image, role, prepared=None):
    data = inspect(image)
    config = data.get('Config')
    if not isinstance(config, dict):
        raise ValueError(f'{image}: malformed image configuration')
    labels = config.get('Labels')
    if not isinstance(labels, dict) or not isinstance(labels.get(LABEL), str):
        raise ValueError(f'{image}: missing {LABEL}; rebuild with build-stp-boundary-images.sh')
    provenance = json.loads(labels[LABEL])
    expected_keys = {'schema', 'role', 'sourceSha256', 'compositionSha256', 'recipeSha256',
                     'patchSha256', 'roleSha256', 'sourceRevision', 'toolchainSha256',
                     'bases', 'jarSha256', 'payloadSha256', 'platform', 'patchPath'}
    if not isinstance(provenance, dict) or set(provenance) != expected_keys or provenance['schema'] != SCHEMA:
        raise ValueError(f'{image}: malformed/unsupported STP provenance')
    for key in expected_keys:
        if key.endswith('Sha256') and not isinstance(provenance[key], str):
            raise ValueError(f'{image}: malformed {key}')
        if key.endswith('Sha256') and not SHA.fullmatch(provenance[key]):
            raise ValueError(f'{image}: malformed {key}')
    if not isinstance(provenance['sourceRevision'], str) or not re.fullmatch(r'[0-9a-f]{40,64}', provenance['sourceRevision']):
        raise ValueError(f'{image}: malformed sourceRevision')
    if provenance['role'] != role:
        raise ValueError(f'{image}: wrong image role (expected {role})')
    if provenance['platform'] != os.environ.get('YU15_PLATFORM', 'native'):
        raise ValueError(f'{image}: platform input changed')
    current = selected(root)
    for key, value in current.items():
        if provenance[key] != value:
            raise ValueError(f'{image}: {key} input changed; rebuild with build-stp-boundary-images.sh')
    if prepared is None:
        with tempfile.TemporaryDirectory(prefix='ri18-admission-') as folder:
            work = Path(folder)
            prepare(root, work)
            prepared = json.loads((work / 'prepared.json').read_text())
    if provenance['roleSha256'] != prepared['roles'][role]:
        raise ValueError(f'{image}: transformed role content mismatch')
    context = root / 'generated/code/target-generated/order-matcher'
    if provenance['bases'] != base_inputs(context):
        raise ValueError(f'{image}: resolved base input changed')
    if provenance['toolchainSha256'] != host_toolchain():
        raise ValueError(f'{image}: host Java toolchain changed')
    if provenance['payloadSha256'] != artifact_payload(data['Id']):
        raise ValueError(f'{image}: packaged classes/resources/dependencies mismatch')
    # Git SHA is recorded history, not admission identity; dirty bytes are hashed.
    if current != selected(root):
        raise ValueError('selected inputs changed during admission')
    print(f"[ok] {image}: {role} content current (image {data['Id']}, source revision {provenance['sourceRevision']})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('fingerprint', 'prepare', 'build', 'verify'))
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--work', type=Path)
    parser.add_argument('--role', choices=('pre', 'fix'))
    parser.add_argument('--image')
    args = parser.parse_args()
    if args.command == 'fingerprint':
        print(canonical(selected(args.root)).decode())
    elif args.command == 'prepare':
        if args.work is None: parser.error('--work required')
        prepare(args.root, args.work)
    else:
        if not args.role or not args.image: parser.error('--role and --image required')
        if args.command == 'build':
            if args.work is None: parser.error('--work required')
            build(args.root, args.work, args.role, args.image)
        else:
            verify(args.root, args.image, args.role)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f'[FAIL] STP image provenance: {error}', file=sys.stderr)
        sys.exit(1)
