import {TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {Api} from './api';
import {TicketPanel} from './ticket-panel';
describe('listed option story reference',()=>{
 it('uses admitted reference rather than a drifting display quote and keeps both tickets aligned',async()=>{
  jasmine.clock().install();jasmine.clock().mockDate(new Date('2026-10-11T16:00:00Z'));
  try{
   const prices=signal({'AAPL260918C00240000':{price:1},'AAPL261218C00240000':{price:30}});
   TestBed.configureTestingModule({providers:[{provide:Api,useValue:{prices,instruments:signal([]),loadBands:()=>{},load:async()=>({status:200,body:{books:[{ticker:'AAPL261218C00240000',ref:9.603,mark:11.17}]}})}}]});
   const t=TestBed.createComponent(TicketPanel).componentInstance;
   await t.usePreset(2);expect(t.occSymbol()).toBe('AAPL261218C00240000');expect(t.limitPrice).toBe(9.603);expect(t.accountId).toBe(42422);
   prices.set({'AAPL260918C00240000':{price:1},'AAPL261218C00240000':{price:40}});
   await t.usePreset(3);expect(t.limitPrice).toBe(9.603);expect(t.accountId).toBe(22214);expect(t.side).toBe('Buy');
  }finally{jasmine.clock().uninstall();}
 });
});
