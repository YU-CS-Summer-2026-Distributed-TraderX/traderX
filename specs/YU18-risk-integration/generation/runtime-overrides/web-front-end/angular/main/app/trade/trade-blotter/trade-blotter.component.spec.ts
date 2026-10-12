import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { AgGridModule } from 'ag-grid-angular';
import { CommonModule } from '@angular/common';
import { of, Subject } from 'rxjs';
import { TradeBlotterComponent } from './trade-blotter.component';
import { PositionService } from 'main/app/service/position.service';
import { TradeFeedService } from 'main/app/service/trade-feed.service';

const ACCOUNT = { id: 22214, displayName: 'Test Account 20' };
const TRADES = [
  { id: 'A', accountid: 22214, security: 'IBM', quantity: 10, side: 'Buy', state: 'Settled' },
  { id: 'B', accountid: 22214, security: 'MS', quantity: 20, side: 'Sell', state: 'Settled' }
] as any[];

describe('Trade blotter account scope and row identity', () => {
  let fixture: ComponentFixture<TradeBlotterComponent>;
  let component: TradeBlotterComponent;
  let service: any;
  let feed: any;
  let callbacks: Map<string, Function>;
  let unsubscribes: Map<string, jasmine.Spy>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('PositionService', ['getTrades', 'getAllTrades']);
    service.getTrades.and.returnValue(of(TRADES)); service.getAllTrades.and.returnValue(of(TRADES));
    callbacks = new Map(); unsubscribes = new Map();
    feed = { subscribe: jasmine.createSpy('subscribe').and.callFake((topic: string, callback: Function) => {
      callbacks.set(topic, callback); const stop = jasmine.createSpy('stop'); unsubscribes.set(topic, stop); return stop;
    }) };
    await TestBed.configureTestingModule({ declarations: [TradeBlotterComponent], imports: [CommonModule, AgGridModule],
      providers: [{ provide: PositionService, useValue: service }, { provide: TradeFeedService, useValue: feed }]
    }).compileComponents();
    fixture = TestBed.createComponent(TradeBlotterComponent); component = fixture.componentInstance;
  });

  it('loads a bound Angular account input and renders its trades', fakeAsync(() => {
    fixture.componentRef.setInput('account', ACCOUNT); fixture.detectChanges(); tick(100); fixture.detectChanges();
    expect(service.getTrades).toHaveBeenCalledWith(22214);
    expect(feed.subscribe).toHaveBeenCalledWith('/accounts/22214/trades', jasmine.any(Function));
    expect(component.trades.length).toBe(2);
    const headers = fixture.nativeElement.querySelectorAll('.ag-header-cell');
    expect(Array.from(headers).map((cell: any) => cell.textContent.trim())).toContain('SECURITY');
    expect(fixture.nativeElement.querySelectorAll('.ag-center-cols-container .ag-row').length).toBe(2);
    expect(component.pendingTrades).toEqual([]); fixture.destroy();
  }));

  it('does not fetch an absent account and uses stable prefixed row identities', () => {
    fixture.componentRef.setInput('account', undefined); fixture.detectChanges();
    expect(service.getTrades).not.toHaveBeenCalled(); expect(component.trades).toEqual([]);
    expect(component.getRowId({ data: TRADES[0] } as any)).toBe('Trade-A');
  });

  it('all-account scope fetches once and subscribes to each supplied account', () => {
    fixture.componentRef.setInput('allAccountsMode', true); fixture.componentRef.setInput('accountIds', [22214, 42422]);
    fixture.componentRef.setInput('accountNameById', { 22214: 'Test Account 20' }); fixture.detectChanges();
    expect(service.getAllTrades).toHaveBeenCalledTimes(1); expect(service.getTrades).not.toHaveBeenCalled();
    expect(callbacks.has('/accounts/22214/trades')).toBeTrue(); expect(callbacks.has('/accounts/42422/trades')).toBeTrue();
    expect((component.trades[0] as any).accountDisplayName).toBe('Test Account 20');
  });

  it('cleans up old subscriptions on a scope change and on destruction', () => {
    fixture.componentRef.setInput('account', ACCOUNT); fixture.detectChanges();
    const stop = unsubscribes.get('/accounts/22214/trades')!;
    fixture.componentRef.setInput('account', { id: 42422, displayName: 'Algo' }); fixture.detectChanges();
    expect(stop).toHaveBeenCalledTimes(1);
    const second = unsubscribes.get('/accounts/42422/trades')!; fixture.destroy(); expect(second).toHaveBeenCalledTimes(1);
  });

  it('queues a feed event while the initial response is pending and drains it once', () => {
    const initial = new Subject<any[]>(); service.getTrades.and.returnValue(initial);
    fixture.componentRef.setInput('account', ACCOUNT); fixture.detectChanges();
    callbacks.get('/accounts/22214/trades')!(TRADES[0]); expect(component.pendingTrades.length).toBe(1);
    const applyTransaction = jasmine.createSpy('applyTransaction');
    component.gridApi = { getRowNode: () => undefined, applyTransaction } as any;
    initial.next([]);
    expect(applyTransaction).toHaveBeenCalledWith(jasmine.objectContaining({ add: [jasmine.objectContaining({ id: 'A' })] }));
    expect(component.pendingTrades).toEqual([]); expect(component.isPending).toBeFalse();
  });

  it('updates the existing trade rather than inserting another row with the same identity', () => {
    fixture.componentRef.setInput('account', ACCOUNT); fixture.detectChanges();
    const applyTransaction = jasmine.createSpy('applyTransaction');
    const getRowNode = jasmine.createSpy('getRowNode').and.returnValue({ data: { ...TRADES[0] } });
    component.gridApi = { getRowNode, applyTransaction } as any;
    callbacks.get('/accounts/22214/trades')!({ ...TRADES[0], state: 'Updated' });
    expect(getRowNode).toHaveBeenCalledWith('Trade-A');
    expect(applyTransaction).toHaveBeenCalledWith({ update: [jasmine.objectContaining({ id: 'A', state: 'Updated' })] });
  });
});
