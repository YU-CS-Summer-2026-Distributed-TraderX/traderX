import {test} from 'node:test';
import assert from 'node:assert/strict';
import {deskOrderAction} from '../web-front-end-console/desk-orders.mjs';
const body={accountId:11,projectionScope:'run-a',orderRef:7,quantity:12,limitPrice:100};
function rig(overrides={}) {
 const writes=[];let pointers=0;
 const upstream=async(path,options)=>{
  if(options.method==='POST'){writes.push({path,body:options.body});if(overrides.timeout)throw Error('lost ack');return {status:200,body:{replaced:true,canceled:true,orderRef:7}};}
  if(path.endsWith('/active'))return {status:200,body:{projectionScope:overrides.switch && ++pointers>1?'run-b':'run-a'}};
  if(path.endsWith('/projections'))return {status:200,body:[{projection_scope:'run-a',cluster_epoch:'epochA',phase:'ACTIVE',descriptor_hash:'hash-a',...overrides.run}]};
  if(path.endsWith('/run/status'))return {status:200,body:{projectionScope:'run-a',descriptorHash:'hash-a',runPhase:2,...overrides.gateway}};
  return {status:200,body:[{id:'epochA-7',accountId:11,projectionScope:'run-a',status:'PARTIALLY_FILLED',orderType:'LIMIT',quantity:10,remainingQuantity:6,...overrides.order}]};
 };return {writes,upstream};
}
test('current-account partial-fill replace carries receiving-gateway identity and exact price',async()=>{
 const r=rig();const result=await deskOrderAction(r.upstream,[11],'replace',body);
 assert.equal(result.status,200);assert.equal(r.writes.length,1);
 assert.deepEqual(r.writes[0].body,{orderRef:7,quantity:12,limitPrice:100,clientOrderId:undefined,expectedDescriptorHash:'hash-a',expectedProjectionScope:'run-a'});
});
test('cancel is checked against the full epoch-qualified order identity',async()=>{
 const r=rig();assert.equal((await deskOrderAction(r.upstream,[11],'cancel',body)).status,200);assert.equal(r.writes[0].path,'/order-matcher/cancel');
});
test('account, historical, mismatched gateway, terminal and colliding-reference requests never write',async()=>{
 for(const [accounts,b,overrides] of [
  [[12],body,{}],[[11],{...body,projectionScope:'run-b'},{}],[[11],body,{gateway:{descriptorHash:'hash-b'}}],
  [[11],body,{run:{phase:'SEALED'}}],[[11],body,{order:{accountId:12}}],[[11],body,{order:{id:'epochB-7'}}],
  [[11],body,{order:{projectionScope:'run-b'}}],[[11],body,{order:{status:'FILLED'}}],[[11],body,{switch:true}]
 ]) {const r=rig(overrides);assert.ok((await deskOrderAction(r.upstream,accounts,'cancel',b)).status>=400);assert.equal(r.writes.length,0);}
});
test('partial fill floor and unsupported editors refuse without a write',async()=>{
 for(const [b,overrides] of [[{...body,quantity:4},{}],[{...body,quantity:4.5},{}],[{...body,limitPrice:Infinity},{}],[body,{order:{orderType:'ICEBERG'}}]]) {
  const r=rig(overrides);assert.equal((await deskOrderAction(r.upstream,[11],'replace',b)).status,422);assert.equal(r.writes.length,0);
 }
});
test('all seven order payloads retain their fields and idempotency key',async()=>{
 for(const type of ['MARKET','LIMIT','STOP','STOP_LIMIT','ICEBERG','PEGGED','TRAILING_STOP']) {
  const r=rig();const b={...body,orderType:type,clientOrderId:'stable-key',stopPrice:99,displayQuantity:2,pegOffset:-1,trailAmount:1};
  assert.equal((await deskOrderAction(r.upstream,[11],'orders',b)).status,200);
  for(const key of ['orderType','clientOrderId','stopPrice','displayQuantity','pegOffset','trailAmount'])assert.equal(r.writes[0].body[key],b[key]);
 }
});
test('ambiguous committed outcome sends exactly once and stays unknown',async()=>{
 const r=rig({timeout:true});const result=await deskOrderAction(r.upstream,[11],'replace',body);
 assert.equal(result.status,504);assert.match(result.body.error,/Outcome unknown/);assert.equal(r.writes.length,1);
});
