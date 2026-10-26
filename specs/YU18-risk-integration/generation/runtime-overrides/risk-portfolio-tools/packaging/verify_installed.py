"""Check installed engine bytes and dependency versions against the archived build."""
import hashlib
import importlib.metadata
import importlib.util
import json
from pathlib import Path


def verify(path=Path('/opt/pilot/build.json')):
    manifest = json.loads(path.read_bytes())
    package = Path(importlib.util.find_spec('engine').origin).parent
    for name, expected in manifest['engine_files'].items():
        target = package / Path(name).relative_to('engine')
        if target.is_symlink() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            raise ValueError('installed engine identity mismatch: ' + name)
    for name, expected in manifest['dependencies'].items():
        if importlib.metadata.version(name) != expected:
            raise ValueError('installed dependency mismatch: ' + name)
    for name, expected in manifest['consumer_files'].items():
        if hashlib.sha256((path.parent / name).read_bytes()).hexdigest() != expected:
            raise ValueError('installed consumer identity mismatch: ' + name)
    return manifest


if __name__ == '__main__':
    m = verify()
    print(json.dumps({'event': 'installed_identity_verified', 'engine_revision': m['engine_revision'],
                      'numerical_revision': m['numerical_revision'], 'engine_files': len(m['engine_files'])}))
