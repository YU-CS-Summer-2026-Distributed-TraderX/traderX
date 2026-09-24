import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { Api } from './api';
import { AdminPanel } from './admin-panel';

// Actual component with a scripted transport that deliberately ignores AbortSignal.
describe('Admin bounded run reads', () => {
  let panel: AdminPanel;
  let mode: 'ok' | 'algo' | 'both' = 'ok';
  let late: (() => void)[];
  let signals: AbortSignal[];
  let api: any;
  beforeEach(() => {
    mode = 'ok'; late = []; signals = [];
    api = { load: jasmine.createSpy('load').and.callFake((url: string, init?: RequestInit) => {
      if (init?.signal) signals.push(init.signal);
      const body = url.endsWith('/active') ? { projectionScope: 'a' }
        : url.endsWith('/v2/projections') ? [{ projection_scope: 'a', phase: 'ACTIVE' }]
        : url === '/algo/orders' ? [] : [{ id: 'e1-a-1-B', projectionScope: 'a' }];
      if (mode === 'both' || (mode === 'algo' && url === '/algo/orders')) {
        return new Promise(resolve => late.push(() => resolve({ status: 200, body })));
      }
      return Promise.resolve({ status: 200, body });
    }), log: () => {} };
    TestBed.configureTestingModule({ providers: [{ provide: Api, useValue: api }] });
    panel = TestBed.runInInjectionContext(() => new AdminPanel());
  });
  afterEach(() => panel.ngOnDestroy());

  it('clears previously actionable trades when only algo hangs; late results cannot restore them', fakeAsync(() => {
    void panel.poll(); flushMicrotasks(); expect(panel.run().ok).toBeTrue();
    mode = 'algo'; void panel.poll(); flushMicrotasks();
    tick(2500); flushMicrotasks();
    expect(panel.run().ok).toBeFalse(); expect(panel.trades()).toEqual([]);
    expect(panel.algoDown()).toBeTrue();
    api.load.calls.reset(); void panel.settle({ id: 'e1-a-1-B' } as any); flushMicrotasks();
    expect(api.load).not.toHaveBeenCalled();
    late.forEach(resolve => resolve()); flushMicrotasks();
    expect(panel.run().ok).toBeFalse(); expect(panel.trades()).toEqual([]);
  }));

  it('bounds abort-ignoring trade reads and preserves a newer successful poll', fakeAsync(() => {
    void panel.poll(); flushMicrotasks();
    mode = 'both'; void panel.poll(); flushMicrotasks(); tick(2500); flushMicrotasks();
    expect(panel.run().ok).toBeFalse();
    mode = 'ok'; void panel.poll(); flushMicrotasks(); expect(panel.run().ok).toBeTrue();
    late.forEach(resolve => resolve()); flushMicrotasks();
    expect(panel.run().ok).toBeTrue(); expect(panel.trades().length).toBe(1);
  }));

  it('aborts old context requests on account switch and destruction', fakeAsync(() => {
    mode = 'both'; void panel.poll(); flushMicrotasks(); const old = [...signals];
    mode = 'ok'; panel.accountId = 1001; panel.onAccount(); flushMicrotasks();
    expect(old.every(s => s.aborted)).toBeTrue(); expect(panel.run().ok).toBeTrue();
    late.forEach(resolve => resolve()); flushMicrotasks(); expect(panel.run().ok).toBeTrue();
    mode = 'both'; const start = signals.length; void panel.poll(); flushMicrotasks(); const pending = signals.slice(start); const count = api.load.calls.count();
    panel.ngOnDestroy(); flushMicrotasks();
    late.forEach(resolve => resolve()); flushMicrotasks(); tick(2500);
    expect(pending.every(s => s.aborted)).toBeTrue();
    expect(api.load.calls.count()).toBe(count);
  }));
});
