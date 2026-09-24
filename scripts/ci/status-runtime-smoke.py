#!/usr/bin/env python3
"""Build the existing status-runtime layer and test its installed readers, offline."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--console-image', required=True, help='locally built console image; resolved to immutable ID')
p.add_argument('--evidence', type=Path, required=True, help='new evidence directory')
a = p.parse_args()
a.evidence.mkdir(parents=True, exist_ok=False)
base = json.loads(subprocess.check_output(['docker', 'image', 'inspect', a.console_image]))[0]
if base['Os'] != 'linux' or base['Architecture'] != 'amd64':
    raise SystemExit('console image must be linux/amd64')
tag = 'traderx-ri08-status:' + uuid.uuid4().hex
# BuildKit FROM does not accept a bare local image ID. Give the inspected ID a
# unique local tag, then verify it did not move during the build.
base_tag = 'traderx-ri08-console-base:' + uuid.uuid4().hex
subprocess.run(['docker', 'tag', base['Id'], base_tag], check=True)
with (a.evidence/'build.log').open('w') as log:
    subprocess.run(['bash', str(ROOT/'scripts/package-state-YU18-risk-runtime.sh')], check=True,
                   env={**os.environ, 'CONSOLE_IMAGE': base_tag, 'RISK_RUNTIME_IMAGE': tag},
                   stdout=log, stderr=subprocess.STDOUT)
if json.loads(subprocess.check_output(['docker', 'image', 'inspect', base_tag]))[0]['Id'] != base['Id']:
    raise SystemExit('console base moved during build')
image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag]))[0]
(a.evidence/'image.json').write_text(json.dumps(image, indent=2)+'\n')
component = ROOT/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles'
# Mount only tests. Runtime Python code and schemas must come from the image being verified.
script = '''set -eu
"$EOD_PYTHON" - <<'CHECK'
from pathlib import Path
import os, shutil, sys, unittest
sys.path.insert(0, '/opt/traderx/eod-risk-bundles')
from coordinator import Coordinator
suite = unittest.defaultTestLoader.discover('/opt/traderx/eod-risk-bundles/tests')
assert suite.countTestCases() > 0
result = unittest.TextTestRunner(verbosity=2).run(suite)
if not result.wasSuccessful(): raise SystemExit(1)
os.umask(0o077)
inbox = Path('/tmp/smoke-inbox'); inbox.mkdir()
shutil.copytree('/opt/traderx/eod-risk-bundles/tests/fixtures/shared/bill/v2', inbox/'cut')
with Coordinator('/tmp/smoke-state') as c:
    assert len(c.discover(inbox)['queued']) == 1
    job = c.run()['jobs'][0]
    assert job['status'] == 'MOCK_COMPLETE' and not job['usableForRisk']
CHECK
EOD_COORDINATOR_STATE=/tmp/smoke-state node --input-type=module - <<'CHECK'
import assert from 'node:assert/strict';
import { readEodJobs } from '/app/eod-jobs.mjs';
const r = await readEodJobs();
assert.equal(r.status, 200);
assert.equal(r.body.usableForRisk, false);
assert.equal(r.body.jobs.length, 1);
assert.equal(r.body.jobs[0].status, 'MOCK_COMPLETE');
assert.equal(r.body.jobs[0].resultIntegrity, 'VERIFIED');
console.log('PASS installed Node/Python status bridge; one synthetic mock job, usableForRisk=false');
CHECK
'''
name = 'traderx-ri08-status-smoke-' + uuid.uuid4().hex
try:
    with (a.evidence/'smoke.log').open('w') as log:
        subprocess.run(['docker', 'run', '--rm', '--name', name, '--platform', 'linux/amd64', '--network', 'none',
                        '--read-only', '--cpus', '2', '--memory', '1g', '--pids-limit', '128',
                        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                        '--tmpfs', '/tmp:rw,nosuid,nodev,size=134217728,mode=1777',
                        '--mount', f'type=bind,src={component}/tests,dst=/opt/traderx/eod-risk-bundles/tests,readonly',
                        '--entrypoint', '/bin/sh', image['Id'], '-c', script],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
except subprocess.TimeoutExpired:
    subprocess.run(['docker', 'rm', '-f', name], check=True)
    raise
print(json.dumps({'consoleImage': base['Id'], 'statusImage': image['Id'],
                  'scope': 'installed EOD suite and synthetic mock status bridge; no pricing'}, indent=2))
