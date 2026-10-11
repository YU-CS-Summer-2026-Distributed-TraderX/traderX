import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('./server.mjs',import.meta.url),'utf8');
function harness(signedIn,secret) {
  const calls=[]; const replies=[];
  const context={process:{env:{RISK_CONTROL_TOKEN:secret}},edgePath:p=>p,NEEDS_INTERNAL_JWT:()=>false,
    readToken:()=>signedIn,json:(_res,status,body)=>replies.push({status,body}),EDGE_HOST:'edge',EDGE_PORT:'8080',
    http:{request:options=>{calls.push(options);return {on(){}};}}};
  vm.runInNewContext(source.slice(source.indexOf('function proxyToEdge('),source.indexOf('// Local desk stores'))+'\nglobalThis.run=proxyToEdge;',context);
  const req={url:'/order-matcher/risk/control/account',method:'POST',headers:{'x-risk-control-token':'browser-forged','x-risk-operator':'browser'},pipe(){}};
  context.run(req,{});return {calls,replies};
}
test('signed-out account control never reaches upstream',()=>{
  const x=harness(false,'private-test');assert.equal(x.calls.length,0);assert.equal(x.replies[0].status,401);
  assert.equal(x.replies[0].body.code,'admin_auth_required');
});
test('signed-in control replaces browser credentials with server credentials',()=>{
  const x=harness(true,'private-test');assert.equal(x.calls.length,1);
  assert.equal(x.calls[0].headers['x-risk-control-token'],'private-test');
  assert.equal(x.calls[0].headers['x-risk-operator'],'console-admin');
});
test('missing private control credential fails closed',()=>{
  const x=harness(true,undefined);assert.equal(x.calls.length,0);assert.equal(x.replies[0].status,503);
});
