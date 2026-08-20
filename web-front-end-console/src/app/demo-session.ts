import { Component, Injectable, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, OrderResult, nextClientOrderId, traceIdFor } from './api';
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
}

const MAX_PER_MIN = 120;
const MAX_DURATION = 900;
const MAX_BATCH = 500;
const RANDOM_QTY = -1;

/** Random multiple of 25 in [25, 10000] — the lot convention the seeded books already use. */
const randomQty = () => 25 * (1 + Math.floor(Math.random() * 400));

const actor = (accountId: number, side: Actor['side'], perMin: number, quantity: number, durationSec: number): Actor =>
  ({ accountId, side, perMin, quantity, durationSec, sent: 0, accepted: 0, rejected: 0, noPrice: 0, running: false, lastReason: '' });

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
  readonly elapsed = signal(0);
  readonly actors = signal<Actor[]>([
    actor(22214, 'Buy', 20, 25, 120),
    actor(42422, 'Sell', 20, 25, 120),
  ]);

  readonly sent = computed(() => this.actors().reduce((s, a) => s + a.sent, 0));
  readonly accepted = computed(() => this.actors().reduce((s, a) => s + a.accepted, 0));

  /** Selected catalog instruments plus anything typed in Extra symbols (OCC contracts, mostly). */
  readonly pool = computed(() => [
    ...this.picked(),
    ...this.extra().split(',').map(s => s.trim().toUpperCase()).filter(Boolean),
  ]);

  /** Every actor's tick interval and its stop timeout, cleared together on stop. */
  private timers: ReturnType<typeof setTimeout>[] = [];
  private clock: ReturnType<typeof setInterval> | undefined;
  private startedAt = 0;
  /** The pool as it was when Start was pressed — the inputs are locked while running anyway. */
  private livePool: string[] = [];

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
    this.actors.update(l => l.map(a => ({
      ...a,
      perMin: Math.min(MAX_PER_MIN, Math.max(1, a.perMin)),
      durationSec: Math.min(MAX_DURATION, Math.max(5, a.durationSec)),
      sent: 0, accepted: 0, rejected: 0, noPrice: 0, lastReason: '', running: true,
    })));
    this.batchSize.set(Math.min(MAX_BATCH, Math.max(1, this.batchSize())));
    this.running.set(true);
    this.startedAt = Date.now();
    this.elapsed.set(0);
    this.clock = setInterval(() => this.elapsed.set(Math.round((Date.now() - this.startedAt) / 1000)), 1000);
    this.actors().forEach((a, i) => {
      const every = Math.max(250, Math.round(60_000 / a.perMin));
      this.timers.push(setInterval(() => this.fire(i), every));
      // Each actor stops on its own duration; the session ends when the last one does.
      this.timers.push(setTimeout(() => this.retire(i), a.durationSec * 1000));
    });
    this.api.log({ kind: 'algo', ok: true,
      summary: `live session started · ${this.actors().length} accounts, ${this.livePool.length} instruments`
        + (this.batch() ? `, batch ingress ${this.batchSize()}/request` : '') });
  }

  stop(why: string): void {
    if (!this.running() && !this.timers.length) return;
    this.timers.forEach(t => { clearInterval(t); clearTimeout(t); });
    this.timers = [];
    clearInterval(this.clock);
    this.running.set(false);
    this.actors.update(l => l.map(a => ({ ...a, running: false })));
    this.api.log({ kind: 'algo', ok: true,
      summary: `live session ${why} · ${this.sent()} ${this.batch() ? 'batches' : 'orders'} sent, ${this.accepted()} accepted` });
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
    return { ticker, side, quantity: a.quantity === RANDOM_QTY ? randomQty() : a.quantity, limitPrice };
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
      @if (d.running()) { <span class="pill good">running · {{ d.elapsed() }}s</span> }
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
      </div>
      <span class="spacer"></span>
      <div class="go">
        @if (!d.running()) {
          <button class="btn-primary" (click)="d.start()" [disabled]="!d.actors().length || !d.pool().length">Start session</button>
        } @else {
          <button class="stop" (click)="d.stop('stopped by operator')">Stop</button>
        }
      </div>
    </div>

    <table>
      <thead><tr><th>account</th><th>side</th><th class="num">{{ d.batch() ? 'batches/min' : 'orders/min' }}</th>
        <th class="num">qty</th><th class="num">for (s)</th><th class="num">{{ d.batch() ? 'batches' : 'sent' }}</th>
        <th class="num">accepted</th><th class="num">rejected</th><th>last reject</th><th></th></tr></thead>
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
            <td class="sub">{{ a.lastReason || (a.noPrice ? a.noPrice + ' skipped, no price' : '—') }}</td>
            <td>@if (!d.running()) { <button class="cancel" (click)="d.remove(i)">✕</button> }</td>
          </tr>
        }
      </tbody>
    </table>
    @if (!d.running()) { <button class="add" (click)="d.add()">+ add an account</button> }

    <div class="sub note">Quantity <b>-1</b> draws a random multiple of 25 between 25 and 10,000 per
      order. Rejections are logged individually to Activity &amp; rejections with their reason code —
      accepted orders are only counted, because a session at these rates would otherwise push
      everything else out of that list. Capped at {{ maxPerMin }}/min and {{ maxDuration }}s per
      actor@if (d.batch()) { , {{ maxBatch }} orders per batch }.</div>
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
