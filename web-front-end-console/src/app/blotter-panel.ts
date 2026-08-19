import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, BlotterTrade, Position, parseOcc } from './api';
import { HelpTip } from './help';

// The system's own convention, read off the risk-extract cut files: contractMultiplier is 100 for
// OCC option symbols and 1 for everything else (a bond's quantity is already USD face, so face ×
// fraction-of-par is the dollar value with multiplier 1).
const mult = (security: string) => (parseOcc(security) ? 100 : 1);

interface PosRow extends Position {
  last?: number; dir: 1 | -1 | 0; value?: number; upnl?: number;
}

@Component({
  selector: 'blotter-panel',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>Blotter &amp; positions</h2>
      <help-tip text="The account's positions and trade history from the position service, a read model fed downstream of the matching engine. Last prices stream in live from the price publisher over the message bus; market value and unrealized P&L are computed against them, green when the position is in profit, red when it is not. A trade stays 'Processing' until its T+n settlement date passes (or an operator force-settles it from the Admin page) — that is the settlement lifecycle, not a stuck trade." />
      <span class="spacer"></span>
      <span class="pill" [class.good]="live()" [class.warn]="!live()">{{ live() ? 'live · message bus' : 'polling' }}</span>
    </div>
    <label class="field acct">Account
      <select [(ngModel)]="accountId" (ngModelChange)="onAccount()">
        @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
      </select>
    </label>
    <h3>Positions</h3>
    <table>
      <thead><tr><th>security</th><th class="num">qty</th><th class="num">avg cost</th>
        <th class="num">last</th><th class="num">mkt value</th><th class="num">unrealized P&amp;L</th></tr></thead>
      <tbody>
        @for (p of rows(); track p.security) {
          <tr>
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
          <td colspan="4"><b>total</b></td>
          <td class="num"><b>{{ fmt(t.value) }}</b></td>
          <td class="num" [class.pos]="t.upnl > 0" [class.neg]="t.upnl < 0"><b>{{ fmt(t.upnl) }}</b></td>
        </tr></tfoot>
      }
    </table>
    <h3>Trades</h3>
    <table>
      <thead><tr><th>id</th><th>security</th><th>side</th><th class="num">qty</th><th class="num">price</th><th>state</th></tr></thead>
      <tbody>
        @for (t of trades(); track t.id) {
          <tr class="rowlink" (click)="toggle(t)">
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
                @if (t.state !== 'Settled' && !t.rejectionReason && api.adminToken()) {
                  <button (click)="settle(t); $event.stopPropagation()">Force settle</button>
                }
                @if (api.adminToken()) { <button (click)="tca(t); $event.stopPropagation()">TCA</button> }
              </div>
              @if (tcaText()) { <div class="tca">{{ tcaText() }}</div> }
            </td></tr>
          }
        } @empty { <tr><td colspan="6" class="faint">no trades</td></tr> }
      </tbody>
    </table>
  `,
  styles: `
    .acct { margin-bottom: 6px; max-width: 340px; }
    .spacer { flex: 1; }
    h3 { margin: 10px 0 3px; font-size: 12.5px; font-weight: 600; color: var(--muted); }
    .rowlink { cursor: pointer; }
    .rowlink:hover td { background: #f5f7fa; }
    td.det { background: #f8f9fb; }
    .kv { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; font-size: 12.5px; color: var(--muted); }
    .kv b { color: var(--text); font-weight: 600; }
    .tca { margin-top: 5px; font-size: 12.5px; color: var(--accent); }
    td.up { color: var(--good); background: var(--good-soft); transition: background .15s; }
    td.down { color: var(--bad); background: var(--bad-soft); transition: background .15s; }
    .pos { color: var(--good); }
    .neg { color: var(--bad); }
    tfoot td { border-top: 1px solid var(--border); }
  `,
})
export class BlotterPanel implements OnInit, OnDestroy {
  readonly api = inject(Api);
  accountId = 22214;
  readonly positions = signal<Position[]>([]);
  readonly trades = signal<BlotterTrade[]>([]);
  readonly live = signal(false);
  readonly openId = signal<string | null>(null);
  readonly tcaText = signal('');
  private timer: ReturnType<typeof setInterval> | undefined;
  private unsub: (() => void) | null = null;

  toggle(t: BlotterTrade): void {
    this.tcaText.set('');
    this.openId.set(this.openId() === t.id ? null : t.id);
  }

  async settle(t: BlotterTrade): Promise<void> {
    const r = await this.api.load<void>(`/trade-processor/trades/${t.id}/settlement/force`, {
      method: 'POST', headers: this.api.authHeaders(),
    });
    this.api.log({ kind: 'eod', ok: r.status === 200, summary: `force settle ${t.id} → HTTP ${r.status}` });
    this.poll();
  }

  async tca(t: BlotterTrade): Promise<void> {
    const r = await this.api.load<{ benchmarkPrice: number; arrivalPrice: number; slippageBps: number; benchmarkSampleCount: number }>(
      `/trade-processor/tca/report/${t.id}`, { headers: this.api.authHeaders() });
    this.tcaText.set(r.status === 200 && r.body
      ? `TCA: execution ${t.price} vs benchmark ${r.body.benchmarkPrice} (arrival ${r.body.arrivalPrice}) · ${r.body.slippageBps} bps · ${r.body.benchmarkSampleCount} samples`
      : `TCA unavailable (HTTP ${r.status})`);
  }

  readonly rows = computed<PosRow[]>(() => {
    const prices = this.api.prices();
    return this.positions().map(p => {
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
  readonly totals = computed(() => {
    const rs = this.rows().filter(r => r.value !== undefined);
    if (!rs.length) return null;
    return {
      value: rs.reduce((s, r) => s + r.value!, 0),
      upnl: rs.reduce((s, r) => s + (r.upnl ?? 0), 0),
    };
  });

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
      [`/accounts/${Number(this.accountId)}/trades`, `/accounts/${Number(this.accountId)}/positions`],
      () => this.poll(),
      up => this.live.set(up));
  }

  async poll(): Promise<void> {
    const id = Number(this.accountId);
    const [p, t] = await Promise.all([
      this.api.load<Position[]>(`/position-service/positions/${id}`),
      this.api.load<BlotterTrade[]>(`/position-service/trades/${id}`),
    ]);
    if (p.status === 200 && Array.isArray(p.body)) this.positions.set(p.body);
    // The service returns newest-first; take the head as-is (reversing dropped the NEWEST past 30).
    if (t.status === 200 && Array.isArray(t.body)) this.trades.set(t.body.slice(0, 30));
  }
}
