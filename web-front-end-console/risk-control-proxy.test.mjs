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
  vm.runInNewContext(source.slice(source.indexOf('function proxyToEdge('),source.indexOf('// Demo workspace'))+'\nglobalThis.run=proxyToEdge;',context);
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

test('account creation is server-gated while trading directory reads remain available',async()=>{
  const {edgePath}=await import('./request-path.mjs');
  const context={edgePath};
  vm.runInNewContext(source.slice(source.indexOf('const GATEWAY_ORDER_ROUTE ='),source.indexOf('// Paths the edge proxy'))+'\nglobalThis.needsAuth=needsAuth;',context);
  assert.equal(context.needsAuth('POST','/account-service/account/'),true);
  assert.equal(context.needsAuth('POST','/legacy/account-service/account/'),true);
  assert.equal(context.needsAuth('GET','/account-service/account/'),false);
  assert.equal(context.needsAuth('POST','/order-matcher/risk/control/account'),true);
  assert.equal(context.needsAuth('POST','/order-matcher/orders'),true);   // ENGINE-REVIEW S1
  assert.equal(context.needsAuth('GET','/order-matcher/orders'),false);
});

test('same-origin hosted POST omits Origin on internal hop',()=>{
 const calls=[],replies=[];
 const context={URL,process:{env:{}},edgePath:p=>p,NEEDS_INTERNAL_JWT:()=>true,
 internalToken:{get:()=> 'server-test'},json:(_r,status)=>replies.push(status),EDGE_HOST:'edge',EDGE_PORT:'8080',
 http:{request:o=>{calls.push(o);return {on(){}};}}};
 vm.runInNewContext(source.slice(source.indexOf('function proxyToEdge('),source.indexOf('// Demo workspace'))+'\nglobalThis.run=proxyToEdge;',context);
 context.run({url:'/trade-processor/recon/orphan-sweep',method:'POST',headers:{origin:'https://demo.example',host:'demo.example'},pipe(){}},{});
 assert.equal(calls.length,1);assert.equal(calls[0].headers.origin,undefined);assert.equal(calls[0].headers.authorization,'Bearer server-test');
});
test('foreign origin is refused before privileged forwarding',()=>{
 let forwarded=false;const replies=[];
 const context={URL,json:(_r,status)=>replies.push(status),http:{request:()=>{forwarded=true;}}};
 vm.runInNewContext(source.slice(source.indexOf('function proxyToEdge('),source.indexOf('// Demo workspace'))+'\nglobalThis.run=proxyToEdge;',context);
 context.run({url:'/trade-processor/eod/session/close',method:'POST',headers:{origin:'https://foreign.example',host:'demo.example'},pipe(){}},{});
 assert.equal(forwarded,false);assert.deepEqual(replies,[403]);
});
