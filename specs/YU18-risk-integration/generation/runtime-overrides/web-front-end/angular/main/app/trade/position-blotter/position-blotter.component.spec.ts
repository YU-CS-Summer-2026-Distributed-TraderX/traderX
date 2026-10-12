import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { AgGridModule } from 'ag-grid-angular';
import { CommonModule } from '@angular/common';
import { of } from 'rxjs';
import { PriceSnapshotService } from 'main/app/service/price-snapshot.service';
import { PositionBlotterComponent } from './position-blotter.component';
import { PositionService } from 'main/app/service/position.service';
import { TradeFeedService } from 'main/app/service/trade-feed.service';

const ACCOUNT = { id: 22214, displayName: 'Test Account 20' };
const POSITIONS = [{ accountid: 22214, security: 'IBM', quantity: 10 }, { accountid: 22214, security: 'MS', quantity: 20 }] as any[];

describe('Position blotter scope and upsert', () => {
  let fixture: ComponentFixture<PositionBlotterComponent>;
  let component: PositionBlotterComponent;
  let service: any;
  let callbacks: Map<string, Function>;
  let unsubscribes: Map<string, jasmine.Spy>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('PositionService', ['getPositions', 'getAllPositions']);
    service.getPositions.and.returnValue(of(POSITIONS)); service.getAllPositions.and.returnValue(of(POSITIONS));
    callbacks = new Map(); unsubscribes = new Map();
    const feed = { subscribe: jasmine.createSpy('subscribe').and.callFake((topic: string, callback: Function) => {
      callbacks.set(topic, callback); const stop = jasmine.createSpy('stop'); unsubscribes.set(topic, stop); return stop;
    }) };
    await TestBed.configureTestingModule({ declarations: [PositionBlotterComponent], imports: [CommonModule, AgGridModule],
      providers: [{ provide: PositionService, useValue: service }, { provide: TradeFeedService, useValue: feed },
        { provide: PriceSnapshotService, useValue: { getPrices: () => of([]) } }]
    }).compileComponents();
    fixture = TestBed.createComponent(PositionBlotterComponent); component = fixture.componentInstance;
  });

  it('binds the account before loading and renders actual position rows', fakeAsync(() => {
    fixture.componentRef.setInput('account', ACCOUNT); fixture.detectChanges(); tick(100); fixture.detectChanges();
    expect(service.getPositions).toHaveBeenCalledWith(22214);
    expect(fixture.nativeElement.querySelectorAll('.ag-center-cols-container .ag-row').length).toBe(2);
    expect(fixture.nativeElement.querySelector('.ag-center-cols-container [col-id="security"]').textContent).toContain('IBM');
    expect(fixture.nativeElement.querySelector('.ag-center-cols-container [col-id="quantity"]').textContent).toContain('10');
    fixture.destroy();
  }));

  it('does not query an absent or nonpositive account', () => {
    fixture.componentRef.setInput('account', { id: 0, displayName: 'None' }); fixture.detectChanges();
    expect(service.getPositions).not.toHaveBeenCalled(); expect(component.positions).toEqual([]);
  });

  it('upserts an existing security rather than adding a duplicate row', () => {
    const applyTransaction = jasmine.createSpy('applyTransaction');
    const row = { data: { security: 'IBM', quantity: 10 } };
    const getRowNode = jasmine.createSpy('getRowNode').and.returnValue(row);
    component.gridApi = { getRowNode, applyTransaction, forEachNode: (visit: Function) => visit(row) } as any;
    component.update({ security: 'IBM', quantity: 35, updated: '2026-03-31T00:00:00.000Z' });
    expect(getRowNode).toHaveBeenCalledWith('Position-IBM');
    expect(applyTransaction).toHaveBeenCalledWith({ update: [jasmine.objectContaining({ security: 'IBM', quantity: 35 })] });
  });

  it('adds a new security and ignores updates without a security identity', () => {
    const applyTransaction = jasmine.createSpy('applyTransaction');
    component.gridApi = { getRowNode: () => undefined, applyTransaction, forEachNode: () => {} } as any;
    component.update({ security: 'MS', quantity: 5, accountid: 22214 });
    expect(applyTransaction).toHaveBeenCalledWith(jasmine.objectContaining({ add: [jasmine.objectContaining({ security: 'MS', quantity: 5 })] }));
    applyTransaction.calls.reset(); component.update({ quantity: 99 }); expect(applyTransaction).not.toHaveBeenCalled();
  });

  it('keeps the row identity callback usable without component context', () => {
    const detached = component.getRowId;
    expect(detached({ data: { security: 'IBM' } } as any)).toBe('Position-IBM');
  });

  it('queues a position when the grid is absent, then drains the queue', () => {
    component.update({ security: 'IBM', quantity: 5 }); expect(component.pendingPosition.length).toBe(1);
    const applyTransaction = jasmine.createSpy('applyTransaction');
    component.gridApi = { getRowNode: () => undefined, applyTransaction, forEachNode: () => {} } as any;
    component.processPendingPositions();
    expect(applyTransaction).toHaveBeenCalledTimes(1); expect(component.pendingPosition).toEqual([]); expect(component.isPending).toBeFalse();
  });

  it('all-account scope combines quantities by security and listens to each supplied account', () => {
    service.getAllPositions.and.returnValue(of([...POSITIONS, { accountid: 42422, security: 'IBM', quantity: 5 }]));
    fixture.componentRef.setInput('allAccountsMode', true); fixture.componentRef.setInput('accountIds', [22214, 42422]); fixture.detectChanges();
    expect(component.positions.find((row: any) => row.security === 'IBM').quantity).toBe(15);
    expect(component.positions.length).toBe(2); expect(service.getAllPositions).toHaveBeenCalledTimes(1);
    expect(callbacks.has('/accounts/22214/positions')).toBeTrue(); expect(callbacks.has('/accounts/42422/positions')).toBeTrue();
  });

  it('replaces account subscriptions and releases them on destruction', () => {
    fixture.componentRef.setInput('account', ACCOUNT); fixture.detectChanges();
    const stop = unsubscribes.get('/accounts/22214/positions')!;
    fixture.componentRef.setInput('account', { id: 42422, displayName: 'Algo' }); fixture.detectChanges(); expect(stop).toHaveBeenCalledTimes(1);
    const second = unsubscribes.get('/accounts/42422/positions')!; fixture.destroy(); expect(second).toHaveBeenCalledTimes(1);
  });
});
