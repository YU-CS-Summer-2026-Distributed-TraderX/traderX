import test from 'node:test';
import assert from 'node:assert/strict';
import {createCliRunner} from './cli-runner.mjs';
test('shell and argv reads share the same process ceiling and continue after an error',async()=>{
 const waiting=[];let peak=0,active=0;
 const execute=(...args)=>{active++;peak=Math.max(peak,active);waiting.push((error,value)=>{active--;args.at(-1)(error,value);});};
 const r=createCliRunner({limit:2,execImpl:execute,execFileImpl:execute});
 const a=r.shell('a',{}),b=r.args('b',[],{}),c=r.shell('c',{}),d=r.shell('d',{});
 assert.equal(waiting.length,2);assert.equal(r.stats().queued,2);
 waiting.shift()(null,'a');assert.equal(await a,'a');assert.equal(waiting.length,2);
 const rejected=assert.rejects(b,/fixture/);waiting.shift()(Error('fixture'));await rejected;
 waiting.shift()(null,'c');waiting.shift()(null,'d');await Promise.all([c,d]);assert.equal(peak,2);assert.equal(r.stats().active,0);
});
test('queued reads cannot grow without a bound',async()=>{
 const callbacks=[],r=createCliRunner({limit:1,maxQueued:1,execImpl:(...args)=>callbacks.push(args.at(-1))});
 const a=r.shell('a',{}),b=r.shell('b',{});await assert.rejects(r.shell('overflow',{}),/queue is full/);
 callbacks.shift()(null,'a');await a;callbacks.shift()(null,'b');await b;
});
