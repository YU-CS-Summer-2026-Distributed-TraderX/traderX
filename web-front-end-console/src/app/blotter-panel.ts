import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, BlotterTrade, Position } from './api';

@Component({
  selector: 'blotter-panel',
  imports: [FormsModule],
  template: `
    <h2>Blotter &amp; positions <span class="sub">read model (position-service)</span></h2>
    <label class="acct">Account
      <select [(ngModel)]="accountId" (ngModelChange)="poll()">
        @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
      </select>
    </label>
    <h3>Positions</h3>
    <table>
      <thead><tr><th>security</th><th>qty</th><th>avg cost</th><th>updated</th></tr></thead>
      <tbody>
        @for (p of positions(); track p.security) {
          <tr><td>{{ p.security }}</td><td class="num">{{ p.quantity }}</td>
              <td class="num">{{ p.averageCostBasis.toFixed(6) }}</td><td>{{ p.updated.slice(11, 19) }}</td></tr>
        } @empty { <tr><td colspan="4" class="none">no positions</td></tr> }
      </tbody>
    </table>
    <h3>Trades</h3>
    <table>
      <thead><tr><th>id</th><th>security</th><th>side</th><th>qty</th><th>price</th><th>state</th></tr></thead>
      <tbody>
        @for (t of trades(); track t.id) {
          <tr [class.rej]="t.rejectionReason">
            <td>{{ t.id }}</td><td>{{ t.security }}</td><td>{{ t.side }}</td>
            <td class="num">{{ t.quantity }}</td><td class="num">{{ t.price.toFixed(6) }}</td>
            <td>{{ t.rejectionReason || t.state }}</td>
          </tr>
        } @empty { <tr><td colspan="6" class="none">no trades</td></tr> }
      </tbody>
    </table>
  `,
  styles: `
    .acct { display: flex; flex-direction: column; font-size: 12px; color: #999; gap: 2px; margin-bottom: 4px; }
    select { background: #1a1a1a; color: #eee; border: 1px solid #444; padding: 4px 6px; border-radius: 3px; }
    h3 { margin: 8px 0 2px; font-size: 12px; color: #8fb8e8; }
    td.num { text-align: right; font-variant-numeric: tabular-nums; }
    td.none { color: #666; }
    tr.rej td { color: #ff9d9d; }
  `,
})
export class BlotterPanel implements OnInit, OnDestroy {
  readonly api = inject(Api);
  accountId = 22214;
  readonly positions = signal<Position[]>([]);
  readonly trades = signal<BlotterTrade[]>([]);
  private timer: ReturnType<typeof setInterval> | undefined;

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 3000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  async poll(): Promise<void> {
    const id = Number(this.accountId);
    const [p, t] = await Promise.all([
      this.api.load<Position[]>(`/position-service/positions/${id}`),
      this.api.load<BlotterTrade[]>(`/position-service/trades/${id}`),
    ]);
    if (p.status === 200 && p.body) this.positions.set(p.body);
    if (t.status === 200 && t.body) this.trades.set([...t.body].reverse().slice(0, 30));
  }
}
