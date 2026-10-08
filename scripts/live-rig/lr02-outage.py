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
results={};held=[]
try:
 current=feed();results['beforeFeedPod']=current;results['oldFeedProcess']=target(current)
 log=command(K+['logs',current['metadata']['name']]);(E/'lr02-outage-feed-before.log').write_text(log)
 states=[json.loads(l.split(' ',1)[1]) for l in log.splitlines() if l.startswith('FEED_RECOVERY ')];results['established']=states[-1];assert states[-1]['state']=='CONNECTED' and states[-1]['received']>0
 members=json.loads(command(K+['get','pods','-l','app=order-matcher-cluster','-o','json']))['items'];assert len(members)==3
 results['membersBefore']=members;results['targets']=[]
 for p in members:
  t=target(p);held.append(t);results['targets'].append(t);command(['docker','exec',t['node'],'kill','-STOP',str(t['pidInNode'])])
  raw=command(['docker','exec',t['node'],'cat',f"/proc/{t['pidInNode']}/status"]);t['stoppedState']=raw.splitlines()[:4];assert 'State:\tT' in raw
 results['allStoppedEpochMs']=int(time.time()*1000)
 # Hold longer than observed established session disconnect plus the actual 10s reconnect budget.
 time.sleep(40)
 results['holdEndEpochMs']=int(time.time()*1000)
finally:
 cleanup=[]
 for t in held:
  try:
   inspected=json.loads(command(['docker','exec',t['node'],'crictl','inspect',t['containerId']]));now=int(inspected['info'].get('pid',0))
   if now==t['pidInNode']:command(['docker','exec',t['node'],'kill','-CONT',str(now)]);cleanup.append({'pod':t['pod'],'action':'resumed exactsamecontainerPID'})
   else:cleanup.append({'pod':t['pod'],'action':'originalprocess no longer present','currentPid':now})
  except Exception as error:cleanup.append({'pod':t['pod'],'error':str(error)})
 results['cleanup']=cleanup;(E/'lr02-outage.json').write_text(json.dumps(results,indent=2))
assert all('error' not in c for c in cleanup),cleanup
old=results['beforeFeedPod']['status']['containerStatuses'][0]['restartCount'];deadline=time.monotonic()+40
while time.monotonic()<deadline:
 current=feed();status=current['status']['containerStatuses'][0]
 if status['restartCount']>old:break
 time.sleep(1)
else:raise AssertionError('no nativefeed exhaustion exit/restart')
results['afterFeedPod']=current;log=command(K+['logs',current['metadata']['name'],'--previous']);(E/'lr02-outage-exhausted-feed.log').write_text(log)
states=[json.loads(l.split(' ',1)[1]) for l in log.splitlines() if l.startswith('FEED_RECOVERY ')];results['exhaustedStates']=[s for s in states if s['state']=='EXHAUSTED'];assert results['exhaustedStates']
terminated=status['lastState'].get('terminated',{});results['actualTermination']=terminated;assert terminated.get('exitCode')==1
oldtarget=results['oldFeedProcess'];inspect=json.loads(command(['docker','exec',oldtarget['node'],'crictl','inspect',oldtarget['containerId']]));results['oldFeedContainerStatus']=inspect['status'];assert inspect['status']['state']=='CONTAINER_EXITED'
command(K+['rollout','status','sts/order-matcher-cluster','--timeout=90s'])
results['verdict']='PASS named established native permanent endpoint outage exceedingbudget:3actualmemberprocessesT-held40s, nativeEXHAUSTED/exit1/oldcontainerEXITED; alloriginalmemberPIDsresumed, replacementfeedpreservedbykubelet. No managedHA/financial/replay claim.'
(E/'lr02-outage.json').write_text(json.dumps(results,indent=2));print(results['verdict'])
