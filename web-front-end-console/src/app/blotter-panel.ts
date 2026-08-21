import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, BlotterTrade, OtcContract, Position, parseOcc } from './api';
import { HelpTip } from './help';
import { Gated } from './gated';
import { TraceView } from './trace-view';
import { SecHead, SecPager, Section } from './section';

// The system's own convention, read off the risk-extract cut files: contractMultiplier is 100 for
// OCC option symbols and 1 for everything else (a bond's quantity is already USD face, so face ×
// fraction-of-par is the dollar value with multiplier 1).
const mult = (security: string) => (parseOcc(security) ? 100 : 1);

interface OpenOrder {
  orderId: string; security: string; side: string;
  quantity: number; remainingQuantity: number; limitPrice: number;
}

interface PosRow extends Position {
  last?: number; dir: 1 | -1 | 0; value?: number; upnl?: number;
}

/**
 * `<epoch>-<orderRef>` → the ref. Returns 0 for a market sweep (`<epoch>-0`), which has no
 * originating order by design, and null when there is nothing parseable to join on.
 */
const orderRefOf = (t: { sourceOrderId?: string | null }): number | null => {
  const m = /^\d+-(\d+)$/.exec(String(t.sourceOrderId ?? ''));
  return m ? Number(m[1]) : null;
};

@Component({
  selector: 'blotter-panel',
  imports: [FormsModule, HelpTip, SecHead, SecPager, Gated, TraceView],
  template: `
    <div class="card-head">
      <h2>Blotter &amp; positions</h2>
      <help-tip text="The account's positions and trade history from the position service, a read model fed downstream of the matching engine. Last prices stream in live from the price publisher over the message bus; market value and unrealized P&L are computed against them, green when the position is in profit, red when it is not. A trade stays 'Processing' until its T+n settlement date passes (or an operator force-settles it from the Admin page) — that is the settlement lifecycle, not a stuck trade." />
      <span class="spacer"></span>
      <span class="pill" [class.good]="live()" [class.warn]="!live()">{{ live() ? 'message bus · live' : 'polling' }}</span>
    </div>
    <div class="bar">
      <label class="field acct">Account
        <select [ngModel]="accountId()" (ngModelChange)="accountId.set($event); onAccount()">
          @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
        </select>
      </label>
      <label class="field find">Find by reference
        <input [(ngModel)]="query" (keyup.enter)="find()" placeholder="order or trade id" spellcheck="false">
      </label>
      <button type="button" (click)="find()">Go</button>
      @if (findMsg(); as m) { <span class="pill" [class.good]="m.ok" [class.warn]="!m.ok">{{ m.text }}</span> }
    </div>

    <sec-head [s]="positions" label="Positions" />
    @if (positions.open()) {
      <table>
        <thead><tr><th>security</th><th class="num">qty</th><th class="num">avg cost</th>
          <th class="num">last</th><th class="num">mkt value</th><th class="num">unrealized P&amp;L</th></tr></thead>
        <tbody>
          @for (p of positions.view(); track p.security) {
            <tr [class.hit]="hit() === p.security">
              <td>{{ p.security }}</td>
              <td class="num">{{ p.quantity }}</td>
              <td class="num">{{ p.averageCostBasis.toFixed(6) }}</td>
              <td class="num" [class.up]="p.dir === 1" [class.down]="p.dir === -1">
                {{ p.last !== undefined ? p.last.toFixed(p.last < 2 ? 6 : 3) : '—' }}</td>
              <td class="num">{{ p.value !== undefined ? fmt(p.value) : '—' }}</td>
              <td class="num" [class.pos]="(p.upnl ?? 0) > 0" [class.neg]="(p.upnl ?? 0) < 0">
                {{ p.upnl !== undefined ? fmt(p.upnl) : '—' }}</td>
            </tr>
          } @empty { <tr><td colspan="6" class="faint">no positions</td></tr> }
        </tbody>
        @if (totals(); as t) {
          <tfoot><tr>
            <td colspan="4"><b>total</b> <span class="faint">(all {{ rows().length }} positions)</span></td>
            <td class="num"><b>{{ fmt(t.value) }}</b></td>
            <td class="num" [class.pos]="t.upnl > 0" [class.neg]="t.upnl < 0"><b>{{ fmt(t.upnl) }}</b></td>
          </tr></tfoot>
        }
      </table>
      <sec-pager [s]="positions" />
    }

    <sec-head [s]="openOrders" label="Open orders">
      <help-tip text="Orders resting in the matching engine's book: submitted, sequenced through consensus, accepted — and waiting for someone to trade against them. An order that rests is a normal outcome, not a failure; it only becomes a trade when a counterparty crosses it. Without this view a resting order is invisible, which makes a perfectly good order look like it vanished." />
    </sec-head>
    @if (openOrders.open()) {
      <table>
        <thead><tr><th>id</th><th>security</th><th>side</th><th class="num">qty</th><th class="num">remaining</th><th class="num">limit</th><th></th></tr></thead>
        <tbody>
          @for (o of openOrders.view(); track o.orderId) {
            <tr [class.hit]="hit() === o.orderId">
              <td class="sub">{{ o.orderId }}</td><td>{{ o.security }}</td><td>{{ o.side }}</td>
              <td class="num">{{ o.quantity }}</td><td class="num">{{ o.remainingQuantity }}</td>
              <td class="num">{{ o.limitPrice }}</td>
              <td><button class="cancel" (click)="cancelOrder(o)">cancel</button></td>
            </tr>
          } @empty { <tr><td colspan="7" class="faint">no resting orders</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="openOrders" />
    }

    @if (contracts().length) {
      <sec-head [s]="otc" label="OTC contracts">
        <help-tip text="Swaps and swaptions are carried at CONTRACT grain, never as positions — a receive-fixed and a pay-fixed of equal notional would net to zero as positions and destroy both rates, which the netting proof exists to catch. They are also invisible everywhere else on this tier until the next end-of-day cut: the booking routes are write-only, there is no contract table, and the regulatory report enumerates order and trade kinds only. So the console keeps what it booked, with the consensus sequence the engine assigned." />
      </sec-head>
      @if (otc.open()) {
        <table>
          <thead><tr><th>contract</th><th class="num">seq</th><th>terms</th><th class="num">notional</th><th>booked</th></tr></thead>
          <tbody>
            @for (c of otc.view(); track c.contractId) {
              <tr [class.hit]="hit() === c.contractId">
                <td>{{ c.contractId }}</td>
                <td class="num">{{ c.sequence }}</td>
                <td class="sub">{{ c.payReceive }} fixed {{ (c.fixedRate * 100).toFixed(3) }}% ·
                  {{ c.conventions }} · {{ c.effectiveDate }} → {{ c.maturityDate }}@if (c.exerciseStyle) {
                    · {{ c.exerciseStyle }} expiry {{ c.expiryDate }} }</td>
                <td class="num">{{ fmt(c.notional) }}</td>
                <td class="sub">{{ c.bookedAt.slice(11, 19) }}</td>
              </tr>
            }
          </tbody>
        </table>
        <sec-pager [s]="otc" />
      }
    }

    <sec-head [s]="trades" label="Trades" />
    @if (trades.open()) {
      <table>
        <thead><tr><th>id</th><th>security</th><th>side</th><th class="num">qty</th><th class="num">price</th><th>state</th></tr></thead>
        <tbody>
          @for (t of trades.view(); track t.id) {
            <tr class="rowlink" [class.hit]="hit() === t.id" (click)="toggle(t)">
              <td class="sub">{{ t.id }} {{ openId() === t.id ? '▾' : '▸' }}</td><td>{{ t.security }}</td><td>{{ t.side }}</td>
              <td class="num">{{ t.quantity }}</td><td class="num">{{ t.price.toFixed(6) }}</td>
              <td>@if (t.rejectionReason) { <span class="pill bad">{{ t.rejectionReason }}</span> }
                  @else if (t.state === 'Settled') { <span class="pill good">Settled</span> }
                  @else { {{ t.state }} }</td>
            </tr>
            @if (openId() === t.id) {
              <tr><td colspan="6" class="det">
                <div class="kv">
                  <span>created <b>{{ t.created.slice(0, 19).replace('T', ' ') }}</b></span>
                  <span>updated <b>{{ t.updated.slice(0, 19).replace('T', ' ') }}</b></span>
                  @if (t.sourceOrderId) { <span>source order <b>{{ t.sourceOrderId }}</b></span> }
                  <span>notional <b>{{ (t.quantity * t.price).toLocaleString('en-US', { maximumFractionDigits: 2 }) }}</b></span>
                  <!-- No longer gated on holding a token: the console holds none, and hiding a
                       control the server merely refuses tells the operator it does not exist. It
                       renders, marked, and the server decides. -->
                  @if (t.state !== 'Settled' && !t.rejectionReason) {
                    <button (click)="settle(t); $event.stopPropagation()">Force settle</button> <gated />
                  }
                  <button (click)="tca(t); $event.stopPropagation()">TCA</button>
                </div>
                <!-- Joined through sourceOrderId to this session's own activity entry, which is
                     where the client order id and its trace live. Not derived from the trade — a
                     trade row carries no client order id. See traceOf(). -->
                <div class="trace" (click)="$event.stopPropagation()">
                  <trace-view [traceId]="traceOf(t)" derivedFrom="trade" />
                </div>
                @if (tcaText()) { <div class="tca">{{ tcaText() }}</div> }
              </td></tr>
            }
          } @empty { <tr><td colspan="6" class="faint">no trades</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="trades" />
    }
  `,
  styles: `
    .bar { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; margin-bottom: 4px; }
    .acct { max-width: 300px; }
    .find input { width: 190px; }
    .spacer { flex: 1; }
    .rowlink { cursor: pointer; }
    .rowlink:hover td { background: #f5f7fa; }
    tr.hit td { background: var(--accent-soft); }
    td.det { background: #f8f9fb; }
    .kv { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; font-size: 12.5px; color: var(--muted); }
    .kv b { color: var(--text); font-weight: 600; }
    .tca { margin-top: 5px; font-size: 12.5px; color: var(--accent); }
    td.up { color: var(--good); background: var(--good-soft); transition: background .15s; }
    td.down { color: var(--bad); background: var(--bad-soft); transition: background .15s; }
    .cancel { font-size: 11.5px; padding: 1px 8px; }
    .pos { color: var(--good); }
    .neg { color: var(--bad); }
    tfoot td { border-top: 1px solid var(--border); }
  `,
})
export class BlotterPanel implements OnInit, OnDestroy {
  readonly api = inject(Api);
  // A signal, not a plain field: the contracts list below is a computed(), and a computed only
  // recomputes when a SIGNAL it reads changes — as a plain field this would have kept showing the
  // previous account's contracts after a switch. Same trap that made the ticket's bond terms go
  // stale; worth fixing at the second sighting rather than the third.
  readonly accountId = signal(22214);
  readonly rawPositions = signal<Position[]>([]);
  readonly rawTrades = signal<BlotterTrade[]>([]);
  readonly rawOpenOrders = signal<OpenOrder[]>([]);
  readonly live = signal(false);
  readonly openId = signal<string | null>(null);
  readonly contracts = computed<OtcContract[]>(() =>
    this.api.contracts().filter(c => c.accountId === Number(this.accountId())));
  readonly tcaText = signal('');
  query = '';
  readonly findMsg = signal<{ ok: boolean; text: string } | null>(null);
  /** The row the last search landed on, highlighted until the next search. */
  readonly hit = signal<string | null>(null);
  private timer: ReturnType<typeof setInterval> | undefined;
  private unsub: (() => void) | null = null;

  toggle(t: BlotterTrade): void {
    this.tcaText.set('');
    this.openId.set(this.openId() === t.id ? null : t.id);
  }

  async settle(t: BlotterTrade): Promise<void> {
    const r = await this.api.load<void>(`/trade-processor/trades/${t.id}/settlement/force`, {
      method: 'POST',
    });
    this.api.log({ kind: 'eod', ok: r.status === 200, summary: `force settle ${t.id} → HTTP ${r.status}` });
    this.poll();
  }

  /** Cancel takes the gateway's sibling /cancel route with the numeric ref — NOT
   *  /orders/{id}/cancel, which the gateway routes to its new-order handler. */
  async cancelOrder(o: OpenOrder): Promise<void> {
    const ref = Number(o.orderId.includes('-') ? o.orderId.slice(o.orderId.lastIndexOf('-') + 1) : o.orderId);
    const r = await this.api.post<{ canceled?: boolean }>('/order-matcher/cancel', { orderRef: ref });
    this.api.log({
      kind: 'cancel', ok: r.status === 200 && !!r.body?.canceled,
      summary: `cancel ${o.orderId} (${o.security}) → ${r.status === 200 && r.body?.canceled ? 'canceled' : `HTTP ${r.status}`}`,
    });
    this.poll();
  }

  /**
   * A trade's trace id, JOINED rather than derived.
   *
   * `sourceOrderId` now links a trade to the order that produced it (`<epoch>-<orderRef>`), and the
   * console already knows the client order id AND the trace id of every order IT submitted — so the
   * path is trade → order ref → this session's own activity entry → trace id. Verified end to end:
   * trade `8-B` carries `1-8`, and the entry for order ref 8 holds `console-…821` and trace
   * `deea9ba9b453d31e124bb5938cb68aef`.
   *
   * NOT a derivation from the trade, and the distinction matters. Trace ids come from the CLIENT
   * ORDER ID, which the engine never sees and no trade row can carry; `sourceOrderId` is a
   * trade→order link, not a trace link. This works only because the console kept what it generated.
   * A trade from any other source — FIX, another browser, a previous page load — has no entry to
   * join to and gets no trace, which the view says plainly rather than guessing a hash.
   *
   * `<epoch>-0` is a market sweep with no originating order (FR-09B08), and is not a missing join.
   *
   * A joined id still usually 404s, and that is the RIG, not this. GKE sets `OTEL_SAMPLE_MASK=127`
   * — 1 in 128 — where the kind manifests set 0 (trace everything). Measured 2026-08-21 against the
   * cloud rig: nine consecutive accepted orders all 404, while a rejected order's trace answered 200
   * within 15s from the same page. Rejections escalate past the mask; accepted orders, which are the
   * only ones that ever become trades, do not. So expect the button to resolve about 1 trade in 128
   * here and every trade on a kind rig.
   *
   * (Before `sourceOrderId` existed I read the trade id `3580-S` as `<orderRef>-<side>` and derived
   * from that. It was wrong — the numeric part is the engine's TRADE sequence, a different counter:
   * measured at the time, trade counter 4626 against a live order ref of 2719. The evidence that
   * convinced me included "no trade id carries two sides", which is a non-discriminator, since trade
   * sequence also increments per side.)
   */
  traceOf(t: BlotterTrade): string | undefined {
    const ref = orderRefOf(t);
    if (ref === null || ref === 0) return undefined;
    return this.api.activity().find(e => e.orderRef === ref && e.traceId)?.traceId;
  }

  /** The originating order's ref, or null when the trade carries no usable link. */
  sourceRef(t: BlotterTrade): number | null { return orderRefOf(t); }

  async tca(t: BlotterTrade): Promise<void> {
    const r = await this.api.load<{ benchmarkPrice: number; arrivalPrice: number; slippageBps: number; benchmarkSampleCount: number }>(
      `/trade-processor/tca/report/${t.id}`);
    this.tcaText.set(r.status === 200 && r.body
      ? `TCA: execution ${t.price} vs benchmark ${r.body.benchmarkPrice} (arrival ${r.body.arrivalPrice}) · ${r.body.slippageBps} bps · ${r.body.benchmarkSampleCount} samples`
      : `TCA unavailable (HTTP ${r.status})`);
  }

  readonly rows = computed<PosRow[]>(() => {
    const prices = this.api.prices();
    return this.rawPositions().map(p => {
      const tick = prices[p.security];
      const m = mult(p.security);
      return {
        ...p,
        last: tick?.price,
        dir: tick?.dir ?? 0,
        value: tick ? p.quantity * tick.price * m : undefined,
        upnl: tick ? (tick.price - p.averageCostBasis) * p.quantity * m : undefined,
      };
    });
  });
  // Totals span EVERY position, not the visible page: a portfolio total that changed when you
  // turned the page would be a lie.
  readonly totals = computed(() => {
    const rs = this.rows().filter(r => r.value !== undefined);
    if (!rs.length) return null;
    return {
      value: rs.reduce((s, r) => s + r.value!, 0),
      upnl: rs.reduce((s, r) => s + (r.upnl ?? 0), 0),
    };
  });

  readonly positions = new Section<PosRow>(this.rows, p => p.security);
  readonly openOrders = new Section<OpenOrder>(this.rawOpenOrders, o => o.orderId);
  readonly otc = new Section<OtcContract>(this.contracts, c => c.contractId);
  readonly trades = new Section<BlotterTrade>(this.rawTrades, t => t.id);

  /** Find a reference across every section and page straight to it. */
  find(): void {
    const q = this.query.trim();
    if (!q) { this.findMsg.set(null); this.hit.set(null); return; }
    const where: { name: string; sec: Section<any> }[] = [
      { name: 'open orders', sec: this.openOrders },
      { name: 'trades', sec: this.trades },
      { name: 'contracts', sec: this.otc },
      { name: 'positions', sec: this.positions },
    ];
    for (const { name, sec } of where) {
      if (!sec.reveal(q)) continue;
      const row = sec.items().find(x => sec.idOf(x).toLowerCase().includes(q.toLowerCase()));
      this.hit.set(row ? sec.idOf(row) : null);
      this.findMsg.set({ ok: true, text: `${name} · page ${sec.cur() + 1}` });
      return;
    }
    this.hit.set(null);
    this.findMsg.set({ ok: false, text: `${q} not in this account's rows` });
  }

  fmt(v: number): string {
    return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 3000);
    this.api.watchPrices();
    this.follow();
  }
  ngOnDestroy(): void { clearInterval(this.timer); this.unsub?.(); }

  onAccount(): void { this.poll(); this.follow(); }

  /** Subscribe this account's bus topics; any message triggers an immediate re-read. */
  private follow(): void {
    this.unsub?.();
    this.unsub = this.api.busSubscribe(
      [`/accounts/${Number(this.accountId())}/trades`, `/accounts/${Number(this.accountId())}/positions`],
      () => this.poll(),
      up => this.live.set(up));
  }

  async poll(): Promise<void> {
    const id = Number(this.accountId());
    const [p, t, o] = await Promise.all([
      this.api.load<Position[]>(`/position-service/positions/${id}`),
      this.api.load<BlotterTrade[]>(`/position-service/trades/${id}`),
      // The gateway serves no order snapshot (405 POST only); trade-processor's order read model
      // does, keyed `id` rather than `orderId`.
      this.api.load<any[]>(`/trade-processor/accounts/${id}/orders`),
    ]);
    if (p.status === 200 && Array.isArray(p.body)) this.rawPositions.set(p.body);
    if (o.status === 200 && Array.isArray(o.body)) {
      this.rawOpenOrders.set(o.body.map(row => ({
        orderId: String(row.id ?? row.orderId ?? ''),
        security: row.security, side: row.side,
        quantity: Number(row.quantity ?? 0),
        remainingQuantity: Number(row.remainingQuantity ?? 0),
        limitPrice: Number(row.limitPrice ?? 0),
      })));
    }
    // The service returns newest-first; take the head as-is (reversing dropped the NEWEST past 30).
    // 200 rather than 30 since the section pages: a search for an older reference has to be able
    // to find it.
    if (t.status === 200 && Array.isArray(t.body)) this.rawTrades.set(t.body.slice(0, 200));
  }
}
