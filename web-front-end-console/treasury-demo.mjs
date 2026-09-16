// A single bounded operator demo. Durable creation precedes dispatch; retry never creates a second run.
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID, timingSafeEqual } from 'node:crypto';
export const scenario = Object.freeze({instrument:'UST-BILL-20261112',label:'Treasury bill',quantity:'1000',quantityUnit:'USD face',currency:'USD',valuationDate:'2026-09-16',assumedCurve:'Flat 3%',executionLocation:'local',singleRun:true});
const stages=['QUEUED','TRADE_SUBMITTED','TRADE_BOOKED','POSITION_EXPORTED','PRICING','INDEPENDENTLY_CHECKED'];
const messages={QUEUED:'Waiting for the local worker.',TRADE_SUBMITTED:'Demo orders accepted; checking booking.',TRADE_BOOKED:'Demo trade booked and position confirmed.',POSITION_EXPORTED:'The booked position has been exported.',PRICING:'Calculating the exported position locally.',INDEPENDENTLY_CHECKED:'Pricing agrees with the independent check.',FAILED:'The demo could not complete. Operator review is required.',NEEDS_REVIEW:'The trade outcome needs operator review. No order will be retried automatically.'};
export function createTreasuryDemo({directory=process.env.TREASURY_DEMO_STATE,secret=process.env.TREASURY_WORKER_SECRET,origin=process.env.TREASURY_DEMO_ORIGIN,now=Date.now}={}) {
 let heartbeat=0; let run=null; let unavailable=false;
 const file=directory && path.join(directory,'run.json');
 if(file) { try { fs.mkdirSync(directory,{recursive:true,mode:0o700}); if(fs.existsSync(file)){run=JSON.parse(fs.readFileSync(file,'utf8')); if(!run.id||!stages.includes(run.stage))throw Error();} } catch {unavailable=true;} }
 const configured=()=>!!(file&&secret&&origin&&!unavailable);
 const alive=()=>configured()&&heartbeat>0&&now()-heartbeat<30000;
 const save=()=>{try {const tmp=file+'.tmp'; const fd=fs.openSync(tmp,'w',0o600);try {fs.writeFileSync(fd,JSON.stringify(run));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}fs.renameSync(tmp,file);const d=fs.openSync(directory,'r');try{fs.fsyncSync(d);}finally{fs.closeSync(d);}} catch(e){unavailable=true;throw e;}};
 const view=()=>({schema:'traderx.treasury-trade-demo.v1',workerAvailable:alive(),scenario:configured()?scenario:null,run});
 const response=(status,body)=>({status,body});
 const error=(status,code,message)=>response(status,{code,message});
 const token=(headers)=>{const a=Buffer.from(headers.authorization||''),b=Buffer.from('Bearer '+secret);return !!secret&&a.length===b.length&&timingSafeEqual(a,b);};
 return {snapshot:()=>response(200,view()),async handle({method,pathname,headers={},authenticated=false,body={}}){
  if(pathname==='/risk/treasury-demo')return method==='GET'?response(200,view()):error(405,'method','Read only.');
  if(pathname==='/risk/treasury-demo/start'){
   if(method!=='POST')return error(405,'method','Use the Run button.');
   if(!authenticated)return error(401,'admin_auth_required','Sign in to run the demo.');
   if(!configured())return error(503,'NOT_CONFIGURED','The demo is not configured.');
   if(headers.origin!==origin||!String(headers['content-type']||'').startsWith('application/json'))return error(403,'ORIGIN_REJECTED','Reload this page before running the demo.');
   if(Object.keys(body).join()!=='idempotencyKey'||!/^[-a-f0-9]{36}$/i.test(body.idempotencyKey))return error(400,'request','The run request is invalid.');
   if(run)return response(['SUCCEEDED','FAILED','NEEDS_REVIEW'].includes(run.status)?200:202,view());
   if(!alive())return error(503,'WORKER_UNAVAILABLE','The local worker is unavailable. No trade has been started.');
   run={id:randomUUID(),status:'QUEUED',stage:'QUEUED',message:messages.QUEUED,result:null};save();return response(202,view());
  }
  if(pathname==='/risk/treasury-demo/worker'){
   if(method!=='POST')return error(405,'method','Unsupported request.');
   if(!configured()||!token(headers))return error(401,'unauthorized','Unauthorized.');
   if(body.update){const u=body.update;
    if(!run||u.id!==run.id||!['RUNNING','SUCCEEDED','FAILED','NEEDS_REVIEW'].includes(u.status)||!stages.includes(u.stage))return error(409,'state','Invalid transition.');
    if(['SUCCEEDED','FAILED','NEEDS_REVIEW'].includes(run.status))return response(200,view());
    if(stages.indexOf(u.stage)<stages.indexOf(run.stage))return error(409,'state','Invalid transition.');
    let result=null;
    if(u.status==='SUCCEEDED'){
      const r=u.result;
      const numeric=['quantity','signedFaceUsd','bookedPrice','pricingUsd','referenceUsd','differenceUsd','toleranceUsd'];
      if(u.stage!=='INDEPENDENTLY_CHECKED'||!r||numeric.some(k=>typeof r[k]!=='string'||!Number.isFinite(Number(r[k])))||r.instrument!==scenario.instrument||r.quantity!=='1000'||r.signedFaceUsd!=='1000'||r.currency!=='USD'||r.valuationDate!==scenario.valuationDate||r.withinTolerance!==true||r.usableForRisk!==false||Math.abs(Number(r.differenceUsd))>1e-8||Number(r.toleranceUsd)!==1e-8)return error(400,'result','Invalid result.');
      result=Object.fromEntries(['instrument','currency',...numeric,'valuationDate'].map(k=>[k,r[k]]));
      Object.assign(result,{bookedPriceUnit:'fraction of par',withinTolerance:true,rateSensitivity:'unsupported',executionLocation:'local',assumedCurve:'Flat 3%',usableForRisk:false});
    }
    run={id:run.id,status:u.status,stage:u.stage,message:messages[u.status]||messages[u.stage],result};save();
   }
   heartbeat=now();return response(200,view());
  }
  return error(404,'missing','Not found.');
 }};
}
