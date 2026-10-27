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
test('background reads never take the last slot nor overtake a waiting foreground read',async()=>{
 const running=[];const r=createCliRunner({limit:2,execImpl:(cmd,_o,cb)=>running.push({cmd,cb})});
 const bg=['g1','g2','g3'].map(c=>r.background(c,{}));
 assert.deepEqual(running.map(j=>j.cmd),['g1']);                 // one slot left for the operator
 const chain=r.shell('chain',{});
 assert.deepEqual(running.map(j=>j.cmd),['g1','chain']);         // starts at once, not after g2/g3
 const next=r.shell('chain-2',{});
 running.shift().cb(null,'g1');                                   // freed slot goes to the foreground read
 assert.deepEqual(running.map(j=>j.cmd),['chain','chain-2']);
 running.shift().cb(null,'chain');running.shift().cb(null,'chain-2');
 assert.equal(await chain,'chain');assert.equal(await next,'chain-2');
 assert.deepEqual(running.map(j=>j.cmd),['g2']);
 running.shift().cb(null,'g2');running.shift().cb(null,'g3');await Promise.all(bg);
 assert.equal(r.stats().active,0);
});
