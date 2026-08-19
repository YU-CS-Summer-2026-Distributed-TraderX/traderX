import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, BlotterTrade, Position } from './api';
import { HelpTip } from './help';

@Component({
  selector: 'blotter-panel',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>Blotter &amp; positions</h2>
      <help-tip text="The account's positions and trade history, read from the position service — a read model fed downstream of the matching engine. A trade booked in the engine flows through the trade processor into this view. 'live' means updates arrive over the message bus the moment a trade books; otherwise the view refreshes every few seconds." />
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
      <thead><tr><th>security</th><th class="num">qty</th><th class="num">avg cost</th><th>updated</th></tr></thead>
      <tbody>
        @for (p of positions(); track p.security) {
          <tr><td>{{ p.security }}</td><td class="num">{{ p.quantity }}</td>
              <td class="num">{{ p.averageCostBasis.toFixed(6) }}</td><td class="sub">{{ p.updated.slice(11, 19) }}</td></tr>
        } @empty { <tr><td colspan="4" class="faint">no positions</td></tr> }
      </tbody>
    </table>
    <h3>Trades</h3>
    <table>
      <thead><tr><th>id</th><th>security</th><th>side</th><th class="num">qty</th><th class="num">price</th><th>state</th></tr></thead>
      <tbody>
        @for (t of trades(); track t.id) {
          <tr>
            <td class="sub">{{ t.id }}</td><td>{{ t.security }}</td><td>{{ t.side }}</td>
            <td class="num">{{ t.quantity }}</td><td class="num">{{ t.price.toFixed(6) }}</td>
            <td>@if (t.rejectionReason) { <span class="pill bad">{{ t.rejectionReason }}</span> } @else { {{ t.state }} }</td>
          </tr>
        } @empty { <tr><td colspan="6" class="faint">no trades</td></tr> }
      </tbody>
    </table>
  `,
  styles: `
    .acct { margin-bottom: 6px; max-width: 340px; }
    .spacer { flex: 1; }
    h3 { margin: 10px 0 3px; font-size: 12.5px; font-weight: 600; color: var(--muted); }
  `,
})
export class BlotterPanel implements OnInit, OnDestroy {
  readonly api = inject(Api);
  accountId = 22214;
  readonly positions = signal<Position[]>([]);
  readonly trades = signal<BlotterTrade[]>([]);
  readonly live = signal(false);
  private timer: ReturnType<typeof setInterval> | undefined;
  private unsub: (() => void) | null = null;

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 3000);
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
    if (t.status === 200 && Array.isArray(t.body)) this.trades.set([...t.body].reverse().slice(0, 30));
  }
}
