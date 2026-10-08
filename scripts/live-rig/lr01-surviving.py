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
 (E/'lr01v2-member-last.metrics').write_text('\n'.join(raw))
 return [int(re.search(r'^traderx_cluster_next_order_ref\S* ([0-9]+)$',r,re.M)[1]) for r in raw]
def fault(mode,on):
 command(K+['exec','deploy/cluster-gateway','--',('touch' if on else 'rm'),'/tmp/lr-owner-'+mode])
def order(i):return call('/orders',{'clientOrderId':f'lr01v2-{i}','accountId':42422,'ticker':'LRQ0002','side':'Buy','quantity':1,'limitPrice':100})
def waitlog(text):
 for i in range(40):
  log=command(K+['logs','deploy/cluster-gateway']);(E/'lr01v2-gateway.log').write_text(log)
  if text in log:return log
  time.sleep(.25)
 raise AssertionError('no boundary witness '+text)
results={}
def save(): (E/'lr01v2.json').write_text(json.dumps(results,indent=2))
try:
 results['seed']=call('/seed',{'accountId':42422,'tickers':'LRQ0002','price':100});assert results['seed']['status']==200
 results['baseline']=order('baseline');assert json.loads(results['baseline']['body'])['kind']==1
 results['initialRefs']=refs();assert len(set(results['initialRefs']))==1
 fault('before',True);waitlog('LR-OWNER-BEFORE')
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
  jobs=[pool.submit(order,f'retire-{i}') for i in range(8)]
  time.sleep(1);results['pausedDepth']=metric('traderx_gateway_inflight_orders');assert results['pausedDepth']==8
  results['timeouts']=[j.result() for j in jobs]
 assert all(r['status']==504 for r in results['timeouts']),results
 log=waitlog('LR-OWNER-RETIRED');results['retirementWitnesses']=log.count('LR-OWNER-RETIRED');assert results['retirementWitnesses']==8
 results['retiredDepth']=metric('traderx_gateway_inflight_orders');assert results['retiredDepth']==0
 results['whilePausedRefs']=refs();assert results['whilePausedRefs']==results['initialRefs']
 fault('before',False);waitlog('LR-OWNER-RESUMED');time.sleep(1)
 results['afterRetirementRefs']=refs();assert results['afterRetirementRefs']==results['initialRefs']
 results['capacityRecovery']=[order(f'recovery-{i}') for i in range(8)]
 assert all(json.loads(r['body']).get('kind')==1 for r in results['capacityRecovery']),results
 results['afterRecoveryRefs']=refs();assert all(v==results['initialRefs'][0]+8 for v in results['afterRecoveryRefs'])
 fault('after',True)
 with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
  future=pool.submit(order,'start-wins');waitlog('LR-OWNER-STARTED');results['startWinnerTimeout']=future.result()
 assert results['startWinnerTimeout']['status']==504
 results['startedDepthBeforeRelease']=metric('traderx_gateway_inflight_orders');assert results['startedDepthBeforeRelease']==1
 fault('after',False);waitlog('LR-OWNER-START-RESUMED');time.sleep(1)
 results['startWinnerRefs']=refs();assert all(v==results['afterRecoveryRefs'][0]+1 for v in results['startWinnerRefs'])
 results['finalDepth']=metric('traderx_gateway_inflight_orders');assert results['finalDepth']==0
 results['verdict']='pass for instrumented native gateway owner-boundary timeout/retirement and start-winning uncertainty; surviving native session; election/binary/interruption unmeasured'
finally:
 save()
 for mode in ('before','after'):
  try: command(K+['exec','deploy/cluster-gateway','--','rm','-f','/tmp/lr-owner-'+mode])
  except Exception:pass
print(json.dumps(results,indent=2))
