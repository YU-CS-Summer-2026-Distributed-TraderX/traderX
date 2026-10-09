import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {scryptSync} from 'node:crypto';
import {once} from 'node:events';
import {canonicalRequestTarget} from './request-path.mjs';
import {createInternalToken} from './internal-token.mjs';
import {pnlStatus} from './eod-pnl-status.mjs';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const listen=async s=>{s.listen(0,'127.0.0.1');await once(s,'listening');return s.address().port;};
function request(port,path,method='GET',body,headers={}) {
 return new Promise((resolve,reject)=>{
  const r=http.request({host:'127.0.0.1',port,path,method,headers:{'Content-Type':'application/json',...headers}},res=>{
   let data='';res.on('data',x=>data+=x);res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(data),headers:res.headers}));
  });r.on('error',reject);r.end(body===undefined?undefined:JSON.stringify(body));
 });
}
test('canonical target rejects ambiguous syntax and normalizes before routing',()=>{
 assert.equal(canonicalRequestTarget('/trade-processor/%2572econ//orphan-sweep?x=%2f'),'/trade-processor/recon/orphan-sweep?x=%2f');
 assert.equal(canonicalRequestTarget('/legacy/trade-processor/x/%2e%2e/recon/orphan-sweep'),'/legacy/trade-processor/recon/orphan-sweep');
 for(const p of ['/x%3fy','/x%23y','/x%00y','/x;y','/%zz','http://other/x','//other/x'])assert.throws(()=>canonicalRequestTarget(p),p);
});
test('expired credentials are never returned while retrying',async()=>{
 let clock=0,calls=0;
 const token=createInternalToken({mint:async()=>{calls++;if(calls>1)throw Error('offline');return 'a.b.c';},ttlMs:40,retryMs:5,maxRetryMs:10,now:()=>clock,log:()=>{}});
 try {await sleep(5);assert.equal(token.get(),'a.b.c');clock=50;assert.equal(token.get(),'');await sleep(30);assert.ok(calls>=2);assert.equal(token.get(),'');}finally{token.stop();}
});
test('PnL reports partial, halted and unverified coverage without green success',()=>{
 assert.equal(pnlStatus(10,'eod pnl marked accounts=2 halted=2').state,'partial');
 assert.equal(pnlStatus(0,'eod pnl marked accounts=0 halted=2').state,'halted');
 assert.equal(pnlStatus(10,'eod pnl marked accounts=2 halted=0').state,'ok');
 assert.equal(pnlStatus(10,'').state,'unverified');
 assert.equal(pnlStatus(0,'').state,'pending');
 assert.equal(pnlStatus(0,'eod pnl marked accounts=0 halted=0').state,'ok');
});
test('actual HTTP server blocks bypasses, retries initial mint, and retains authorized control',async()=>{
 let mintCalls=0,sweeps=0,fixConnections=0;const upstreamRequests=[];
 const edge=http.createServer((req,res)=>{
  upstreamRequests.push({url:req.url,auth:req.headers.authorization});res.setHeader('Content-Type','application/json');
  if(req.url==='/trade-processor/auth/dev-token') {mintCalls++;res.statusCode=mintCalls===1?502:200;return res.end(mintCalls===1?'{}':'test.admin.signature');}
  if(req.url==='/trade-processor/recon/orphan-sweep'){sweeps++;assert.equal(req.headers.authorization,'Bearer test.admin.signature');}
  res.end(JSON.stringify({ok:true}));
 });
 const fix=net.createServer(s=>{fixConnections++;s.destroy();});
 const probe=net.createServer();const port=await listen(probe);await new Promise(r=>probe.close(r));
 const edgePort=await listen(edge),fixPort=await listen(fix);
 const salt='audit-test-salt',password='audit-only';
 const child=spawn(process.execPath,['server.mjs'],{cwd:new URL('.',import.meta.url),env:{...process.env,PORT:String(port),EDGE_PROXY:`127.0.0.1:${edgePort}`,LOCAL_ONLY:'1',FIX_HOST:'127.0.0.1',FIX_PORT:String(fixPort),AUTH_MASTER_SECRET:'synthetic',AUTH_SESSION_SECRET:'synthetic-session',ADMIN_PASSWORD_HASH:`scrypt$${salt}$${scryptSync(password,salt,64).toString('hex')}`},stdio:['ignore','pipe','pipe']});
 let logs='';child.stdout.on('data',x=>logs+=x);child.stderr.on('data',x=>logs+=x);
 try {
  let ready=false;for(let i=0;i<100;i++){try{await request(port,'/healthz');ready=true;break;}catch{await sleep(20);}}
  assert.ok(ready,logs);
  const unavailable=await request(port,'/trade-processor/recon/status');assert.equal(unavailable.status,503);
  for(let i=0;i<100&&mintCalls<2;i++)await sleep(20);
  assert.ok(mintCalls>=2);await sleep(30);
  assert.equal((await request(port,'/trade-processor/recon/status')).status,200);
  for(const path of ['/fixorder','/%66ixorder','/fixorder/../fixorder']) {
   for(const cookie of ['', 'tx_desk=foreign-workspace'])assert.equal((await request(port,path,'POST',{accountId:65001},{Cookie:cookie})).status,403,path);
  }
  assert.equal(fixConnections,0);
  for(const path of ['/trade-processor/recon/orphan-sweep','/trade-processor/%72econ/orphan-sweep','/trade-processor/%2572econ/orphan-sweep','/trade-processor//recon/orphan-sweep','/legacy/trade-processor/%72econ/orphan-sweep','/trade-processor/recon/orphan-sweep?x=1'])assert.equal((await request(port,path,'POST',{})).status,401,path);
  assert.equal(sweeps,0);
  for(const path of ['/trade-processor/auth/dev-token','/legacy/trade-processor/auth/%64ev-token'])assert.equal((await request(port,path,'POST',{})).status,403,path);
  const login=await request(port,'/auth/login','POST',{user:'admin',password});assert.equal(login.status,200);
  const cookie=login.headers['set-cookie'][0].split(';')[0];
  assert.equal((await request(port,'/trade-processor/%72econ/orphan-sweep','POST',{}, {Cookie:cookie})).status,200);
  assert.equal(sweeps,1);
  assert.equal((await request(port,'/legacy/trade-processor/%72econ/orphan-sweep','POST',{}, {Cookie:cookie})).status,200);
  assert.equal(sweeps,2);
  assert.equal(upstreamRequests.filter(r=>r.url.includes('%')).length,0);
 } finally {child.kill();await once(child,'exit');edge.closeAllConnections();await new Promise(r=>edge.close(r));await new Promise(r=>fix.close(r));}
});
