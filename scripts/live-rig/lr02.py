import json,subprocess,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008')
K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def log():
 s=command(K+['logs','deploy/feed-adapter']);(E/'lr02-feed.log').write_text(s);return s
def status():
 rows=[json.loads(line.split(' ',1)[1]) for line in log().splitlines() if line.startswith('FEED_RECOVERY ')]
 assert rows,'missing native FEED_RECOVERY';return rows[-1]
def publish(ticker,price):
 payload=json.dumps({'payload':{'price':price}})
 script="const n=require('nats');(async()=>{const c=await n.connect({servers:'nats://nats:4222'});c.publish("+json.dumps('pricing.'+ticker)+",new TextEncoder().encode("+json.dumps(payload)+"));await c.flush();await c.close();})().catch(e=>{console.error(e);process.exit(1)});"
 return command(K+['exec','deploy/price-publisher','--','node','-e',script])
def books():
 return [json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','--max-time','5','http://localhost:8080/bbo'])) for i in range(3)]
def awaitprice(ticker,px):
 deadline=time.monotonic()+45
 while time.monotonic()<deadline:
  b=books();vals=[next((row.get('ref') for row in m['books'] if row['ticker']==ticker),None) for m in b]
  if vals==[px]*3:return b
  time.sleep(1)
 raise AssertionError((ticker,px,vals))
def awaitconnected(min_generation):
 for _ in range(70):
  s=status()
  if s['state']=='CONNECTED' and s['sessionGeneration']>=min_generation:return s
  time.sleep(1)
 raise AssertionError(s)
results={};pod=None;node=None;pid=None
try:
 results['established']=status();assert results['established']['state']=='CONNECTED'
 results['newQuote1']={'ticker':'LRFEED01','price':101.125,'source':'previous attempt already verified; not republished'}
 results['price1AllMembers']=awaitprice('LRFEED01',101.125)
 assert 'SYMBOL LRFEED01=' in log()
 results['registration1']=[line for line in log().splitlines() if line.startswith('SYMBOL LRFEED01=')]
 pod=command(K+['get','pods','-l','app=feed-adapter','-o','jsonpath={.items[0].metadata.name}']).strip()
 results['feedPod']=pod;results['beforePause']=status()
 p=json.loads(command(K+['get','pod',pod,'-o','json']));node=p['spec']['nodeName']
 assert node.startswith('traderx-lr-20261008-01a11baa-')
 cid=p['status']['containerStatuses'][0]['containerID'].split('://')[1]
 inspected=json.loads(command(['docker','exec',node,'crictl','inspect',cid]));pid=int(inspected['info']['pid']);assert pid>1
 results['nativeFaultTarget']={'node':node,'containerId':cid,'pidInNode':pid}
 command(['docker','exec',node,'kill','-STOP',str(pid)])
 state=command(['docker','exec',node,'cat',f'/proc/{pid}/status']);results['stoppedState']=state.splitlines()[:4];assert 'State:\tT' in state
 time.sleep(20)
 command(['docker','exec',node,'kill','-CONT',str(pid)])
 results['afterRecovery']=awaitconnected(results['beforePause']['sessionGeneration']+1)
 # New upstream bytes after recovery; not a claim already-offered ticks replayed.
 results['newQuote2']={'ticker':'LRFEED01','price':102.25,'publish':publish('LRFEED01',102.25)}
 results['price2AllMembers']=awaitprice('LRFEED01',102.25)
 results['registration2']=[line for line in log().splitlines() if line.startswith('SYMBOL LRFEED01=')];assert len(results['registration2'])>=2
 # bounded valid idle native session: no further fixture quotes, background publisher continues other symbols
 results['idleBegin']=status();time.sleep(6);results['idleEnd']=status()
 assert results['idleEnd']['state']=='CONNECTED' and results['idleEnd']['sessionGeneration']==results['idleBegin']['sessionGeneration']
 results['verdict']='PARTIAL: established native feed, attributable sequenced prices101.125->102.25 on all3members, native session-loss recovery/re-registration and6second fixture-idle control. Leader/endpoint/fresh-epoch/permanent-outage/complete thread cleanup remain open.'
finally:
 (E/'lr02.json').write_text(json.dumps(results,indent=2))
 if node and pid:
  try:command(['docker','exec',node,'kill','-CONT',str(pid)])
  except Exception:pass
 log()
print(json.dumps({k:v for k,v in results.items() if not k.startswith('price')},indent=2))
