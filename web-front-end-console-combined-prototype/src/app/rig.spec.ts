import { NO_MANAGED_RUN, readBook, readContracts, readRigAccount } from './rig';
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
  it('names the missing managed run on a plain bring-up instead of an HTTP status',async()=>{
    const plain=async(u:string)=>u===REGISTRY_URL?{status:200,body:[]}:u===ACTIVE_URL?{status:503,body:{error:'active projection scope unavailable'}}:{status:200,body:[]};
    await expectAsync(readRigAccount(plain,22214,{kind:'active'})).toBeRejectedWithError(NO_MANAGED_RUN);
    expect(NO_MANAGED_RUN).toContain('Demo console');
  });
  it('FR-CUI-23: an operator session reads the legacy projection of a plain bring-up; history stays refused',async()=>{
    const plain=async(u:string)=>u===REGISTRY_URL?{status:200,body:[]}:u===ACTIVE_URL?{status:503,body:{}}:{status:200,body:[{accountId:22214,projectionScope:'legacy-unknown'}]};
    const r=await readRigAccount(plain,22214,{kind:'active'},true);expect(r.scope).toBeNull();expect(r.positions.length).toBe(1);
    await expectAsync(readRigAccount(plain,22214,{kind:'history',scope:'x'},true)).toBeRejectedWithError(NO_MANAGED_RUN);
    await expectAsync(readRigAccount(async u=>u!==REGISTRY_URL&&u!==ACTIVE_URL&&u.includes('position')?{status:200,body:[{accountId:7,projectionScope:'legacy-unknown'}]}:plain(u),22214,{kind:'active'},true)).toBeRejected();
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

describe('bounded account transport',()=>{
  it('rejects on timeout even if transport ignores abort and resolves late',async()=>{
    const {bounded}=await import('./rig');const c=new AbortController();let finish!:(v:number)=>void;
    const p=bounded(new Promise<number>(r=>finish=r),c.signal);
    c.abort();await expectAsync(p).toBeRejectedWithError(/timed out/);finish(42);
    await expectAsync(p).toBeRejected();
  });
});
describe('booked OTC contracts (FR-CUI-24)',()=>{
  it('falls through to the next member and keeps only the selected account',async()=>{
    const urls:string[]=[];
    const r=await readContracts(async u=>{urls.push(u);return u.startsWith('/mem/0/')?{status:503,body:{}}:
      {status:200,body:{contracts:[{ref:'SW-1',accountId:22214},{ref:'SW-2',accountId:99}]}};},22214);
    expect(urls).toEqual(['/mem/0/otc/contracts?account=22214','/mem/1/otc/contracts?account=22214']);
    expect(r!.map(c=>c.ref)).toEqual(['SW-1']);
  });
  it('is null when no member answers',async()=>{
    expect(await readContracts(async()=>{throw Error('down');},22214)).toBeNull();
  });
});
describe('order book (FR-CUI-25)',()=>{
  it('reads one member, encodes the ticker, and treats an unknown ticker as an empty book',async()=>{
    const urls:string[]=[];
    const b=await readBook(async u=>{urls.push(u);return u.startsWith('/mem/0/')?{status:503,body:{}}:{status:200,body:{ticker:'BRK B',bids:[{px:1,qty:2}],asks:[]}};},'BRK B',5);
    expect(urls).toEqual(['/mem/0/book?ticker=BRK%20B&levels=5','/mem/1/book?ticker=BRK%20B&levels=5']);
    expect(b!.bids.length).toBe(1);
    expect(await readBook(async()=>({status:404,body:{}}),'ZZZ')).toEqual({ticker:'ZZZ',bids:[],asks:[]});
    expect(await readBook(async()=>{throw Error('down');},'IBM')).toBeNull();
  });
});
