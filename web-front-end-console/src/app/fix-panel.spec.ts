import {TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {Api} from './api';
import {FixPanel} from './fix-panel';
describe('FIX quote subscription',()=>{
 let watch:jasmine.Spy;
 let marks:any;
 beforeEach(()=>{watch=jasmine.createSpy('watchPrices');marks=signal({});TestBed.configureTestingModule({providers:[{provide:Api,useValue:{watchPrices:watch,prices:marks,accounts:signal([])}}]});});
 it('subscribes when FIX is the first page visited and prices the ticket',()=>{
 const p=TestBed.createComponent(FixPanel).componentInstance;expect(watch).toHaveBeenCalledTimes(1);
 marks.set({IBM:{price:150}});p.atMarket();expect(p.limitPrice).toBe(150.05);
 });
 it('shows unavailable quote without overwriting an explicit price',()=>{
 const p=TestBed.createComponent(FixPanel).componentInstance;p.limitPrice=125;p.atMarket();expect(p.limitPrice).toBe(125);expect(p.priceError()).toContain('No positive live mark');
 });
});
