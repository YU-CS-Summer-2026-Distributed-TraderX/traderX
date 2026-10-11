// Stream into the deployed console with initialize-accounts.py. Credentials stay in its environment.
import assert from 'node:assert/strict';
const apply = process.argv.includes('--apply');
async function request(url, options = {}) {
  const r = await fetch(url, {...options, signal: AbortSignal.timeout(15000)});
  assert.equal(r.status, 200, `${new URL(url).pathname}: HTTP ${r.status}`);
  return r;
}
const directory = await (await request('http://account-service:18088/account/')).json();
const ids = directory.map(a => a.id).sort((a,b) => a-b);
assert.ok(ids.length > 0 && ids.every(id => Number.isSafeInteger(id) && id > 0));
assert.equal(new Set(ids).size, ids.length, 'duplicate directory account');
const token = (await (await request('http://edge-proxy:8080/trade-processor/auth/dev-token', {
  method: 'POST', headers: {'Content-Type':'application/json', 'x-auth-master-secret':process.env.AUTH_MASTER_SECRET},
  body: JSON.stringify({subject:'compsci-account-initialization',accounts:[],admin:true,ttlSeconds:300})
})).text()).trim();
async function members() {
  return Promise.all([0,1,2].map(async i => {
    const m = await (await request(`http://order-matcher-cluster-${i}.order-matcher-cluster:8080/risk/control/accounts`,
      {headers:{Authorization:`Bearer ${token}`}})).json();
    assert.equal(m.memberId,i); assert.equal(m.available,true);
    assert.equal(m.source,'member-engine-risk-table');
    assert.ok(Array.isArray(m.accounts));
    assert.equal(m.count,m.accounts.length);
    assert.equal(new Set(m.accounts.map(a=>a.accountId)).size,m.count);
    return m;
  }));
}
const before = await members();
// Bootstrap adds absent accounts, never silently reverses an operator suspension.
const disabled = ids.filter(id=>before.some(m=>m.accounts.some(a=>a.accountId===id && !a.enabled)));
assert.deepEqual(disabled, [], `Explicitly disabled accounts require operator review: ${disabled}`);
const missing = ids.filter(id=>before.some(m=>!m.accounts.some(a=>a.accountId===id && a.enabled)));
console.log(JSON.stringify({phase:'before',directoryIds:ids,missing,members:before}));
if (apply) {
  assert.ok(process.env.RISK_CONTROL_TOKEN, 'missing server control credential');
  for (const accountId of missing) {
    // One submission only. On ambiguous failure, stop and reconcile through GET before any retry.
    console.log(JSON.stringify({phase:'intent',accountId}));
    const result = await (await request('http://order-matcher:18110/risk/control/account', {
      method:'POST',headers:{'Content-Type':'application/json','X-Risk-Control-Token':process.env.RISK_CONTROL_TOKEN,
        'X-Risk-Operator':'compsci-demo-initialization'},body:JSON.stringify({accountId,enabled:true})
    })).json();
    assert.equal(result.applied,true);console.log(JSON.stringify({phase:'applied',accountId,result}));
  }
}
let after;
for(let attempt=0;attempt<20;attempt++) {
  after=await members();
  if(after.every(m=>ids.every(id=>m.accounts.some(a=>a.accountId===id && a.enabled)))) break;
  await new Promise(resolve=>setTimeout(resolve,500));
}
assert.ok(after.every(m=>ids.every(id=>m.accounts.some(a=>a.accountId===id && a.enabled))), 'not all directory accounts enabled on every member');
console.log(JSON.stringify({phase:'verified',directoryIds:ids,count:ids.length,members:after,
  sampling:'sequential observations; not an atomic quorum cut'}));
