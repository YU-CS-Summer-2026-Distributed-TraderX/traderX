import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, OrderResult, nextClientOrderId, traceIdFor } from './api';
import { HelpTip } from './help';

/**
 * A scripted market: several accounts submitting real orders at their own rate for their own
 * duration, so every downstream surface has something to show at once — positions and P&L moving,
 * prints landing on the message bus, consensus latency under actual load, the kdb tap filling.
 *
 * Deliberately client-side and ordinary: each order goes through the same gateway route the ticket
 * uses, so nothing here is a special path the rest of the system does not already serve. Buyers
 * price just through the touch and sellers just under it, which is what makes the accounts cross
 * each other and print rather than pile up as resting orders.
 */
interface Actor {
  accountId: number;
  side: 'Buy' | 'Sell' | 'Alternate';
  perMin: number;
  quantity: number;
  durationSec: number;
  sent: number; accepted: number; rejected: number; noPrice: number;
  running: boolean;
  lastReason: string;
}

const MAX_PER_MIN = 120;
const MAX_DURATION = 900;

const actor = (accountId: number, side: Actor['side'], perMin: number, quantity: number, durationSec: number): Actor =>
  ({ accountId, side, perMin, quantity, durationSec, sent: 0, accepted: 0, rejected: 0, noPrice: 0, running: false, lastReason: '' });

@Component({
  selector: 'demo-session',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>Live trading session</h2>
      <help-tip text="Runs a scripted market: each account submits real orders at its own rate for its own duration, through the same gateway route as a hand-typed ticket. Buy actors price just above the live market and sell actors just below, so they cross each other and produce prints — positions, P&L, latency, the kdb tap and the message-bus feed all move together while it runs. Stop halts every actor immediately; leaving the page does too." />
      <span class="spacer"></span>
      @if (running()) { <span class="pill good">running · {{ elapsed() }}s</span> }
    </div>

    <div class="bar">
      <label class="field">Security
        <select [(ngModel)]="security" [disabled]="running()">
          @for (i of equities(); track i.instrumentKey) { <option [value]="i.instrumentKey">{{ i.instrumentKey }}</option> }
        </select>
      </label>
      <div class="live">
        @if (last(); as p) { <span class="lv">{{ p.toFixed(3) }}</span> <span class="sub">live price</span> }
        @else { <span class="sub">waiting for a price tick on {{ security }}</span> }
      </div>
      <span class="spacer"></span>
      @if (!running()) {
        <button class="btn-primary" (click)="start()" [disabled]="!actors().length">Start session</button>
      } @else {
        <button class="stop" (click)="stop('stopped by operator')">Stop</button>
      }
    </div>

    <table>
      <thead><tr><th>account</th><th>side</th><th class="num">orders/min</th><th class="num">qty</th>
        <th class="num">for (s)</th><th class="num">sent</th><th class="num">accepted</th>
        <th class="num">rejected</th><th>last reject</th><th></th></tr></thead>
      <tbody>
        @for (a of actors(); track $index; let i = $index) {
          <tr [class.on]="a.running">
            <td>
              <select [ngModel]="a.accountId" (ngModelChange)="patch(i, { accountId: +$event })" [disabled]="running()">
                @for (acct of api.accounts(); track acct.id) { <option [value]="acct.id">{{ acct.displayName }} ({{ acct.id }})</option> }
              </select>
            </td>
            <td>
              <select [ngModel]="a.side" (ngModelChange)="patch(i, { side: $event })" [disabled]="running()">
                <option>Buy</option><option>Sell</option><option>Alternate</option>
              </select>
            </td>
            <td class="num"><input type="number" min="1" [max]="maxPerMin" [ngModel]="a.perMin"
                (ngModelChange)="patch(i, { perMin: +$event })" [disabled]="running()"></td>
            <td class="num"><input type="number" min="1" [ngModel]="a.quantity"
                (ngModelChange)="patch(i, { quantity: +$event })" [disabled]="running()"></td>
            <td class="num"><input type="number" min="5" [max]="maxDuration" [ngModel]="a.durationSec"
                (ngModelChange)="patch(i, { durationSec: +$event })" [disabled]="running()"></td>
            <td class="num">{{ a.sent }}</td>
            <td class="num pos">{{ a.accepted }}</td>
            <td class="num" [class.neg]="a.rejected > 0">{{ a.rejected }}</td>
            <td class="sub">{{ a.lastReason || (a.noPrice ? a.noPrice + ' skipped, no price' : '—') }}</td>
            <td>@if (!running()) { <button class="cancel" (click)="remove(i)">✕</button> }</td>
          </tr>
        }
      </tbody>
    </table>
    @if (!running()) { <button class="add" (click)="add()">+ add an account</button> }

    <div class="sub note">Rejections are logged individually to Activity &amp; rejections with their
      reason code — accepted orders are only counted, because a session at these rates would
      otherwise push everything else out of that list. Rates are capped at {{ maxPerMin }}/min and
      {{ maxDuration }}s per actor.</div>
  `,
  styles: `
    .bar { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; margin-bottom: 10px; }
    .spacer { flex: 1; }
    .live { display: flex; align-items: baseline; gap: 6px; padding-bottom: 3px; }
    .lv { font-family: var(--mono); font-weight: 600; font-size: 13.5px; }
    td input { width: 62px; text-align: right; }
    tr.on td { background: var(--good-soft); }
    .cancel, .add { font-size: 11.5px; padding: 1px 9px; }
    .add { margin-top: 8px; }
    .stop { background: var(--bad); color: #fff; border-color: var(--bad); font-weight: 600; }
    .note { margin-top: 10px; max-width: 720px; }
    .pos { color: var(--good); } .neg { color: var(--bad); }
  `,
})
export class DemoSession implements OnInit, OnDestroy {
  readonly api = inject(Api);
  readonly maxPerMin = MAX_PER_MIN;
  readonly maxDuration = MAX_DURATION;
  security = 'IBM';
  readonly running = signal(false);
  readonly elapsed = signal(0);
  readonly actors = signal<Actor[]>([
    actor(22214, 'Buy', 20, 25, 120),
    actor(42422, 'Sell', 20, 25, 120),
  ]);

  /** Every actor's tick interval and its stop timeout, cleared together on stop. */
  private timers: ReturnType<typeof setTimeout>[] = [];
  private clock: ReturnType<typeof setInterval> | undefined;
  private startedAt = 0;

  readonly equities = computed(() =>
    this.api.instruments().filter(i => i.securityType === 'Equity' || i.securityType === 'Fund'));
  readonly last = computed(() => this.api.prices()[this.security]?.price);

  ngOnInit(): void { this.api.watchPrices(); }
  // Timers outlive the component otherwise — a session left running while you navigate away keeps
  // firing orders at a rig with nothing on screen to say so.
  ngOnDestroy(): void { this.stop('page left'); }

  patch(i: number, fields: Partial<Actor>): void {
    this.actors.update(list => list.map((a, j) => (j === i ? { ...a, ...fields } : a)));
  }
  add(): void { this.actors.update(l => [...l, actor(22214, 'Alternate', 12, 10, 120)]); }
  remove(i: number): void { this.actors.update(l => l.filter((_, j) => j !== i)); }

  start(): void {
    if (this.running()) return;
    this.actors.update(l => l.map(a => ({
      ...a,
      perMin: Math.min(MAX_PER_MIN, Math.max(1, a.perMin)),
      durationSec: Math.min(MAX_DURATION, Math.max(5, a.durationSec)),
      sent: 0, accepted: 0, rejected: 0, noPrice: 0, lastReason: '', running: true,
    })));
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
      summary: `live session started · ${this.actors().length} accounts on ${this.security}` });
  }

  stop(why: string): void {
    if (!this.running() && !this.timers.length) return;
    this.timers.forEach(t => { clearInterval(t); clearTimeout(t); });
    this.timers = [];
    clearInterval(this.clock);
    this.running.set(false);
    this.actors.update(l => l.map(a => ({ ...a, running: false })));
    const sent = this.actors().reduce((s, a) => s + a.sent, 0);
    this.api.log({ kind: 'algo', ok: true, summary: `live session ${why} · ${sent} orders sent` });
  }

  private retire(i: number): void {
    this.patch(i, { running: false });
    if (!this.actors().some(a => a.running)) this.stop('finished');
  }

  private async fire(i: number): Promise<void> {
    const a = this.actors()[i];
    if (!a?.running) return;
    const tick = this.api.prices()[this.security];
    if (!tick) { this.patch(i, { noPrice: a.noPrice + 1 }); return; }
    const side = a.side === 'Alternate' ? (a.sent % 2 === 0 ? 'Buy' : 'Sell') : a.side;
    // Buyers reach up through the touch and sellers down through it, so the actors cross each
    // other. A tick-tight price on both sides would just rest and the session would look dead.
    const off = Math.max(0.01, tick.price * 0.0005);
    const limitPrice = Math.round((side === 'Buy' ? tick.price + off : tick.price - off) * 100) / 100;
    const clientOrderId = nextClientOrderId();
    this.patch(i, { sent: a.sent + 1 });
    const r = await this.api.post<OrderResult>('/order-matcher/orders', {
      accountId: Number(a.accountId), ticker: this.security, side,
      quantity: a.quantity, limitPrice, clientOrderId,
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
        summary: `session ${side} ${a.quantity} ${this.security} @ ${limitPrice} (acct ${a.accountId}) → REJECTED: ${reason}`,
        clientOrderId, traceId: traceIdFor(clientOrderId, r.body?.orderRef),
      });
    }
  }
}
