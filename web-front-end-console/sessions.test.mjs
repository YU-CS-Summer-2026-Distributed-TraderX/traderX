import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {scryptSync} from 'node:crypto';
import {once} from 'node:events';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
// A cookie jar per client: what a separate browser profile holds.
function client(port) {
 const jar=new Map();
 return (path,method='GET',body,extra={})=>new Promise((resolve,reject)=>{
  const Cookie=[...jar].map(([k,v])=>`${k}=${v}`).join('; ');
  const r=http.request({host:'127.0.0.1',port,path,method,headers:{'Content-Type':'application/json',...(Cookie?{Cookie}:{}),...extra}},res=>{
   for(const c of res.headers['set-cookie']??[]){const [kv]=c.split(';');const i=kv.indexOf('=');const v=kv.slice(i+1);v?jar.set(kv.slice(0,i),v):jar.delete(kv.slice(0,i));}
   let data='';res.on('data',x=>data+=x);res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(data),headers:res.headers}));
  });r.on('error',reject);r.end(body===undefined?undefined:JSON.stringify(body));
 });
}
const salt='session-test-salt',password='session-only';
async function start(env={}) {
 const probe=http.createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(r=>probe.close(r));
 const child=spawn(process.execPath,['server.mjs'],{cwd:new URL('.',import.meta.url),env:{...process.env,PORT:String(port),EDGE_PROXY:'127.0.0.1:1',LOCAL_ONLY:'1',DESK_DEMO_PROFILE:'1',DESK_USERS_FILE:'',AUTH_SESSION_SECRET:'synthetic-session',ADMIN_PASSWORD_HASH:`scrypt$${salt}$${scryptSync(password,salt,64).toString('hex')}`,...env},stdio:['ignore','pipe','pipe']});
 let logs='';child.stdout.on('data',x=>logs+=x);child.stderr.on('data',x=>logs+=x);
 const probeClient=client(port);
 let ready=false;for(let i=0;i<100;i++){try{await probeClient('/healthz');ready=true;break;}catch{await sleep(20);}}
 assert.ok(ready,logs);
 return {port,stop:async()=>{child.kill();await once(child,'exit');}};
}
test('two cookie jars get independent console and desk sessions',async()=>{
 const {port,stop}=await start();
 try {
  const a=client(port),b=client(port);
  // Console: same admin password, two logins, two sessions.
  const la=await a('/auth/login','POST',{user:'admin',password}),lb=await b('/auth/login','POST',{user:'admin',password});
  assert.equal(la.status,200);assert.equal(lb.status,200);
  const ma=await a('/auth/me'),mb=await b('/auth/me');
  assert.equal(ma.headers['cache-control'],'no-store');
  assert.equal(ma.body.user,'admin');assert.ok(ma.body.session);
  assert.equal(ma.body.session,la.body.session);assert.equal(mb.body.session,lb.body.session);
  assert.notEqual(ma.body.session,mb.body.session);
  // Signing one browser out leaves the other signed in.
  await a('/auth/logout','POST');
  assert.equal((await a('/auth/me')).status,401);
  assert.equal((await b('/auth/me')).body.session,mb.body.session);
  // Desk: each workspace login carries its own identity into the admin cookie, not a shared "admin".
  const c=client(port),d=client(port);
  assert.equal((await c('/desk-api/login','POST',{username:'alice',adminPassword:password})).status,200);
  assert.equal((await d('/desk-api/login','POST',{username:'bob.smith',adminPassword:password})).status,200);
  assert.equal((await c('/desk-api/session')).body.name,'alice');
  assert.equal((await d('/desk-api/session')).body.name,'bob.smith');
  const mc=await c('/auth/me'),md=await d('/auth/me');
  assert.equal(mc.body.user,'alice');assert.equal(md.body.user,'bob.smith');
  assert.notEqual(mc.body.session,md.body.session);
  await c('/desk-api/logout','POST');
  assert.equal((await c('/desk-api/session')).status,401);assert.equal((await c('/auth/me')).status,401);
  assert.equal((await d('/desk-api/session')).body.name,'bob.smith');assert.equal((await d('/auth/me')).body.user,'bob.smith');
  // A forged session-less token (the pre-fix shape) is refused.
  assert.equal((await client(port)('/auth/me')).status,401);
 } finally {await stop();}
});
// ENGINE-REVIEW S1: the gateway refuses order routes without a JWT. The console spends its own
// admin token there for a signed-in operator only, never for a signed-out visitor.
test('gateway order routes carry the service JWT only for a signed-in operator',async()=>{
 const seen=[];
 const edge=http.createServer((req,res)=>{
  res.setHeader('Content-Type','application/json');
  if(req.url==='/trade-processor/auth/dev-token')return res.end('svc.admin.jwt');
  seen.push({url:req.url,method:req.method,auth:req.headers.authorization});res.end('{}');
 });
 edge.listen(0,'127.0.0.1');await once(edge,'listening');
 const {port,stop}=await start({EDGE_PROXY:`127.0.0.1:${edge.address().port}`,AUTH_MASTER_SECRET:'synthetic',DESK_DEMO_PROFILE:'',LOCAL_ONLY:''});
 try {
  const anon=client(port),op=client(port);
  for(let i=0;i<100;i++){if((await anon('/healthz')).body.serviceAuth==='ready')break;await sleep(20);}
  const routes=['/order-matcher/orders','/order-matcher/orders/batch','/order-matcher/cancel','/order-matcher/replace','/order-matcher/trades','/order-matcher/swaps','/order-matcher/swaptions','/legacy/order-matcher/Orders'];
  for(const r of routes){const x=await anon(r,'POST',{accountId:42422});assert.equal(x.status,401,r);assert.equal(x.body.code,'admin_auth_required',r);}
  assert.equal(seen.length,0);
  await anon('/order-matcher/orders','GET',undefined,{Authorization:'Bearer forged'});
  assert.notEqual(seen.at(-1).auth,'Bearer svc.admin.jwt','a signed-out read never carries the service token');
  assert.equal((await op('/auth/login','POST',{user:'admin',password})).status,200);
  for(const r of routes.slice(0,7))assert.equal((await op(r,'POST',{accountId:42422})).status,200,r);
  assert.deepEqual(seen.slice(-7).map(s=>s.auth),Array(7).fill('Bearer svc.admin.jwt'));
 } finally {await stop();edge.closeAllConnections();await new Promise(r=>edge.close(r));}
});
