import subprocess,json,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def status():
 log=command(K+['logs','deploy/feed-adapter']);(E/'lr02-idle-feed.log').write_text(log);return json.loads([line.split(' ',1)[1] for line in log.splitlines() if line.startswith('FEED_RECOVERY ')][-1])
results={'before':status()}
try:
 command(K+['scale','deploy/price-publisher','--replicas=0']);command(K+['wait','--for=delete','pod','-l','app=price-publisher','--timeout=30s'])
 results['publisherAbsentAtEpochMs']=int(time.time()*1000)
 deadline=time.monotonic()+80
 while time.monotonic()<deadline:
  latest=status()
  if latest['observedAtEpochMs']>=results['publisherAbsentAtEpochMs']+20_000:break
  time.sleep(1)
 else:raise AssertionError('no fresh native idle observation')
 results['afterIdle']=latest
 assert latest['state']=='CONNECTED' and latest['sessionGeneration']==results['before']['sessionGeneration']
 assert latest['natsState']=='CONNECTED' and latest['recoveries']==results['before']['recoveries']
 results['verdict']='PASS named native feed idle milestone: sole publisher absent >=20s with fresh adapter observation, native session/generation healthy, no reconnect/restart; no freshness/quorum inference.'
finally:
 command(K+['scale','deploy/price-publisher','--replicas=1'])
 command(K+['rollout','status','deploy/price-publisher','--timeout=30s'])
 (E/'lr02-idle.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2))
