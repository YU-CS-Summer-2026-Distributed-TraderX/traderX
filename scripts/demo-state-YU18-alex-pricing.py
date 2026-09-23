#!/usr/bin/env python3
"""Real pinned Alex bill/note pricing into the separate provisional LOCAL intake."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--engine',type=Path,required=True)
p.add_argument('--component',type=Path,default=ROOT/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles')
a=p.parse_args();engine=a.engine.resolve();component=a.component.resolve()
sys.path.insert(0,str(component))
import bundle
import coordinator
import job_status
import pricing_result as pricing
import w0_result


def git(*args):
    return subprocess.check_output(['git','-C',str(engine),*args]).decode().strip()


def tracked_hashes():
    names=subprocess.check_output(['git','-C',str(engine),'ls-files','-z']).split(b'\0')
    return {os.fsdecode(n):hashlib.sha256((engine/os.fsdecode(n)).read_bytes()).hexdigest()
            for n in names if n and (engine/os.fsdecode(n)).is_file()}


head=git('rev-parse','HEAD');status=git('status','--porcelain','--untracked-files=all')
before=tracked_hashes()
root=Path(tempfile.mkdtemp(prefix='traderx-real-pricing-'))
producer_root=engine
# Preserve the reviewed tree if Alex moved on, without changing his refs/index/worktree.
if head!=pricing.ENGINE_COMMIT or status:
    producer_root=root/'reviewed-engine';producer_root.mkdir()
    archive=subprocess.check_output(['git','-C',str(engine),'archive',pricing.ENGINE_COMMIT])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            name=Path(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir()):
                raise ValueError('unexpected archive member')
        tar.extractall(producer_root,filter='data')
    for f in producer_root.rglob('*'):
        f.chmod(0o555 if f.is_dir() else 0o444)
    producer_root.chmod(0o555)
producer = "from engine.integration import price_bundle; import json,sys; print(json.dumps(price_bundle(sys.argv[1],market_inputs={'mode':'assumed-profile','assumedProfileId':'flat-3pct-v1'}).to_dict(),sort_keys=True,allow_nan=False))"
report={'engineCommit':pricing.ENGINE_COMMIT,'checkoutHead':head,'checkoutClean':not bool(status),
        'producerSource':str(producer_root),'sourceTree':git('rev-parse',pricing.ENGINE_COMMIT+'^{tree}'),
        'python':sys.version,'profile':pricing.PRICING_PROFILE,'financialValidation':False,'examples':{}}
for name in ('bill','note'):
    case=root/name;inbox=case/'inbox';inbox.mkdir(parents=True);incoming=case/'incoming';incoming.mkdir()
    source=component/'tests/fixtures/shared'/name/'v2';shutil.copytree(source,inbox/'cut')
    pricing.inputs(source)  # refuse unsupported fixture before invoking producer
    with coordinator.Coordinator(case/'state',pricing.PricingFileAdapter(incoming)) as ctl:
        ctl.discover(inbox);pending=ctl.run()['jobs'][0]
        assert pending['status']=='RUNNING' and len(pending['attempts'])==1
    proc=subprocess.run([sys.executable,'-c',producer,str(source)],cwd=producer_root,
        env={**os.environ,'PYTHONPATH':str(producer_root),'PYTHONDONTWRITEBYTECODE':'1'},capture_output=True,check=True)
    data=proc.stdout;(case/'producer.stderr').write_bytes(proc.stderr)
    result=json.loads(data);(incoming/(result['bundleId']+'.json')).write_bytes(data)
    with coordinator.Coordinator(case/'state',pricing.PricingFileAdapter(incoming)) as ctl:
        job=ctl.run()['jobs'][0]
        assert job['status']=='SYNTHETIC_PRICING_VALIDATED' and job['resultIntegrity']=='VERIFIED'
        assert job['selectedSyntheticPricingResult'] and not job['selectedW0Result'] and not job['usableForRisk']
        assert len(ctl.discover(inbox)['duplicates'])==1 and ctl.run()['processed']==[] and len(job['attempts'])==1
        try:w0_result.validate(source,data)
        except ValueError as exc:
            assert 'permits no market computation' in str(exc)
        else:raise AssertionError('W0 admitted a priced result')
        assert (case/'state'/job['result_path']/'results.json').read_bytes()==data
        snapshot=job_status.snapshot(case/'state');assert snapshot['jobs'][0]['pricingAvailable']
        (case/'status.json').write_bytes(bundle.encoded(snapshot))
        report['examples'][name]={'jobId':job['job_id'],'status':job['status'],
            'resultSha256':bundle.digest(data),**pricing.validate(source,data)}
assert git('rev-parse','HEAD')==head and git('status','--porcelain','--untracked-files=all')==status
assert tracked_hashes()==before
report['alexTrackedBytesUnchanged']=True
(root/'evidence.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'evidence':str(root/'evidence.json'),**report},indent=2))
