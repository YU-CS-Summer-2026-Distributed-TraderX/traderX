import {signal} from '@angular/core';
import {TestBed,fakeAsync,tick,discardPeriodicTasks} from '@angular/core/testing';
import {Desk} from './desk';
import {Session} from './session';
import {CONNECTED_RIG} from './rig';
import {Api} from '../../../web-front-end-console/src/app/api';
import {ACTIVE_URL,REGISTRY_URL} from '../../../web-front-end-console/src/app/run-scope';

describe('connected order management context',()=>{
 let session:any,api:any,scope:string,hang:boolean,writes:any[];
 beforeEach(()=>{
  scope='run-a';hang=false;writes=[];
  session={generation:signal(1),user:signal({id:'u',accounts:[11,12]}),account:signal(11),check:()=>true,prefs:()=>({watchlist:[]})};
  api={accounts:signal([{id:11,displayName:'A'},{id:12,displayName:'B'}]),instruments:signal([]),prices:signal({}),watchPrices:()=>{},init:async()=>{},
   load:async(url:string,options:any={})=>{
    if(options.method==='POST'){writes.push({url,body:JSON.parse(options.body)});return {status:200,body:{canceled:true}};}
    if(hang)return new Promise(()=>{});
    if(url===ACTIVE_URL)return {status:200,body:{projectionScope:scope}};
    if(url===REGISTRY_URL)return {status:200,body:[{projection_scope:'run-a',phase:'ACTIVE',descriptor_hash:'hash-a'},{projection_scope:'run-b',phase:'ACTIVE',descriptor_hash:'hash-b'}]};
    if(url.includes('/account-service/'))return {status:200,body:api.accounts()};
    if(url.includes('/reference-data/'))return {status:200,body:[]};
    if(url.endsWith('orders?status=all'))return {status:200,body:[{id:scope+'-7',accountId:session.account(),projectionScope:scope,status:'PARTIALLY_FILLED',orderType:'LIMIT',quantity:10,remainingQuantity:6}]};
    return {status:200,body:[]};
   }};
  TestBed.configureTestingModule({providers:[{provide:CONNECTED_RIG,useValue:true},{provide:Session,useValue:session},{provide:Api,useValue:api}]});
 });
 it('clears identity and rows when an abort-ignoring poll exceeds its deadline',fakeAsync(()=>{
  const d=TestBed.inject(Desk);TestBed.tick();tick();expect(d.canChangeExisting()).toBeTrue();
  hang=true;void (d as any).refreshRig(false);tick(8001);
  expect(d.canChangeExisting()).toBeFalse();expect(d.activeScope()).toBeNull();expect(d.accountOrders()).toEqual([]);
  discardPeriodicTasks();
 }));
 it('refuses a cancel when account changes during the asynchronous active-run check',fakeAsync(()=>{
  const d=TestBed.inject(Desk);TestBed.tick();tick();
  d.cancel(7);session.account.set(12);session.generation.update((n:number)=>n+1);TestBed.tick();tick();
  expect(writes).toEqual([]);discardPeriodicTasks();
 }));
 it('does not let an older poll confirm while its newer replacement is pending, and never redraws loading (no flash)',fakeAsync(()=>{
  const d=TestBed.inject(Desk);TestBed.tick();tick();
  const original=api.load;const answers:Array<()=>void>=[];
  api.load=(url:string,options:any)=>url.endsWith('orders?status=all')?new Promise(resolve=>answers.push(async()=>resolve(await original(url,options)))):original(url,options);
  void (d as any).refreshRig(false);tick();void (d as any).refreshRig(false);tick();
  answers[0]();tick();expect((d as any).confirmed).toBeFalse();expect(d.readState()).toBe('ready');
  answers[1]();tick();expect((d as any).confirmed).toBeTrue();expect(d.readState()).toBe('ready');discardPeriodicTasks();
 }));
 it('keeps suspended pegged orders in the working list and dispatches cancellation',fakeAsync(()=>{
  const original=api.load;
  api.load=async(url:string,options:any)=>{const r=await original(url,options);return url.endsWith('orders?status=all')?{...r,body:r.body.map((o:any)=>({...o,status:'SUSPENDED',orderType:'PEGGED'}))}:r;};
  const d=TestBed.inject(Desk);TestBed.tick();tick();
  expect(d.workingOrders().length).toBe(1);expect(d.workingOrders()[0].status).toBe('SUSPENDED');
  d.cancel(7);tick();expect(writes.length).toBe(1);expect(writes[0].url).toBe('/desk-api/orders/cancel');discardPeriodicTasks();
 }));
 it('sends explicit account and run to the checked endpoint and refuses history',fakeAsync(()=>{
  const d=TestBed.inject(Desk);TestBed.tick();tick();d.cancel(7);tick();
  expect(writes.length).toBe(1);expect(writes[0]).toEqual({url:'/desk-api/orders/cancel',body:{orderRef:7,accountId:11,projectionScope:'run-a'}});
  d.runView.set({kind:'history',scope:'run-a'});d.cancel(7);tick();expect(writes.length).toBe(1);discardPeriodicTasks();
 }));
 it('FR-CUI-23: when the active run is the registry\'s LEGACY row (the CompSci rig), an operator trades through the operator path',fakeAsync(()=>{
  const original=api.load;
  api.load=async(url:string,options:any={})=>url===REGISTRY_URL?{status:200,body:[{projection_scope:'legacy-unknown',phase:'LEGACY',descriptor_hash:null}]}:
    url===ACTIVE_URL?{status:200,body:{projectionScope:'legacy-unknown'}}:
    url.endsWith('orders?status=all')?{status:200,body:[{id:'1-7',accountId:session.account(),projectionScope:'legacy-unknown',status:'NEW',orderType:'LIMIT',quantity:10,remainingQuantity:10}]}:original(url,options);
  session.user.set({id:'u',accounts:[11,12],roles:['trader','admin']});
  const d=TestBed.inject(Desk);TestBed.tick();tick();
  expect(d.activeScope()).toBe('legacy-unknown');expect(d.operatorUnmanaged()).toBeTrue();
  d.cancel(7);tick();expect(writes).toEqual([{url:'/order-matcher/cancel',body:{orderRef:7,accountId:11}}]);discardPeriodicTasks();
 }));
 it('FR-CUI-23: on a plain bring-up an operator session trades through the operator order path; a trader session is refused',fakeAsync(()=>{
  const original=api.load;
  api.load=async(url:string,options:any={})=>url===REGISTRY_URL?{status:200,body:[]}:url===ACTIVE_URL?{status:503,body:{}}:
    url.endsWith('orders?status=all')?{status:200,body:[{id:'1-7',accountId:session.account(),projectionScope:'legacy-unknown',status:'NEW',orderType:'LIMIT',quantity:10,remainingQuantity:10}]}:original(url,options);
  session.user.set({id:'u',accounts:[11,12],roles:['trader','admin']});
  const d=TestBed.inject(Desk);TestBed.tick();tick();
  expect(d.operatorUnmanaged()).toBeTrue();expect(d.readState()).toBe('ready');
  d.cancel(7);tick();expect(writes).toEqual([{url:'/order-matcher/cancel',body:{orderRef:7,accountId:11}}]);
  session.user.set({id:'u',accounts:[11,12],roles:['trader']});session.generation.update((n:number)=>n+1);TestBed.tick();tick();
  expect(d.operatorUnmanaged()).toBeFalse();d.cancel(7);tick();expect(writes.length).toBe(1);discardPeriodicTasks();
 }));
});
