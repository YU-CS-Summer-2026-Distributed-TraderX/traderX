import json,urllib.request,urllib.error,subprocess,time,re
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=35);assert r.returncode==0,(args,r.stderr);return r.stdout
def call(port,path,data=None,headers=None):
 req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json',**(headers or {})})
 try:
  with urllib.request.urlopen(req,timeout=30) as r:return r.status,r.read().decode()
 except urllib.error.HTTPError as r:return r.code,r.read().decode()
def sql(query):return command(K+['exec','deploy/eod-price-db','-c','mariadb','--','mariadb','-utraderx','-ptraderx','traderx','-sN','-e',query]).strip()
def sample(n):
 status,raw=call(28191,'/actuator/prometheus');assert status==200;(E/f'lr06-{n}-projector.metrics').write_text(raw)
 heap=sum(float(line.split()[-1]) for line in raw.splitlines() if line.startswith('jvm_memory_used_bytes{area="heap"'))
 gc=sum(float(line.split()[-1]) for line in raw.splitlines() if line.startswith('jvm_gc_pause_seconds_count{'))
 rss={}
 for role,pod in [('projector','deploy/trade-processor')]+[(f'member-{i}',f'order-matcher-cluster-{i}') for i in range(3)]:
  value=command(K+['exec',pod,'--','cat','/proc/1/status']);(E/f'lr06-{n}-{role}.proc').write_text(value);rss[role]=int(re.search(r'VmRSS:\s+(\d+) kB',value)[1])*1024
 status,recon=call(28191,'/recon/status');assert status==200
 return {'population':n,'observedEpochMs':int(time.time()*1000),'projectorHeapUsedBytes':heap,'projectorGcPauseCount':gc,'rssBytes':rss,'recon':json.loads(recon),'sqlFixtureTrades':int(sql("SELECT COUNT(*) FROM trades WHERE security LIKE 'LRM%';"))}
results={'scope':'bounded synthetic ordinary/native 3member engine+actualSQL/JDBC projector; background publishers active; point-sample heap/RSS/GC notexclusive retained attribution','stages':[]}
try:
 results['before']=sample(0)
 count=0
 for stage,groups in enumerate((2,8,24)):
  ticker=f'LRM000{stage}'
  for account in (42422,22214):
   status,body=call(28110,'/seed',{'accountId':account,'tickers':ticker,'price':100});assert status==200
  acks=[]
  for i in range(groups):
   for side,account in [('Buy',42422),('Sell',22214)]:
    status,body=call(28110,'/orders',{'clientOrderId':f'lr06-{stage}-{i}-{side}','accountId':account,'ticker':ticker,'side':side,'quantity':1,'limitPrice':100});acks.append({'status':status,'body':json.loads(body)});assert status==200 and json.loads(body).get('kind')==1
  count+=groups*2
  for _ in range(25):
   s=sample(count)
   if s['sqlFixtureTrades']==count:break
   time.sleep(1)
  assert s['sqlFixtureTrades']==count, s
  results['stages'].append({'groups':groups,'ticker':ticker,'acks':acks,'sample':s});(E/'lr06.json').write_text(json.dumps(results,indent=2))
 status,token=call(28191,'/auth/dev-token',{'subject':'lr06-owned-fixture','accounts':[],'admin':True,'ttlSeconds':600},{'X-Auth-Master-Secret':'kind-local-dev-token-secret-not-a-real-credential'});assert status==200
 headers={'Authorization':'Bearer '+token}
 status,body=call(28191,'/recon/orphan-sweep',{},headers);results['orphanSweep']={'status':status,'body':body}
 # Missing supported managed run or legacy full-history prevents broad acceptance, never empty success.
 status,body=call(28191,'/recon/orphan-sweep/last',headers=headers);results['savedReport']={'status':status,'body':body}
 status,body=call(28191,'/recon/orphan-sweep',{});results['unauthorizedControl']={'status':status,'body':body};assert status==401
 results['after']=sample(count)
 results['verdict']='PARTIAL actual workload:68 attributable synthetic SQL trades, native engine/SQL projector heap,RSS,GC/backlog point samples; managed-scoped full history/repeated report readiness recorded separately, no production sizing or riskjob correlation'
finally:(E/'lr06.json').write_text(json.dumps(results,indent=2))
print(json.dumps({k:v for k,v in results.items() if k!='stages'},indent=2))
