#!/usr/bin/env python3
"""Actual local container proof. Inputs must be a fresh shared_examples exporter output.

Container must already mount ROOT/staging at /data/inputs and use an external engine store.
No source imports from the risk engine; its immutable image executes all pricing.
"""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

REPO = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--root', required=True, type=Path)
p.add_argument('--exports', required=True, type=Path, help='shared_examples.py output from fresh Java exporter')
p.add_argument('--container', default='traderx-ri03')
p.add_argument('--endpoint', default='http://127.0.0.1:18303')
p.add_argument('--component', type=Path, default=REPO/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles')
a = p.parse_args()
sys.path.insert(0, str(a.component.resolve()))
import bundle
import container_adapter as ca
from coordinator import Coordinator
import job_status
import pricing_result
os.umask(0o077)
root = a.root.resolve(); root.mkdir(mode=0o700, parents=True, exist_ok=True)
inspection = json.loads(subprocess.check_output(['docker', 'inspect', a.container]))[0]
assert inspection['Image'] == ca.CONTAINER_PROFILE['imageId']
mounts = {m['Destination']: m for m in inspection['Mounts']}
assert mounts['/data/inputs']['Source'] == str(root/'staging') and not mounts['/data/inputs']['RW']
assert mounts['/data/eod-store']['RW'] and not str(mounts['/data/eod-store']['Source']).startswith(str(REPO))
assert inspection['HostConfig']['ReadonlyRootfs']
assert inspection['HostConfig']['PortBindings']['8000/tcp'] == [{'HostIp': '127.0.0.1', 'HostPort': a.endpoint.rsplit(':', 1)[1]}]
(root/'container-inspect.json').write_bytes(ca.canonical(inspection))
code, schema_bytes = ca.ContainerAdapter(a.endpoint).request('GET', '/eod/schemas/result')
assert code == 200 and schema_bytes == (a.component/'container-result-schema.json').read_bytes()
(root/'served-schema.json').write_bytes(schema_bytes)
inbox = root/'inbox'; inbox.mkdir(mode=0o700)
source = a.exports/'bill/v2'
# Build through the current bundle producer using exporter artifacts, not edited economics.
bundle.build(source/'positions.csv', source/'contracts.csv', inbox/'cut',
             'synthetic-shared-examples-v1', '2025-06-02T16:00:00-04:00', 'synthetic', source/'instrument-terms.json')
assert {f.name: f.read_bytes() for f in (inbox/'cut').iterdir()} == {f.name: f.read_bytes() for f in source.iterdir()}
manifest, _ = ca.inputs(inbox/'cut')
adapter = ca.ContainerAdapter(a.endpoint, root/'staging')
with Coordinator(root/'state', adapter) as c:
    discovered = c.discover(inbox); assert len(discovered['queued']) == 1
    job = c.run()['jobs'][0]
    assert job['status'] == 'CONTAINER_PRICING_VALIDATED', job
    assert job['resultIntegrity'] == 'VERIFIED' and len(job['attempts']) == 1
    result_path = root/'state'/job['result_path']
    original = (result_path/'http-response.json').read_bytes()
# Restart consumer; verify exact bytes and one attempt, no second computation.
with Coordinator(root/'state', ca.ContainerAdapter(a.endpoint, root/'staging')) as c:
    assert len(c.discover(inbox)['duplicates']) == 1
    again = c.run(); assert again['processed'] == [] and again['recovered'] == []
    assert len(again['jobs'][0]['attempts']) == 1
assert (result_path/'http-response.json').read_bytes() == original
result = json.loads((result_path/'results.json').read_bytes())
# Independent existing Decimal pricing checks, only in acceptance tooling.
legacy = json.loads(json.dumps(result)); legacy.pop('resultSchema')
for item in legacy['items']:
    assert item['calculations']['accruedInterest'].pop('accrualSource') == 'structural-zero'
pricing_result.validate(inbox/'cut', ca.canonical(legacy))
report = {'at': datetime.now(timezone.utc).isoformat(), 'image': ca.CONTAINER_PROFILE['imageId'],
          'inputBundle': manifest['bundleId'], 'inputOrigin': 'fresh synthetic TraderX exporter',
          'jobId': job['job_id'], 'consumerAttemptId': job['attempts'][0]['attempt_id'],
          'engineAttemptId': json.loads(original)['attemptId'],
          'workloadKey': ca.workload(manifest), 'originalHttpSha256': bundle.digest(original),
          'consumerRestart': 'same result and one attempt', 'independentBillReference': 'passed USD 1e-8',
          'usableForRisk': False, 'portfolioRiskAvailable': False, 'refusals': {}}
# Explicit missing/unresolvable market refusal, unsupported convention remains per-item outcome.
for name in ('bill', 'sofr'):
    target = root/'staging'/('negative-'+name)
    shutil.copytree(a.exports/name/'v2', target)
    target.chmod(0o755)
    for f in target.iterdir(): f.chmod(0o444)
checks = {
    'missing-bundle': {'bundlePath': '/data/inputs/absent'},
    'unresolvable-market': {'bundlePath': '/data/inputs/negative-bill', 'marketInputs': {'mode': 'assumed-profile', 'assumedProfileId': 'absent'}},
    'missing-market': {'bundlePath': '/data/inputs/negative-bill'},
    'unsupported-sofr': {'bundlePath': '/data/inputs/negative-sofr', 'marketInputs': ca.MARKET},
}
for label, body in checks.items():
    body.update(submissionId='ri03-negative-'+label, reuseExistingResult=False)
    code, raw = adapter.request('POST', '/eod/price', body)
    (root/(label+'.json')).write_bytes(raw)
    data = json.loads(raw)
    if label == 'missing-bundle': assert code == 422
    elif label == 'unresolvable-market': assert code == 400
    else:
        assert code == 200
        assert all(i['calculations']['npv']['status'] != 'ok' for i in data['result']['items'])
    report['refusals'][label] = {'http': code, 'priced': False}
(root/'status.json').write_bytes(bundle.encoded(job_status.snapshot(root/'state')))
(root/'evidence.json').write_bytes(bundle.encoded(report))
print(json.dumps(report, indent=2))
