#!/usr/bin/env python3
"""RI-03 actual process crash and container replacement proof; task-owned rig only."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--root', type=Path, required=True)
p.add_argument('--component', type=Path, default=ROOT/'generated/code/target-generated/eod-risk-bundles')
p.add_argument('--endpoint', default='http://127.0.0.1:18303')
p.add_argument('--container', default='traderx-ri03')
p.add_argument('--case', default='recovery')
p.add_argument('--crash', action='store_true', help=argparse.SUPPRESS)
a = p.parse_args();sys.path.insert(0,str(a.component.resolve()))
import bundle
from coordinator import Coordinator
from container_adapter import ContainerAdapter, canonical, CONTAINER_PROFILE
from worker_protocol import Uncertain
root=a.root.resolve();os.umask(0o077)
if a.crash:
    class Crash(ContainerAdapter):
        def request(self, method, path, body=None):
            code,raw=super().request(method,path,body)
            if method=='POST':
                assert code==200
                os._exit(75) # Server answered; local immutable response not yet published.
            return code,raw
    with Coordinator(root/(a.case+'-state'),Crash(a.endpoint,root/'staging')) as c:
        c.discover(root/'inbox');c.run()
    raise AssertionError('crash injection did not execute')
assert not (root/(a.case+'-state')).exists(), 'choose a fresh recovery state'
child=subprocess.run([sys.executable,__file__,'--root',str(root),'--component',str(a.component),
                      '--endpoint',a.endpoint,'--case',a.case,'--crash'])
assert child.returncode==75
class ReadOnlyRecovery(ContainerAdapter):
    calls=[]
    def request(self,method,path,body=None):
        assert method=='GET','recovery must never submit'
        self.calls.append(path)
        return super().request(method,path,body)
adapter=ReadOnlyRecovery(a.endpoint,root/'staging')
with Coordinator(root/(a.case+'-state'),adapter) as c:
    job=c.run()['jobs'][0]
    assert job['status']=='CONTAINER_PRICING_VALIDATED' and len(job['attempts'])==1
assert len(adapter.calls)==2 # workload lookup and immutable attempt binding
# Restrict replacement to exact accepted image, task-named container, expected mount/port.
info=json.loads(subprocess.check_output(['docker','inspect',a.container]))[0]
assert a.container.startswith('traderx-ri03') and info['Image']==CONTAINER_PROFILE['imageId']
mounts={m['Destination']:m for m in info['Mounts']}
assert mounts['/data/inputs']['Source']==str(root/'staging') and not mounts['/data/inputs']['RW']
store=Path(mounts['/data/eod-store']['Source'])
assert store.is_relative_to(root) and mounts['/data/eod-store']['RW']
ports=info['HostConfig']['PortBindings']['8000/tcp']; assert len(ports)==1 and ports[0]['HostIp']=='127.0.0.1'
records=[]
for f in (store/'attempts').glob('*.json'):
    data=json.loads(f.read_bytes())
    records.append(data)
# Use public attempt endpoint addresses from durable metadata, with before/after wire comparison.
ids=[r['attemptId'] for r in records]
assert ids
client=ContainerAdapter(a.endpoint)
before={i:client.request('GET','/eod/attempts/'+i) for i in ids}
assert all(code==200 for code,_ in before.values())
assert {'failed','completed'} <= {json.loads(raw)['state'] for _,raw in before.values()}
subprocess.run(['docker','stop',a.container],check=True)
subprocess.run(['docker','rm',a.container],check=True)
command=['docker','run','-d','--name',a.container,'--platform','linux/amd64','--cpus','2','--cpuset-cpus','0,1',
         '--memory','2g','--memory-swap','2g','--pids-limit','256','--read-only','--cap-drop','ALL',
         '--security-opt','no-new-privileges','--stop-timeout','30',
         '--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=268435456,mode=1777',
         '--mount',f'type=bind,src={root / "staging"},dst=/data/inputs,readonly',
         '--mount',f'type=bind,src={store},dst=/data/eod-store',
         '--publish',f'127.0.0.1:{ports[0]["HostPort"]}:8000',CONTAINER_PROFILE['imageId']]
subprocess.run(command,check=True)
for _ in range(60):
    try:
        if client.request('GET','/health')[0]==200: break
    except Uncertain: pass
    time.sleep(1)
else: raise AssertionError('container did not restart')
after={i:client.request('GET','/eod/attempts/'+i) for i in ids}
(root/(a.case+'-before.json')).write_bytes(canonical({i:json.loads(raw) for i,(_,raw) in before.items()}))
(root/(a.case+'-after.json')).write_bytes(canonical({i:json.loads(raw) for i,(_,raw) in after.items()}))
assert {i:(code,json.loads(raw)) for i,(code,raw) in before.items()} == {i:(code,json.loads(raw)) for i,(code,raw) in after.items()}, 'terminal attempt semantics changed/lost across replacement'
with Coordinator(root/'state',ContainerAdapter()) as c:
    original=c.status()['jobs'][0];assert original['resultIntegrity']=='VERIFIED'
report={'abruptConsumerExit':75,'recoveryMethods':['GET','GET'],'recoveryJob':job['job_id'],
        'consumerAttempts':1,'containerReplacement':'same completed and failed attempt documents (HTTP key order may differ)',
        'attemptCountCompared':len(ids),'originalConsumerIntegrity':'VERIFIED','command':command,
        'noActiveStateDurabilityClaim':True}
(root/'recovery-evidence.json').write_bytes(canonical(report));print(json.dumps(report,indent=2))
