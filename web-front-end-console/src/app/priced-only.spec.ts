import {TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {Api} from './api';
import {TicketPanel} from './ticket-panel';
describe('FR-CUI-26 priced only',()=>{
 it('the equity ticket hides unpriced equities by default, keeps the chosen ticker, and shows all when off',()=>{
  const inst=(k:string,t='Equity')=>({instrumentKey:k,displayName:k,securityType:t});
  TestBed.configureTestingModule({providers:[{provide:Api,useValue:{prices:signal({AAPL:{price:1},SPY:{price:2}}),
    instruments:signal([inst('AAPL'),inst('ZZZ'),inst('IBM'),inst('SPY','Fund'),inst('UST','Debt')]),loadBands:()=>{},load:async()=>({status:503,body:{}})}}]});
  const t=TestBed.createComponent(TicketPanel).componentInstance;
  t.ticker.set('IBM');
  expect(t.equities().map(i=>i.instrumentKey)).toEqual(['AAPL','IBM','SPY']);
  t.pricedOnly.set(false);
  expect(t.equities().map(i=>i.instrumentKey)).toEqual(['AAPL','ZZZ','IBM','SPY']);
 });
});
