"""Exercise the actual installer block with independently specified script bytes."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HELPER = ROOT / 'pipeline/install-generated-runtime-harness.sh'
SHELL_HEADER = '''set -euo pipefail

export TRADERX_LOCAL_RUNTIME_SCRIPT=1
export TRADERX_SKIP_GENERATE=1
if [[ -z "${TRADERX_GENERATED_ROOT:-}" ]]; then
  TRADERX_GENERATED_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
  export TRADERX_GENERATED_ROOT
fi
'''
PS_HEADER = '''$ErrorActionPreference = 'Stop'

$env:TRADERX_LOCAL_RUNTIME_SCRIPT = '1'
$env:TRADERX_SKIP_GENERATE = '1'
if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('TRADERX_GENERATED_ROOT'))) {
  $env:TRADERX_GENERATED_ROOT = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
}
'''
LIBS = ('resolve-socketio-client-path.sh', 'generated-state-detection.sh',
        'build-jvm-jar.sh', 'kubernetes-smoke-readiness.sh', 'observability-runtime.sh')


class RuntimeScriptControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ri29-scripts-')
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        (self.folder / 'lib').mkdir()
        for name in LIBS:
            (self.folder / 'lib' / name).write_text('library\n')
        text = HELPER.read_text()
        self.block = text.split('# Mark copied scripts as local-runtime scripts and disable re-generation there.\n', 1)[1].split('# Recreate component-compat symlinks', 1)[0]

    def write(self, name, content):
        path = self.folder / name
        path.write_bytes(content)
        path.chmod(0o640)
        return path

    def run_block(self):
        env = dict(os.environ, SCRIPTS_DST=str(self.folder))
        return subprocess.run(['/bin/bash', '-euo', 'pipefail', '-c', self.block],
                              env=env, capture_output=True, timeout=20)

    def test_multiple_files_keep_individual_guards_and_first_header_only(self):
        ordinary = self.write('ordinary name.sh', b'#!/bin/bash\nset -euo pipefail\necho hi\nset -euo pipefail\n')
        marked = self.write('already.sh', b'set -euo pipefail\nexport TRADERX_SKIP_GENERATE=1\n')
        suffix = self.write('suffix.sh', b'set -euo pipefail\nexport TRADERX_SKIP_GENERATE=1 # comment\n')
        no_header = self.write('no-header.sh', b'echo unchanged\n')
        self.assertEqual(0, self.run_block().returncode)
        self.assertEqual(b'#!/bin/bash\n' + SHELL_HEADER.encode() + b'echo hi\nset -euo pipefail\n', ordinary.read_bytes())
        self.assertEqual(b'set -euo pipefail\nexport TRADERX_SKIP_GENERATE=1\n', marked.read_bytes())
        self.assertEqual(SHELL_HEADER.encode() + b'export TRADERX_SKIP_GENERATE=1 # comment\n', suffix.read_bytes())
        self.assertEqual(b'echo unchanged\n', no_header.read_bytes())
        for path in (ordinary, marked, suffix, no_header):
            self.assertEqual(0o751, path.stat().st_mode & 0o777)

    def test_powershell_prefix_guard_and_modes(self):
        ordinary = self.write('ordinary name.ps1', b"$ErrorActionPreference = 'Stop'\nWrite-Output 'hi'\n")
        marked = self.write('already.ps1', b"$ErrorActionPreference = 'Stop'\n$env:TRADERX_LOCAL_RUNTIME_SCRIPT = '1' # suffix\n")
        self.assertEqual(0, self.run_block().returncode)
        self.assertEqual(PS_HEADER.encode() + b"Write-Output 'hi'\n", ordinary.read_bytes())
        self.assertEqual(b"$ErrorActionPreference = 'Stop'\n$env:TRADERX_LOCAL_RUNTIME_SCRIPT = '1' # suffix\n", marked.read_bytes())
        self.assertEqual(0o640, ordinary.stat().st_mode & 0o777)
        self.assertEqual(0o640, marked.stat().st_mode & 0o777)

    def test_empty_script_selection_has_no_literal_glob_input(self):
        result = self.run_block()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'lib'}, {p.name for p in self.folder.iterdir()})

    def test_missing_required_library_stays_failure(self):
        (self.folder / 'lib' / LIBS[0]).unlink()
        self.assertNotEqual(0, self.run_block().returncode)

    def test_regeneration_does_not_duplicate_runtime_header(self):
        path = self.write('repeat.sh', b'set -euo pipefail\necho hi\n')
        self.assertEqual(0, self.run_block().returncode)
        first = path.read_bytes()
        self.assertEqual(0, self.run_block().returncode)
        self.assertEqual(first, path.read_bytes())
        self.assertEqual(1, first.count(b'export TRADERX_SKIP_GENERATE=1\n'))

    def test_copy_selection_includes_additions_and_optional_companions(self):
        source = self.folder / 'source with spaces'
        source.mkdir()
        expected = {
            'test-added script.sh': b'new shell input\n',
            'test-added.ps1': b'new powershell input\n',
            'start probe.sh': b'selected shell input\n',
            'start probe.ps1': b'optional companion\n',
        }
        for name, data in expected.items():
            path = source / name
            path.write_bytes(data)
            path.chmod(0o640)
        (source / 'ignored.txt').write_text('not a selected script\n')
        text = HELPER.read_text()
        function = 'copy_script_if_exists() {' + text.split('copy_script_if_exists() {', 1)[1].split('# Grafana dashboard helper scripts', 1)[0]
        globs = 'shopt -s nullglob' + text.split('shopt -s nullglob', 1)[1].split('case "${STATE_ID}"', 1)[0]
        flush = 'if ((${#script_sources[@]})); then' + text.split('if ((${#script_sources[@]})); then', 1)[1].split('\nfi', 1)[0] + '\nfi\n'
        command = 'script_sources=()\n' + function + globs + '\ncopy_script_if_exists "start probe.sh"\ncopy_script_if_exists "absent.sh"\n' + flush
        result = subprocess.run(['/bin/bash', '-euo', 'pipefail', '-c', command],
                                env=dict(os.environ, SCRIPTS_SRC=str(source), SCRIPTS_DST=str(self.folder)),
                                capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        copied = {p.name for p in self.folder.iterdir() if p.is_file()}
        self.assertEqual(set(expected), copied)
        for name, data in expected.items():
            self.assertEqual(data, (self.folder / name).read_bytes())
            self.assertEqual(0o640, (self.folder / name).stat().st_mode & 0o777)


if __name__ == '__main__':
    unittest.main(verbosity=2)
