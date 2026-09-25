import { Api, nextClientOrderId, parseOcc, PriceMark } from '../../../web-front-end-console/src/app/api';
import { typedBody } from '../../../web-front-end-console/src/app/order-types';
import { ACTIVE_URL, readActive } from '../../../web-front-end-console/src/app/run-scope';
import { CONNECTED_RIG, readRigAccount } from './rig';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { TypedTicket, validateTicket, REASON_HINT, LIVE_STATUSES } from '../../../web-front-end-console/src/app/order-types';
import { Context } from '../../../web-front-end-console/src/app/run-scope';
import {
  ACCOUNTS, FixtureInstrument, FixtureOrder, FixturePosition, FixtureTrade, HISTORY_POSITIONS,
  INSTRUMENTS, ORDERS, POSITIONS, RUNS, TRADES,
} from './fixtures';
import { Session } from './session';

/**
 * The FIXTURE DESK: an in-memory stand-in for the order gateway, position service and price feed,
 * so the design can be walked without a rig. Orders placed here live in this browser tab's memory
 * and vanish on reload. Nothing is sent to any service.
 */

export type Scenario = 'normal' | 'stale' | 'unavailable' | 'refused' | 'slow';
export const SCENARIOS: { id: Scenario; label: string }[] = [
  { id: 'normal', label: 'Normal' },
  { id: 'slow', label: 'Slow reads (1.5 s)' },
  { id: 'stale', label: 'Price feed stopped' },
  { id: 'unavailable', label: 'Position service down' },
  { id: 'refused', label: 'Risk result refused' },
];

/** A price older than this is shown as stale rather than live. */
export const STALE_AFTER_MS = 15_000;

export interface Mark { price: number; dir: -1 | 0 | 1; receivedAt: number; }
export type Freshness = { kind: 'live' | 'stale'; ageS: number } | { kind: 'none' };
export type ReadState = 'loading' | 'ready' | 'unavailable' | 'refused';

export interface PositionRow extends FixturePosition {
  inst: FixtureInstrument; mark?: Mark; fresh: Freshness; value?: number; upnl?: number;
}

export function freshnessOf(mark: Mark | undefined, now: number): Freshness {
  if (!mark) return { kind: 'none' };
  const ageS = Math.max(0, Math.round((now - mark.receivedAt) / 1000));
  return { kind: now - mark.receivedAt > STALE_AFTER_MS ? 'stale' : 'live', ageS };
}

/** Market value and unrealized P&L: quantity × (price − average cost) × multiplier. No price, no number. */
export function valueOf(p: FixturePosition, inst: FixtureInstrument, mark: Mark | undefined) {
  if (!mark) return {};
  return {
    value: p.quantity * mark.price * inst.multiplier,
    upnl: p.quantity * (mark.price - p.avgCost) * inst.multiplier,
  };
}

/** A limit that crosses the current price fills at that price in the fixture; anything else rests. */
export function avgAfterFill(q0: number, avg0: number, signed: number, price: number): number {
  const q = q0 + signed;
  return q === 0 ? 0
    : q0 === 0 || Math.sign(signed) === Math.sign(q0) ? (q0 * avg0 + signed * price) / q
    : Math.sign(q) === Math.sign(q0) ? avg0 : price;
}

export function crosses(t: TypedTicket, price: number): boolean {
  if (t.orderType === 'MARKET') return true;
  if (t.orderType !== 'LIMIT' || !t.limitPrice) return false;
  return t.side === 'Buy' ? t.limitPrice >= price : t.limitPrice <= price;
}

const stamp = () => new Date().toISOString().slice(0, 19).replace('T', ' ');
const pick = (k: string) => INSTRUMENTS.find(i => i.key === k)!;

@Injectable({ providedIn: 'root' })
export class Desk {
  private session = inject(Session);
  readonly connected = inject(CONNECTED_RIG);
  readonly api = inject(Api);
  readonly error = signal('');
  readonly activeScope = signal<string|null>(null);
  readonly runLabel = signal('not confirmed');
  readonly directory = signal<FixtureInstrument[]>([]);
  readonly instrumentList = computed(()=>this.connected ? this.directory() : INSTRUMENTS);
  readonly accounts = computed(()=>this.connected ? this.api.accounts().map(a=>({id:a.id,name:a.displayName})) : ACCOUNTS);
  instrument(key: string): FixtureInstrument {
    return this.instrumentList().find(i=>i.key===key) ?? {key,name:key,cls:'Equity',source:'',unit:'unknown',qtyUnit:'units',multiplier:1,seed:0};
  }
  private polling = false;
  private directoryAt = 0;
  private lastPrices: Record<string,PriceMark> = {};
  private pollSequence = 0;
  private appliedPoll = 0;
  readonly actionBusy = signal(false);
  readonly typedOrdersEnabled = false;
  readonly canChangeExisting = computed(()=>!this.connected || this.activeScope()!==null);
  private confirmed = false;


  readonly scenario = signal<Scenario>(initialScenario());
  readonly now = signal(Date.now());
  readonly marks = signal<Record<string, Mark>>(this.connected ? {} : seedMarks(this.scenario()));

  // Shared "venue" state for this tab. In the real system these are shared, permissioned data.
  private readonly orders = signal<FixtureOrder[]>((this.connected ? [] : ORDERS).map(o => ({ ...o })));
  private readonly trades = signal<FixtureTrade[]>((this.connected ? [] : TRADES).map(t => ({ ...t })));
  private readonly positions = signal<FixturePosition[]>((this.connected ? [] : POSITIONS).map(p => ({ ...p })));
  private nextRef = 106;
  private nextTrade = 7816;

  /** Per identity: cleared on every sign-in, sign-out and user change. */
  readonly activity = signal<{ at: string; text: string; ok: boolean }[]>([]);

  /** Which run the Positions tab shows. History is read-only. */
  readonly runView = signal<{ kind: 'active' } | { kind: 'history'; scope: string }>({ kind: 'active' });
  readonly runRows = signal<import('../../../web-front-end-console/src/app/run-scope').RunRow[]>(this.connected ? [] : RUNS);
  get runs() { return this.runRows(); }

  /** Account-scoped read state, fenced by the console's own Context counter. */
  readonly readState = signal<ReadState>('loading');
  private ctx = new Context();
  private lastUser: string | undefined;

  constructor() {
    setInterval(() => this.now.set(Date.now()), 1000);
    if (!this.connected) setInterval(() => this.tick(), 2000);
    else {
      this.api.watchPrices();
      effect(()=>{
        const prices=this.api.prices();
        untracked(()=>this.marks.update(old=>Object.fromEntries(Object.entries(prices).map(([key,p])=>[key,{...p,receivedAt:old[key] && this.lastPrices[key]===p ? old[key].receivedAt : Date.now()}]))));
        this.lastPrices=prices;
      });
      setInterval(()=>{ if(this.session.user() && !this.polling) void this.refreshRig(false); },4000);
      void this.api.init().then(()=>this.loadDirectory());
    }
    // Identity or account changed: drop the old answers, show loading, then read again.
    effect(() => {
      this.session.generation();
      const user = this.session.user()?.id;
      untracked(() => {
        if (user !== this.lastUser) { this.activity.set([]); this.runView.set({ kind: 'active' }); this.lastUser = user; }
        this.reload();
      });
    });
  }

  setScenario(s: Scenario): void {
    if (this.connected) return;
    this.scenario.set(s);
    try { sessionStorage.setItem('tx-combined:scenario', s); } catch { /* storage blocked */ }
    this.marks.set(seedMarks(s));
    this.reload();
  }

  /** One read of the selected account; a reply for an older context is dropped. */
  reload(): void {
    if (this.connected) { void this.refreshRig(true); return; }
    const stampNo = this.ctx.next();
    this.readState.set('loading');
    const delay = this.scenario() === 'slow' ? 1500 : 250;
    setTimeout(() => {
      if (!this.ctx.isCurrent(stampNo)) return;
      const u = this.session.user();
      const a = this.session.account();
      if (!u || a === null || !u.accounts.includes(a)) { this.readState.set('refused'); return; }
      this.readState.set(this.scenario() === 'unavailable' ? 'unavailable' : 'ready');
    }, delay);
  }

  readonly accountName = computed(() => this.accounts().find(a => a.id === this.session.account())?.name ?? '');

  readonly positionRows = computed<PositionRow[]>(() => {
    const a = this.session.account();
    const v = this.runView();
    const src = !this.connected && v.kind === 'history' ? HISTORY_POSITIONS : this.positions();
    const now = this.now();
    return src.filter(p => p.account === a && p.quantity !== 0).map(p => {
      const inst = this.instrument(p.key);
      const mark = this.marks()[p.key];
      return { ...p, inst, mark, fresh: freshnessOf(mark, now), ...valueOf(p, inst, freshnessOf(mark,now).kind==='live' && inst.unit!=='unknown' ? mark : undefined) };
    });
  });

  readonly accountOrders = computed(() =>
    !this.connected && this.runView().kind === 'history' ? [] : this.orders().filter(o => o.account === this.session.account()).sort((x, y) => y.ref - x.ref));
  readonly workingOrders = computed(() => this.accountOrders().filter(o => LIVE_STATUSES.includes(o.status)));
  readonly accountTrades = computed(() => {
    const v = this.runView();
    const rows = this.trades().filter(t => t.account === this.session.account());
    // The retired run holds only trades booked before it ended; the fixture uses the date as the cut.
    return (!this.connected && v.kind === 'history' ? rows.filter(t => t.bookedAt < '2026-09-25') : rows)
      .sort((x, y) => y.bookedAt.localeCompare(x.bookedAt));
  });

  readonly alerts = computed(() => {
    const out: { tone: 'bad' | 'warn' | 'good'; text: string }[] = [];
    const now = this.now();
    const stale = this.session.prefs().watchlist.filter(k => freshnessOf(this.marks()[k], now).kind !== 'live');
    if (stale.length) out.push({ tone: 'warn', text: `No live price for ${stale.join(', ')}.` });
    for (const o of this.accountOrders().filter(o => o.status === 'REJECTED').slice(0, 3)) {
      out.push({ tone: 'bad', text: `Order ${o.ref} (${o.side} ${o.quantity} ${o.key}) was rejected: ${reasonText(o.reason)}.` });
    }
    for (const o of this.accountOrders().filter(o => o.status === 'PARTIALLY_FILLED')) {
      out.push({ tone: 'good', text: `Order ${o.ref} is partly filled: ${o.filled.toLocaleString()} of ${o.quantity.toLocaleString()}.` });
    }
    return out;
  });

  freshness(key: string): Freshness { return freshnessOf(this.marks()[key], this.now()); }

  /** Submit to the fixture desk. Returns the result text shown under the ticket. */
  submit(key: string, t: TypedTicket): { ok: boolean; text: string } {
    if(this.connected) return {ok:false,text:'Use the connected order path.'};
    if (this.runView().kind === 'history') return {ok:false, text:'Previous runs are read-only.'};
    if (this.readState() !== 'ready') return {ok:false, text:'Account data is not available.'};
    if (!this.session.check()) return { ok: false, text: 'Your session has ended. Sign in again.' };
    const a = this.session.account();
    if (a === null) return { ok: false, text: 'Choose an account first.' };
    const invalid = validateTicket(t);
    const at = stamp();
    const ref = this.nextRef++;
    const base: FixtureOrder = {
      ref, account: a, key, side: t.side, quantity: t.quantity, filled: 0, orderType: t.orderType, tif: t.timeInForce,
      limitPrice: t.limitPrice, stopPrice: t.stopPrice, status: 'NEW', createdAt: at, updatedAt: at,
    };
    if (invalid) {
      this.log(`Order not sent: ${invalid}.`, false);
      return { ok: false, text: `Not sent: ${invalid}.` };
    }
    const mark = this.marks()[key];
    if (t.orderType === 'STOP' || t.orderType === 'STOP_LIMIT' || t.orderType === 'TRAILING_STOP') {
      base.status = 'PENDING_TRIGGER';
    } else if (mark && crosses(t, mark.price)) {
      this.fill(base, mark.price);
    } else if (t.orderType === 'MARKET' || t.timeInForce === 'IOC' || t.timeInForce === 'FOK') {
      base.status = t.timeInForce === 'FOK' ? 'REJECTED' : 'CANCELED';
      base.reason = t.timeInForce === 'FOK' ? 'FOK_UNFILLABLE' : undefined;
    }
    this.orders.update(l => [base, ...l]);
    const text = base.status === 'FILLED' ? `Order ${ref} filled at ${fmtPrice(mark!.price, pick(key))}.`
      : base.status === 'REJECTED' ? `Order ${ref} rejected: ${reasonText(base.reason)}.`
      : base.status === 'CANCELED' ? `Order ${ref} did not fill and was cancelled (${t.timeInForce}).`
      : `Order ${ref} accepted: ${base.status === 'PENDING_TRIGGER' ? 'waiting for its trigger' : 'working'}.`;
    this.log(text, base.status !== 'REJECTED');
    return { ok: base.status !== 'REJECTED', text };
  }

  /** Algo parents: recorded only. The fixture does not slice them into child orders. */
  readonly algos = signal<{ id: string; account: number; key: string; side: string; quantity: number; mode: string;
    durationS: number; bucketS: number; at: string }[]>([
    ...(!this.connected ? [{ id: 'P-12', account: 22214, key: 'IBM', side: 'Sell', quantity: 30, mode: 'TWAP', durationS: 30, bucketS: 10, at: '2026-09-25 09:30:02' }] : []),
  ]);

  submitAlgo(key: string, side: 'Buy' | 'Sell', quantity: number, mode: 'TWAP' | 'VWAP', durationS: number, bucketS: number) {
    if(this.connected) return {ok:false,text:'Use the connected algo path.'};
    if (this.runView().kind === 'history') return {ok:false, text:'Previous runs are read-only.'};
    if (this.readState() !== 'ready') return {ok:false, text:'Account data is not available.'};
    if (!this.session.check()) return { ok: false, text: 'Your session has ended. Sign in again.' };
    const a = this.session.account();
    if (a === null) return { ok: false, text: 'Choose an account first.' };
    const id = `P-${this.nextRef++}`;
    this.algos.update(l => [{ id, account: a, key, side, quantity, mode, durationS, bucketS, at: stamp() }, ...l]);
    const text = `${mode} parent ${id} recorded: ${side} ${quantity} ${key} over ${durationS}s.`;
    this.log(text, true);
    return { ok: true, text };
  }

  cancel(ref: number): void {
    if (!this.canChangeExisting() || this.runView().kind === 'history' || this.readState() !== 'ready' || !this.session.check()) return;
    if (this.connected) {
      if(!this.accountOrders().some(o=>o.ref===ref && LIVE_STATUSES.includes(o.status))) return;
      void this.rigAction('/order-matcher/cancel',{orderRef:ref},'canceled'); return; }
    this.orders.update(l => l.map(o => o.ref === ref && LIVE_STATUSES.includes(o.status)
      ? { ...o, status: 'CANCELED', updatedAt: stamp() } : o));
    this.log(`Order ${ref} cancelled.`, true);
  }

  /** Replace keeps the order reference; only quantity and limit price change (LIMIT orders). */
  replace(ref: number, quantity: number, limitPrice: number | undefined): string {
    if(this.connected) return 'Use the connected replacement path.';
    if (this.runView().kind === 'history' || this.readState() !== 'ready') return 'This view is read-only.';
    if (!this.session.check()) return 'Your session has ended. Sign in again.';
    const o = this.orders().find(x => x.ref === ref);
    if (!o || !LIVE_STATUSES.includes(o.status)) return 'Only working orders can be changed.';
    if (!(quantity > o.filled)) return `Quantity must be above the filled ${o.filled}.`;
    this.orders.update(l => l.map(x => x.ref === ref ? { ...x, quantity, limitPrice: limitPrice ?? x.limitPrice, updatedAt: stamp() } : x));
    this.log(`Order ${ref} changed to ${quantity}${limitPrice ? ' @ ' + limitPrice : ''}.`, true);
    return '';
  }

  private loadDirectory(): void {
    this.directory.set(this.api.instruments().map(i=>{
      const opt=parseOcc(i.instrumentKey);
      const cls = opt ? 'Option' : i.assetClass==='US_TREASURY' ? 'Treasury' : i.assetClass==='CORPORATE_BOND' ? 'Corporate' : 'Equity';
      const bond=cls==='Treasury'||cls==='Corporate';
      return {key:i.instrumentKey,name:i.displayName,cls,source:this.api.prices()[i.instrumentKey]?.source??'',unit:bond?'fraction of par':opt?'USD per share':i.currency,qtyUnit:bond?'USD face':opt?'contracts':'shares',multiplier:opt?100:1,seed:0};
    }));
  }

  private async refreshRig(reset: boolean): Promise<void> {
    const seq=++this.pollSequence;
    const token=reset?this.ctx.next():this.ctx.current;
    const account=this.session.account(); const view=this.runView();
    if(reset) { this.orders.set([]);this.positions.set([]);this.trades.set([]);this.readState.set('loading');this.confirmed=false;this.runLabel.set('not confirmed'); }
    if(account===null || !this.session.check()) {this.error.set('Add a trading account to start.');this.readState.set('unavailable');return;}
    this.polling=true;
    const controller=new AbortController(); const deadline=setTimeout(()=>controller.abort(),8000);
    try {
      if(Date.now()-this.directoryAt>60000) {
      const [ar,ir]=await Promise.all([this.api.load<any[]>('/account-service/account/',{signal:controller.signal}),this.api.load<any[]>('/reference-data/instruments',{signal:controller.signal})]);
      if(!this.ctx.isCurrent(token)) return;
      if(ar.status!==200 || !Array.isArray(ar.body) || ir.status!==200 || !Array.isArray(ir.body)) throw Error('Account or instrument directory is unavailable.');
      this.api.accounts.set(ar.body);this.api.instruments.set(ir.body);this.directoryAt=Date.now();
      }
      this.loadDirectory();
      if(!this.accounts().some(a=>a.id===account)) throw Error('Selected account is not on this rig.');
      const data=await readRigAccount(url=>this.api.load(url,{signal:controller.signal}),account,view);
      if(!this.ctx.isCurrent(token)) return;
      if(seq<this.appliedPoll) return;
      this.appliedPoll=seq;
      this.runRows.set(data.runs);this.activeScope.set(data.scope);this.runLabel.set(data.scope??'legacy run');
      this.positions.set(data.positions.map(p=>({account,key:p.security,quantity:Number(p.quantity),avgCost:Number(p.averageCostBasis)})));
      this.trades.set(data.trades.map(t=>({account,id:String(t.id),key:t.security,side:t.side,quantity:Number(t.quantity),price:Number(t.price),state:t.state,sourceOrder:t.sourceOrderId,bookedAt:String(t.created??t.updated??'')})));
      this.orders.set(data.orders.map(o=>({account,ref:Number(String(o.id??o.orderId).split('-').pop()),key:o.security,side:o.side,quantity:Number(o.quantity),filled:o.status==='REJECTED'?0:o.status==='CANCELED'?Number.NaN:Number(o.quantity)-Number(o.remainingQuantity),orderType:o.orderType??'LIMIT',tif:o.timeInForce??'GTC',limitPrice:o.limitPrice,stopPrice:o.stopPrice,status:o.status,createdAt:o.createdAt??'',updatedAt:o.updatedAt??'',reason:o.reason})));
      this.error.set('');this.readState.set('ready');this.confirmed=true;
    } catch(e) {
      if(this.ctx.isCurrent(token) && seq>=this.appliedPoll) {this.appliedPoll=seq;this.error.set(String(e instanceof Error?e.message:e));this.orders.set([]);this.positions.set([]);this.trades.set([]);this.readState.set('unavailable');this.confirmed=false;}
    } finally {clearTimeout(deadline);this.polling=false;}
  }

  async rigAction(url:string,body:unknown,expected:'orderRef'|'canceled'|'replaced'|'parentOrderId'='orderRef'): Promise<{ok:boolean;text:string}> {
    if(this.actionBusy()) return {ok:false,text:'A request is already in progress.'};
    this.actionBusy.set(true);
    try {
    const generation=this.session.generation(); const token=this.ctx.current; const scope=this.activeScope();
    if(!this.connected || !this.confirmed || this.runView().kind!=='active' || !this.session.check()) return {ok:false,text:'Active account data is required before sending.'};
    if(scope!==null) {
      const r=await this.api.load(ACTIVE_URL,{signal:AbortSignal.timeout(5000)}),a=readActive(r.status,r.body);
      if(!a.ok || a.scope!==scope) {this.reload();return {ok:false,text:'Active run changed or could not be confirmed. Nothing sent.'};}
    }
    if(generation!==this.session.generation() || !this.ctx.isCurrent(token) || this.runView().kind!=='active') return {ok:false,text:'Context changed. Nothing sent.'};
    const r=await this.api.load<any>(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
    const ok=r.status>=200 && r.status<300 && !!r.body?.[expected] && !r.body?.reason;
    const text=ok ? `${expected==='orderRef'?'Order accepted':expected==='parentOrderId'?'Algo parent accepted':expected==='canceled'?'Order cancelled':'Order changed'}${r.body?.orderRef?' · '+r.body.orderRef:''}.`
      : r.status===0 || r.status>=500 ? 'Outcome unknown. Check orders before submitting again; this request will not be retried.' : r.body?.reason || r.body?.error || `Request refused (HTTP ${r.status}).`;
    if(generation===this.session.generation() && this.ctx.isCurrent(token)) {this.log(text,ok);this.reload();}
    return {ok,text};
    } finally {this.actionBusy.set(false);}
  }
  refreshDirectory():void {this.directoryAt=0;this.reload();}
  submitRig(key:string,t:TypedTicket) {
    if(!this.typedOrdersEnabled && (t.orderType!=='LIMIT' || t.timeInForce!=='GTC')) return Promise.resolve({ok:false,text:'This legacy rig has not advertised typed-order support. Use Limit / GTC or update the rig.'});
    const invalid=validateTicket(t);if(invalid) return Promise.resolve({ok:false,text:invalid});
    return this.rigAction('/order-matcher/orders',{accountId:this.session.account(),ticker:key,clientOrderId:nextClientOrderId(),...(!this.typedOrdersEnabled ? {side:t.side,quantity:t.quantity,limitPrice:t.limitPrice} : typedBody(t))});
  }
  replaceRig(ref:number,quantity:number,limitPrice:number|undefined) {
    if(!this.canChangeExisting()) return Promise.resolve({ok:false,text:'Run identity is not confirmed; existing orders cannot be changed here.'});
    const o=this.accountOrders().find(x=>x.ref===ref);
    if(!o || !LIVE_STATUSES.includes(o.status) || quantity<=o.filled || !Number.isSafeInteger(quantity) || !limitPrice || limitPrice<=0) return Promise.resolve({ok:false,text:'A working order, valid quantity and positive limit price are required.'});
    return this.rigAction('/order-matcher/replace',{orderRef:ref,quantity,limitPrice},'replaced');
  }

  private fill(o: FixtureOrder, price: number): void {
    o.status = 'FILLED'; o.filled = o.quantity;
    const signed = o.side === 'Buy' ? o.quantity : -o.quantity;
    this.trades.update(l => [{ id: `T-${this.nextTrade++}`, account: o.account, key: o.key, side: o.side, quantity: o.quantity,
      price, state: 'Processing', sourceOrder: `e8-${o.ref}`, bookedAt: stamp() }, ...l]);
    this.positions.update(l => {
      const p = l.find(x => x.account === o.account && x.key === o.key);
      if (!p) return [...l, { account: o.account, key: o.key, quantity: signed, avgCost: price }];
      const q = p.quantity + signed;
      const avg = avgAfterFill(p.quantity, p.avgCost, signed, price);
      return l.map(x => x === p ? { ...x, quantity: q, avgCost: avg } : x);
    });
  }

  private log(text: string, ok: boolean): void {
    this.activity.update(l => [{ at: stamp().slice(11), text, ok }, ...l].slice(0, 50));
  }

  private tick(): void {
    if (this.scenario() === 'stale') return;           // the feed has stopped: marks age in place
    const now = Date.now();
    this.marks.update(m => {
      const next = { ...m };
      for (const i of INSTRUMENTS) {
        const prev = m[i.key];
        if (!prev) continue;
        if (i.frozen) { next[i.key] = { ...prev, dir: 0, receivedAt: now }; continue; }
        const step = (Math.random() - 0.5) * (i.cls === 'Treasury' ? 0.0004 : i.seed * 0.002);
        const price = Math.max(0.0001, +(prev.price + step).toFixed(i.unit === 'fraction of par' ? 6 : 2));
        next[i.key] = { price, dir: price > prev.price ? 1 : price < prev.price ? -1 : 0, receivedAt: now };
      }
      return next;
    });
  }
}

function initialScenario(): Scenario {
  const q = new URLSearchParams(location.search).get('scenario');
  const s = (q ?? safeGet('tx-combined:scenario') ?? 'normal') as Scenario;
  return SCENARIOS.some(x => x.id === s) ? s : 'normal';
}
const safeGet = (k: string) => { try { return sessionStorage.getItem(k); } catch { return null; } };

function seedMarks(s: Scenario): Record<string, Mark> {
  // Stopped feed: every mark was last received 2½ minutes ago. MSFT never published at all.
  const at = s === 'stale' ? Date.now() - 150_000 : Date.now();
  const out: Record<string, Mark> = {};
  for (const i of INSTRUMENTS) if (!(s === 'stale' && i.key === 'MSFT')) out[i.key] = { price: i.seed, dir: 0, receivedAt: at };
  return out;
}

export function reasonText(reason: string | undefined): string {
  if (!reason) return 'no reason given';
  return REASON_HINT[reason] ? `${REASON_HINT[reason]} (${reason})` : reason;
}

export function fmtPrice(p: number, inst: FixtureInstrument): string {
  return inst.unit === 'fraction of par' ? p.toFixed(6) : p.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export const fmtUsd = (v: number) =>
  v.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const fmtQty = (v: number) => Number.isFinite(v) ? v.toLocaleString('en-US') : '—';
