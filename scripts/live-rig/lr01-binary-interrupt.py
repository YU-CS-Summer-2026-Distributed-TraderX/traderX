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
 (E/'lr01binary-member-last.metrics').write_text('\n'.join(raw))
 return [int(re.search(r'^traderx_cluster_next_order_ref\S* ([0-9]+)$',r,re.M)[1]) for r in raw]
def fault(mode,on):
 command(K+['exec','deploy/cluster-gateway','--',('touch' if on else 'rm'),'/tmp/lr-owner-'+mode])
def order(i):return call('/orders',{'clientOrderId':f'lr01binary-{i}','accountId':42422,'ticker':'LRQ0005','side':'Buy','quantity':1,'limitPrice':100})
def waitlog(text):
 for i in range(40):
  log=command(K+['logs','deploy/cluster-gateway']);(E/'lr01binary-gateway.log').write_text(log)
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
 assert call('/seed',{'accountId':42422,'tickers':'LRQ0005','price':100})['status']==200
 resolved=call('/resolve',{'ticker':'LRQ0005'});assert resolved['status']==200;security=json.loads(resolved['body'])['securityId'];results['resolved']=resolved
 results['baseline']=binary(51000001,security);assert results['baseline']['status']==1 and results['baseline']['clientKey']==51000001
 results['malformedControl']=binary(0,security,255);assert results['malformedControl']['status']==3
 results['beforeRefs']=refs();fault('before',True);waitlog('LR-OWNER-BEFORE')
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
  jobs=[pool.submit(binary,51000100+i,security) for i in range(8)]
  time.sleep(1);results['binaryPendingDepth']=metric('traderx_gateway_inflight_orders');assert results['binaryPendingDepth']==8
  results['binaryTimeouts']=[j.result() for j in jobs]
 assert all(r['status']==2 and r['clientKey']==51000100+i for i,r in enumerate(results['binaryTimeouts']))
 log=waitlog('cause=TimeoutException');results['binaryRetirements']=log.count('cause=TimeoutException');assert results['binaryRetirements']==8
 assert metric('traderx_gateway_inflight_orders')==0
 fault('before',False);time.sleep(1);results['afterBinaryRetirementRefs']=refs();assert results['afterBinaryRetirementRefs']==results['beforeRefs']
 results['binaryHealthy']=[binary(51000200+i,security) for i in range(8)];assert all(r['status']==1 for r in results['binaryHealthy'])
 results['afterBinaryHealthyRefs']=refs();assert all(v==results['beforeRefs'][0]+8 for v in results['afterBinaryHealthyRefs'])
 fault('before',True);waitlog('LR-OWNER-BEFORE');command(K+['exec','deploy/cluster-gateway','--','touch','/tmp/lr-interrupt-waiters'])
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results['interruptedHttp']=list(pool.map(order,[f'interrupt-{i}' for i in range(8)]))
 assert all(r['status']==504 and r['elapsed']<5 for r in results['interruptedHttp'])
 log=waitlog('cause=InterruptedException');results['interruptWitnesses']=log.count('LR-WAITER-INTERRUPT');results['interruptedRetirements']=log.count('cause=InterruptedException');assert results['interruptWitnesses']==8 and results['interruptedRetirements']==8
 results['interruptDepth']=metric('traderx_gateway_inflight_orders');assert results['interruptDepth']==0
 command(K+['exec','deploy/cluster-gateway','--','rm','/tmp/lr-interrupt-waiters']);fault('before',False);time.sleep(1)
 results['afterInterruptedRefs']=refs();assert results['afterInterruptedRefs']==results['afterBinaryHealthyRefs']
 results['finalHealthy']=[order(f'final-{i}') for i in range(8)];assert all(json.loads(r['body']).get('kind')==1 for r in results['finalHealthy'])
 results['finalRefs']=refs();assert all(v==results['beforeRefs'][0]+16 for v in results['finalRefs'])
 results['finalDepth']=metric('traderx_gateway_inflight_orders');assert results['finalDepth']==0
 results['verdict']='PASS named actual binary/HTTP interruption milestone withdistinct faultimage:8binaryambiguousacks/retirements/no execution,8real waiterInterruptedException retirements/noexecution; both recover8healthycapacity, malformedframe protocolerrorcontrol. Instrumentedlocalprofile only.'
finally:
 (E/'lr01-binary-interrupt.json').write_text(json.dumps(results,indent=2))
 command(K+['exec','deploy/cluster-gateway','--','rm','-f','/tmp/lr-owner-before','/tmp/lr-interrupt-waiters'])
 (E/'lr01-binary-interrupt-gateway.log').write_text(command(K+['logs','deploy/cluster-gateway']))
print(results.get('verdict',results))
