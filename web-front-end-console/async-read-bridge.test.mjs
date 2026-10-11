import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtempSync,writeFileSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import net from 'node:net';
import {once} from 'node:events';
test('a slow EOD CLI read leaves the real HTTP server responsive',async()=>{
 const dir=mkdtempSync(path.join(tmpdir(),'console-async-')),marker=path.join(dir,'started');
 for(const n of ['kubectl','gcloud'])writeFileSync(path.join(dir,n),'#!/bin/sh\ntouch "$READ_PROOF_MARKER"\nsleep 0.2\nexit 0\n',{mode:0o700});
 const probe=net.createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(r=>probe.close(r));
 const child=spawn(process.execPath,[new URL('./server.mjs',import.meta.url).pathname],{env:{...process.env,PORT:String(port),HOST:'127.0.0.1',PATH:dir+':'+process.env.PATH,READ_PROOF_MARKER:marker,LOCAL_ONLY:'0',AUTH_MASTER_SECRET:'',DESK_USERS_FILE:''},stdio:['ignore','pipe','pipe']});
 try{
 await new Promise((resolve,reject)=>{child.stdout.on('data',b=>{if(b.toString().includes('[console]'))resolve();});child.once('error',reject);child.once('exit',()=>reject(Error('server exited before listening')));});
 let finished=false;const base=`http://127.0.0.1:${port}`;
 const pending=fetch(base+'/eod/chain?date=2026-10-11').then(async r=>{const b=await r.json();finished=true;return {r,b};});
 while(!existsSync(marker))await new Promise(r=>setTimeout(r,10));
 const health=await fetch(base+'/healthz');assert.equal(health.status,200);await health.text();assert.equal(finished,false,'health was held behind the CLI read');
 const {r,b}=await pending;assert.equal(r.status,200);assert.ok(b.prices&&b.pnl&&b.extract&&b.published);
 }finally{child.kill('SIGTERM');await once(child,'exit');rmSync(dir,{recursive:true,force:true});}
});
