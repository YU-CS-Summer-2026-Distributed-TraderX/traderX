import json,subprocess,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def pod():return json.loads(command(K+['get','pods','-l','app=feed-adapter','-o','json']))['items'][0]
def state():
 log=command(K+['logs','deploy/feed-adapter']);(E/'lr02-resources-feed-last.log').write_text(log);return json.loads([l.split(' ',1)[1] for l in log.splitlines() if l.startswith('FEED_RECOVERY ')][-1])
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
results={'cycles':[]};held=None
try:
 results['postOutageNativeState']=state();assert results['postOutageNativeState']['state']=='CONNECTED'
 publish(104.5);results['postOutageNewUpstreamQuote']=104.5;results['postOutageAllMemberBooks']=awaitprice(104.5)
 for index,price in enumerate((105.625,106.75)):
  before=state();p=pod();t=target(p);r={'before':before,'beforeResources':threads(),'target':t};held=t
  command(['docker','exec',t['node'],'kill','-STOP',str(t['pid'])]);raw=command(['docker','exec',t['node'],'cat',f"/proc/{t['pid']}/status"]);r['stoppedState']=raw.splitlines()[:4];assert 'State:\tT' in raw
  time.sleep(20);command(['docker','exec',t['node'],'kill','-CONT',str(t['pid'])]);held=None
  deadline=time.monotonic()+30
  while time.monotonic()<deadline:
   after=state()
   if after['state']=='CONNECTED' and after['sessionGeneration']>before['sessionGeneration']:break
   time.sleep(.5)
  else:raise AssertionError(after)
  r['after']=after;time.sleep(1);r['afterResources']=threads();assert pod()['status']['containerStatuses'][0]['containerID'].endswith(t['containerId'])
  old={tid for tid,name in r['beforeResources']['threads'] if name.startswith('aeron-client')};new={tid for tid,name in r['afterResources']['threads'] if name.startswith('aeron-client')};assert old and new and old.isdisjoint(new),(old,new)
  r['oldClientConductorTids']=sorted(old);r['newClientConductorTids']=sorted(new);r['sameProcessOldClientThreadsGone']=True
  publish(price);r['newUpstreamQuote']=price;r['allMemberBooks']=awaitprice(price);results['cycles'].append(r);(E/'lr02-resources.json').write_text(json.dumps(results,indent=2))
 results['verdict']='PASS named post-outage newquote/repeatednative reconnect cleanup:2sameprocessgeneration transitions, oldaeron-client conductorTIDs absent/newTIDspresent, actualnewprices104.5/105.625/106.75 onall3members. FDcounts descriptive, notfullresource/heap leakproof.'
finally:
 if held:command(['docker','exec',held['node'],'kill','-CONT',str(held['pid'])])
 (E/'lr02-resources.json').write_text(json.dumps(results,indent=2));(E/'lr02-resources-feed-final.log').write_text(command(K+['logs','deploy/feed-adapter']))
print(results.get('verdict',results))
