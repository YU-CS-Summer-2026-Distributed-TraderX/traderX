import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Api } from './api';
import { BlotterPanel } from './blotter-panel';
import { LEGACY_SCOPE, RunRow, readActive, readActiveRunTrades, readRegistry, scopesOf, topicsFor, urlsFor } from './run-scope';

const run = (projection_scope: string, phase: string, cluster_epoch: string | null = null): RunRow =>
  ({ projection_scope, phase, cluster_epoch, event_id_scheme: 'epoch-v1', descriptor_hash: null, checkpoint_seq: 0 });
const legacy = run(LEGACY_SCOPE, 'LEGACY');

describe('run scope rules (pure)', () => {
  it('treats only a 404 registry as unmanaged; every other failure is unavailable', () => {
    expect(readRegistry(404, null)).toEqual({ kind: 'unmanaged' });
    expect(readRegistry(500, { error: 'x' }).kind).toBe('unavailable');
    expect(readRegistry(0, null).kind).toBe('unavailable');
    expect(readRegistry(200, '<html>').kind).toBe('unavailable');   // SPA fallback wearing a 200
    expect(readRegistry(200, [{ nope: 1 }]).kind).toBe('unavailable');
    expect(readRegistry(200, [legacy])).toEqual({ kind: 'managed', runs: [legacy] });
  });

  it('accepts only the exact active-reader contract; 503/other shapes are not a scope', () => {
    expect(readActive(200, { projectionScope: 'run_b' })).toEqual({ ok: true, scope: 'run_b' });
    expect(readActive(503, { error: 'x' }).ok).toBeFalse();
    expect(readActive(0, null).ok).toBeFalse();
    expect(readActive(200, '<html>').ok).toBeFalse();
    expect(readActive(200, { projectionScope: 'a', phase: 'ACTIVE' }).ok).toBeFalse();
    expect(readActive(200, { scope: 'a' }).ok).toBeFalse();
  });

  it('reads rows without projectionScope (pre-RI06 service) as legacy', () => {
    expect([...scopesOf([{ a: 1 }], [{ projectionScope: 'x' }])].sort()).toEqual([LEGACY_SCOPE, 'x']);
  });

  it('uses the managed subjects for a managed scope and the legacy subjects for legacy-unknown', () => {
    expect(topicsFor(LEGACY_SCOPE, 7)).toEqual(['/accounts/7/trades', '/accounts/7/positions']);
    expect(topicsFor('run_b', 7)).toEqual(['/v2/projections/run_b/accounts/7/trades', '/v2/projections/run_b/accounts/7/positions']);
  });

  it('reads a named scope only through the explicit scoped routes', () => {
    expect(urlsFor({ kind: 'history', scope: 'a/b' }, 3, true)).toEqual({
      positions: '/position-service/v2/projections/a%2Fb/accounts/3/positions',
      trades: '/position-service/v2/projections/a%2Fb/accounts/3/trades',
      orders: '/trade-processor/v2/projections/a%2Fb/accounts/3/orders?status=all',
    });
    expect(urlsFor({ kind: 'active' }, 3, false).orders).toBe('/trade-processor/accounts/3/orders');
  });
});

describe('readActiveRunTrades (Admin trade list; scripted loader — mock)', () => {
  const loader = (answers: Record<string, { status: number; body: unknown }[]>) => {
    const seen: string[] = [];
    const load = async (u: string) => { seen.push(u); const q = answers[u]; return q?.length ? q.shift()! : { status: 404, body: null }; };
    return { load, seen };
  };
  const R = '/position-service/v2/projections', A = R + '/active';
  const reg = { status: 200, body: [legacy, run('b', 'ACTIVE')] };
  const ptr = (s: string) => ({ status: 200, body: { projectionScope: s } });

  it('reads the pointer scope through the scoped reader and revalidates', async () => {
    const { load, seen } = loader({ [R]: [reg], [A]: [ptr('b'), ptr('b')],
      [R + '/b/accounts/1/trades']: [{ status: 200, body: [{ id: 'e1-b-1-B', projectionScope: 'b' }] }] });
    const r = await readActiveRunTrades(load, 1);
    expect(r).toEqual({ ok: true, scope: 'b', managed: true, rows: [{ id: 'e1-b-1-B', projectionScope: 'b' }] });
    expect(seen.filter(u => u === A).length).toBe(2);
  });
  it('refuses on pointer 503, pointer move, and foreign rows; never infers', async () => {
    expect((await readActiveRunTrades(loader({ [R]: [reg], [A]: [{ status: 503, body: {} }] }).load, 1)).ok).toBeFalse();
    const moved = await readActiveRunTrades(loader({ [R]: [reg], [A]: [ptr('b'), ptr('c')],
      [R + '/b/accounts/1/trades']: [{ status: 200, body: [] }] }).load, 1);
    expect(moved).toEqual({ ok: false, error: 'active run changed during the read (b → c)' });
    const foreign = await readActiveRunTrades(loader({ [R]: [reg], [A]: [ptr('b'), ptr('b')],
      [R + '/b/accounts/1/trades']: [{ status: 200, body: [{ id: 'x', projectionScope: LEGACY_SCOPE }] }] }).load, 1);
    expect(foreign.ok).toBeFalse();
  });
  it('registry 404 keeps the unversioned read (unmanaged)', async () => {
    const r = await readActiveRunTrades(loader({ '/position-service/trades/1': [{ status: 200, body: [{ id: 'T' }] }] }).load, 1);
    expect(r).toEqual({ ok: true, scope: LEGACY_SCOPE, managed: false, rows: [{ id: 'T' }] });
  });
});

/**
 * MOCK-ONLY: the Api is a stub whose replies the test releases by hand (and which IGNORES abort, like
 * a backend that never answers), so reply order, lateness and bus events can be forced. This checks
 * the panel's fencing and fail-closed logic, not any backend.
 */
describe('blotter run context (stubbed Api — mock)', () => {
  type Pending = { url: string; resolve: (r: { status: number; body: unknown }) => void };
  let pending: Pending[];
  let subs: { topics: string[]; onMsg: () => void; closed: boolean }[];
  let posts: string[];
  let panel: BlotterPanel;
  const tick = (ms = 0) => new Promise(r => setTimeout(r, ms));

  const reply = (match: (u: string) => boolean, status: number, body: unknown) => {
    const i = pending.findIndex(p => match(p.url));
    expect(i).withContext('no pending request for ' + match).toBeGreaterThanOrEqual(0);
    pending.splice(i, 1)[0].resolve({ status, body });
  };
  const isReg = (u: string) => u.endsWith('/v2/projections');
  const isAct = (u: string) => u.endsWith('/v2/projections/active');
  /** Stage 1 of a poll: registry list (+ active pointer on the active view). */
  const head = async (reg: unknown, active?: unknown, regStatus = 200, actStatus = 200) => {
    reply(isReg, regStatus, reg);
    if (active !== undefined) reply(isAct, actStatus, active);
    await tick();
  };
  const rows = async (r: { p?: unknown[]; t?: unknown[]; o?: unknown[] }) => {
    reply(u => u.includes('/positions'), 200, r.p ?? []);
    reply(u => u.includes('/trades'), 200, r.t ?? []);
    reply(u => u.includes('/orders'), 200, r.o ?? []);
    await tick();
  };
  /** A whole managed active-view poll: pointer, scoped rows, pointer again. */
  const activePoll = async (reg: RunRow[], scope: string, r: { p?: unknown[]; t?: unknown[]; o?: unknown[] }, again = scope) => {
    await head(reg, { projectionScope: scope });
    await rows(r);
    reply(isAct, 200, { projectionScope: again });
    await tick();
  };
  const historyPoll = async (reg: RunRow[], r: { p?: unknown[]; t?: unknown[]; o?: unknown[] }) => { await head(reg); await rows(r); };

  const pos = (security: string, scope: string) =>
    ({ accountId: 1, security, quantity: 1, averageCostBasis: 1, projectionScope: scope });
  const order = (id: string, scope: string) =>
    ({ id, security: 'X', side: 'Buy', quantity: 1, remainingQuantity: 1, limitPrice: 1, status: 'NEW', projectionScope: scope });
  const trade = (id: string, scope: string) =>
    ({ id, accountId: 1, security: 'X', side: 'Buy', quantity: 1, price: 1, state: 'Processing', projectionScope: scope });

  beforeEach(() => {
    pending = []; subs = []; posts = [];
    const stub = {
      accounts: signal([]), contracts: signal([]), prices: signal({}),
      load: (url: string) => new Promise(resolve => pending.push({ url, resolve })),
      post: (url: string) => { posts.push(url); return Promise.resolve({ status: 200, body: {} }); },
      busSubscribe: (topics: string[], onMsg: () => void) => {
        const s = { topics, onMsg, closed: false };
        subs.push(s);
        return () => { s.closed = true; };
      },
      watchPrices: () => {}, noteEpoch: () => {}, traceForOrderRef: () => undefined, log: () => {},
    };
    TestBed.configureTestingModule({ providers: [{ provide: Api, useValue: stub }] });
    panel = TestBed.runInInjectionContext(() => new BlotterPanel());
    panel.accountId.set(1);
  });

  const openSubs = () => subs.filter(s => !s.closed).map(s => s.topics);
  const shown = () => [...panel.rawPositions(), ...panel.rawTrades(), ...panel.rawOpenOrders()].length;
  const reg2 = [run(LEGACY_SCOPE, 'SEALED'), run('b', 'ACTIVE')];

  it('R1: managed active view reads the pointer scope through scoped routes and subscribes to it', async () => {
    void panel.poll();
    await head(reg2, { projectionScope: 'b' });
    expect(pending.map(p => p.url)).toContain('/position-service/v2/projections/b/accounts/1/positions');
    expect(pending.map(p => p.url)).toContain('/trade-processor/v2/projections/b/accounts/1/orders');
    await rows({ o: [order('b-1', 'b')] });
    reply(isAct, 200, { projectionScope: 'b' }); await tick();
    expect(panel.active()).toEqual({ scope: 'b', basis: 'selected pointer, GET /v2/projections/active' });
    expect(panel.cancellable(panel.rawOpenOrders()[0])).toBeTrue();
    expect(openSubs()).toEqual([['/v2/projections/b/accounts/1/trades', '/v2/projections/b/accounts/1/positions']]);
  });

  it('R1: pointer 503 shows nothing and infers nothing, even with a sole ACTIVE run listed', async () => {
    void panel.poll();
    await head(reg2, { error: 'active projection scope unavailable' }, 200, 503);
    expect(pending.length).toBe(0);                       // no row reads issued at all
    expect(panel.active().scope).toBeNull();
    expect(panel.readError()).toContain('active run: HTTP 503');
    expect(panel.actionable()).toBeFalse();
    expect(openSubs()).toEqual([]);
  });

  it('R1: pointer moving between the first read and revalidation shows nothing and re-reads the new run', async () => {
    void panel.poll();
    await activePoll(reg2, 'b', { o: [order('b-1', 'b')] }, 'c');
    expect(shown()).toBe(0);
    expect(panel.notice()).toContain('b → c');
    await activePoll([...reg2, run('c', 'ACTIVE')], 'c', { o: [order('c-1', 'c')] });
    expect(panel.rawOpenOrders().map(o => o.orderId)).toEqual(['c-1']);
    expect(openSubs()).toEqual([['/v2/projections/c/accounts/1/trades', '/v2/projections/c/accounts/1/positions']]);
  });

  it('R1: pointer changing between polls announces the change and resets', async () => {
    void panel.poll();
    await activePoll(reg2, 'b', { o: [order('b-1', 'b')] });
    void panel.poll();
    await head([...reg2, run('c', 'ACTIVE')], { projectionScope: 'c' });
    expect(panel.notice()).toContain('b → c');
    expect(shown()).toBe(0);
    expect(openSubs()).toEqual([]);
  });

  it('R1: a pointer to a scope missing from the registry list is refused', async () => {
    void panel.poll();
    await head(reg2, { projectionScope: 'ghost' });
    expect(panel.readError()).toContain('not in the registry list');
    expect(pending.length).toBe(0);
  });

  it('R2: history populated, then a response with foreign rows clears rows, detail state and subscription', async () => {
    panel.onView('a');
    await historyPoll([...reg2, run('a', 'SEALED')], { o: [order('a-1', 'a')], t: [trade('e1-a-1-B', 'a')] });
    expect(shown()).toBe(2);
    panel.toggleOrder('a-1'); panel.openId.set('e1-a-1-B');
    expect(openSubs().length).toBe(1);
    void panel.poll();
    await historyPoll([...reg2, run('a', 'SEALED')], { o: [order('a-1', 'a'), order('b-9', 'b')] });
    expect(shown()).toBe(0);
    expect(panel.expanded()).toEqual({});
    expect(panel.openId()).toBeNull();
    expect(openSubs()).toEqual([]);
    expect(panel.readError()).toContain('outside run a');
  });

  it('R2: history populated, then the registry disappears (404) clears everything', async () => {
    panel.onView('a');
    await historyPoll([run('a', 'SEALED')], { p: [pos('A', 'a')] });
    expect(shown()).toBe(1);
    void panel.poll();
    await head(null, undefined, 404);
    expect(shown()).toBe(0);
    expect(openSubs()).toEqual([]);
  });

  it('R2: active populated, then pointer failure clears rows and disables actions', async () => {
    void panel.poll();
    await activePoll(reg2, 'b', { o: [order('b-1', 'b')] });
    const o = panel.rawOpenOrders()[0];
    void panel.poll();
    await head(reg2, null, 200, 503);
    expect(shown()).toBe(0);
    expect(panel.cancellable(o)).toBeFalse();
    await panel.cancelOrder(o);
    expect(posts).toEqual([]);
    expect(openSubs()).toEqual([]);
  });

  it('R2: active rows naming another run than the pointer are refused whole', async () => {
    void panel.poll();
    await activePoll(reg2, 'b', { p: [pos('OLD', LEGACY_SCOPE)], o: [order('b-1', 'b')] });
    expect(shown()).toBe(0);
    expect(panel.readError()).toContain('outside run b');
  });

  it('R3: a hung read times out, fails closed, and its late reply cannot restore rows or subscriptions', async () => {
    panel.readDeadlineMs = 20;
    void panel.poll();
    await activePoll(reg2, 'b', { o: [order('b-1', 'b')] });
    expect(shown()).toBe(1);
    void panel.poll();
    await head(reg2, { projectionScope: 'b' });            // row reads now hang
    await tick(60);
    expect(panel.state()).toBe('unavailable');
    expect(panel.readError()).toContain('timed out');
    expect(shown()).toBe(0);
    expect(panel.actionable()).toBeFalse();
    expect(openSubs()).toEqual([]);
    await rows({ o: [order('b-1', 'b')] });                // late answers arrive
    await tick();
    expect(shown()).toBe(0);
    expect(openSubs()).toEqual([]);
    expect(pending.filter(p => isAct(p.url)).length).toBe(0); // no revalidation issued either
  });

  it('R3: a newer poll still wins after an older one timed out', async () => {
    panel.readDeadlineMs = 20;
    void panel.poll();                                     // hangs entirely
    await tick(60);
    expect(panel.state()).toBe('unavailable');
    void panel.poll();
    const stale = pending.splice(0, 2);                    // the timed-out poll's head requests
    await activePoll(reg2, 'b', { o: [order('b-1', 'b')] });
    expect(panel.state()).toBe('ok');
    stale.forEach(p => p.resolve({ status: 200, body: reg2 }));
    await tick();
    expect(panel.rawOpenOrders().length).toBe(1);
  });

  it('R3: destroy disposes pending work; replies after it change nothing', async () => {
    void panel.poll();
    panel.ngOnDestroy();
    await head(reg2, { projectionScope: 'b' });
    expect(pending.length).toBe(0);                        // went no further: no row reads issued
    expect(shown()).toBe(0);
    expect(openSubs()).toEqual([]);
  });

  it('drops a slow reply for the previous account after a switch (A→B→A)', async () => {
    void panel.poll();
    panel.accountId.set(2); panel.onAccount();
    panel.accountId.set(1); panel.onAccount();
    await tick();                                              // aborted old polls settle
    expect(panel.state()).withContext('an aborted old-context poll must not mark the new one').toBe('loading');
    expect(panel.readError()).toBe('');
    await head([legacy], { projectionScope: LEGACY_SCOPE });   // first poll's head: dropped
    await head([legacy], { projectionScope: LEGACY_SCOPE });   // second poll's head: dropped
    expect(pending.filter(p => p.url.includes('/positions')).length).toBe(0);
    await activePoll([legacy], LEGACY_SCOPE, { p: [pos('FRESH', LEGACY_SCOPE)] });
    expect(panel.rawPositions().map(p => p.security)).toEqual(['FRESH']);
    expect(openSubs()).toEqual([['/accounts/1/trades', '/accounts/1/positions']]);
  });

  it('a bus event from the old context cannot trigger a read after a scope switch', async () => {
    void panel.poll();
    await activePoll(reg2, 'b', {});
    const old = subs[subs.length - 1];
    panel.onView(LEGACY_SCOPE);
    expect(old.closed).toBeTrue();
    const before = pending.length;
    old.onMsg();
    expect(pending.length).toBe(before);
  });

  it('history view offers no row actions', async () => {
    panel.onView('a');
    await historyPoll([run('a', 'SEALED')], { o: [order('a-1', 'a')] });
    expect(panel.cancellable(panel.rawOpenOrders()[0])).toBeFalse();
    await panel.cancelOrder(panel.rawOpenOrders()[0]);
    await panel.settle({ id: 'e1-a-1-B' } as any);
    expect(posts).toEqual([]);
  });

  it('unmanaged backend (registry 404) keeps the legacy reads and subjects', async () => {
    void panel.poll();
    await head(null, { status: 404 }, 404, 404);
    expect(pending.map(p => p.url)).toContain('/position-service/positions/1');
    await rows({ p: [{ accountId: 1, security: 'IBM', quantity: 1, averageCostBasis: 1 }] });
    expect(panel.rawPositions().map(p => p.security)).toEqual(['IBM']);
    expect(panel.active().basis).toContain('unmanaged');
    expect(openSubs()).toEqual([['/accounts/1/trades', '/accounts/1/positions']]);
  });

  it('out-of-order polls in one context apply only the newest', async () => {
    void panel.poll(); void panel.poll();
    const first = pending.splice(0, 2);
    await activePoll([legacy], LEGACY_SCOPE, { p: [pos('NEW', LEGACY_SCOPE)] });
    first[0].resolve({ status: 200, body: [legacy] }); first[1].resolve({ status: 200, body: { projectionScope: LEGACY_SCOPE } });
    await tick();
    expect(pending.length).toBe(0);                        // the older poll went no further
    expect(panel.rawPositions().map(p => p.security)).toEqual(['NEW']);
  });
});
