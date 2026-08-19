import { Component, computed, effect, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, OrderResult, parseOcc } from './api';

type Cls = 'Equity' | 'Option' | 'Treasury' | 'Corporate' | 'Swap' | 'Swaption';

// Measured from SwapConventions.java (YU17 layer).
const CONVENTIONS = [
  'USD-SOFR-1Y-ACT360', 'USD-SOFR-3M-ACT360', 'EUR-ESTR-1Y-ACT360',
  'GBP-SONIA-1Y-ACT365F', 'JPY-TONA-1Y-ACT365F',
];

@Component({
  selector: 'ticket-panel',
  imports: [FormsModule, NgTemplateOutlet],
  template: `
    <h2>Order entry <span class="sub">five instrument classes, one consensus path</span></h2>
    <div class="tabs">
      @for (c of classes; track c) {
        <button [class.on]="cls() === c" (click)="cls.set(c)">{{ c }}</button>
      }
    </div>
    <form (ngSubmit)="submit()">
      <label>Account
        <select [(ngModel)]="accountId" name="acct">
          @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
        </select>
      </label>

      @switch (cls()) {
        @case ('Equity') {
          <label>Ticker
            <select [(ngModel)]="ticker" name="ticker">
              @for (i of equities(); track i.instrumentKey) { <option [value]="i.instrumentKey">{{ i.instrumentKey }} — {{ i.displayName }}</option> }
            </select>
          </label>
          <label>Side <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select></label>
          <label>Quantity <input type="number" [(ngModel)]="quantity" name="qty" min="1"></label>
          <label>Limit price <input type="number" [(ngModel)]="limitPrice" name="px" step="0.01"></label>
        }
        @case ('Option') {
          <label>OCC symbol <input [(ngModel)]="occSymbol" name="occ" placeholder="AAPL261218C00260000" spellcheck="false"></label>
          @if (occ(); as o) {
            <div class="derived">{{ o.underlying }} · {{ o.expiry }} · {{ o.callPut }} · strike {{ o.strike }}
              <span class="sub">derived from the symbol — not entered</span></div>
          } @else if (occSymbol) { <div class="derived bad">not a valid OCC symbol</div> }
          <label>Side <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select></label>
          <label>Contracts <input type="number" [(ngModel)]="quantity" name="qty" min="1"></label>
          <label>Limit price <input type="number" [(ngModel)]="limitPrice" name="px" step="0.01"></label>
        }
        @case ('Treasury') { <ng-container *ngTemplateOutlet="bond"></ng-container> }
        @case ('Corporate') { <ng-container *ngTemplateOutlet="bond"></ng-container> }
        @case ('Swap') { <ng-container *ngTemplateOutlet="swap"></ng-container> }
        @case ('Swaption') {
          <ng-container *ngTemplateOutlet="swap"></ng-container>
          <label>Option expiry <input type="date" [(ngModel)]="expiryDate" name="expiry"></label>
          <label>Exercise style
            <select [(ngModel)]="exerciseStyle" name="style"><option>European</option><option>Bermudan</option><option>American</option></select>
          </label>
        }
      }
      <button class="go" type="submit" [disabled]="busy()">{{ busy() ? '…' : 'Submit' }}</button>
      @if (last(); as r) {
        <div class="result" [class.bad]="!r.ok">{{ r.text }}</div>
      }
    </form>

    <ng-template #bond>
      <label>Instrument
        <select [(ngModel)]="ticker" name="ticker">
          @for (i of bonds(); track i.instrumentKey) { <option [value]="i.instrumentKey">{{ i.shortDisplayName || i.instrumentKey }} — {{ i.displayName }}</option> }
        </select>
      </label>
      @if (bondInfo(); as b) {
        <div class="derived">{{ b.issuer }}@if (b.creditRating) { · {{ b.creditRating }} } · day count {{ b.dayCount }}
          <span class="sub">from the reference-data join, never the ticker</span></div>
      }
      <label>Side <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select></label>
      <label>USD face <input type="number" [(ngModel)]="quantity" name="qty" min="100" step="100"></label>
      <label>Price (fraction of par) <input type="number" [(ngModel)]="limitPrice" name="px" step="0.000001" placeholder="0.998780"></label>
    </ng-template>

    <ng-template #swap>
      <div class="derived">Terms, not a price — there is no NPV in this system, by design.</div>
      <label>Pay / receive fixed
        <select [(ngModel)]="payReceive" name="payrec"><option>Pay</option><option>Receive</option></select>
      </label>
      <label>Notional <input type="number" [(ngModel)]="notional" name="notional" min="1"></label>
      <label>Fixed rate (fraction: 0.042 = 4.2%) <input type="number" [(ngModel)]="fixedRate" name="rate" step="0.0001"></label>
      <label>Effective <input type="date" [(ngModel)]="effectiveDate" name="eff"></label>
      <label>Maturity <input type="date" [(ngModel)]="maturityDate" name="mat"></label>
      <label>Convention
        <select [(ngModel)]="conventions" name="conv">
          @for (c of conventionList; track c) { <option>{{ c }}</option> }
        </select>
      </label>
    </ng-template>
  `,
  styles: `
    .tabs { display: flex; gap: 4px; flex-wrap: wrap; margin: 6px 0; }
    .tabs button { background: #222; color: #bbb; border: 1px solid #444; padding: 3px 10px; border-radius: 3px; cursor: pointer; }
    .tabs button.on { background: #2d4a6b; color: #fff; border-color: #5b8cc4; }
    form { display: flex; flex-direction: column; gap: 6px; }
    label { display: flex; flex-direction: column; font-size: 12px; color: #999; gap: 2px; }
    input, select { background: #1a1a1a; color: #eee; border: 1px solid #444; padding: 4px 6px; border-radius: 3px; }
    .derived { font-size: 12px; color: #8fb8e8; padding: 4px 6px; background: #16202e; border-radius: 3px; }
    .derived.bad { color: #ff9d9d; background: #2e1616; }
    .go { margin-top: 4px; background: #2d6b3f; color: #fff; border: none; padding: 6px; border-radius: 3px; cursor: pointer; }
    .go:disabled { opacity: .5; }
    .result { padding: 5px 8px; border-radius: 3px; background: #143d14; color: #7be07b; font-size: 12px; }
    .result.bad { background: #4d1414; color: #ff9d9d; }
  `,
})
export class TicketPanel {
  readonly api = inject(Api);
  readonly classes: Cls[] = ['Equity', 'Option', 'Treasury', 'Corporate', 'Swap', 'Swaption'];
  readonly conventionList = CONVENTIONS;

  readonly cls = signal<Cls>('Equity');
  readonly busy = signal(false);
  readonly last = signal<{ ok: boolean; text: string } | null>(null);

  accountId = 22214;
  ticker = '';
  side = 'Buy';
  quantity = 100;
  limitPrice = 0;
  occSymbol = '';
  payReceive = 'Pay';
  notional = 10_000_000;
  fixedRate = 0.042;
  effectiveDate = '';
  maturityDate = '';
  expiryDate = '';
  exerciseStyle = 'European';

  readonly equities = computed(() =>
    this.api.instruments().filter(i => i.securityType === 'Equity' || i.securityType === 'Fund'));
  readonly bonds = computed(() => {
    const want = this.cls() === 'Treasury' ? 'US_TREASURY' : 'CORPORATE_BOND';
    return this.api.instruments().filter(i => i.assetClass === want);
  });
  readonly occ = computed(() => parseOcc(this.occSymbol));

  constructor() {
    // Keep the ticker pointing at something valid for the selected class.
    effect(() => {
      const list = ['Treasury', 'Corporate'].includes(this.cls()) ? this.bonds() : this.equities();
      if (list.length && !list.some(i => i.instrumentKey === this.ticker)) {
        this.ticker = list[0].instrumentKey;
      }
    });
  }
  readonly bondInfo = computed(() =>
    this.api.instruments().find(i => i.instrumentKey === this.ticker)?.debtEconomics);

  async submit(): Promise<void> {
    this.busy.set(true);
    try {
      const c = this.cls();
      if (c === 'Swap' || c === 'Swaption') {
        const body: Record<string, unknown> = {
          accountId: Number(this.accountId), payReceive: this.payReceive,
          notional: this.notional, fixedRate: this.fixedRate,
          effectiveDate: this.effectiveDate, maturityDate: this.maturityDate,
          conventions: this.conventions,
        };
        if (c === 'Swaption') { body['expiryDate'] = this.expiryDate; body['exerciseStyle'] = this.exerciseStyle; }
        const r = await this.api.post<OrderResult>(c === 'Swap' ? '/order-matcher/swaps' : '/order-matcher/swaptions', body);
        this.report(c === 'Swap' ? 'swap' : 'swaption',
          r.status === 200 && !!r.body?.booked,
          r.status === 404
            ? 'route absent — gateway on this rig predates YU17 swaps'
            : r.body?.contractId
              ? `${r.body.contractId} booked at consensus seq ${r.body.sequence}`
              : r.body?.error ?? `HTTP ${r.status}`,
          `${this.payReceive} fixed ${this.fixedRate} on ${this.notional} ${this.conventions}`);
      } else {
        const ticker = c === 'Option' ? this.occSymbol.trim().toUpperCase() : this.ticker;
        const r = await this.api.post<OrderResult>('/order-matcher/orders', {
          accountId: Number(this.accountId), ticker, side: this.side,
          quantity: this.quantity, limitPrice: this.limitPrice,
        });
        const ok = r.status === 200;
        this.report('order', ok,
          ok ? `orderRef ${r.body?.orderRef} accepted`
             : r.body?.reason ? `REJECTED: ${r.body.reason}` : r.body?.error ?? `HTTP ${r.status}`,
          `${this.side} ${this.quantity} ${ticker} @ ${this.limitPrice}`,
          r.body?.reason);
      }
    } finally { this.busy.set(false); }
  }

  conventions = CONVENTIONS[0];

  private report(kind: 'order' | 'swap' | 'swaption', ok: boolean, text: string, detail: string, reason?: string): void {
    this.last.set({ ok, text });
    this.api.log({ kind, ok, summary: `${detail} → ${text}`, reason, detail });
  }
}
