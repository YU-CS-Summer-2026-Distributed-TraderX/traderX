#!/usr/bin/env python3
"""Run Alex's pinned W0 adapter and our independent intake, entirely locally."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--engine',type=Path,required=True)
p.add_argument('--component',type=Path,default=ROOT/'specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles')
a=p.parse_args();engine=a.engine.resolve();component=a.component.resolve()
sys.path.insert(0,str(component))
import coordinator
import w0_result

def git(*args):
    return subprocess.check_output(['git','-C',str(engine),*args],text=True).strip()
if git('rev-parse','HEAD')!=w0_result.ENGINE_COMMIT:
    p.error('engine HEAD differs from the reviewed W0 compatibility profile')
if git('status','--porcelain','--untracked-files=all','--','engine'):
    p.error('engine source must be clean for reproducible producer evidence')
root=Path(tempfile.mkdtemp(prefix='traderx-real-w0-'))
producer='from engine.integration import price_bundle; import json,sys; print(json.dumps(price_bundle(sys.argv[1]).to_dict(),sort_keys=True,allow_nan=False))'
report={'engineCommit':git('rev-parse','HEAD'),'financialValidation':False,'examples':{}}
for name in ('bill','note','sofr'):
    case=root/name;inbox=case/'inbox';inbox.mkdir(parents=True);incoming=case/'incoming';incoming.mkdir()
    source=component/'tests/fixtures/shared'/name/'v2'
    shutil.copytree(source,inbox/'cut')
    data=subprocess.check_output([sys.executable,'-c',producer,str(source)],cwd=engine,
        env={**os.environ,'PYTHONPATH':str(engine),'PYTHONDONTWRITEBYTECODE':'1'})
    result=json.loads(data);(incoming/(result['bundleId']+'.json')).write_bytes(data)
    with coordinator.Coordinator(case/'state',w0_result.W0FileAdapter(incoming)) as ctl:
        ctl.discover(inbox);job=ctl.run()['jobs'][0]
        assert job['status']=='W0_VALIDATED' and job['resultIntegrity']=='VERIFIED'
        assert job['selectedW0Result'] and not job['selectedMockResult'] and not job['usableForRisk']
        assert len(ctl.discover(inbox)['duplicates'])==1
        assert ctl.run()['processed']==[] and len(job['attempts'])==1
        report['examples'][name]={'jobId':job['job_id'],'status':job['status'],**w0_result.validate(source,data)}
(root/'evidence.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'evidence':str(root/'evidence.json'),**report},indent=2))
