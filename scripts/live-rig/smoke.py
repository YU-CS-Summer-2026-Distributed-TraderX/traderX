import json, subprocess, urllib.request, urllib.error, time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');E.mkdir(exist_ok=True)
K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=30);assert r.returncode==0,(args,r.stderr);return r.stdout

def call(path,data=None):
 req=urllib.request.Request('http://127.0.0.1:28110'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=20) as r: return {'status':r.status,'body':json.loads(r.read())}
 except urllib.error.HTTPError as r:return {'status':r.code,'body':json.loads(r.read())}
results={'health':call('/health')}
for account in [42422,22214]:
 results[f'seed-{account}']=call('/seed',{'accountId':account,'tickers':'LRS0001','price':100})
 assert results[f'seed-{account}']['status']==200,results
results['Buy']={'status':200,'body':{'orderRef':1,'kind':1},'source':'first smoke attempt; not resubmitted'}
for side,account in [('Sell',22214)]:
 results[side]=call('/orders',{'clientOrderId':'lr-smoke-'+side,'accountId':account,'ticker':'LRS0001','side':side,'quantity':5,'limitPrice':100})
 assert results[side]['status']==200 and results[side]['body']['kind'] in (1,2),results
for i in range(20):
 sql=command(K+['exec','deploy/eod-price-db','-c','mariadb','--','mariadb','-utraderx','-ptraderx','traderx','-sN','-e',"SELECT COUNT(*) FROM trades WHERE security='LRS0001';"])
 if int(sql.strip())==2:break
 time.sleep(1)
results['sqlTradeCount']=int(sql.strip());assert results['sqlTradeCount']==2,results
results['members']=[json.loads(command(K+['exec',f'order-matcher-cluster-{m}','--','curl','-sS','--max-time','5','http://localhost:8080/health'])) for m in range(3)]
assert all(m['applied']>=0 and m['trades']>=2 for m in results['members']),results
(E/'smoke.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
