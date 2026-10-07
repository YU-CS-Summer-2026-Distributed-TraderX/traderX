#!/usr/bin/env python3
"""Owned scratch image, synthetic boot-jar producer; real Docker label/payload path.

No production Java compilation or live STP assertion is performed. Needs access to
Docker. Builds/removes only this script's uniquely named image; never starts it.
"""
import json
import os
from pathlib import Path
import subprocess
import uuid
from test_provenance import EntrypointControls, LABEL

fixture = EntrypointControls(methodName='test_unchanged_sources_with_new_mtimes')
fixture.setUp()
tag = 'traderx/ri18-provenance-roundtrip:' + uuid.uuid4().hex
try:
    # Keep synthetic producer, Java identity and Git history stubs; use real Docker.
    (fixture.bin / 'docker').unlink()
    (fixture.om / 'Dockerfile.cluster').write_text('FROM scratch\nCOPY build/payload /opt/app\n')
    helper = fixture.root / 'scripts/yu15/stp-image-provenance.py'
    work = fixture.root / 'real-work'; work.mkdir()
    def execute(args, expected=0):
        result = subprocess.run(args, cwd=fixture.root, env=fixture.env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        print(result.stdout, end='', flush=True)
        assert result.returncode == expected, (args, result.returncode)
        return result
    execute(['python3', str(helper), 'prepare', '--work', str(work)])
    execute(['python3', str(helper), 'build', '--work', str(work), '--role', 'fix', '--image', tag])
    before = json.loads(execute(['docker', 'image', 'inspect', tag]).stdout)[0]
    provenance = json.loads(before['Config']['Labels'][LABEL])
    # Rebuild from the same input bytes after advancing every source mtime.
    for path in fixture.om.rglob('*'):
        os.utime(path, (1900000000, 1900000000))
    execute(['python3', str(helper), 'build', '--work', str(work), '--role', 'fix', '--image', tag])
    after = json.loads(execute(['docker', 'image', 'inspect', tag]).stdout)[0]
    # Docker 29 may return the OCI index identity, which also includes a new
    # BuildKit attestation. Compare the cached runtime config/layers themselves.
    assert before['Config'] == after['Config'] and before['RootFS'] == after['RootFS']
    assert before['Created'] == after['Created'], 'identical rebuild changed config creation time'
    execute(['python3', str(helper), 'verify', '--role', 'fix', '--image', tag])
    execute(['python3', str(helper), 'verify', '--role', 'pre', '--image', tag], expected=1)
    path = fixture.om / 'src/main/resources/input.txt'; stat = path.stat()
    path.write_text('changed with preserved mtime\n'); os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    execute(['python3', str(helper), 'verify', '--role', 'fix', '--image', tag], expected=1)
    print(json.dumps(dict(result='PASS', imageIds=[before['Id'], after['Id']], role=provenance['role'],
                          cachedConfigReused=True, qualification='real Docker, synthetic compiler/inputs'), sort_keys=True))
finally:
    subprocess.run(['docker', 'image', 'rm', tag], check=True)
    fixture.tearDown()
