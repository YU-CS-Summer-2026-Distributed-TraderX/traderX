import { readRigAccount } from './rig';
import { ACTIVE_URL, REGISTRY_URL } from '../../../web-front-end-console/src/app/run-scope';
describe('connected desk run boundary',()=>{
  const run={projection_scope:'r1',phase:'ACTIVE'};
  const good=async(url:string):Promise<{status:number;body:any}>=>url===REGISTRY_URL?{status:200,body:[run]}:url===ACTIVE_URL?{status:200,body:{projectionScope:'r1'}}:{status:200,body:[{accountId:22214,projectionScope:'r1'}]};
  it('uses explicit readers and rechecks the active pointer',async()=>{
    const urls:string[]=[];const r=await readRigAccount(async u=>{urls.push(u);return good(u);},22214,{kind:'active'});
    expect(r.scope).toBe('r1');expect(urls.filter(u=>u===ACTIVE_URL).length).toBe(2);
    expect(urls.filter(u=>u.includes('/accounts/22214/')).length).toBe(3);
  });
  it('rejects an active-pointer change',async()=>{
    let n=0;await expectAsync(readRigAccount(async u=>u===ACTIVE_URL&&++n===2?{status:200,body:{projectionScope:'r2'}}:good(u),22214,{kind:'active'})).toBeRejectedWithError(/changed/);
  });
  it('rejects cross-account and cross-run rows',async()=>{
    for(const bad of [{accountId:7,projectionScope:'r1'},{accountId:22214,projectionScope:'r2'}]) {
      await expectAsync(readRigAccount(async u=>u.endsWith('/positions')?{status:200,body:[bad]}:good(u),22214,{kind:'active'})).toBeRejected();
    }
  });
  it('does not treat a failed or HTML registry as legacy',async()=>{
    for(const r of [{status:503,body:[]},{status:200,body:'<html>'},{status:0,body:null}]) await expectAsync(readRigAccount(async()=>r,22214,{kind:'active'})).toBeRejected();
  });
  it('accepts empty legacy reads only when registry is absent',async()=>{
    const r=await readRigAccount(async u=>u===REGISTRY_URL?{status:404,body:null}:{status:200,body:[]},22214,{kind:'active'});expect(r.scope).toBeNull();expect(r.positions).toEqual([]);
  });
  it('refuses historical reads on a legacy rig',async()=>{
    await expectAsync(readRigAccount(async()=>({status:404,body:null}),22214,{kind:'history',scope:'old'})).toBeRejectedWithError(/historical/);
  });
  it('refuses unregistered historical scopes',async()=>{
    await expectAsync(readRigAccount(good,22214,{kind:'history',scope:'unknown'})).toBeRejectedWithError(/not registered/);
  });
  it('refuses a partial account read',async()=>{
    await expectAsync(readRigAccount(async u=>u.includes('/trades')?{status:503,body:[]}:good(u),22214,{kind:'active'})).toBeRejectedWithError(/trades/);
  });
});
