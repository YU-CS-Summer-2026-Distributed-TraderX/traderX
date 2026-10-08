# Same surviving-session gate; deliberately broken actual retirement claim is the sole guard difference.
import json,subprocess,urllib.request,urllib.error,time,concurrent.futures,re
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def call(path,data=None):
 req=urllib.request.Request('http://127.0.0.1:28110'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=22) as r:return {'status':r.status,'body':r.read().decode()}
 except urllib.error.HTTPError as r:return {'status':r.code,'body':r.read().decode()}
def metric(name):return int(re.search('^'+name+' ([0-9]+)$',call('/metrics')['body'],re.M)[1])
def refs():return [int(re.search(r'^traderx_cluster_next_order_ref\S* ([0-9]+)$',command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/metrics']),re.M)[1]) for i in range(3)]
def order(i):return call('/orders',{'clientOrderId':f'lr01bad-{i}','accountId':42422,'ticker':'LRQ0003','side':'Buy','quantity':1,'limitPrice':100})
results={}
try:
 assert call('/seed',{'accountId':42422,'tickers':'LRQ0003','price':100})['status']==200
 results['baseline']=order('baseline');assert json.loads(results['baseline']['body'])['kind']==1
 results['beforeRefs']=refs();command(K+['exec','deploy/cluster-gateway','--','touch','/tmp/lr-owner-before'])
 time.sleep(.5)
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results['timeouts']=list(pool.map(order,range(8)))
 assert all(r['status']==504 for r in results['timeouts'])
 results['depthAfterTimeout']=metric('traderx_gateway_inflight_orders');assert results['depthAfterTimeout']==8
 command(K+['exec','deploy/cluster-gateway','--','rm','/tmp/lr-owner-before']);time.sleep(1)
 results['afterRefs']=refs();assert all(v==results['beforeRefs'][0]+8 for v in results['afterRefs'])
 results['depthAfterApply']=metric('traderx_gateway_inflight_orders');assert results['depthAfterApply']==0
 results['verdict']='NEGATIVE CONTROL discriminates actual retirement omission: caller504 yet8 nativecommits after gate release; retirement/depth/nonexecution assertion must fail, session alive.'
finally:
 (E/'lr01-omission.json').write_text(json.dumps(results,indent=2))
 command(K+['exec','deploy/cluster-gateway','--','rm','-f','/tmp/lr-owner-before'])
 (E/'lr01-omission-gateway.log').write_text(command(K+['logs','deploy/cluster-gateway']))
print(json.dumps(results,indent=2))
