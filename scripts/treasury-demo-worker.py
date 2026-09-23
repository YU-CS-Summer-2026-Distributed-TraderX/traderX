#!/usr/bin/env python3
"""Maintained local, single-run Treasury bridge. No server-supplied commands or trade parameters."""
import argparse,fcntl,hashlib,io,json,os,subprocess,sys,tarfile,threading,time,urllib.request,urllib.error
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles'))
import bundle,treasury_trade as profile,pricing_result
P=argparse.ArgumentParser();P.add_argument('--state',type=Path,required=True);P.add_argument('--secret-file',type=Path,required=True);P.add_argument('--engine',type=Path,required=True);P.add_argument('--url',default='https://yaakovseif.dev');P.add_argument('--check',action='store_true');args=P.parse_args()
args.state.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(args.state,0o700)
lock=(args.state/'worker.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
SECRET=args.secret_file.read_text().strip();BASE=args.url.rstrip('/');K=['kubectl','--context','gke_traderx-505400_us-east1-b_traderx-bench','-n','traderx']
ENGINE=args.state/'reviewed-engine'
if not ENGINE.exists():
    archive=subprocess.check_output(['git','-C',str(args.engine),'archive',pricing_result.ENGINE_COMMIT]);ENGINE.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(ENGINE,filter='data')
# Operator-selected pin, not authenticated producer attestation. Record archive tree hash privately.
expected=subprocess.check_output(['git','-C',str(args.engine),'archive',pricing_result.ENGINE_COMMIT])
with tarfile.open(fileobj=io.BytesIO(expected)) as t:
    for m in t.getmembers():
        if m.isfile():assert (ENGINE/m.name).read_bytes()==t.extractfile(m).read(),'engine source differs from pin'
def cmd(*a,timeout=45,input=None):return subprocess.check_output(list(a),input=input,timeout=timeout)
def put(p,v):
    data=bundle.encoded(v);tmp=p.with_suffix('.tmp')
    with tmp.open('wb') as f:os.chmod(tmp,0o600);f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(tmp,p);fd=os.open(p.parent,os.O_RDONLY);os.fsync(fd);os.close(fd)
def request(route,data=None,worker=False):
    headers={'Content-Type':'application/json'}
    if worker:headers['Authorization']='Bearer '+SECRET
    req=urllib.request.Request(BASE+route,data=None if data is None else bundle.encoded(data),headers=headers)
    with urllib.request.urlopen(req,timeout=35) as r:return json.loads(r.read(2*1024*1024))
def pulse(update=None):return request('/risk/treasury-demo/worker',{'update':update} if update else {},True)
def kube(*a,input=None):return cmd(*K,*a,input=input)
def positions(account):return request('/position-service/positions/'+account)
def trades(account):return request('/position-service/trades/'+account)
def face(rows):return sum(int(r['quantity']) for r in rows if r['security']==profile.SECURITY)
def check(ok,msg):
    if not ok:raise ValueError(msg)
class Review(Exception):pass

def execute(run):
    work=args.state/run['id'];work.mkdir(exist_ok=True,mode=0o700);jp=work/'journal.json'
    j=json.loads(jp.read_text()) if jp.exists() else {'stage':'QUEUED','actions':{},'proof':{'profile':profile.PROFILE,'security':profile.SECURITY,'account':profile.ACCOUNT,'seller':profile.SELLER,'valuationDate':profile.DAY,'epoch':'2026091601'}}
    def save():put(jp,j)
    def stage(value):
        order=['QUEUED','TRADE_SUBMITTED','TRADE_BOOKED','POSITION_EXPORTED','PRICING','INDEPENDENTLY_CHECKED']
        if order.index(value)<order.index(j['stage']):return
        j['stage']=value;save();pulse({'id':run['id'],'status':'RUNNING','stage':value})
    def action(name,fn):
        old=j['actions'].get(name)
        if old and old.get('done'):return old['result']
        if old:
            # Reconcile read-side evidence; deliberately no blind resend even though core dedup exists.
            put(work/(name+'-reconciliation.json'),{'buyerPositions':positions(profile.ACCOUNT),'sellerPositions':positions(profile.SELLER),'buyerTrades':trades(profile.ACCOUNT),'sellerTrades':trades(profile.SELLER)})
            raise Review('uncertain prior mutation; evidence preserved, no automatic resubmission')
        j['actions'][name]={'intent':True};save();result=fn();j['actions'][name]={'done':True,'result':result};save();return result
    try:
        check(datetime.now(timezone.utc).date().isoformat()==profile.DAY,'demo valuation date is no longer current')
        reference=request('/reference-data/instruments/'+profile.SECURITY);put(work/'reference.json',reference)
        debt=reference['debtEconomics']
        check(reference['instrumentKey']==profile.SECURITY and reference['currency']=='USD' and debt['issueDate']=='2026-08-13' and debt['maturityDate']=='2026-11-12' and debt['zeroCoupon']['couponRatePercent']==0 and debt['principalRepayment']['parAmount']==100,'unsupported reference economics')
        if 'baselineBuyerFace' not in j['proof']:
            bp=positions(profile.ACCOUNT);sp=positions(profile.SELLER)
            check(face(bp)==face(sp)==0,'demo account already holds the bill')
            check(not [t for a in (profile.ACCOUNT,profile.SELLER) for t in trades(a) if t['security']==profile.SECURITY],'existing bill trades require operator review')
            j['proof'].update(baselineBuyerFace='0',baselineSellerFace='0');save()
        quote=request('/price-publisher/prices/'+profile.SECURITY)
        check(quote['priceSemantics']=='CLEAN_FRACTION_OF_PAR' and not quote['matured'] and quote['simulated'],'invalid quote')
        check(abs(time.time()-datetime.fromisoformat(quote['asOf'].replace('Z','+00:00')).timestamp())<30,'stale quote')
        if 'bookedPrice' not in j['proof']:j['proof']['bookedPrice']=str(quote['cleanPrice']);put(work/'quote.json',quote);save()
        price=j['proof']['bookedPrice'];orders={}
        for side,acct in [('Sell',profile.SELLER),('Buy',profile.ACCOUNT)]:
            result=action('order-'+side,lambda side=side,acct=acct:request('/order-matcher/orders',{'clientOrderId':'treasury-demo-'+run['id']+'-'+side,'accountId':int(acct),'ticker':profile.SECURITY,'side':side,'quantity':1000,'limitPrice':float(price)}))
            check(result['kind']==1,'order rejected');orders[side]=result
        j['proof']['orders']=orders;save();stage('TRADE_SUBMITTED')
        for _ in range(40):
            book={}
            for side,acct in [('Buy',profile.ACCOUNT),('Sell',profile.SELLER)]:
                matches=[t for t in trades(acct) if t['sourceOrderId']==j['proof']['epoch']+'-'+str(orders[side]['orderRef']) and t['security']==profile.SECURITY]
                if len(matches)==1:book[side]=matches[0]
            if len(book)==2:break
            time.sleep(1)
        check(len(book)==2,'accepted orders not confirmed booked')
        check(all(t['state'] in ('Processing','Settled') and not t.get('rejectionReason') for t in book.values()),'booking rejected')
        bp=positions(profile.ACCOUNT);sp=positions(profile.SELLER)
        check(face(bp)==1000 and face(sp)==-1000,'position delta mismatch')
        j['proof'].update(trades=book,buyerFace=str(face(bp)),sellerFace=str(face(sp)));save();put(work/'booked-positions.json',{'buyer':bp,'seller':sp});stage('TRADE_BOOKED')
        js="""(async()=>{const b='http://trade-processor:18091';const a=await fetch(b+'/auth/dev-token',{method:'POST',headers:{'Content-Type':'application/json','x-auth-master-secret':process.env.AUTH_MASTER_SECRET},body:JSON.stringify({subject:'treasury-demo',accounts:[],admin:true,ttlSeconds:120}),signal:AbortSignal.timeout(15000)});if(!a.ok)throw Error('auth');const t=(await a.text()).trim();const r=await fetch(b+'/eod/session/close?sessionDate=2026-09-16',{method:'POST',headers:{Authorization:'Bearer '+t},signal:AbortSignal.timeout(30000)});if(!r.ok)throw Error('close');console.log(JSON.stringify(await r.json()));})().catch(()=>process.exit(1))"""
        if 'closeMillis' not in j['proof']:j['proof']['closeMillis']=int(time.time()*1000);save()
        report=action('close',lambda:json.loads(kube('exec','deploy/web-front-end-console','--','node','-e',js)))
        check(report['status']=='PUBLISHED' and report['flaggedCount']==0,'EOD quality gate refused')
        mark=profile.eod_mark(report);j['proof']['eodMark']=mark;save()
        receipt=None
        for _ in range(40):
            raw=kube('exec','deploy/risk-extract','--','sh','-c','for f in /data/risk-extracts/ready/*.ready.json; do cat "$f"; echo; done')
            found=[json.loads(line) for line in raw.decode().splitlines() if line.strip()]
            found=[v for v in found if v['sessionDate']==profile.DAY and v['priceSnapshotVersion']==report['version']]
            if len(found)==1:receipt=found[0];break
            time.sleep(2)
        check(receipt is not None,'export receipt unavailable')
        original=work/'original';original.mkdir(exist_ok=True);put(original/'receipt.json',receipt)
        import re
        for kind,field in [('positions','uri'),('contracts','contractsUri')]:
            uri=receipt[field];check(bool(re.fullmatch(r'gs://traderx-505400-risk-extracts/2026-09-16/v\d+/seq-\d+(?:-contracts)?\.csv',uri)),'unexpected artifact location')
            data=cmd('gcloud','storage','cat',uri);(original/(kind+'.csv')).write_bytes(data)
        j['proof']['exportHashes']={k:bundle.digest((original/(k+'.csv')).read_bytes()) for k in bundle.KINDS};save();put(work/'proof.json',j['proof'])
        destination=work/'bundle'
        if not destination.exists():profile.prepare(original,destination,j['proof'],reference)
        stage('POSITION_EXPORTED');stage('PRICING')
        code="from engine.integration import price_bundle; import json,sys; print(json.dumps(price_bundle(sys.argv[1],market_inputs={'mode':'assumed-profile','assumedProfileId':'flat-3pct-v1'}).to_dict(),sort_keys=True,allow_nan=False))"
        proc=subprocess.run([sys.executable,'-c',code,str(destination)],cwd=ENGINE,env={**os.environ,'PYTHONPATH':str(ENGINE),'PYTHONDONTWRITEBYTECODE':'1'},capture_output=True,timeout=90,check=True)
        data=proc.stdout;(work/'producer-result.json').write_bytes(data);(work/'producer-stderr.txt').write_bytes(proc.stderr)
        result=profile.validate(destination,original,j['proof'],reference,data)
        put(work/'acceptance.json',{'profile':profile.PROFILE,'engineCommit':pricing_result.ENGINE_COMMIT,'executionLocation':'local','resultHash':bundle.digest(data),'bundleId':bundle.validate(destination)[0]['bundleId'],'runId':run['id'],'result':result})
        update={'id':run['id'],'status':'SUCCEEDED','stage':'INDEPENDENTLY_CHECKED','result':result};put(work/'terminal.json',update);pulse(update)
    except Exception as e:
        # An attempted order/close without recorded response is NEVER automatically replayed.
        uncertain=any(not a.get('done') for a in j['actions'].values())
        put(work/'failure.json',{'type':type(e).__name__,'detail':str(e),'uncertain':uncertain})
        if uncertain:
            try:put(work/'failure-reconciliation.json',{'buyer':trades(profile.ACCOUNT),'seller':trades(profile.SELLER)})
            except Exception:pass
        update={'id':run['id'],'status':'NEEDS_REVIEW' if uncertain or isinstance(e,Review) else 'FAILED','stage':j['stage']};put(work/'terminal.json',update);pulse(update)

# Dependency check before advertising availability; no trades or state changes.
subprocess.run([sys.executable,'-c','from engine.integration import price_bundle'],cwd=ENGINE,env={**os.environ,'PYTHONPATH':str(ENGINE),'PYTHONDONTWRITEBYTECODE':'1'},check=True,timeout=60)
if args.check:print('Pinned producer imports; local worker configuration valid.');sys.exit()
active=None
while True:
    try:
        v=pulse();run=v.get('run')
        if run and run['status'] in ('QUEUED','RUNNING') and (active is None or not active.is_alive()):
            terminal=args.state/run['id']/'terminal.json'
            if terminal.exists():pulse(json.loads(terminal.read_text()))
            else:active=threading.Thread(target=execute,args=(run,),daemon=True);active.start()
    except Exception as e:print('Worker connection unavailable: '+type(e).__name__,flush=True)
    time.sleep(5)
