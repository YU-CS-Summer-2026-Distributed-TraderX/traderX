import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, BlotterTrade } from './api';
import { HelpTip } from './help';
import { Gated } from './gated';
import { SecHead, SecPager, Section } from './section';

interface TcaReport {
  tradeId: string; security: string; side: string; quantity: number;
  executionPrice: number; benchmarkPrice: number; arrivalPrice: number;
  slippageBps: number; benchmarkSampleCount: number;
}

interface ReconStatus { [k: string]: unknown; }

interface ParentOrder {
  parentOrderId: string; accountId: number; security: string; side: string; quantity: number;
  algoType: string; durationSeconds: number; bucketSeconds: number; status: string;
  buckets: { quantity: number; state?: string; [k: string]: unknown }[];
}

@Component({
  selector: 'admin-panel',
  imports: [FormsModule, HelpTip, SecHead, SecPager, Gated],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>Trade lifecycle &amp; TCA</h2>
      </button>
      <help-tip text="A trade books immediately but settles on a T+n cycle: it stays 'Processing' until its settlement date passes, when a sweep advances it to 'Settled'. Force-settle demonstrates that transition on demand. TCA (transaction cost analysis) compares each execution against the market's benchmark and arrival prices and expresses the difference in basis points — the standard measure of execution quality." />
    </div>

    @if (!open()) {
    } @else if (!api.adminToken()) {
      <div class="sub">Admin operations need a token — open the End of day page once to auto-mint it.</div>
    } @else {
      <label class="field acct">Account
        <select [(ngModel)]="accountId" (ngModelChange)="poll()">
          @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
        </select>
      </label>
      <sec-head [s]="section" label="Trades" />
      @if (section.open()) {
      <table>
        <thead><tr><th>id</th><th>security</th><th>side</th><th class="num">qty</th><th class="num">price</th><th>state</th><th></th><th></th></tr></thead>
        <tbody>
          @for (t of section.view(); track t.id) {
            <tr>
              <td class="sub">{{ t.id }}</td><td>{{ t.security }}</td><td>{{ t.side }}</td>
              <td class="num">{{ t.quantity }}</td><td class="num">{{ t.price.toFixed(6) }}</td>
              <td>@if (t.state === 'Settled') { <span class="pill good">Settled</span> } @else { {{ t.state }} }</td>
              <td>@if (t.state !== 'Settled' && !t.rejectionReason) {
                <button (click)="settle(t)">Force settle</button> <gated /> }</td>
              <td><button (click)="tca(t)">TCA</button></td>
            </tr>
            @if (tcaReport()?.tradeId === t.id) {
              <tr class="tca-row"><td colspan="8">
                <b>TCA — {{ t.id }}</b> · execution {{ tcaReport()!.executionPrice }} vs benchmark
                {{ tcaReport()!.benchmarkPrice }} (arrival {{ tcaReport()!.arrivalPrice }}) ·
                <b [class.pos]="tcaReport()!.slippageBps >= 0" [class.neg]="tcaReport()!.slippageBps < 0">
                  {{ tcaReport()!.slippageBps }} bps</b>
                · {{ tcaReport()!.benchmarkSampleCount }} benchmark samples
                <a (click)="tcaReport.set(null)">close</a>
              </td></tr>
            }
          } @empty { <tr><td colspan="8" class="faint">no trades for this account</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="section" />
      }

      <div class="card-head sect">
        <h2>Algo parent orders</h2>
        <help-tip text="A parent order handed to the execution algo engine, sliced into child orders on a TWAP or VWAP schedule — each child goes through the same gateway and consensus path as a manual order, and rests in the book as a live limit order until something crosses it. When a child fills, its bucket is marked filled and the parent completes. Submit one from the order ticket via the two 'Algo 1/2 + 2/2' presets — the book is consumable, so the demo posts its own liquidity first. Parents left RUNNING with unfilled buckets are simply waiting: their slices are resting limit orders that nothing has crossed yet." />
      </div>
      @if (parents().length) {
        <table>
          <thead><tr><th>parent</th><th>security</th><th>side</th><th class="num">qty</th><th>algo</th><th>status</th><th class="num">buckets</th></tr></thead>
          <tbody>
            @for (p of parents(); track p.parentOrderId) {
              <tr class="rowlink" (click)="openParent.set(openParent() === p.parentOrderId ? null : p.parentOrderId)">
                <td class="sub">{{ p.parentOrderId.slice(0, 12) }}…</td>
                <td>{{ p.security }}</td><td>{{ p.side }}</td><td class="num">{{ p.quantity }}</td>
                <td>{{ p.algoType }}</td>
                <td>@if (p.status === 'COMPLETE') { <span class="pill good">{{ p.status }}</span> } @else { {{ p.status }} }</td>
                <td class="num">{{ p.buckets.length }}</td>
              </tr>
              @if (openParent() === p.parentOrderId) {
                <tr><td colspan="7" class="det">
                  @for (b of p.buckets; track $index) {
                    <span class="bucket" [class.done]="b.state === 'SENT' || b.state === 'FILLED'">
                      {{ b.quantity }}@if (b.state) { · {{ b.state }} }</span>
                  }
                </td></tr>
              }
            }
          </tbody>
        </table>
      } @else if (algoDown()) {
        <div class="banner warn-note">Algo engine is scaled to 0 — the proof suite parks it there
          deliberately (its child orders move counters under counter-exact proofs). To demo:
          <span class="mono">kubectl -n traderx scale deploy/execution-algo-engine --replicas=1</span>
          <div class="sub">— against whichever context your kubectl currently points at. There are two
            rigs and no <span class="mono">--context</span> here on purpose: hardcoding one would be
            right on that rig and quietly wrong on the other. Check it first.</div></div>
      } @else { <div class="faint">no parent orders yet — submit one from the ticket with TWAP/VWAP execution</div> }

      <div class="card-head sect">
        <h2>Open-order controls</h2>
        <help-tip text="Cancels go through the same consensus path as orders: the cancel is sequenced, the engine removes the resting order, and the acknowledgement comes back committed. Force-fill does not exist on the cluster tier — the engine's book decides fills; an operator can only cancel." />
      </div>
      <div class="bar">
        <input type="number" [(ngModel)]="cancelRef" placeholder="orderRef">
        <button (click)="cancel()">Cancel order</button>
        @if (cancelMsg(); as m) { <span class="pill" [class.good]="m.ok" [class.bad]="!m.ok">{{ m.text }}</span> }
      </div>

      <div class="card-head sect">
        <h2>Reconciliation</h2>
        <help-tip text="The reconciliation service continuously compares the trades the engine reports against what the downstream read model recorded, and can sweep for orphans — engine trades that never reached the database. A clean status is the evidence that the read model you see is complete." />
      </div>
      <div class="bar">
        <button (click)="recon()">Refresh status</button>
        <button (click)="sweep()">Run orphan sweep</button> <gated />
      </div>
      @if (reconStatus(); as r) { <pre class="recon">{{ r }}</pre> }
    }
  `,
  styles: `
    .acct { margin-bottom: 6px; max-width: 340px; }
    .sect { margin-top: 18px; }
    .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .tca-row td { background: var(--accent-soft); font-size: 12.5px; }
    .rowlink { cursor: pointer; }
    .rowlink:hover td { background: #f5f7fa; }
    td.det { background: #f8f9fb; }
    .bucket { display: inline-block; margin: 2px 4px 2px 0; padding: 2px 8px; border-radius: 5px;
              background: #eef0f3; color: var(--muted); font-size: 12px; font-family: var(--mono); }
    .bucket.done { background: var(--good-soft); color: var(--good); }
    .warn-note { background: var(--warn-soft); color: var(--warn); }
    .mono { font-family: var(--mono); font-size: 12px; }
    .pos { color: var(--good); } .neg { color: var(--bad); }
    .recon { font-family: var(--mono); font-size: 12px; color: var(--muted); background: #f8f9fb;
             border-radius: 6px; padding: 8px; max-height: 200px; overflow: auto; }
    a { color: var(--faint); cursor: pointer; text-decoration: underline; margin-left: 8px; }
  `,
})
export class AdminPanel implements OnInit, OnDestroy {
  readonly api = inject(Api);
  accountId = 22214;
  cancelRef: number | null = null;
  readonly open = signal(true);
  readonly trades = signal<BlotterTrade[]>([]);
  /** Same collapse-and-page behaviour as the blotter's sections, from the same class. */
  readonly section = new Section<BlotterTrade>(this.trades, t => t.id);
  readonly parents = signal<ParentOrder[]>([]);
  readonly openParent = signal<string | null>(null);
  readonly algoDown = signal(false);
  readonly tcaReport = signal<TcaReport | null>(null);
  readonly cancelMsg = signal<{ ok: boolean; text: string } | null>(null);
  readonly reconStatus = signal<string | null>(null);
  private timer: ReturnType<typeof setInterval> | undefined;

  ngOnInit(): void {
    if (!this.api.adminToken()) this.api.mintAdminToken().then(() => this.poll());
    else this.poll();
    this.timer = setInterval(() => this.poll(), 5000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  async poll(): Promise<void> {
    const [r, p] = await Promise.all([
      this.api.load<BlotterTrade[]>(`/position-service/trades/${Number(this.accountId)}`),
      this.api.load<ParentOrder[]>('/algo/orders'),
    ]);
    // Newest-first from the service; head as-is (reversing dropped the NEWEST past 30). 200 rather
    // than 30 now that the section pages — a page-4 row has to exist to be paged to.
    if (r.status === 200 && Array.isArray(r.body)) this.trades.set(r.body.slice(0, 200));
    if (p.status === 200 && Array.isArray(p.body)) { this.parents.set([...p.body].reverse()); this.algoDown.set(false); }
    else if (p.status >= 500 || p.status === 0 || p.status === 502) { this.parents.set([]); this.algoDown.set(true); }
  }

  async settle(t: BlotterTrade): Promise<void> {
    const r = await this.api.load<void>(`/trade-processor/trades/${t.id}/settlement/force`, {
      method: 'POST', headers: this.api.authHeaders(),
    });
    this.api.log({ kind: 'eod', ok: r.status === 200, summary: `force settle ${t.id} → HTTP ${r.status}` });
    this.poll();
  }

  async tca(t: BlotterTrade): Promise<void> {
    const r = await this.api.load<TcaReport>(`/trade-processor/tca/report/${t.id}`, { headers: this.api.authHeaders() });
    this.tcaReport.set(r.status === 200 ? r.body : null);
    if (r.status !== 200) this.api.log({ kind: 'eod', ok: false, summary: `tca ${t.id} → HTTP ${r.status}` });
  }

  async cancel(): Promise<void> {
    if (!this.cancelRef) return;
    const r = await this.api.post<{ canceled?: boolean; kind?: number; error?: string }>(
      '/order-matcher/cancel', { orderRef: Number(this.cancelRef) });
    const ok = r.status === 200 && !!r.body?.canceled;
    this.cancelMsg.set({ ok, text: ok ? `orderRef ${this.cancelRef} canceled` : r.status === 404 ? 'order not found' : `HTTP ${r.status}` });
    this.api.log({ kind: 'cancel', ok, summary: `cancel orderRef ${this.cancelRef} → HTTP ${r.status}` });
  }

  async recon(): Promise<void> {
    const r = await this.api.load<ReconStatus>('/trade-processor/recon/status', { headers: this.api.authHeaders() });
    this.reconStatus.set(r.body ? JSON.stringify(r.body, null, 2) : `HTTP ${r.status}`);
  }

  async sweep(): Promise<void> {
    const r = await this.api.load<ReconStatus>('/trade-processor/recon/orphan-sweep', {
      method: 'POST', headers: this.api.authHeaders(),
    });
    this.reconStatus.set(r.body ? JSON.stringify(r.body, null, 2) : `HTTP ${r.status}`);
  }
}
