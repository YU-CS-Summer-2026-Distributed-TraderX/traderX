#!/usr/bin/env python3
"""Exercise the actual console server's local coordinator read route; no cluster calls."""
import argparse
import json
from pathlib import Path
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--component',type=Path,default=ROOT/'specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles')
args=parser.parse_args();component=args.component.resolve();sys.path.insert(0,str(component))
from coordinator import Coordinator
from w0_result import W0FileAdapter

root=Path(tempfile.mkdtemp(prefix='traderx-job-status-'))
state=root/'state';incoming=root/'incoming';incoming.mkdir();inbox=root/'inbox';inbox.mkdir()
shutil.copytree(component/'tests/fixtures/shared/bill/v2',inbox/'bill')
with socket.socket() as sock:
    sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
env={**os.environ,'PORT':str(port),'AUTH_MASTER_SECRET':'','EOD_COORDINATOR_STATE':str(state),'EOD_STATUS_SCRIPT':str(component/'job_status.py'),'EOD_PYTHON':sys.executable}
log=(root/'server.log').open('w')
server=subprocess.Popen(['node','server.mjs'],cwd=ROOT/'web-front-end-console',env=env,stdout=log,stderr=subprocess.STDOUT)
def request(method='GET'):
    req=urllib.request.Request(f'http://127.0.0.1:{port}/eod/jobs',method=method)
    try:response=urllib.request.urlopen(req,timeout=15)
    except urllib.error.HTTPError as e:response=e
    with response:return response.status,json.load(response),response.headers
try:
    for _ in range(100):
        try:status,body,headers=request();break
        except urllib.error.URLError:time.sleep(.05)
    else:raise AssertionError('console failed to start')
    assert status==503 and not state.exists()
    assert request('POST')[0]==405
    with Coordinator(state,W0FileAdapter(incoming)) as ctl:
        assert request()[1]['jobs']==[]
        ctl.discover(inbox)
        assert request()[1]['jobs'][0]['status']=='QUEUED'
        ctl.run()
        job=request()[1]['jobs'][0];assert job['status']=='RUNNING'
        shutil.copyfile(component/'tests/fixtures/w0-results/bill.json',incoming/(job['bundle_id']+'.json'))
        done=ctl.run()['jobs'][0]
        status,body,headers=request();assert status==200 and headers['Cache-Control']=='no-store'
        job=body['jobs'][0]
        assert job['status']=='W0_VALIDATED' and job['selectedW0Result'] and not job['usableForRisk']
        assert job['coverage']['itemCount']==2
        (root/'accepted-status.json').write_text(json.dumps(body,indent=2)+'\n')
        output=state/done['result_path']/'results.json';output.write_bytes(output.read_bytes()+b' ')
        job=request()[1]['jobs'][0]
        assert job['resultIntegrity']=='INVALID' and not job['selectedW0Result'] and job['coverage'] is None
    print(f'Actual console GET route passed missing/empty/pending/running/W0/tamper cases and POST refusal. Evidence: {root}')
finally:
    server.terminate()
    try:server.wait(timeout=5)
    except subprocess.TimeoutExpired:server.kill();server.wait()
    log.close()
