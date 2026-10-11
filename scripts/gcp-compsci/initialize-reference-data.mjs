// Explicit cold-demo bootstrap. Run through initialize-reference-data.py, inside the console pod.
import assert from 'node:assert/strict';
const apply=process.argv.includes('--apply');
async function get(path) {
  const r=await fetch(path,{signal:AbortSignal.timeout(20000)});
  assert.equal(r.status,200,`${new URL(path).pathname}: HTTP ${r.status}`);return r.json();
}
async function post(path,body) {
  // Never retry an uncertain mutation. The caller must reconcile recorded intents first.
  const r=await fetch('http://order-matcher:18110'+path,{method:'POST',
    headers:{'Content-Type':'application/json','X-Risk-Control-Token':process.env.RISK_CONTROL_TOKEN,
      'X-Risk-Operator':'compsci-reference-initialization'},body:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
  assert.equal(r.status,200,`${path}: HTTP ${r.status}`);return r.json();
}
async function members(path) {
  return Promise.all([0,1,2].map(i=>get(`http://order-matcher-cluster-${i}.order-matcher-cluster:8080${path}`)));
}
const catalog=await get('http://reference-data:18085/instruments');
const snapshot=await get('http://reference-data:18085/instruments/control-snapshot');
const quotes=(await get('http://price-publisher:18100/prices')).prices;
assert.ok(Array.isArray(catalog)&&catalog.length>0&&Array.isArray(quotes)&&quotes.length>0);
assert.equal(snapshot.count,snapshot.records.length);
assert.deepEqual(catalog.map(x=>x.instrumentKey).sort(),snapshot.records.map(x=>x.ticker).sort());
const securities=new Map(catalog.map(x=>[x.instrumentKey,{ticker:x.instrumentKey,enabled:!x.matured,source:'catalog'}]));
assert.equal(securities.size,catalog.length,'duplicate catalog identity');
const now=Date.now();
for(const quote of quotes) {
  const match=/^[A-Z]{1,6}(\d{2})(\d{2})(\d{2})[CP]\d{8}$/.exec(quote.ticker);
  if(match)securities.set(quote.ticker,{ticker:quote.ticker,enabled:Date.UTC(2000+Number(match[1]),Number(match[2])-1,Number(match[3]),21)>now,source:'publisher-option-chain'});
}
assert.ok(securities.size<1024,'catalog exceeds engine symbol capacity');
assert.ok(securities.has('AMZN'),'Amazon missing from authoritative catalog');
const before=await members('/health');
assert.ok(before.every(m=>m.started&&m.phase===before[0].phase&&m.queueDepth===0),'members not ready in a common empty-queue phase');
console.log(JSON.stringify({phase:'plan',catalogCount:catalog.length,securityCount:securities.size,
  enabled:[...securities.values()].filter(x=>x.enabled).length,disabled:[...securities.values()].filter(x=>!x.enabled),
  catalog,snapshot,quotes,members:before}));
if(apply) {
  assert.ok(process.env.RISK_CONTROL_TOKEN,'missing private control credential');
  for(const security of securities.values()) {
    console.log(JSON.stringify({phase:'intent',...security}));
    const result=await post('/risk/control/security',security);assert.equal(result.applied,true);
    console.log(JSON.stringify({phase:'offered',ticker:security.ticker,result}));
  }
  // Existing member prices are preserved. Only missing demo marks get existing publisher values.
  const books=await members('/bbo');
  for(const quote of quotes) {
    if(!securities.get(quote.ticker)?.enabled)continue;
    if(books.some(m=>m.books.some(b=>b.ticker===quote.ticker && (b.mark>0||b.ref>0))))continue;
    assert.ok(Number.isFinite(quote.price)&&quote.price>0,'invalid publisher price');
    console.log(JSON.stringify({phase:'price-intent',ticker:quote.ticker,price:quote.price,source:quote.source,asOf:quote.asOf}));
    const result=await post('/seed',{accountId:10031,tickers:quote.ticker,price:quote.price});assert.equal(result.seeded,true);
  }
  const current=await members('/health');
  assert.ok(current.every(m=>m.phase===before[0].phase&&m.queueDepth===0),'session changed during initialization');
  // Reapply the SAME phase as a committed acknowledgement fence, without changing market phase.
  const fence=await post('/session',{phase:before[0].phase});assert.ok(Number.isSafeInteger(fence.sequence));
  let after;
  for(let i=0;i<40;i++) {
    after=await members('/health');
    if(after.every(m=>m.applied>=fence.sequence))break;
    await new Promise(r=>setTimeout(r,250));
  }
  assert.ok(after.every(m=>m.started&&m.applied>=fence.sequence),'member has not applied the final initialization fence');
  console.log(JSON.stringify({phase:'committed-fence',fence,members:after}));
}
const controls=await get('http://order-matcher:18110/risk/control/snapshot');
for(const security of securities.values())assert.equal(controls.securities.find(x=>x.ticker===security.ticker)?.enabled,security.enabled,security.ticker);
const books=await members('/bbo');assert.ok(books.every(m=>m.books.some(b=>b.ticker==='AMZN'&&(b.mark>0||b.ref>0))),'Amazon mark missing on a member');
console.log(JSON.stringify({phase:'verified',catalogCount:catalog.length,securityCount:securities.size,
  controls,amazon:books.map(m=>({member:m.member,applied:m.applied,book:m.books.find(b=>b.ticker==='AMZN')})),
  qualification:'Gateway control inventory plus all-member committed fence; not a direct member security-table readout.'}));
