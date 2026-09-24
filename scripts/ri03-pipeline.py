#!/usr/bin/env python3
"""One closed synthetic TraderX export -> accepted EOD container -> existing intake.

Use the accepted RI-02 launcher with --inputs pointing to --staging. This is local
filesystem staging, not an upload protocol. The input bundle is never fabricated.
"""
import argparse
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--component', type=Path, default=ROOT/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles')
p.add_argument('--state', type=Path, required=True)
p.add_argument('--staging', type=Path, required=True)
p.add_argument('--endpoint', default='http://127.0.0.1:18303')
p.add_argument('--inbox', type=Path, help='discover validated bundles before running')
p.add_argument('--status-only', action='store_true')
a = p.parse_args()
sys.path.insert(0, str(a.component.resolve()))
import bundle
from coordinator import Coordinator
from container_adapter import ContainerAdapter
import job_status
os.umask(0o077)
if a.status_only:
    result = job_status.snapshot(a.state)
else:
    with Coordinator(a.state, ContainerAdapter(a.endpoint, a.staging)) as ctl:
        if a.inbox:
            discovery = ctl.discover(a.inbox)
            bundle.require(not discovery['invalid'] and not discovery['incomplete'], 'invalid/incomplete discovery')
        ctl.run()
    result = job_status.snapshot(a.state)
print(bundle.encoded(result).decode())
raise SystemExit(1 if any(j['status'] in ('FAILED', 'UNCERTAIN') or j['resultIntegrity'] == 'INVALID' for j in result['jobs'])
                 else 2 if any(j['status'] in ('QUEUED', 'RUNNING') for j in result['jobs']) else 0)
