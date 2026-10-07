import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyTapeStatus as classify } from './src/app/tape-status.ts';

test('structured absence does not depend on prose; corrupt and unknown codes alarm', () => {
  assert.equal(classify({state:'unavailable', reason:'EXTRACT_MISSING', error:'reworded detail'}), 'synthetic');
  assert.equal(classify({state:'not_attempted', reason:'NOT_ATTEMPTED'}), 'synthetic');
  for (const reason of ['EXTRACT_UNREADABLE','EXTRACT_INVALID_GZIP','EXTRACT_INVALID_JSON',
    'EXTRACT_INVALID_SCHEMA','CLOCK_MISSING','CLOCK_INVALID','CLOCK_UNADDRESSABLE','future']) {
    assert.equal(classify({state:'invalid', reason, error:'no extract at misleading prose'}), 'error');
  }
  for (const t of [{state:'unavailable'}, {reason:'EXTRACT_MISSING'},
    {state:'future',reason:'EXTRACT_MISSING'}, {state:'invalid',reason:'EXTRACT_MISSING'},
    {state:'unavailable',reason:'EXTRACT_MISSING',source:'loaded'}]) {
    assert.equal(classify(t), 'error');
  }
});

test('loaded running, paused and held tape retain tape classification', () => {
  for (const [state,reason] of [['replaying','REPLAY_ACTIVE'],['paused','REPLAY_PAUSED'],['finished','REPLAY_FINISHED']]) {
    const t={state,reason,position:{tapeDate:'2025-02-03'},error:null};
    assert.equal(classify(t),'tape');
    assert.equal(classify({...t,position:null}),'error');
    assert.equal(classify({...t,error:'fault'}),'error');
  }
});

test('legacy producers retain narrow absent/error fallback', () => {
  assert.equal(classify(undefined),'synthetic');
  assert.equal(classify({}),'synthetic');
  assert.equal(classify({error:'no extract at /tmp/missing.gz'}),'synthetic');
  assert.equal(classify({error:'did not gunzip+parse'}),'error');
  assert.equal(classify({error:'unknown fault'}),'error');
  assert.equal(classify({source:'tape',position:{},error:null}),'tape');
  assert.equal(classify({source:'tape',error:'no extract at contradictory detail'}),'error');
});

import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
const require = createRequire(import.meta.url);
test('actual producer outcomes reach the consumer independently of prose', (t) => {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'tape-consumer-'));
  const oldPath=process.env.TAQ_REPLAY_EXTRACT_PATH, oldEpoch=process.env.REPLAY_EPOCH_START_MS;
  t.after(() => {
    fs.rmSync(dir,{recursive:true,force:true});
    if(oldPath===undefined) delete process.env.TAQ_REPLAY_EXTRACT_PATH; else process.env.TAQ_REPLAY_EXTRACT_PATH=oldPath;
    if(oldEpoch===undefined) delete process.env.REPLAY_EPOCH_START_MS; else process.env.REPLAY_EPOCH_START_MS=oldEpoch;
  });
  const root=process.env.PRICE_PUBLISHER_ROOT || path.resolve(import.meta.dirname,
    '../specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher');
  const modulePath=require.resolve(path.join(root,'src/taq-replay.js'));
  for(const [name,bytes,state,reason,ui] of [
    ['missing',null,'unavailable','EXTRACT_MISSING','synthetic'],
    ['corrupt',Buffer.from('bad gzip'),'invalid','EXTRACT_INVALID_GZIP','error'],
    ['valid',zlib.gzipSync(JSON.stringify({version:1,source:'synthetic-test',sessionSeconds:10,
      windowSeconds:10,compression:1,days:[{date:'2025-02-03',openMs:1738593000000}],
      prices:{AAPL:[[200]]}})),'replaying','REPLAY_ACTIVE','tape']]) {
    const file=path.join(dir,name+'.gz');
    if(bytes)fs.writeFileSync(file,bytes);
    process.env.TAQ_REPLAY_EXTRACT_PATH=file;
    process.env.REPLAY_EPOCH_START_MS='1700000000000';
    delete require.cache[modulePath];
    const m=require(modulePath);m.load(1700000000000);
    const st=m.status(1700000000000);
    assert.equal(st.state,state);assert.equal(st.reason,reason);
    assert.equal(classify(st),ui);
    if(ui!=='tape')assert.equal(classify({...st,error:'no extract at identical detail'}),ui);
  }
});

test('actual producer invalid timestamps and overflow alarm in the consumer without throwing', (t) => {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'tape-domain-consumer-'));
  const oldPath=process.env.TAQ_REPLAY_EXTRACT_PATH,oldEpoch=process.env.REPLAY_EPOCH_START_MS;
  t.after(()=>{
    fs.rmSync(dir,{recursive:true,force:true});
    if(oldPath===undefined)delete process.env.TAQ_REPLAY_EXTRACT_PATH;else process.env.TAQ_REPLAY_EXTRACT_PATH=oldPath;
    if(oldEpoch===undefined)delete process.env.REPLAY_EPOCH_START_MS;else process.env.REPLAY_EPOCH_START_MS=oldEpoch;
  });
  const root=process.env.PRICE_PUBLISHER_ROOT || path.resolve(import.meta.dirname,
    '../specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher');
  const modulePath=require.resolve(path.join(root,'src/taq-replay.js'));
  const epoch=1700000000000;
  const base={version:1,source:'synthetic-domain-test',sessionSeconds:10,windowSeconds:10,
    compression:1,days:[{date:'2025-02-03',openMs:1738593000000}],prices:{AAPL:[[200]]}};
  const cases=[
    ['unrepresentable open',{...base,days:[{date:'synthetic',openMs:1e20}]},epoch,'EXTRACT_INVALID_SCHEMA'],
    ['unrepresentable asOf',{...base,days:[{date:'synthetic',openMs:8640000000000000}]},epoch,'EXTRACT_INVALID_SCHEMA'],
    ['duration overflow',{...base,sessionSeconds:1e307,windowSeconds:1e307},epoch,'EXTRACT_INVALID_SCHEMA'],
    ['compression overflow',{...base,compression:1e308},epoch+2000,'CLOCK_UNADDRESSABLE'],
    ['day-index overflow',{...base,sessionSeconds:5e-324,windowSeconds:5e-324},epoch+1000,'CLOCK_UNADDRESSABLE'],
    ['invalid wall time',base,1e20,'CLOCK_UNADDRESSABLE'],
  ];
  for(const [name,ex,now,reason] of cases){
    const file=path.join(dir,name+'.gz');fs.writeFileSync(file,zlib.gzipSync(JSON.stringify(ex)));
    process.env.TAQ_REPLAY_EXTRACT_PATH=file;process.env.REPLAY_EPOCH_START_MS=String(epoch);
    delete require.cache[modulePath];const m=require(modulePath);m.load(epoch);
    const st=m.status(now);
    assert.equal(st.state,'invalid',name);assert.equal(st.reason,reason,name);
    assert.equal(m.positionAt(now),null,name);assert.equal(m.priceAt('AAPL',now),null,name);
    assert.equal(classify(st),'error',name);
    assert.equal(classify({...st,error:'no extract at identical detail'}),'error',name);
  }
});
