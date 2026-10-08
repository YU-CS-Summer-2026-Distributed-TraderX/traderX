import json,urllib.request,hashlib,subprocess
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008')
rows=json.loads((E/'lr01-actual-audit.json').read_text());assert rows
accepted=[r for r in rows if r['kind']=='ORDER_ACCEPTED']
report={'auditRowCount':len(rows),'auditRawSha256':hashlib.sha256((E/'lr01-actual-audit.json').read_bytes()).hexdigest(),'cases':{},'keyLimit':'Legacy accepted AuditRow omits clientOrderKey/requestId. Actual binary ACK echoes key->ref; HTTP responses map submitted key->ref. Direct abandoned-key absence cannot be read from this route; no new event/wire/core fields added.'}
def refs_http(receipts):return [json.loads(r['body'])['orderRef'] for r in receipts]
cases={}
d=json.loads((E/'lr01v2.json').read_text());cases['surviving-session']={'ticker':'LRQ0002','expectedRefs':[json.loads(d['baseline']['body'])['orderRef']]+refs_http(d['capacityRecovery'])+[d['startWinnerRefs'][0]-1],'abandonedRequestCount':8,'startedUncertainRef':d['startWinnerRefs'][0]-1}
d=json.loads((E/'lr01-election.json').read_text());cases['election-queued']={'ticker':'LRQ0004','expectedRefs':[json.loads(d['baseline']['body'])['orderRef']]+refs_http(d['healthyRecovery']),'abandonedRequestCount':8}
d=json.loads((E/'lr01-binary-interrupt.json').read_text());cases['binary-interrupt']={'ticker':'LRQ0005','expectedRefs':[d['baseline']['orderRef']]+[a['orderRef'] for a in d['binaryHealthy']]+refs_http(d['finalHealthy']),'abandonedRequestCount':16,'actualBinaryKeyRefPairs':[{k:a[k] for k in ('clientKey','orderRef')} for a in [d['baseline']]+d['binaryHealthy']]}
d=json.loads((E/'lr01-saturation.json').read_text());cases['saturation']={'ticker':'LRQ0006','expectedRefs':[d['baseline']['orderRef']]+[a['orderRef'] for a in d['healthyRecovery']],'abandonedRequestCount':9,'actualBinaryKeyRefPairs':[{k:a[k] for k in ('clientKey','orderRef')} for a in [d['baseline']]+d['healthyRecovery']]}
K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
query="SELECT JSON_ARRAYAGG(JSON_OBJECT('id',orderid,'accountId',accountid,'security',security,'side',side,'quantity',quantity,'price',limitprice,'status',status,'projectionScope',projectionscope)) FROM orderbook WHERE accountid=42422 AND security LIKE 'LRQ%';"
r=subprocess.run(K+['exec','deploy/eod-price-db','-c','mariadb','--','mariadb','-utraderx','-ptraderx','traderx','-sN','-e',query],capture_output=True,timeout=30);assert r.returncode==0,r.stderr;sql_raw=r.stdout.strip()
report['readModelRouteLimit']='Console public account-order proxy correctly403; existing explicit-owned SQL read used. No admin/Desk auth bypass or route/source changes.'
(E/'lr01-order-readmodel.json').write_bytes(sql_raw);sql=json.loads(sql_raw);assert sql
for name,c in cases.items():
 actual=[r for r in accepted if r['security']==c['ticker']];assert actual and all(r['accountId']==42422 and r['side']=='Buy' and r['quantity']==1 and r['price']==100 for r in actual)
 got=[int(r['orderId'].rsplit('-',1)[1]) for r in actual];assert sorted(got)==sorted(c['expectedRefs']),(name,got,c['expectedRefs'])
 projected=[r for r in sql if r['security']==c['ticker']];assert sorted(r['id'] for r in projected)==sorted(r['orderId'] for r in actual),(name,projected,actual)
 c['actualAuditRows']=actual;c['actualProjectedOrders']=projected;c['verdict']='Exact expected distinct committedrefs/account/ticker/economics match actualaudit andSQLreadmodel; noextra acceptedrows for abandonedwork in this sole-writer fixture scope.';report['cases'][name]=c
# Actual retirement omission deliberately produces extra acknowledged-late orders, so emptiness/count-zero cannot pass.
bad=json.loads((E/'lr01-omission.json').read_text());negative=[r for r in accepted if r['security']=='LRQ0003'];assert len(negative)==9
report['omissionNegativeControl']={'ticker':'LRQ0003','actualAcceptedRows':negative,'lateRefs':list(range(bad['beforeRefs'][0],bad['beforeRefs'][0]+8))};assert set(report['omissionNegativeControl']['lateRefs']).issubset({int(r['orderId'].rsplit('-',1)[1]) for r in negative})
(E/'lr01-identity-reconciliation.json').write_text(json.dumps(report,indent=2));print({name:len(c['actualAuditRows']) for name,c in report['cases'].items()})
