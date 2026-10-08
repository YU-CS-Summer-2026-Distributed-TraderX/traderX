import json,subprocess,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def pod():return json.loads(command(K+['get','pods','-l','app=feed-adapter','-o','json']))['items'][0]
def state():
 log=command(K+['logs','deploy/feed-adapter']);(E/'lr02-transient-feed-last.log').write_text(log);return json.loads([l.split(' ',1)[1] for l in log.splitlines() if l.startswith('FEED_RECOVERY ')][-1])
def target(p):
 node=p['spec']['nodeName'];assert node.startswith('traderx-lr-20261008-01a11baa-');cid=p['status']['containerStatuses'][0]['containerID'].split('://')[1];pid=int(json.loads(command(['docker','exec',node,'crictl','inspect',cid]))['info']['pid']);assert pid>1;return {'node':node,'containerId':cid,'pid':pid}
def threads():
 p=pod()['metadata']['name'];out=command(K+['exec',p,'--','sh','-c','for x in /proc/1/task/*; do printf "%s " "${x##*/}"; cat "$x/comm"; done']);fds=command(K+['exec',p,'--','sh','-c','ls -l /proc/1/fd']);return {'threads':[line.split(' ',1) for line in out.splitlines()],'fdsRaw':fds,'fdLines':len(fds.splitlines())}
def publish(price):
 payload=json.dumps({'payload':{'price':price}});script="const n=require('nats');(async()=>{const c=await n.connect({servers:'nats://nats:4222'});c.publish('pricing.LRFEED01',new TextEncoder().encode("+json.dumps(payload)+"));await c.flush();await c.close()})().catch(e=>{console.error(e);process.exit(1)});";command(K+['exec','deploy/price-publisher','--','node','-e',script])
def awaitprice(px):
 deadline=time.monotonic()+45
 while time.monotonic()<deadline:
  raw=[json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/bbo'])) for i in range(3)]
  got=[next((r.get('ref') for r in d['books'] if r['ticker']=='LRFEED01'),None) for d in raw]
  if got==[px]*3:return raw
  time.sleep(1)
 raise AssertionError(got)
results={};held=[]
try:
 results['before']=state();results['beforeFeedPod']=pod();assert results['before']['state']=='CONNECTED'
 members=json.loads(command(K+['get','pods','-l','app=order-matcher-cluster','-o','json']))['items'];assert len(members)==3
 results['targets']=[]
 for m in members:
  t=target(m);t['pod']=m['metadata']['name'];held.append(t);results['targets'].append(t);command(['docker','exec',t['node'],'kill','-STOP',str(t['pid'])]);raw=command(['docker','exec',t['node'],'cat',f"/proc/{t['pid']}/status"]);t['stoppedState']=raw.splitlines()[:4];assert 'State:\tT' in raw
 results['allEndpointsHeldEpochMs']=int(time.time()*1000);time.sleep(8)
finally:
 cleanup=[]
 for t in held:
  command(['docker','exec',t['node'],'kill','-CONT',str(t['pid'])]);cleanup.append({'pod':t['pod'],'resumedSamePID':t['pid']})
 results['cleanup']=cleanup;(E/'lr02-transient.json').write_text(json.dumps(results,indent=2))
deadline=time.monotonic()+35
while time.monotonic()<deadline:
 after=state()
 if after['state']=='CONNECTED' and after['sessionGeneration']>results['before']['sessionGeneration']:break
 time.sleep(.5)
else:raise AssertionError(after)
results['after']=after;results['afterFeedPod']=pod();assert results['afterFeedPod']['status']['containerStatuses'][0]['containerID']==results['beforeFeedPod']['status']['containerStatuses'][0]['containerID']
publish(107.875);results['newUpstreamQuote']=107.875;results['allMemberBooks']=awaitprice(107.875)
results['verdict']='PASS named actual transientendpointoutage:3nativeingress processesstateT-held8s, sameadapterprocessnative reconnect/generationadvance beforebudgetexhaustion, new107.875 upstreamquote appliedall3; all3samePID endpointsrestored. No source/financial/latency change.'
(E/'lr02-transient.json').write_text(json.dumps(results,indent=2));(E/'lr02-transient-feed-final.log').write_text(command(K+['logs','deploy/feed-adapter']));print(results['verdict'])
