import json,subprocess,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def feed():return json.loads(command(K+['get','pods','-l','app=feed-adapter','-o','json']))['items'][0]
def target(p):
 node=p['spec']['nodeName'];assert node.startswith('traderx-lr-20261008-01a11baa-');cid=p['status']['containerStatuses'][0]['containerID'].split('://')[1]
 inspected=json.loads(command(['docker','exec',node,'crictl','inspect',cid]));pid=int(inspected['info']['pid']);assert pid>1
 return {'node':node,'containerId':cid,'pidInNode':pid,'pod':p['metadata']['name'],'podUid':p['metadata']['uid']}
results={};held=[];stream=None;handle=None
try:
 current=feed();results['beforeFeedPod']=current;results['oldFeedProcess']=target(current)
 log=command(K+['logs',current['metadata']['name']]);(E/'lr02-outage2-feed-before.log').write_text(log)
 states=[json.loads(l.split('FEED_RECOVERY ',1)[1]) for l in log.splitlines() if 'FEED_RECOVERY ' in l];results['established']=states[-1];assert states[-1]['state']=='CONNECTED' and states[-1]['received']>0
 handle=(E/'lr02-outage2-exact-container-stream.log').open('w');stream=subprocess.Popen(K+['logs',current['metadata']['name'],'-f','--timestamps'],stdout=handle,stderr=subprocess.STDOUT,text=True);results['streamPid']=stream.pid;time.sleep(1);assert stream.poll() is None,'stream mustattach beforefault'
 members=json.loads(command(K+['get','pods','-l','app=order-matcher-cluster','-o','json']))['items'];assert len(members)==3
 results['membersBefore']=members;results['targets']=[]
 for p in members:
  t=target(p);held.append(t);results['targets'].append(t);command(['docker','exec',t['node'],'kill','-STOP',str(t['pidInNode'])])
  raw=command(['docker','exec',t['node'],'cat',f"/proc/{t['pidInNode']}/status"]);t['stoppedState']=raw.splitlines()[:4];assert 'State:\tT' in raw
 results['allStoppedEpochMs']=int(time.time()*1000)
 # Hold longer than observed established session disconnect plus the actual 10s reconnect budget.
 deadline=time.monotonic()+40
 while time.monotonic()<deadline:
  observed=feed()['status']['containerStatuses'][0]
  for state in ('state','lastState'):
   term=observed.get(state,{}).get('terminated',{})
   if term.get('containerID')==current['status']['containerStatuses'][0]['containerID']:
    results['exactOriginalTermination']=term
  time.sleep(.5)
 results['holdEndEpochMs']=int(time.time()*1000)
finally:
 cleanup=[]
 for t in held:
  try:
   inspected=json.loads(command(['docker','exec',t['node'],'crictl','inspect',t['containerId']]));now=int(inspected['info'].get('pid',0))
   if now==t['pidInNode']:command(['docker','exec',t['node'],'kill','-CONT',str(now)]);cleanup.append({'pod':t['pod'],'action':'resumed exactsamecontainerPID'})
   else:cleanup.append({'pod':t['pod'],'action':'originalprocess no longer present','currentPid':now})
  except Exception as error:cleanup.append({'pod':t['pod'],'error':str(error)})
 results['cleanup']=cleanup
 if stream:
  try:stream.wait(timeout=3)
  except subprocess.TimeoutExpired:stream.terminate();stream.wait(timeout=5)
  results['streamExit']=stream.returncode
 if handle:handle.close()
 (E/'lr02-outage2.json').write_text(json.dumps(results,indent=2))
assert all('error' not in c for c in cleanup),cleanup
old=results['beforeFeedPod']['status']['containerStatuses'][0]['restartCount'];deadline=time.monotonic()+40
while time.monotonic()<deadline:
 current=feed();status=current['status']['containerStatuses'][0]
 if status['restartCount']>old:break
 time.sleep(1)
else:raise AssertionError('no nativefeed exhaustion exit/restart')
results['afterFeedPod']=current;log=(E/'lr02-outage2-exact-container-stream.log').read_text();(E/'lr02-outage2-exhausted-feed.log').write_text(log)
states=[json.loads(l.split('FEED_RECOVERY ',1)[1]) for l in log.splitlines() if 'FEED_RECOVERY ' in l];results['exhaustedStates']=[s for s in states if s['state']=='EXHAUSTED'];assert results['exhaustedStates']
terminated=results.get('exactOriginalTermination',{});results['actualTermination']=terminated;assert terminated.get('exitCode')==1
oldtarget=results['oldFeedProcess'];inspect=subprocess.run(['docker','exec',oldtarget['node'],'crictl','inspect',oldtarget['containerId']],capture_output=True,text=True);results['oldFeedContainerLookup']={'exit':inspect.returncode,'body':inspect.stdout,'stderr':inspect.stderr}
proc=subprocess.run(['docker','exec',oldtarget['node'],'test','-e',f"/proc/{oldtarget['pidInNode']}/status"],capture_output=True,text=True);results['oldFeedProcessLookupExit']=proc.returncode;assert proc.returncode==1
(E/'lr02-outage2.json').write_text(json.dumps(results,indent=2))
command(K+['rollout','status','sts/order-matcher-cluster','--timeout=90s'])
results['verdict']='PASS named established native permanent endpoint outage exceedingbudget:3actualmemberprocessesT-held40s, nativeEXHAUSTED/exit1/exactoldcontainerlogstream/oldPIDabsent; alloriginalmemberPIDsresumed, replacementfeedpreservedbykubelet. No managedHA/financial/replay claim.'
(E/'lr02-outage2.json').write_text(json.dumps(results,indent=2));print(results['verdict'])
