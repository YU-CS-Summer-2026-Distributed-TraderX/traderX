import json,subprocess,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=40);assert r.returncode==0,(args,r.stderr);return r.stdout
def log():
 value=command(K+['logs','deploy/feed-adapter']);(E/'lr02-leader-feed.log').write_text(value);return value
def health():return [json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','--max-time','5','http://localhost:8080/health'])) for i in range(3)]
def publish(px):
 payload=json.dumps({'payload':{'price':px}})
 script="const n=require('nats');(async()=>{const c=await n.connect({servers:'nats://nats:4222'});c.publish('pricing.LRFEED01',new TextEncoder().encode("+json.dumps(payload)+"));await c.flush();await c.close()})().catch(e=>{console.error(e);process.exit(1)});"
 command(K+['exec','deploy/price-publisher','--','node','-e',script])
def refs():
 raw=[json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','--max-time','5','http://localhost:8080/bbo'])) for i in range(3)]
 return [next((r.get('ref') for r in d['books'] if r['ticker']=='LRFEED01'),None) for d in raw]
results={}
try:
 results['before']=health();leader=[h['memberId'] for h in results['before'] if h['role']=='LEADER'];assert len(leader)==1;leader=leader[0]
 results['leaderPodBefore']=json.loads(command(K+['get','pod',f'order-matcher-cluster-{leader}','-o','json']))
 (E/'lr02-leader-old-member.log').write_text(command(K+['logs',f'order-matcher-cluster-{leader}']))
 results['allMemberImages']=command(K+['get','pods','-l','app=order-matcher-cluster','-o','jsonpath={.items[*].spec.containers[0].image}'])
 command(K+['delete','pod',f'order-matcher-cluster-{leader}','--grace-period=0','--force','--wait=false'])
 command(K+['rollout','status','sts/order-matcher-cluster','--timeout=90s'])
 # The successor leader may be observed before the replacement has replayed; bounded poll, no empty agreement.
 deadline=time.monotonic()+60
 while time.monotonic()<deadline:
  try:
   h=health();leaders=[m['memberId'] for m in h if m['role']=='LEADER']
   if len(leaders)==1 and leaders[0]!=leader:break
  except Exception:pass
  time.sleep(1)
 else:raise AssertionError('no different live leader')
 results['after']=h;assert all(m['trades']>=70 and m['applied']>=0 for m in h)
 results['leaderPodAfter']=json.loads(command(K+['get','pod',f'order-matcher-cluster-{leader}','-o','json']));assert results['leaderPodAfter']['metadata']['uid']!=results['leaderPodBefore']['metadata']['uid']
 publish(103.375);results['newQuote']='new upstreamLRFEED01@103.375 afterdifferentleader'
 deadline=time.monotonic()+45
 while time.monotonic()<deadline:
  prices=refs()
  if prices==[103.375]*3:break
  time.sleep(1)
 else:raise AssertionError(prices)
 results['allMemberReferencePrices']=prices
 results['feedNativeStatus']=json.loads([l.split(' ',1)[1] for l in log().splitlines() if l.startswith('FEED_RECOVERY ')][-1])
 results['verdict']='PASS named accepted-image leader-loss/replacement/native price-sequencing milestone; no managed HA, all-sequence snapshot compatibility or latency claim.'
finally:(E/'lr02-leader.json').write_text(json.dumps(results,indent=2));log()
print(results.get('verdict',results))
