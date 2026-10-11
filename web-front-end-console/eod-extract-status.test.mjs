import test from 'node:test';
import assert from 'node:assert/strict';
import {remoteExtractStatus} from './eod-extract-status.mjs';
test('remote location alone never claims cut completion',()=>{
 assert.equal(remoteExtractStatus('pod','gs://sink',null).state,'remote');
 assert.equal(remoteExtractStatus('pod','gs://sink',{state:'running',version:'2'}).state,'remote');
});
test('service READY verdict makes the remote cut complete and identifies its version',()=>{
 const s=remoteExtractStatus('pod','gs://sink',{state:'ok',version:'1'});
 assert.equal(s.state,'ok');assert.equal(s.version,'1');assert.match(s.detail,/v1/);
});
