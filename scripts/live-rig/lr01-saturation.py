import socket,struct
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
 (E/'lr01saturation-member-last.metrics').write_text('\n'.join(raw))
 return [int(re.search(r'^traderx_cluster_next_order_ref\S* ([0-9]+)$',r,re.M)[1]) for r in raw]
def fault(mode,on):
 command(K+['exec','deploy/cluster-gateway','--',('touch' if on else 'rm'),'/tmp/lr-owner-'+mode])
def order(i):return call('/orders',{'clientOrderId':f'lr01saturation-{i}','accountId':42422,'ticker':'LRQ0006','side':'Buy','quantity':1,'limitPrice':100})
def waitlog(text):
 for i in range(40):
  log=command(K+['logs','deploy/cluster-gateway']);(E/'lr01saturation-gateway.log').write_text(log)
  if text in log:return log
  time.sleep(.25)
 raise AssertionError('no boundary witness '+text)
def binary(key,security,kind=1):
 payload=struct.pack('<BBHIIiqQ',kind,0,0,42422,security,1,100000000,key) if kind==1 else bytes([kind])
 with socket.create_connection(('127.0.0.1',28112),timeout=3) as sock:
  sock.settimeout(22);sock.sendall(struct.pack('<H',len(payload))+payload)
  def read(n):
   raw=b''
   while len(raw)<n:
    chunk=sock.recv(n-len(raw));assert chunk,'closed before completeack';raw+=chunk
   return raw
  length=struct.unpack('<H',read(2))[0];assert length==16
  ack=struct.unpack('<BBBBIQ',read(length));return {'type':ack[0],'status':ack[1],'kind':ack[2],'reason':ack[3],'orderRef':ack[4],'clientKey':ack[5]}
results={}
try:
 assert call('/seed',{'accountId':42422,'tickers':'LRQ0006','price':100})['status']==200
 resolved=call('/resolve',{'ticker':'LRQ0006'});security=json.loads(resolved['body'])['securityId'];results['baseline']=binary(53000001,security);assert results['baseline']['status']==1
 results['beforeRefs']=refs();mark=command(K+['logs','deploy/cluster-gateway']).count('LR-OWNER-BEFORE');fault('before',True)
 for _ in range(40):
  if command(K+['logs','deploy/cluster-gateway']).count('LR-OWNER-BEFORE')>mark:break
  time.sleep(.1)
 else:raise AssertionError('no new held-boundary witness')
 with concurrent.futures.ThreadPoolExecutor(max_workers=9) as pool:
  jobs=[pool.submit(binary,53000100+i,security) for i in range(8)]
  time.sleep(.5);results['fullDepth']=metric('traderx_gateway_inflight_orders');assert results['fullDepth']==8
  began=time.monotonic();ninth=pool.submit(binary,53000200,security);results['saturatedNinth']=ninth.result();results['ninthElapsed']=time.monotonic()-began;assert results['saturatedNinth']['status']==2
  results['depthAfterNinth']=metric('traderx_gateway_inflight_orders');assert results['depthAfterNinth']==8
  results['firstEight']=[j.result() for j in jobs]
 assert all(r['status']==2 for r in results['firstEight'])
 results['afterRetireDepth']=metric('traderx_gateway_inflight_orders');assert results['afterRetireDepth']==0
 log=command(K+['logs','deploy/cluster-gateway']);results['retirements']=sum(('key='+str(53000100+i)+' ') in log for i in range(8));assert results['retirements']==8 and 'key=53000200 ' not in log
 fault('before',False);time.sleep(1);results['afterRefs']=refs();assert results['afterRefs']==results['beforeRefs']
 results['healthyRecovery']=[binary(53000300+i,security) for i in range(8)];assert all(r['status']==1 for r in results['healthyRecovery'])
 results['afterHealthyRefs']=refs();assert all(v==results['beforeRefs'][0]+8 for v in results['afterHealthyRefs'])
 results['finalDepth']=metric('traderx_gateway_inflight_orders');assert results['finalDepth']==0
 results['verdict']='PASS bounded actualbinary saturation: full8 permits, ninthacquire-timeout ambiguouswithoutqueuedretirement/permitrelease, first8queuedretire, no memberexecution, fullhealthyrecovery. No blindretry or SLAclaim.'
finally:
 (E/'lr01-saturation.json').write_text(json.dumps(results,indent=2))
 command(K+['exec','deploy/cluster-gateway','--','rm','-f','/tmp/lr-owner-before'])
 (E/'lr01-saturation-gateway.log').write_text(command(K+['logs','deploy/cluster-gateway']))
print(results.get('verdict',results))
