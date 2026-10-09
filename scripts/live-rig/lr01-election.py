import json,subprocess,urllib.request,urllib.error,time,concurrent.futures,re
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008')
K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout

def call(path,data=None):
 req=urllib.request.Request('http://127.0.0.1:28110'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
 begin=time.monotonic()
 try:
  with urllib.request.urlopen(req,timeout=22) as r:b=r.read().decode();return {'status':r.status,'body':b,'elapsed':time.monotonic()-begin}
 except urllib.error.HTTPError as r:return {'status':r.code,'body':r.read().decode(),'elapsed':time.monotonic()-begin}
def metric(name):
 raw=call('/metrics')['body'];return int(re.search('^'+name+' ([0-9]+)$',raw,re.M)[1])
def refs():
 raw=[command(K+['exec',f'order-matcher-cluster-{m}','--','curl','-sS','--max-time','5','http://localhost:8080/metrics']) for m in range(3)]
 (E/'lr01election-member-last.metrics').write_text('\n'.join(raw))
 return [int(re.search(r'^traderx_cluster_next_order_ref\S* ([0-9]+)$',r,re.M)[1]) for r in raw]
def fault(mode,on):
 command(K+['exec','deploy/cluster-gateway','--',('touch' if on else 'rm'),'/tmp/lr-owner-'+mode])
def order(i):return call('/orders',{'clientOrderId':f'lr01election-{i}','accountId':42422,'ticker':'LRQ0004','side':'Buy','quantity':1,'limitPrice':100})
def waitlog(text):
 for i in range(40):
  log=command(K+['logs','deploy/cluster-gateway']);(E/'lr01election-gateway.log').write_text(log)
  if text in log:return log
  time.sleep(.25)
 raise AssertionError('no boundary witness '+text)
results={}
try:
 results['seed']=call('/seed',{'accountId':42422,'tickers':'LRQ0004','price':100});assert results['seed']['status']==200
 results['baseline']=order('baseline');assert json.loads(results['baseline']['body'])['kind']==1
 results['beforeRefs']=refs()
 health=[json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/health'])) for i in range(3)]
 results['beforeHealth']=health;leaders=[h['memberId'] for h in health if h['role']=='LEADER'];assert len(leaders)==1;leader=leaders[0]
 (E/'lr01-election-old-member.log').write_text(command(K+['logs',f'order-matcher-cluster-{leader}']))
 fault('before',True);waitlog('LR-OWNER-BEFORE')
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
  jobs=[pool.submit(order,f'pending-{i}') for i in range(8)]
  time.sleep(1);results['pendingDepth']=metric('traderx_gateway_inflight_orders');assert results['pendingDepth']==8
  results['deletedLeader']=leader
  command(K+['delete','pod',f'order-matcher-cluster-{leader}','--grace-period=0','--force','--wait=false'])
  results['timeouts']=[j.result() for j in jobs]
 assert all(r['status']==504 for r in results['timeouts'])
 log=waitlog('LR-OWNER-RETIRED');results['retirements']=log.count('LR-OWNER-RETIRED');assert results['retirements']==8
 results['depthAfterRetire']=metric('traderx_gateway_inflight_orders');assert results['depthAfterRetire']==0
 command(K+['rollout','status','sts/order-matcher-cluster','--timeout=90s'])
 fault('before',False);time.sleep(1)
 results['afterRefs']=refs();assert results['afterRefs']==results['beforeRefs']
 results['afterHealth']=[json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/health'])) for i in range(3)]
 newleaders=[h['memberId'] for h in results['afterHealth'] if h['role']=='LEADER'];assert len(newleaders)==1 and newleaders[0]!=leader
 results['healthyRecovery']=[order(f'recovery-{i}') for i in range(8)]
 assert all(json.loads(r['body']).get('kind')==1 for r in results['healthyRecovery'])
 results['afterHealthyRefs']=refs();assert all(v==results['beforeRefs'][0]+8 for v in results['afterHealthyRefs'])
 results['verdict']='PASS named actual election during queued work; retirement8, no applied orderrefs, all permits recover and8 later nativecommits. Begun/offered election uncertainty andbinary/interruption notclaimed.'
finally:
 (E/'lr01-election.json').write_text(json.dumps(results,indent=2))
 command(K+['exec','deploy/cluster-gateway','--','rm','-f','/tmp/lr-owner-before'])
 (E/'lr01-election-gateway.log').write_text(command(K+['logs','deploy/cluster-gateway']))
print(results.get('verdict',results))
