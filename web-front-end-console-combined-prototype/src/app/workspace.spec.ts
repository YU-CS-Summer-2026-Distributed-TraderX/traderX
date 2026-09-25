import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { ADMIN_TABS, FEATURES, TRADER_TABS } from './features';
import { INSTRUMENTS, USERS } from './fixtures';
import { STALE_AFTER_MS, avgAfterFill, crosses, freshnessOf, valueOf } from './desk';
import { SESSION_KEY, Session, accountKey, canUse, chooseAccount, prefsKey, readPrefs, readSession, userById } from './session';
import { plain, OrdersPage, MarketsPage } from './trader';
import { Desk } from './desk';

const A = userById('trader.a')!;
const B = userById('trader.b')!;
const clear = () => { localStorage.clear(); sessionStorage.clear(); };

describe('session records', () => {
  it('rejects expired, unknown-user and malformed records', () => {
    const now = 1_000_000;
    expect(readSession(JSON.stringify({ userId: 'trader.a', issuedAt: 0, expiresAt: now + 1 }), now)?.userId).toBe('trader.a');
    expect(readSession(JSON.stringify({ userId: 'trader.a', issuedAt: 0, expiresAt: now }), now)).toBeNull();
    expect(readSession(JSON.stringify({ userId: 'mallory', issuedAt: 0, expiresAt: now + 1 }), now)).toBeNull();
    expect(readSession('{not json', now)).toBeNull();
    expect(readSession(null, now)).toBeNull();
  });

  it('keys preferences and the selected account by identity', () => {
    expect(prefsKey('trader.a')).not.toBe(prefsKey('trader.b'));
    expect(accountKey('trader.a')).not.toBe(accountKey('trader.b'));
  });

  it('never keeps a remembered account the identity is not entitled to', () => {
    expect(chooseAccount(B, 22214)).toBe(51010);
    expect(chooseAccount(A, 42422)).toBe(42422);
    expect(chooseAccount(A, null)).toBe(22214);
  });

  it('offers the admin workspace only to admins', () => {
    expect(canUse(A, 'admin')).toBeFalse();
    expect(canUse(userById('ops.admin'), 'admin')).toBeTrue();
    expect(canUse(undefined, 'trader')).toBeFalse();
  });

  it('falls back to defaults on corrupt preferences', () => {
    expect(readPrefs('garbage').watchlist.length).toBeGreaterThan(0);
  });
});

describe('Session service: two users, one browser', () => {
  beforeEach(clear);
  afterEach(clear);

  it('does not carry one user\'s preferences or account to the next', () => {
    const s = TestBed.inject(Session);
    s.signIn('trader.a');
    s.updatePrefs({ watchlist: ['MSFT'] });
    expect(s.selectAccount(42422)).toBeTrue();
    s.signOut();
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(s.user()).toBeUndefined();
    expect(s.account()).toBeNull();

    s.signIn('trader.b');
    expect(s.prefs().watchlist).not.toContain('MSFT');
    expect(s.account()).toBe(51010);
    expect(s.selectAccount(22214)).toBeFalse();       // UI mirror only; the server must refuse too
    expect(s.account()).toBe(51010);

    s.signOut();
    s.signIn('trader.a');                              // A's own settings come back for A
    expect(s.prefs().watchlist).toEqual(['MSFT']);
  });

  it('bumps the generation on identity and account change so caches are dropped', () => {
    const s = TestBed.inject(Session);
    const g0 = s.generation();
    s.signIn('trader.a');
    const g1 = s.generation();
    s.selectAccount(42422);
    expect(g1).toBeGreaterThan(g0);
    expect(s.generation()).toBeGreaterThan(g1);
  });

  it('ends an expired session on the next check', () => {
    const s = TestBed.inject(Session);
    s.signIn('trader.a');
    localStorage.setItem(SESSION_KEY, JSON.stringify({ userId: 'trader.a', issuedAt: 0, expiresAt: 1 }));
    (s as unknown as { record: { set(v: unknown): void } }).record.set({ userId: 'trader.a', issuedAt: 0, expiresAt: 1 });
    expect(s.check()).toBeFalse();
    expect(s.expired()).toBeTrue();
    expect(s.user()).toBeUndefined();
  });
});

describe('fixture desk rules', () => {
  it('reads freshness as live, stale or none', () => {
    const now = 100_000;
    expect(freshnessOf(undefined, now).kind).toBe('none');
    expect(freshnessOf({ price: 1, dir: 0, receivedAt: now - STALE_AFTER_MS }, now).kind).toBe('live');
    expect(freshnessOf({ price: 1, dir: 0, receivedAt: now - STALE_AFTER_MS - 1 }, now)).toEqual({ kind: 'stale', ageS: 15 });
  });

  it('values with the contract multiplier and gives no value without a price', () => {
    const opt = INSTRUMENTS.find(i => i.multiplier === 100)!;
    expect(valueOf({ account: 1, key: opt.key, quantity: 10, avgCost: 6 }, opt, { price: 7, dir: 0, receivedAt: 0 }))
      .toEqual({ value: 7000, upnl: 1000 });
    expect(valueOf({ account: 1, key: opt.key, quantity: 10, avgCost: 6 }, opt, undefined)).toEqual({});
  });

  it('averages in, keeps the average on a reduction, and restarts on a flip', () => {
    expect(avgAfterFill(100, 10, 100, 20)).toBe(15);
    expect(avgAfterFill(100, 10, -40, 20)).toBe(10);
    expect(avgAfterFill(100, 10, -150, 20)).toBe(20);
    expect(avgAfterFill(100, 10, -100, 20)).toBe(0);
    expect(avgAfterFill(0, 0, -50, 30)).toBe(30);
  });

  it('crosses only a marketable limit or a market order', () => {
    const t = { orderType: 'LIMIT' as const, timeInForce: 'GTC' as const, side: 'Buy' as const, quantity: 1 };
    expect(crosses({ ...t, limitPrice: 101 }, 100)).toBeTrue();
    expect(crosses({ ...t, limitPrice: 99 }, 100)).toBeFalse();
    expect(crosses({ ...t, side: 'Sell', limitPrice: 99 }, 100)).toBeTrue();
    expect(crosses({ ...t, orderType: 'STOP', stopPrice: 90 }, 100)).toBeFalse();
  });

  it('says wire field names as words', () => {
    expect(plain('limitPrice required')).toBe('limit price required');
    expect(plain('exactly one of trailAmount (positive) or trailPercentBps (1-5000) required'))
      .toBe('exactly one of trail amount (positive) or trail (bps) (1-5000) required');
  });
});

describe('navigation and feature map', () => {
  it('has four or five primary tabs per workspace', () => {
    for (const tabs of [TRADER_TABS, ADMIN_TABS]) expect(tabs.length).toBeGreaterThanOrEqual(4), expect(tabs.length).toBeLessThanOrEqual(5);
  });

  it('places every feature on a real tab of its own workspace or in its menu', () => {
    const ids = new Set<string>();
    for (const f of FEATURES) {
      expect(ids.has(f.id)).withContext(f.id).toBeFalse();
      ids.add(f.id);
      const tabs = (f.ws === 'admin' ? ADMIN_TABS : TRADER_TABS).map(t => t.id);
      expect(f.place === 'menu' || tabs.includes(f.place)).withContext(`${f.id} → ${f.place}`).toBeTrue();
    }
    for (const t of [...TRADER_TABS, ...ADMIN_TABS]) expect(FEATURES.some(f => f.place === t.id)).withContext(t.id).toBeTrue();
  });

  it('keeps every fixture user entitled to at least one account', () => {
    for (const u of USERS) expect(u.accounts.length).withContext(u.id).toBeGreaterThan(0);
  });
});

// Combined-candidate regressions: unit changes and historical context must not reuse a draft.
describe('combined ticket context', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear(); TestBed.configureTestingModule({}); });
  it('clears price, stop, trail and quantity when changing product units', fakeAsync(() => {
    const session = TestBed.inject(Session); session.signIn('trader.a');
    const ticket = TestBed.runInInjectionContext(() => new OrdersPage());
    TestBed.tick(); tick(300);
    ticket.limit.set(180); ticket.stop.set(170); ticket.trail.set(5); ticket.qty.set(200);
    ticket.setProduct('Treasury');
    expect(ticket.current().unit).toBe('fraction of par');
    expect(ticket.limit()).toBeUndefined(); expect(ticket.stop()).toBeUndefined(); expect(ticket.trail()).toBeUndefined();
    expect(ticket.qty()).toBe(100); expect(ticket.problem()).toContain('limit price');
    discardPeriodicTasks();
  }));
  it('clears a price when the instrument changes within one product', fakeAsync(() => {
    const session = TestBed.inject(Session); session.signIn('trader.a');
    const ticket = TestBed.runInInjectionContext(() => new OrdersPage()); TestBed.tick(); tick(300);
    ticket.limit.set(180); ticket.setInstrument('AAPL'); expect(ticket.limit()).toBeUndefined();
    discardPeriodicTasks();
  }));
  it('hides current orders and refuses direct mutation in a historical view', fakeAsync(() => {
    const session = TestBed.inject(Session); session.signIn('trader.a');
    const desk = TestBed.inject(Desk); TestBed.tick(); tick(300);
    const before = desk.accountOrders().map(o => ({...o}));
    desk.runView.set({kind:'history',scope:desk.runs[1].projection_scope});
    expect(desk.accountOrders()).toEqual([]);
    expect(desk.submit('IBM',{orderType:'LIMIT',timeInForce:'GTC',side:'Buy',quantity:10,limitPrice:1}).ok).toBeFalse();
    desk.cancel(before[0].ref); expect(desk.replace(before[0].ref,100,2)).toContain('read-only');
    desk.runView.set({kind:'active'}); expect(desk.accountOrders()).toEqual(before);
    discardPeriodicTasks();
  }));
  it('clears a draft on account change', fakeAsync(() => {
    const session = TestBed.inject(Session); session.signIn('trader.a');
    const ticket = TestBed.runInInjectionContext(() => new OrdersPage()); TestBed.tick(); tick(300);
    ticket.limit.set(180); session.selectAccount(42422); TestBed.tick(); tick(300);
    expect(ticket.limit()).toBeUndefined(); discardPeriodicTasks();
  }));
  it('does not carry an equity into an empty product catalog', fakeAsync(() => {
    const session=TestBed.inject(Session);session.signIn('trader.a');
    const ticket=TestBed.runInInjectionContext(()=>new OrdersPage());TestBed.tick();tick(300);
    spyOn(ticket.desk,'instrumentList').and.returnValue([]);
    ticket.setProduct('Option');ticket.limit.set(5);
    expect(ticket.key()).toBe('');expect(ticket.problem()).toContain('Choose an instrument');
    discardPeriodicTasks();
  }));

});

describe('market controls',()=>{
 beforeEach(()=>{localStorage.clear();sessionStorage.clear();TestBed.configureTestingModule({});});
 it('copies only a fresh price and preserves the selected order type',fakeAsync(()=>{
  TestBed.inject(Session).signIn('trader.a');const t=TestBed.runInInjectionContext(()=>new OrdersPage());TestBed.tick();tick(300);
  t.setType('ICEBERG');t.desk.marks.set({IBM:{price:123.456789,dir:0,receivedAt:t.desk.now()}});t.priceAtMarket();expect(t.limit()).toBe(123.456789);expect(t.type()).toBe('ICEBERG');
  t.desk.marks.set({IBM:{price:120,dir:0,receivedAt:t.desk.now()-60000}});t.priceAtMarket();expect(t.limit()).toBe(123.456789);expect(t.canPriceAtMarket()).toBeFalse();discardPeriodicTasks();
 }));
 it('sorts prices numerically, reverses direction, and keeps missing prices last',fakeAsync(()=>{
  TestBed.inject(Session).signIn('trader.a');const m=TestBed.runInInjectionContext(()=>new MarketsPage());TestBed.tick();tick(300);
  m.desk.marks.set({IBM:{price:9,dir:0,receivedAt:0},AAPL:{price:100,dir:0,receivedAt:0}});m.sort('last');expect(m.list().slice(0,2).map(i=>i.key)).toEqual(['IBM','AAPL']);m.sort('last');expect(m.list().slice(0,2).map(i=>i.key)).toEqual(['AAPL','IBM']);discardPeriodicTasks();
 }));
});

describe('gateway order capabilities',()=>{
 beforeEach(()=>{localStorage.clear();sessionStorage.clear();TestBed.configureTestingModule({});});
 it('enables typed submission only when the gateway advertises every required type',async()=>{
  const d=TestBed.inject(Desk);const load=spyOn(d.api,'load');
  load.and.resolveTo({status:200,body:{schemaVersion:1,typedOrders:true,orderTypes:['MARKET','LIMIT','STOP','STOP_LIMIT','ICEBERG','PEGGED','TRAILING_STOP']}});
  await d.refreshCapabilities();expect(d.typedOrdersEnabled()).toBeTrue();
  load.and.resolveTo({status:404,body:null});await d.refreshCapabilities();expect(d.typedOrdersEnabled()).toBeFalse();
  load.and.resolveTo({status:200,body:{schemaVersion:1,typedOrders:true,orderTypes:['LIMIT']}});await d.refreshCapabilities();expect(d.typedOrdersEnabled()).toBeFalse();
 });
});

 describe('accepted ticket reset',()=>{
  beforeEach(()=>{localStorage.clear();sessionStorage.clear();TestBed.configureTestingModule({});});
  it('clears accepted market quantity and prevents a second submit',fakeAsync(()=>{
   TestBed.inject(Session).signIn('trader.a');const t=TestBed.runInInjectionContext(()=>new OrdersPage());TestBed.tick();tick(300);
   t.setType('MARKET');t.qty.set(2);t.desk.marks.set({IBM:{price:180,dir:0,receivedAt:t.desk.now()}});
   const send=spyOn(t.desk,'submit').and.returnValue({ok:true,text:'Order accepted'});
   t.submit();tick();expect(t.qty()).toBeUndefined();expect(t.result()?.text).toBe('Order accepted');
   expect(t.problem()).toBe(t.quantityPrompt);
   t.submit();tick();expect(send).toHaveBeenCalledTimes(1);
   t.qty.set(1.5);expect(t.problem()).toBe('quantity must be a whole number');
   t.qty.set(3);expect(t.problem()).toBe('');expect(t.result()?.text).toBe('Order accepted');
   discardPeriodicTasks();
  }));
  it('guards an in-flight send, preserves refused drafts, and clears accepted prices',fakeAsync(()=>{
   TestBed.inject(Session).signIn('trader.a');const t=TestBed.runInInjectionContext(()=>new OrdersPage());TestBed.tick();tick(300);
   const instruments=t.desk.instrumentList();spyOn(t.desk,'instrumentList').and.returnValue(instruments);
   Object.defineProperty(t.desk,'connected',{value:true});t.desk.readState.set('ready');t.desk.typedOrdersEnabled.set(true);t.limit.set(180);t.qty.set(2);
   let resolve!:(r:{ok:boolean;text:string})=>void;
   const send=spyOn(t.desk,'submitRig').and.callFake(()=>new Promise(r=>resolve=r));
   t.submit();t.submit();expect(send).toHaveBeenCalledTimes(1);
   resolve({ok:false,text:'Outcome unknown'});tick();expect(t.qty()).toBe(2);expect(t.limit()).toBe(180);
   t.submit();resolve({ok:true,text:'Order accepted'});tick();expect(t.qty()).toBeUndefined();expect(t.limit()).toBeUndefined();expect(t.result()?.ok).toBeTrue();discardPeriodicTasks();
  }));
 });
