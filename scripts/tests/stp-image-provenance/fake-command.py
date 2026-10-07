#!/usr/bin/env python3
"""Offline process fixture; no image, Gradle compilation or cluster proof implied."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import zipfile

name = Path(sys.argv[0]).name
args = sys.argv[1:]
store = Path(os.environ['RI18_FIXTURE_STORE'])
with (store / 'trace.jsonl').open('a') as trace:
    trace.write(json.dumps([name, *args]) + '\n')


def load():
    return json.loads((store / 'images.json').read_text()) if (store / 'images.json').exists() else {}


def image(ref):
    images = load()
    if ref in images:
        return images[ref]
    return next(v for v in images.values() if v['Id'] == ref)


if name == 'git':
    print('a0d6da0bfbef481f301bb1172b6810cb8a4c9e3e')
elif name == 'java':
    print('offline fixture Java 21', file=sys.stderr)
elif name == 'kubectl':
    joined = ' '.join(args)
    if 'price-publisher' in joined:
        sys.exit(1)
    if 'jsonpath' in joined:
        if '.name}' in joined:
            print('gateway')
        elif '.image}' in joined:
            print('ri18:pre')
        else:
            print('{"startupProbe":{},"readinessProbe":{},"livenessProbe":{}}')
    else:
        sys.exit(70)
elif name == 'kind':
    sys.exit(88)  # admission completed; stop before all behavioral/rig activity
elif name == 'gradlew':
    root = Path.cwd()
    shutil.rmtree(root / 'build', ignore_errors=True)
    (root / 'build/libs').mkdir(parents=True)
    def write_payload(jar, name, data):
        jar.writestr(zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0)), data)
        rel = name.replace('BOOT-INF/', '', 1)
        path = root / 'build/payload' / rel
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    with zipfile.ZipFile(root / 'build/libs/fixture.jar', 'w') as jar:
        for source in (root / 'src/main/java').rglob('*.java'):
            rel = source.relative_to(root / 'src/main/java').with_suffix('.class').as_posix()
            write_payload(jar, 'BOOT-INF/classes/' + rel, source.read_bytes())
        for source in (root / 'src/main/resources').rglob('*'):
            if source.is_file():
                write_payload(jar, 'BOOT-INF/classes/' + source.relative_to(root / 'src/main/resources').as_posix(), source.read_bytes())
        write_payload(jar, 'BOOT-INF/lib/fixture-dependency.jar', b'synthetic dependency bytes')
elif name == 'docker':
    if args[:2] == ['image', 'inspect']:
        try:
            data = image(args[2])
        except (KeyError, StopIteration):
            sys.exit(1)
        data = {k: v for k, v in data.items() if k != 'payload'}
        mutation = os.environ.get('RI18_INSPECT_MUTATION')
        if mutation == 'missing':
            data['Config']['Labels'] = {}
        elif mutation == 'malformed':
            data['Config']['Labels']['dev.traderx.stp.provenance'] = '{'
        elif mutation == 'wrong-role':
            key = 'dev.traderx.stp.provenance'
            value = json.loads(data['Config']['Labels'][key]); value['role'] = 'fix'
            data['Config']['Labels'][key] = json.dumps(value)
        print(json.dumps([data]))
    elif args[0] == 'build':
        tag = args[args.index('-t') + 1]
        label = args[args.index('--label') + 1]
        key, value = label.split('=', 1)
        jar_arg = args[args.index('--build-arg') + 1].split('=', 1)[1]
        payload = {}
        with zipfile.ZipFile(Path(args[-1]) / jar_arg) as jar:
            for path in jar.namelist():
                for prefix, part in [('BOOT-INF/classes/', 'classes/'), ('BOOT-INF/lib/', 'lib/')]:
                    if path.startswith(prefix) and not path.endswith('/'):
                        payload[part + path[len(prefix):]] = jar.read(path).hex()
        identity = hashlib.sha256((value + json.dumps(payload, sort_keys=True)).encode()).hexdigest()
        data = dict(Id='sha256:' + identity, Config={'Labels': {key: value}}, payload=payload)
        with (store / 'dockerfiles.jsonl').open('a') as log:
            log.write(json.dumps(Path(args[args.index('-f') + 1]).read_text()) + '\n')
        images = load(); images[tag] = data
        (store / 'images.json').write_text(json.dumps(images))
    elif args[0] == 'create':
        container = args[args.index('--name') + 1]
        (store / container).write_text(args[-1])
        print(container)
    elif args[0] == 'cp':
        source, target = args[1:]
        container, path = source.split(':', 1)
        part = path.split('/')[3]
        data = image((store / container).read_text())
        for key, value in data['payload'].items():
            if key.startswith(part + '/'):
                p = Path(target) / key[len(part) + 1:]; p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(bytes.fromhex(value))
    elif args[0] == 'rm':
        (store / args[1]).unlink()
    elif args[0] == 'run':
        data = image(args[args.index('sh') + 1])
        command = args[-1]
        if 'md5sum' in command:
            for key, value in sorted(data['payload'].items()):
                if key.startswith('classes/') and key.endswith('.class'):
                    print(hashlib.md5(bytes.fromhex(value)).hexdigest(), ' ./' + key[len('classes/'):])
        else:
            marker = command.split("'")[1]
            cls = command.split('/opt/app/classes/')[1].split()[0]
            print(int(marker.encode() in bytes.fromhex(data['payload']['classes/' + cls])))
    else:
        sys.exit(71)
else:
    sys.exit(72)
