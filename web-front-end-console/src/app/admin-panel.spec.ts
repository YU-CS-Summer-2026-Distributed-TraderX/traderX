import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { Api } from './api';
import { AdminPanel } from './admin-panel';

// Actual component with a scripted transport that deliberately ignores AbortSignal.
describe('Admin bounded run reads', () => {
  let panel: AdminPanel;
  let mode: 'ok' | 'algo' | 'both' | 'refusal-and-algo' = 'ok';
  let scope: string;
  let late: (() => void)[];
  let signals: AbortSignal[];
  let api: any;
  beforeEach(() => {
    mode = 'ok'; scope = 'a'; late = []; signals = [];
    api = { load: jasmine.createSpy('load').and.callFake((url: string, init?: RequestInit) => {
      if (init?.signal) signals.push(init.signal);
      const body = url.endsWith('/active') ? { projectionScope: scope }
        : url.endsWith('/v2/projections') ? [{ projection_scope: scope, phase: 'ACTIVE' }]
        : url === '/algo/orders' ? [] : [{ id: `e1-${scope}-${url.match(/accounts\/(\d+)/)?.[1]}-B`, projectionScope: scope }];
      if (mode === 'both' || ((mode === 'algo' || mode === 'refusal-and-algo') && url === '/algo/orders')) {
        return new Promise(resolve => late.push(() => resolve({ status: 200, body })));
      }
      if (mode === 'refusal-and-algo' && url.endsWith('/active')) return Promise.resolve({ status: 503, body: null });
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

  it('clears a refused active reader even while algo hangs, and does not restore it late', fakeAsync(() => {
    void panel.poll(); flushMicrotasks(); expect(panel.run().ok).toBeTrue();
    mode = 'refusal-and-algo'; void panel.poll(); flushMicrotasks(); tick(2500); flushMicrotasks();
    expect(panel.run().ok).toBeFalse(); expect(panel.trades()).toEqual([]);
    late.forEach(resolve => resolve()); flushMicrotasks();
    expect(panel.run().ok).toBeFalse(); expect(panel.trades()).toEqual([]);
  }));

  it('cannot restore an old scope or account after a newer account poll succeeds', fakeAsync(() => {
    void panel.poll(); flushMicrotasks(); expect(panel.trades()[0].id).toBe('e1-a-22214-B');
    mode = 'both'; void panel.poll(); flushMicrotasks();
    scope = 'b'; mode = 'ok'; panel.accountId = 11413; panel.onAccount(); flushMicrotasks();
    expect(panel.run().text).toContain('Active run: b'); expect(panel.trades()[0].id).toBe('e1-b-11413-B');
    late.forEach(resolve => resolve()); flushMicrotasks(); tick(2500); flushMicrotasks();
    expect(panel.run().text).toContain('Active run: b'); expect(panel.trades().map(t=>t.id)).toEqual(['e1-b-11413-B']);
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
