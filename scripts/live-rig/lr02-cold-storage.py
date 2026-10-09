import subprocess,json,urllib.request,time,re,ssl,hashlib
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=40);assert r.returncode==0,(args,r.stderr);return r.stdout
def call(path,data):
 req=urllib.request.Request('http://127.0.0.1:28110'+path,data=json.dumps(data).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=20) as r:assert r.status==200;return json.loads(r.read())
def books():return [json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/bbo'])) for i in range(3)]
results={'scope':'cold newJVM/fresh ownedstorage registration; no populated-cache migration/authenticated run-epoch claim'}
try:
 old=json.loads((E/'epoch1/export-manifest.json').read_text());oldCA=json.loads((E/'epoch1/ca-fingerprint.json').read_text())['sha256Der']
 ns=json.loads(command(K+['get','namespace','traderx','-o','json']));pvc=json.loads(command(K+['get','pvc','-o','json']));ca=json.loads(command(K+['get','configmap','kube-root-ca.crt','-o','json']))['data']['ca.crt'];fingerprint=hashlib.sha256(ssl.PEM_cert_to_DER_cert(ca)).hexdigest()
 nodes=json.loads(command(['docker','inspect']+[f'traderx-lr-20261008-01a11baa-{suffix}' for suffix in ('control-plane','worker','worker2','worker3')]))
 assert ns['metadata']['uid']!=old['namespaceUid'] and fingerprint!=oldCA
 assert {a['pvcUid'] for a in old['archives']}.isdisjoint({p['metadata']['uid'] for p in pvc['items']})
 assert {c['id'] for c in old['nodeContainers']}.isdisjoint({c['Id'] for c in nodes})
 results['physicalStorageIdentity']={'oldNamespaceUid':old['namespaceUid'],'newNamespaceUid':ns['metadata']['uid'],'oldCAFingerprint':oldCA,'newCAFingerprint':fingerprint,'oldNodeIds':[c['id'] for c in old['nodeContainers']],'newNodeIds':[c['Id'] for c in nodes],'newPVCs':[{'name':p['metadata']['name'],'uid':p['metadata']['uid']} for p in pvc['items']]}
 before=[json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/health'])) for i in range(3)];assert all(h['trades']==0 and h['engineApplied']>=0 for h in before);results['beforeMembers']=before
 oldfeed=json.loads((E/'lr02.json').read_text());oldId=int(oldfeed['registration1'][0].split('=')[1]);results['oldSymbolId']=oldId;results['padding']=[]
 # Bounded fixture-only registration makes new-symbol ID differ from the old epoch without assuming boot ordering.
 for i in range(32):
  r=call('/resolve',{'ticker':f'LRPAD{i}'});results['padding'].append(r)
  if r['securityId']>=oldId:break
 else:raise AssertionError('bounded fixture pad could not cross prior symbolId')
 payload=json.dumps({'payload':{'price':200.875}});script="const n=require('nats');(async()=>{const c=await n.connect({servers:'nats://nats:4222'});c.publish('pricing.LRFEED01',new TextEncoder().encode("+json.dumps(payload)+"));await c.flush();await c.close()})().catch(e=>{console.error(e);process.exit(1)});";command(K+['exec','deploy/price-publisher','--','node','-e',script]);results['newUpstreamQuote']=200.875
 deadline=time.monotonic()+45
 while time.monotonic()<deadline:
  b=books();got=[next((r.get('ref') for r in d['books'] if r['ticker']=='LRFEED01'),None) for d in b]
  log=command(K+['logs','deploy/feed-adapter']);match=re.search(r'^SYMBOL LRFEED01=(\d+)$',log,re.M)
  if got==[200.875]*3 and match:break
  time.sleep(1)
 else:raise AssertionError((got,match))
 results['newSymbolId']=int(match[1]);assert results['newSymbolId']!=oldId;results['allMemberBooks']=b;(E/'lr02-cold-storage-feed.log').write_text(log)
 states=[json.loads(l.split(' ',1)[1]) for l in log.splitlines() if l.startswith('FEED_RECOVERY ')];results['newFeedNativeState']=states[-1];assert states[-1]['state']=='CONNECTED'
 results['newFeedPod']=json.loads(command(K+['get','pods','-l','app=feed-adapter','-o','json']))
 oldPod=json.loads((E/'epoch1/pods.json').read_text());oldFeed=next(p for p in oldPod['items'] if p['metadata']['labels'].get('app')=='feed-adapter');assert results['newFeedPod']['items'][0]['metadata']['uid']!=oldFeed['metadata']['uid']
 results['oldFeedPodUid']=oldFeed['metadata']['uid']
 # Independent nonempty actual order->SQL smoke for this cold current stack; no replay of old IDs/requests.
 for account in (42422,22214):assert call('/seed',{'accountId':account,'tickers':'LRCOLD2','price':100})['seeded']
 results['coldTradeAcks']=[]
 for side,account in [('Buy',42422),('Sell',22214)]:
  ack=call('/orders',{'accountId':account,'ticker':'LRCOLD2','side':side,'quantity':1,'limitPrice':100,'clientOrderId':'cold-epoch2-'+side});assert ack['kind']==1;results['coldTradeAcks'].append(ack)
 deadline=time.monotonic()+20
 while time.monotonic()<deadline:
  count=int(command(K+['exec','deploy/eod-price-db','-c','mariadb','--','mariadb','-utraderx','-ptraderx','traderx','-sN','-e',"SELECT COUNT(*) FROM trades WHERE security='LRCOLD2';"]).strip())
  if count==2:break
  time.sleep(.5)
 assert count==2;results['coldSQLTradeCount']=count
 results['verdict']='PASS named cold fresh-storage registration: actualnewnamespace/PVC/node/CA identities, oldSID->differentnewSID andnew200.875 quote all3; actual2newSQLtrades. NewadapterJVM means cached-map survival/remapping remainsunexecuted.'
finally:(E/'lr02-cold-storage.json').write_text(json.dumps(results,indent=2))
print(results.get('verdict',results))
