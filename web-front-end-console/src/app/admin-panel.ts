import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, BlotterTrade } from './api';
import { HelpTip } from './help';

interface TcaReport {
  tradeId: string; security: string; side: string; quantity: number;
  executionPrice: number; benchmarkPrice: number; arrivalPrice: number;
  slippageBps: number; benchmarkSampleCount: number;
}

interface ReconStatus { [k: string]: unknown; }

@Component({
  selector: 'admin-panel',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>Trade lifecycle &amp; TCA</h2>
      <help-tip text="A trade books immediately but settles on a T+n cycle: it stays 'Processing' until its settlement date passes, when a sweep advances it to 'Settled'. Force-settle demonstrates that transition on demand. TCA (transaction cost analysis) compares each execution against the market's benchmark and arrival prices and expresses the difference in basis points — the standard measure of execution quality." />
    </div>

    @if (!api.adminToken()) {
      <div class="sub">Admin operations need a token — open the End of day page once to auto-mint it.</div>
    } @else {
      <label class="field acct">Account
        <select [(ngModel)]="accountId" (ngModelChange)="poll()">
          @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
        </select>
      </label>
      <table>
        <thead><tr><th>id</th><th>security</th><th>side</th><th class="num">qty</th><th class="num">price</th><th>state</th><th></th><th></th></tr></thead>
        <tbody>
          @for (t of trades(); track t.id) {
            <tr>
              <td class="sub">{{ t.id }}</td><td>{{ t.security }}</td><td>{{ t.side }}</td>
              <td class="num">{{ t.quantity }}</td><td class="num">{{ t.price.toFixed(6) }}</td>
              <td>@if (t.state === 'Settled') { <span class="pill good">Settled</span> } @else { {{ t.state }} }</td>
              <td>@if (t.state !== 'Settled' && !t.rejectionReason) {
                <button (click)="settle(t)">Force settle</button> }</td>
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
        <button (click)="sweep()">Run orphan sweep</button>
      </div>
      @if (reconStatus(); as r) { <pre class="recon">{{ r }}</pre> }
    }
  `,
  styles: `
    .acct { margin-bottom: 6px; max-width: 340px; }
    .sect { margin-top: 18px; }
    .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .tca-row td { background: var(--accent-soft); font-size: 12.5px; }
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
  readonly trades = signal<BlotterTrade[]>([]);
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
    const r = await this.api.load<BlotterTrade[]>(`/position-service/trades/${Number(this.accountId)}`);
    if (r.status === 200 && Array.isArray(r.body)) this.trades.set([...r.body].reverse().slice(0, 30));
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
