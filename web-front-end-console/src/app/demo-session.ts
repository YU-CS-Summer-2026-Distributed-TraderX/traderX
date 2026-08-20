import { Component, Injectable, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Instrument, OrderResult, nextClientOrderId, riskControlError, traceIdFor } from './api';
import { HelpTip } from './help';

/**
 * A scripted market: several accounts submitting real orders at their own rate for their own
 * duration, so every downstream surface has something to show at once — positions and P&L moving,
 * prints landing on the message bus, consensus latency under actual load, the kdb tap filling.
 *
 * Deliberately client-side and ordinary: every order goes through a gateway route the rest of the
 * console already uses, so nothing here is a special path. Buyers price just through the touch and
 * sellers just under it, which is what makes the accounts cross each other and print rather than
 * pile up as resting orders.
 */
interface Actor {
  accountId: number;
  side: 'Buy' | 'Sell' | 'Alternate';
  perMin: number;
  /** -1 means a random multiple of 25 in [25, 10000], drawn per order. */
  quantity: number;
  durationSec: number;
  sent: number; accepted: number; rejected: number; noPrice: number;
  running: boolean;
  lastReason: string;
  /**
   * Milliseconds of TRADING time this actor still owes, counted down only while armed. An actor
   * with `durationSec: 120` paused at 30s resumes owing 90s — not 120 (a pause would silently
   * lengthen the session) and not 0 (a long pause would retire it the instant you resumed).
   */
  remainingMs: number;
}

const MAX_PER_MIN = 120;
const MAX_DURATION = 900;
const MAX_BATCH = 500;
const RANDOM_QTY = -1;

/** Random multiple of 25 in [25, 10000] — the lot convention the seeded books already use. */
/**
 * The lot an instrument must trade in, enforced by the GATEWAY before the engine sees the order
 * (ClusterGatewayMain:1299-1313): a bond quantity below 100, or not a multiple of 100, is refused
 * 422 at the boundary. Everything else is unconstrained; 25 is this console's own house step, not
 * a rule, kept so equity sizes stay round.
 *
 * SOURCED FROM THE CATALOG, not from a copy of the gateway's list. The gateway keys on the ticker
 * prefixes in ClusterGatewayMain.BOND_KEY_PREFIXES (UST-, CORP-), whose comment says the list
 * exists so "adding an asset class is one edit here rather than a hunt through string literals" —
 * mirroring it here would make it two edits again, and the second one would be forgotten silently.
 * securityType comes from reference-data and agrees with that rule exactly: checked across all 533
 * catalog instruments, 19 Debt, every one prefixed, and no prefixed instrument typed otherwise.
 *
 * The prefix test is kept only as a BACKSTOP for the one direction that hurts. If a UST-/CORP-
 * instrument were ever typed as something other than Debt, the catalog alone would size it wrong
 * and every order would 422; the reverse — rounding something the gateway would have accepted
 * anyway — costs nothing. If a future asset class gets its own lot in BOND_KEY_PREFIXES, this
 * function needs the edit too.
 */
const LOT_DEFAULT = 25;
const LOT_BOND = 100;
export const lotOf = (i: Instrument | undefined, ticker: string): number =>
  (i?.securityType === 'Debt' || /^(UST-|CORP-)/.test(ticker) ? LOT_BOND : LOT_DEFAULT);

/** A random size in [lot, 10000] that is a whole number of lots. */
export const randomQty = (lot: number) => lot * (1 + Math.floor(Math.random() * Math.floor(10_000 / lot)));

/** Round a fixed size up to the instrument's lot — never below it, since the floor is also a rule. */
export const toLot = (qty: number, lot: number) => Math.max(lot, Math.round(qty / lot) * lot);

const actor = (accountId: number, side: Actor['side'], perMin: number, quantity: number, durationSec: number): Actor =>
  ({ accountId, side, perMin, quantity, durationSec, sent: 0, accepted: 0, rejected: 0, noPrice: 0, running: false, lastReason: '', remainingMs: 0 });

/**
 * The session itself, deliberately OUTSIDE the panel that configures it.
 *
 * It used to live in the component and stop itself on destroy, which meant navigating to the System
 * page to watch latency under load ended the very load you went there to watch — the two surfaces
 * that most need traffic are on different pages from the thing generating it. A root-provided
 * service outlives routing, so the session runs until it finishes or someone stops it.
 *
 * The destroy-stop existed for a real reason — a session firing at a rig with nothing on screen to
 * say so — and that reason is answered instead by the running indicator in the app header, which is
 * visible on every page and carries its own Stop.
 */
@Injectable({ providedIn: 'root' })
export class SessionDriver {
  private readonly api = inject(Api);

  readonly picked = signal<string[]>(['IBM']);
  // A signal, not a plain field: pool() is a computed and would never see a plain field change.
  readonly extra = signal('');
  readonly randomPick = signal(false);
  readonly batch = signal(false);
  readonly batchSize = signal(25);
  readonly running = signal(false);
  /**
   * Paused is a state OF a running session, not a third thing beside start and stop: `running()`
   * stays true so the header keeps showing the session and Stop stays reachable from every page.
   */
  readonly paused = signal(false);
  /**
   * Seconds of TRADING time, which is not wall time once a pause exists. The clock stops with the
   * order flow so that sent/elapsed keeps meaning orders per minute of trading — the same reason
   * the metrics tiles say "since startup". A number is only comparable if the window it covers is
   * stated, and a clock that ran through a coffee break states the wrong one.
   */
  readonly elapsed = signal(0);
  readonly actors = signal<Actor[]>([
    actor(22214, 'Buy', 20, 25, 120),
    actor(42422, 'Sell', 20, 25, 120),
  ]);

  readonly sent = computed(() => this.actors().reduce((s, a) => s + a.sent, 0));
  readonly accepted = computed(() => this.actors().reduce((s, a) => s + a.accepted, 0));
  readonly rejected = computed(() => this.actors().reduce((s, a) => s + a.rejected, 0));
  /**
   * Orders never sent because the instrument had no live tick yet. `sent` is incremented before
   * the price is looked up, so the identity a reader needs is
   *
   *     sent = accepted + rejected + skipped
   *
   * and if `skipped` is not on the glass that sum does not close: "1200 sent, 1195 accepted" reads
   * as five orders lost in flight. It was five that never left the browser. The counter existed
   * per actor all along and was shown only as fallback text in the reject column, which is exactly
   * where nobody totalling the header would look.
   */
  readonly skipped = computed(() => this.actors().reduce((s, a) => s + a.noPrice, 0));

  /** Selected catalog instruments plus anything typed in Extra symbols (OCC contracts, mostly). */
  readonly pool = computed(() => [
    ...this.picked(),
    ...this.extra().split(',').map(s => s.trim().toUpperCase()).filter(Boolean),
  ]);

  /**
   * The identity, computed rather than asserted in prose — and it refuses to print a balanced-looking
   * line it cannot balance. Two things make the naive `sent = accepted + rejected + skipped` false,
   * and both bit this line the first time it was written:
   *
   * 1. **Batch mode counts different things.** `sent` counts BATCHES while the other three count
   *    ORDERS, so the left side is batches × batch size. Summing them raw puts batches and orders
   *    in one total — which is what "20 batches sent = 9 accepted + 11 skipped" was doing.
   * 2. **In flight is not loss.** `sent` is incremented before the request goes out, so during a run
   *    the shortfall is orders still awaiting an answer. Reporting that as unaccounted would invent
   *    a fault every second of every session; reporting it as nothing would hide a real one.
   *
   * So the shortfall is named by state: in flight while running, UNACCOUNTED once stopped — at rest
   * there is nothing left to arrive, and a gap then is the real thing this is here to catch. A
   * negative gap (more answered than offered) is impossible and says so rather than rendering.
   */
  readonly tally = computed(() => {
    const unit = this.liveBatch ? `orders in ${this.sent()} batches` : 'orders';
    const offered = this.liveBatch ? this.sent() * this.liveBatchSize : this.sent();
    const accounted = this.accepted() + this.rejected() + this.skipped();
    const gap = offered - accounted;
    const sum = `${this.accepted()} accepted + ${this.rejected()} rejected + `
      + `${this.skipped()} skipped (no price yet)`;
    if (gap < 0) {
      return { bad: true, text: `${offered} ${unit} sent but ${accounted} accounted for — these do `
        + `not add up, which should be impossible: ${sum}` };
    }
    if (!gap) return { bad: false, text: `${offered} ${unit} sent = ${sum}` };
    return {
      bad: !this.running(),
      text: `${offered} ${unit} sent = ${sum} + ${gap} `
        + (this.running() ? 'in flight' : 'UNACCOUNTED — no answer ever arrived'),
    };
  });

  /** Every actor's tick interval and its stop timeout, cleared together on stop. */
  private timers: ReturnType<typeof setTimeout>[] = [];
  private clock: ReturnType<typeof setInterval> | undefined;
  /** The pool as it was when Start was pressed — the inputs are locked while running anyway. */
  private livePool: string[] = [];
  /** Likewise the ingress shape: the tally is still on screen after a stop, when these unlock. */
  private liveBatch = false;
  private liveBatchSize = 1;

  patch(i: number, fields: Partial<Actor>): void {
    this.actors.update(list => list.map((a, j) => (j === i ? { ...a, ...fields } : a)));
  }
  add(): void { this.actors.update(l => [...l, actor(22214, 'Alternate', 12, RANDOM_QTY, 120)]); }
  remove(i: number): void { this.actors.update(l => l.filter((_, j) => j !== i)); }
  toggle(key: string): void {
    this.picked.update(l => (l.includes(key) ? l.filter(k => k !== key) : [...l, key]));
  }

  start(): void {
    if (this.running()) return;
    this.api.watchPrices();
    this.livePool = this.pool();
    this.liveBatch = this.batch();
    this.liveBatchSize = Math.min(MAX_BATCH, Math.max(1, this.batchSize()));
    this.actors.update(l => l.map(a => ({
      ...a,
      perMin: Math.min(MAX_PER_MIN, Math.max(1, a.perMin)),
      durationSec: Math.min(MAX_DURATION, Math.max(5, a.durationSec)),
      sent: 0, accepted: 0, rejected: 0, noPrice: 0, lastReason: '', running: true,
      remainingMs: Math.min(MAX_DURATION, Math.max(5, a.durationSec)) * 1000,
    })));
    this.bankedSec = 0;
    this.batchSize.set(Math.min(MAX_BATCH, Math.max(1, this.batchSize())));
    this.running.set(true);
    this.paused.set(false);
    this.elapsed.set(0);
    this.arm();
    // "12 instruments" is true of the POOL and false of the traffic: with random off, every order
    // goes to livePool[0] and the other eleven never see one. Same shape as a number without its
    // window — so the summary says what is actually being traded.
    const where = this.randomPick()
      ? `${this.livePool.length} instruments at random`
      : `${this.livePool[0]} only (head of ${this.livePool.length}; random pick is off)`;
    this.api.log({ kind: 'algo', ok: true,
      summary: `live session started · ${this.actors().length} accounts, ${where}`
        + (this.batch() ? `, batch ingress ${this.batchSize()}/request` : '') });
  }

  /**
   * Start every actor's tick and its retirement deadline, and run the clock. Shared by start() and
   * resume() so there is exactly one place that knows how a session is wired up — resume differs
   * only in that each actor's deadline is what it has LEFT rather than its full duration.
   */
  private arm(): void {
    this.armedAt = Date.now();
    this.clock = setInterval(
      () => this.elapsed.set(this.bankedSec + Math.round((Date.now() - this.armedAt) / 1000)), 1000);
    this.actors().forEach((a, i) => {
      if (!a.running || a.remainingMs <= 0) return;
      const every = Math.max(250, Math.round(60_000 / a.perMin));
      this.timers.push(setInterval(() => this.fire(i), every));
      // Each actor stops on its own remaining budget; the session ends when the last one does.
      this.timers.push(setTimeout(() => this.retire(i), a.remainingMs));
    });
  }

  /** Trading seconds banked by earlier arm/pause cycles; 0 for a session that has never paused. */
  private bankedSec = 0;
  private armedAt = 0;

  private disarm(): void {
    this.timers.forEach(t => { clearInterval(t); clearTimeout(t); });
    this.timers = [];
    clearInterval(this.clock);
  }

  /**
   * Hold the counters and stop the clock. Every actor's remaining budget is debited by the time it
   * was actually armed, so resuming continues ONE session rather than starting a second.
   */
  pause(): void {
    if (!this.running() || this.paused()) return;
    const spent = Date.now() - this.armedAt;
    this.disarm();
    this.bankedSec += Math.round(spent / 1000);
    this.elapsed.set(this.bankedSec);
    this.actors.update(l => l.map(a =>
      (a.running ? { ...a, remainingMs: Math.max(0, a.remainingMs - spent) } : a)));
    this.paused.set(true);
    this.api.log({ kind: 'algo', ok: true,
      summary: `live session paused at ${this.elapsed()}s · ${this.tally().text}`
        + ' — counters held, clock stopped' });
  }

  resume(): void {
    if (!this.running() || !this.paused()) return;
    // An actor whose budget ran out while paused is already done; if that is all of them, the
    // session is over and resuming would arm nothing and never retire.
    if (!this.actors().some(a => a.running && a.remainingMs > 0)) { this.stop('finished while paused'); return; }
    this.paused.set(false);
    this.arm();
    this.api.log({ kind: 'algo', ok: true, summary: `live session resumed at ${this.elapsed()}s` });
  }

  stop(why: string): void {
    if (!this.running() && !this.timers.length) return;
    // Bank the final segment before the clock is torn down. The 1s tick recomputes elapsed from
    // Date.now(), so each sample is exact — but the browser throttles intervals in an unfocused
    // window, and the LAST sample can be many seconds stale. Measured: a 25s session reported 19s
    // because the tick stopped firing while the tab sat idle, and stop() then froze that value.
    // The deadline itself was never affected (it is one Date.now()-based timeout, not a tick
    // count), so this was a reporting error, not a timing one — which is the more dangerous kind,
    // because the session did the right thing while saying it had not.
    if (this.running() && !this.paused()) {
      this.bankedSec += Math.round((Date.now() - this.armedAt) / 1000);
      this.elapsed.set(this.bankedSec);
    }
    this.disarm();
    this.running.set(false);
    this.paused.set(false);
    this.actors.update(l => l.map(a => ({ ...a, running: false })));
    this.api.log({ kind: 'algo', ok: true,
      summary: `live session ${why} after ${this.elapsed()}s of trading time · ${this.tally().text}` });
    this.bankedSec = 0;
  }

  private retire(i: number): void {
    this.patch(i, { running: false });
    if (!this.actors().some(a => a.running)) this.stop('finished');
  }

  /** Random from the pool, or its head — see the Random instrument explainer. */
  private security(): string {
    if (!this.livePool.length) return '';
    return this.randomPick()
      ? this.livePool[Math.floor(Math.random() * this.livePool.length)]
      : this.livePool[0];
  }

  /** One order priced off the live feed, or null when that instrument has no tick yet. */
  private order(a: Actor, seq: number): { ticker: string; side: string; quantity: number; limitPrice: number } | null {
    const ticker = this.security();
    const tick = this.api.prices()[ticker];
    if (!tick) return null;
    const side = a.side === 'Alternate' ? (seq % 2 === 0 ? 'Buy' : 'Sell') : a.side;
    // Buyers reach up through the touch and sellers down through it, so the actors cross each
    // other. A tick-tight price on both sides would just rest and the session would look dead.
    // Bonds quote as a fraction of par, so the offset is proportional, never a flat cent.
    const off = Math.max(tick.price * 0.0005, tick.price < 2 ? 0.000001 : 0.01);
    const raw = side === 'Buy' ? tick.price + off : tick.price - off;
    const limitPrice = tick.price < 2 ? Math.round(raw * 1e6) / 1e6 : Math.round(raw * 100) / 100;
    // The lot depends on WHICH instrument this order landed on, so it is resolved per order — a
    // random-instrument session crosses asset classes and a session-wide size would be wrong for
    // half of them. A fixed size is rounded rather than sent to a certain 422: the operator asked
    // for orders, and the panel says what it did (see lotNote).
    const lot = lotOf(this.api.instruments().find(x => x.instrumentKey === ticker), ticker);
    const quantity = a.quantity === RANDOM_QTY ? randomQty(lot) : toLot(a.quantity, lot);
    return { ticker, side, quantity, limitPrice };
  }

  private async fire(i: number): Promise<void> {
    const a = this.actors()[i];
    if (!a?.running) return;
    this.patch(i, { sent: a.sent + 1 });
    if (this.batch()) { await this.fireBatch(i, a); return; }

    const o = this.order(a, a.sent);
    if (!o) { this.patch(i, { noPrice: a.noPrice + 1 }); return; }
    const clientOrderId = nextClientOrderId();
    const r = await this.api.post<OrderResult>('/order-matcher/orders', {
      accountId: Number(a.accountId), ...o, clientOrderId,
    });
    const cur = this.actors()[i];
    if (!cur) return;
    if (r.status === 200) {
      this.patch(i, { accepted: cur.accepted + 1 });
    } else {
      const reason = r.body?.reason ?? r.body?.error ?? `HTTP ${r.status}`;
      this.patch(i, { rejected: cur.rejected + 1, lastReason: reason });
      // Rejections carry the reason code, which is the part worth reading — logged individually.
      this.api.log({
        kind: 'order', ok: false, reason: r.body?.reason,
        summary: `session ${o.side} ${o.quantity} ${o.ticker} @ ${o.limitPrice} (acct ${a.accountId}) → REJECTED: ${reason}`,
        clientOrderId, traceId: traceIdFor(clientOrderId, r.body?.orderRef),
      });
    }
  }

  /**
   * One HTTP request carrying the whole tick's worth of orders. The gateway holds its owner thread
   * for the batch and drains any pipelined single order first, so batch and order-at-a-time are
   * mutually exclusive by construction — which is why this is a session-wide toggle and not a
   * per-actor one. The response is a count, not per-order refs, so there is nothing to trace.
   */
  private async fireBatch(i: number, a: Actor): Promise<void> {
    const orders: object[] = [];
    let skipped = 0;
    for (let n = 0; n < this.batchSize(); n++) {
      const o = this.order(a, a.sent * this.batchSize() + n);
      if (o) orders.push({ accountId: Number(a.accountId), ...o }); else skipped++;
    }
    if (skipped) this.patch(i, { noPrice: this.actors()[i].noPrice + skipped });
    if (!orders.length) return;
    const r = await this.api.post<{ accepted?: number; total?: number; error?: string }>(
      '/order-matcher/orders/batch', orders);
    const cur = this.actors()[i];
    if (!cur) return;
    if (r.status === 201 && typeof r.body?.accepted === 'number') {
      const refused = (r.body.total ?? orders.length) - r.body.accepted;
      this.patch(i, {
        accepted: cur.accepted + r.body.accepted,
        rejected: cur.rejected + refused,
        lastReason: refused ? `${refused} of ${r.body.total} not accepted` : cur.lastReason,
      });
    } else {
      this.patch(i, {
        rejected: cur.rejected + orders.length,
        lastReason: r.body?.error ?? `HTTP ${r.status}`,
      });
      this.api.log({ kind: 'order', ok: false,
        summary: `session batch of ${orders.length} (acct ${a.accountId}) → HTTP ${r.status}` });
    }
  }
}

@Component({
  selector: 'demo-session',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>Live trading session</h2>
      </button>
      <help-tip text="Runs a scripted market: each account submits real orders at its own rate for its own duration, through the same gateway routes a hand-typed ticket uses. Buy actors price just above the live market and sell actors just below, so they cross each other and produce prints — positions, P&L, latency, the kdb tap and the message-bus feed all move together while it runs. The session keeps running when you leave this page, which is the point: the surfaces worth watching under load are on other pages. The header carries a running indicator and a Stop from wherever you are." />
      <span class="spacer"></span>
      @if (d.running()) {
        <span class="pill" [class.good]="!d.paused()" [class.warn]="d.paused()">{{ d.paused() ? 'paused' : 'running' }} · {{ d.elapsed() }}s</span>
      }
    </div>

    @if (open()) {
    <div class="cfg">
      <div class="field grow">
        <span class="lbl">Instruments in play
          <help-tip text="Tick every instrument the session should trade. Fewer instruments means buyers and sellers land in the same book and cross each other, which is what produces prints; a wide pool spreads the flow across many books, so more orders simply rest. Both are worth showing — pick the one the demo needs. Instruments with no live price tick are skipped and counted, never guessed at." />
        </span>
        <div class="picker">
          <div class="ptop">
            <input class="filter" [ngModel]="filter()" (ngModelChange)="filter.set($event)"
              placeholder="filter {{ api.instruments().length }} instruments…" spellcheck="false" [disabled]="d.running()">
            <button type="button" (click)="d.picked.set([])" [disabled]="d.running() || !d.picked().length">clear</button>
          </div>
          <div class="list">
            @for (g of groups(); track g.label) {
              @if (g.items.length) {
                <div class="grp">{{ g.label }}</div>
                @for (i of g.items; track i.instrumentKey) {
                  <label class="row" [class.on]="isPicked(i.instrumentKey)">
                    <input type="checkbox" [checked]="isPicked(i.instrumentKey)"
                      (change)="d.toggle(i.instrumentKey)" [disabled]="d.running()">
                    <span class="k">{{ i.shortDisplayName || i.instrumentKey }}</span>
                    <span class="sub">{{ i.displayName }}</span>
                  </label>
                }
              }
            } @empty { <div class="faint pad">nothing matches “{{ filter() }}”</div> }
          </div>
        </div>
      </div>
      <div class="side">
        <label class="field">
          <span class="lbl">Extra symbols
            <help-tip text="Anything not in the reference-data catalog — listed option contracts, above all. OCC symbols are enabled in the engine's risk state rather than listed as instruments, so they are typed here: AAPL260918C00240000, comma separated." />
          </span>
          <input [ngModel]="d.extra()" (ngModelChange)="d.extra.set($event)" placeholder="AAPL260918C00240000, …" spellcheck="false" [disabled]="d.running()">
        </label>
        <label class="check">
          <input type="checkbox" [ngModel]="d.randomPick()" (ngModelChange)="d.randomPick.set($event)" [disabled]="d.running()">
          Random instrument per order
          <help-tip text="On: every order draws its instrument from the pool at random, so the flow spreads across books. Off: all actors trade the first instrument in the pool, which keeps buyers and sellers in one book and maximises prints." />
        </label>
        <label class="check">
          <input type="checkbox" [ngModel]="d.batch()" (ngModelChange)="d.batch.set($event)" [disabled]="d.running()">
          Batch ingress
          <help-tip text="Sends each tick as one POST /orders/batch carrying a whole array of orders instead of one HTTP request per order. The gateway offers every order in the batch back to back without waiting for each acknowledgement, then fences once at the end — far higher throughput than order-at-a-time, and the path the load benches use. Two deliberate trade-offs: a batch answers with a count rather than per-order refs, so batched orders carry no client order id and no trace; and the consensus percentiles on the System page sample the single-order path only, so they stand still during a batch session while throughput and the counters move." />
        </label>
        @if (d.batch()) {
          <label class="field">Orders per batch
            <input type="number" min="1" [max]="maxBatch" [ngModel]="d.batchSize()"
              (ngModelChange)="d.batchSize.set(+$event)" [disabled]="d.running()">
          </label>
        }
        <span class="sub">pool: {{ d.pool().length }} instrument{{ d.pool().length === 1 ? '' : 's' }}</span>
        <!-- Picking twelve and trading one is the default, and nothing said so. -->
        @if (d.pool().length > 1 && !d.randomPick()) {
          <span class="sub one-book">⚠ all orders go to <b>{{ d.pool()[0] }}</b> — with random pick
            off the session trades the head of the pool, and the other
            {{ d.pool().length - 1 }} never see an order.</span>
        }
      </div>
      <span class="spacer"></span>
      <div class="go">
        @if (!d.running()) {
          <button class="btn-primary" (click)="d.start()" [disabled]="!d.actors().length || !d.pool().length">Start session</button>
        } @else {
          @if (d.paused()) { <button class="btn-primary" (click)="d.resume()">Resume</button> }
          @else { <button (click)="d.pause()">Pause</button> }
          <button class="stop" (click)="d.stop('stopped by operator')">Stop</button>
        }
      </div>
    </div>

    @if (unadmitted().length) {
      <div class="banner warn-note">
        <b>UNKNOWN_ACCOUNT is not "no such account".</b> Account
        {{ unadmitted().length === 1 ? '' : 's' }}
        <span class="mono">{{ unadmitted().join(', ') }}</span>
        exist{{ unadmitted().length === 1 ? 's' : '' }} in the directory — that is where this
        dropdown gets them — but the engine keeps its own admitted set, and an epoch roll resets
        that while leaving the directory untouched. Until an account is admitted, every order it
        sends is refused, and the reason code points at the account rather than at the admission.
        <button (click)="admitAll()" [disabled]="admitting()">
          {{ admitting() ? 'admitting…' : 'Admit ' + (unadmitted().length === 1 ? 'it' : 'them') + ' now' }}</button>
        <span class="sub">Sequenced through consensus like an order, the same control the Accounts
          page uses. The bring-up script now admits every directory account on each run, so this is
          defence against a future roll rather than a routine step.</span>
      </div>
    }

    <table>
      <thead><tr><th>account</th><th>side</th><th class="num">{{ d.batch() ? 'batches/min' : 'orders/min' }}</th>
        <th class="num">qty</th><th class="num">for (s)</th><th class="num">{{ d.batch() ? 'batches' : 'orders' }} sent</th>
        <th class="num">accepted</th><th class="num">rejected</th><th class="num">skipped</th>
        <th>last reject</th><th></th></tr></thead>
      <tbody>
        @for (a of d.actors(); track $index; let i = $index) {
          <tr [class.on]="a.running">
            <td>
              <select [ngModel]="a.accountId" (ngModelChange)="d.patch(i, { accountId: +$event })" [disabled]="d.running()">
                @for (acct of api.accounts(); track acct.id) { <option [value]="acct.id">{{ acct.displayName }} ({{ acct.id }})</option> }
              </select>
            </td>
            <td>
              <select [ngModel]="a.side" (ngModelChange)="d.patch(i, { side: $event })" [disabled]="d.running()">
                <option>Buy</option><option>Sell</option><option>Alternate</option>
              </select>
            </td>
            <td class="num"><input type="number" min="1" [max]="maxPerMin" [ngModel]="a.perMin"
                (ngModelChange)="d.patch(i, { perMin: +$event })" [disabled]="d.running()"></td>
            <td class="num">
              <input type="number" min="-1" [ngModel]="a.quantity"
                (ngModelChange)="d.patch(i, { quantity: +$event })" [disabled]="d.running()">
              @if (a.quantity === -1) { <span class="sub rnd">random</span> }
            </td>
            <td class="num"><input type="number" min="5" [max]="maxDuration" [ngModel]="a.durationSec"
                (ngModelChange)="d.patch(i, { durationSec: +$event })" [disabled]="d.running()"></td>
            <td class="num">{{ a.sent }}</td>
            <td class="num pos">{{ a.accepted }}</td>
            <td class="num" [class.neg]="a.rejected > 0">{{ a.rejected }}</td>
            <!-- Its own column, not fallback text in the reject cell: an order counted as sent but
                 never priced has to be visible for sent = accepted + rejected + skipped to close. -->
            <td class="num" [class.warn-n]="a.noPrice > 0" [title]="a.noPrice ? 'counted as sent, never priced — the instrument had no live tick yet' : ''">{{ a.noPrice }}</td>
            <td class="sub">{{ a.lastReason || '—' }}</td>
            <td>@if (!d.running()) { <button class="cancel" (click)="d.remove(i)">✕</button> }</td>
          </tr>
        }
      </tbody>
    </table>
    @if (!d.running()) { <button class="add" (click)="d.add()">+ add an account</button> }
    @if (d.sent()) { <div class="sub tally" [class.neg]="d.tally().bad">{{ d.tally().text }}</div> }

    <div class="sub note">Quantity <b>-1</b> draws a random size per order — a whole number of the
      instrument's lot, up to 10,000.
      @if (bondsInPool().length) {
        <b>{{ bondsInPool().length }} instrument{{ bondsInPool().length === 1 ? '' : 's' }} in this
        pool trade{{ bondsInPool().length === 1 ? 's' : '' }} in lots of 100</b> — the gateway refuses
        a bond quantity below 100 or not a multiple of it, at the boundary, before the engine sees
        the order. Random sizes are drawn in whole lots and a fixed size is rounded to one, so a
        mixed pool does not spend half its orders on certain rejections.
      } Rejections are logged individually to Activity &amp; rejections with their reason code —
      accepted orders are only counted, because a session at these rates would otherwise push
      everything else out of that list. Capped at {{ maxPerMin }}/min and {{ maxDuration }}s per
      actor@if (d.batch()) { , {{ maxBatch }} orders per batch }. <b>Pause</b> holds the counters and
      stops the clock, and each actor keeps the time it has left — so a resumed session is one
      session, and the elapsed figure stays trading time rather than wall time.</div>
    }
  `,
  styles: `
    .cfg { display: flex; gap: 14px; align-items: flex-start; flex-wrap: wrap; margin-bottom: 12px; }
    .grow { min-width: 300px; }
    .picker { border: 1px solid #cfd5dd; border-radius: 6px; overflow: hidden; width: 320px; }
    .ptop { display: flex; gap: 5px; padding: 5px; border-bottom: 1px solid var(--border); background: #f8f9fb; }
    .filter { flex: 1; padding: 3px 7px; font-size: 12px; }
    .ptop button { padding: 2px 8px; font-size: 11.5px; }
    .list { max-height: 190px; overflow-y: auto; }
    .grp { position: sticky; top: 0; background: #f0f2f5; color: var(--muted); font-size: 11px;
           font-weight: 600; padding: 2px 8px; }
    .row { display: flex; align-items: center; gap: 6px; padding: 2px 8px; font-size: 12.5px; cursor: pointer; }
    .row:hover { background: #f5f7fa; }
    .row.on { background: var(--accent-soft); }
    /* Bonds carry a short name with spaces in it ("IBM 4.5% 33", "UST 2Y"), so both spans need
       nowrap and the checkbox needs to be unshrinkable — otherwise the name wraps mid-row and
       drags the box out of line with it. min-width:0 is what lets the ellipsis engage at all. */
    .row input { width: auto; margin: 0; flex: 0 0 auto; }
    .row .k { font-family: var(--mono); font-size: 12px; white-space: nowrap; }
    .row .sub { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pad { padding: 8px; }
    .side { display: flex; flex-direction: column; gap: 7px; }
    .side input[type=text], .side .field input { width: 230px; }
    .check { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--muted); }
    .check input { width: auto; }
    .lbl { display: flex; align-items: center; gap: 5px; }
    .spacer { flex: 1; }
    .go { padding-top: 16px; }
    td input { width: 62px; text-align: right; }
    td .rnd { display: block; font-size: 10.5px; }
    tr.on td { background: var(--good-soft); }
    .cancel, .add { font-size: 11.5px; padding: 1px 9px; }
    .add { margin-top: 8px; }
    .stop { background: var(--bad); color: #fff; border-color: var(--bad); font-weight: 600; }
    .note { margin-top: 10px; max-width: 760px; }
    .pos { color: var(--good); } .neg { color: var(--bad); }
    .warn-n { color: var(--warn); }
    .one-book { color: var(--warn); max-width: 320px; }
    .tally { margin-top: 8px; font-family: var(--mono); font-size: 11.5px; }
    .warn-note { background: var(--warn-soft); color: var(--warn); font-size: 12.5px; max-width: 860px;
                 margin-bottom: 10px; }
    .warn-note button { margin-left: 8px; }
    .warn-note .sub { display: block; margin-top: 6px; color: inherit; opacity: 0.85; }
    .mono { font-family: var(--mono); }
  `,
})
export class DemoSession implements OnInit {
  readonly api = inject(Api);
  /** The session lives in the service; this panel only configures and displays it. */
  readonly d = inject(SessionDriver);
  readonly maxPerMin = MAX_PER_MIN;
  readonly maxDuration = MAX_DURATION;
  readonly maxBatch = MAX_BATCH;
  // View-only state: which card is open and what the picker is filtered to. Deliberately NOT in
  // the driver — a collapsed card should not be something a running session remembers.
  readonly open = signal(true);
  readonly filter = signal('');

  ngOnInit(): void { this.api.watchPrices(); }

  /**
   * Accounts the ENGINE has told us it does not know. Read from the rejection rather than checked
   * up front, because there is no read path for the admitted set: GET /risk/control/snapshot
   * reports the control replica's securities and carries no accounts at all. Marking the picker
   * from anything else would be a guess dressed as a reading — so the panel waits until the engine
   * says so, and then says what it actually means.
   *
   * Only single-order sessions can populate this: a batch answers with a count, not per-order
   * reasons, so its lastReason is "N of M not accepted" and carries no code to match.
   */
  /**
   * Whether the pool contains anything whose lot is not 1 — today that means bonds. Conditional so
   * an equity-only session is not told about a rule that cannot affect it, and so the sentence
   * appears exactly when it explains a number on screen.
   */
  readonly bondsInPool = computed(() => {
    const cat = new Map(this.api.instruments().map(i => [i.instrumentKey, i]));
    return this.d.pool().filter(k => lotOf(cat.get(k), k) !== LOT_DEFAULT);
  });

  readonly unadmitted = computed(() => [...new Set(this.d.actors()
    .filter(a => a.lastReason.includes('UNKNOWN_ACCOUNT'))
    .map(a => a.accountId))]);
  readonly admitting = signal(false);

  async admitAll(): Promise<void> {
    this.admitting.set(true);
    try {
      for (const accountId of this.unadmitted()) {
        const r = await this.api.riskControl<{ applied?: boolean; version?: number; error?: string }>(
          'account', { accountId, enabled: true });
        const ok = r.status === 200 && !!r.body?.applied;
        this.api.log({ kind: 'order', ok,
          summary: `risk control: account ${accountId} admitted → ${ok
            ? 'applied at control version ' + r.body!.version
            : riskControlError(r)}` });
      }
      // Clearing the reasons is what retires the banner: the next order from these actors is the
      // real test, and leaving a stale reject on screen would claim a fix that has not been proven.
      this.d.actors.update(l => l.map(a =>
        (a.lastReason.includes('UNKNOWN_ACCOUNT') ? { ...a, lastReason: '' } : a)));
    } finally { this.admitting.set(false); }
  }

  /** Membership as a Set: this is read once per rendered row, and there are 500+ rows. */
  private readonly pickedSet = computed(() => new Set(this.d.picked()));
  isPicked(key: string): boolean { return this.pickedSet().has(key); }

  readonly groups = computed(() => {
    const q = this.filter().trim().toUpperCase();
    const match = (i: { instrumentKey: string; displayName: string }) =>
      !q || i.instrumentKey.toUpperCase().includes(q) || i.displayName.toUpperCase().includes(q);
    const of = (type: string) => this.api.instruments().filter(i => i.securityType === type && match(i));
    return [
      { label: 'Equities', items: of('Equity') },
      { label: 'Funds / ETFs', items: of('Fund') },
      { label: 'Bonds', items: of('Debt') },
    ].filter(g => g.items.length);
  });
}
