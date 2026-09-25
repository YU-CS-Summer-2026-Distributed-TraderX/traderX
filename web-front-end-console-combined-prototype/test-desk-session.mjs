import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createDeskSessions} from '../web-front-end-console/desk-session.mjs';
const req=(method='POST',cookie='')=>({method,headers:{cookie}});
const login=(s,username,adminPassword='')=>s.handle(req(),'/desk-api/login',{username,adminPassword});
const id='12345678-1234-1234-1234-123456789abc';
test('admin is checked per session and never attached to a username',async()=>{
 const s=createDeskSessions({checkAdmin:p=>p==='test-only'});
 assert.equal((await login(s,'Chris','wrong')).status,401);
 const a=await login(s,'Chris','test-only');assert.deepEqual(a.body.roles,['trader','admin']);
 const b=await login(s,'Chris');assert.deepEqual(b.body.roles,['trader']);
 assert.equal((await s.handle(req('GET',b.cookie),'/desk-api/session')).body.id,'user:chris');
 assert.equal((await s.handle(req('GET','tx_desk=forged'),'/desk-api/session')).status,401);
});
test('ownership persists, duplicate creation writes once, other users cannot admit it',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'desk-users-'));let creates=0;
 const opts={file:join(dir,'users.json'),checkAdmin:()=>false,upstream:async()=>{creates++;return {status:200,body:{id:65001,displayName:'Desk A'}}},enableAccount:async()=>true};
 try {
 const s=createDeskSessions(opts),a=await login(s,'Alice'),b=await login(s,'Bob');
 const [r,duplicate]=await Promise.all([s.handle(req('POST',a.cookie),'/desk-api/accounts',{displayName:'Desk A',requestId:id}),s.handle(req('POST',a.cookie),'/desk-api/accounts',{displayName:'Desk A',requestId:id})]);
 assert.equal((await s.handle(req('POST',a.cookie),'/desk-api/accounts',{displayName:'different',requestId:id})).status,409);
 assert.equal(creates,1);assert.equal(r.status,201);assert.deepEqual(r,duplicate);assert.deepEqual(r.body.user.accounts,[65001]);
 assert.equal((await s.handle(req('POST',b.cookie),'/desk-api/accounts/admit',{accountId:65001})).status,403);
 const restarted=createDeskSessions(opts);assert.deepEqual((await login(restarted,'ALICE')).body.accounts,[65001]);
 }finally{rmSync(dir,{recursive:true});}
});
test('partial admission retains account and allows scoped retry',async()=>{
 let enabled=false;const s=createDeskSessions({checkAdmin:()=>false,upstream:async()=>({status:200,body:{id:65002}}),enableAccount:async()=>enabled});
 const a=await login(s,'Alice');const r=await s.handle(req('POST',a.cookie),'/desk-api/accounts',{displayName:'X',requestId:id});
 assert.equal(r.body.enabled,false);assert.deepEqual(r.body.user.accounts,[65002]);enabled=true;
 assert.equal((await s.handle(req('POST',a.cookie),'/desk-api/accounts/admit',{accountId:65002})).body.enabled,true);
});
test('ambiguous account creation is never retried',async()=>{
 let count=0;const s=createDeskSessions({checkAdmin:()=>false,upstream:async()=>{count++;throw Error('timeout')},enableAccount:async()=>true});
 const a=await login(s,'Alice'),r=req('POST',a.cookie),body={displayName:'X',requestId:id};
 assert.equal((await s.handle(r,'/desk-api/accounts',body)).status,409);assert.equal((await s.handle(r,'/desk-api/accounts',body)).status,409);assert.equal(count,1);
});
test('logout and expiry refuse writes',async()=>{
 let time=0;const s=createDeskSessions({checkAdmin:()=>false,now:()=>time});const a=await login(s,'Alice');
 time=9*3600000;assert.equal((await s.handle(req('POST',a.cookie),'/desk-api/accounts',{})).status,401);
 const b=await login(s,'Bob');await s.handle(req('POST',b.cookie),'/desk-api/logout');assert.equal((await s.handle(req('GET',b.cookie),'/desk-api/session')).status,401);
});

test('accounts in one workspace share a persistent group; another workspace gets another group',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'desk-groups-'));let next=65000;const calls=[];
 const opts={file:join(dir,'users.json'),checkAdmin:()=>false,upstream:async()=>({status:200,body:{id:next++}}),enableAccount:async(a,g,admit=true)=>{calls.push([a,g,admit]);return true;}};
 try{const s=createDeskSessions(opts),a=await login(s,'Alice'),b=await login(s,'Bob');
 for(const [cookie,key] of [[a.cookie,id],[a.cookie,id+'a'],[b.cookie,id]])await s.handle(req('POST',cookie),'/desk-api/accounts',{displayName:'Test',requestId:key});
 assert.deepEqual(calls,[[65000,65000,true],[65001,65000,true],[65002,65002,true]]);
 calls.length=0;await login(createDeskSessions(opts),'Alice');assert.deepEqual(calls,[[65000,65000,false],[65001,65000,false]]);
 opts.enableAccount=async()=>false;assert.equal((await login(createDeskSessions(opts),'Alice')).status,503);
 }finally{rmSync(dir,{recursive:true});}
});
