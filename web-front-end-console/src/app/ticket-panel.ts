import { Component, computed, effect, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, OrderResult, OtcContract, nextClientOrderId, parseOcc, traceIdFor } from './api';
import { HelpTip } from './help';

type Cls = 'Equity' | 'Option' | 'Treasury' | 'Corporate' | 'Swap' | 'Swaption';

// Measured from SwapConventions.java (YU17 layer).
const CONVENTIONS = [
  'USD-SOFR-1Y-ACT360', 'USD-SOFR-3M-ACT360', 'EUR-ESTR-1Y-ACT360',
  'GBP-SONIA-1Y-ACT365F', 'JPY-TONA-1Y-ACT365F',
];

interface Preset { label: string; apply: (t: TicketPanel) => void; }

const iso = (daysFromNow: number) => {
  const d = new Date(Date.now() + daysFromNow * 86_400_000);
  return d.toISOString().slice(0, 10);
};

// One preset per demo story. Each fills the whole ticket; Submit is the only remaining click.
const PRESETS: Preset[] = [
  { label: 'Equity — buy 100 IBM @ 200', apply: t => {
      t.cls.set('Equity'); t.ticker.set('IBM'); t.side = 'Buy'; t.quantity = 100; t.limitPrice = 200; } },
  { label: 'Equity — off the book band → PRICE_COLLAR reject', apply: t => {
      t.cls.set('Equity'); t.ticker.set('IBM'); t.side = 'Buy'; t.quantity = 100; t.limitPrice = 500; } },
  // Options are a two-beat demo for the same reason the algo one is: nothing rests in an option
  // book by default, so a lone buy just sits there and shows no position. Beat 1 posts the offer,
  // beat 2 lifts it — a real print, a real position on both sides. This pair is also the recovery
  // procedure after a proof-suite run: yu15-option-persistence deletes every trade and position
  // whose security is >15 chars (i.e. every OCC symbol) and restores only the catalog rows, so
  // option positions vanish while the contracts stay tradeable. Two clicks put them back.
  { label: 'Listed option 1/2 — rest an offer (AAPL Sep-26 240 Call)', apply: t => {
      t.cls.set('Option'); t.occSymbol.set('AAPL260918C00240000'); t.accountId = 42422;
      t.side = 'Sell'; t.quantity = 5; t.limitPrice = 3.80; } },
  { label: 'Listed option 2/2 — lift it → print, position, both sides', apply: t => {
      t.cls.set('Option'); t.occSymbol.set('AAPL260918C00240000'); t.accountId = 22214;
      t.side = 'Buy'; t.quantity = 5; t.limitPrice = 3.80; } },
  { label: 'Treasury bill — $100k face, fraction of par', apply: t => {
      t.cls.set('Treasury'); t.ticker.set('UST-BILL-20270812'); t.side = 'Buy'; t.quantity = 100_000; t.limitPrice = 0.959560; } },
  { label: 'Corporate — GS 5.75% 2036, 30/360 day count', apply: t => {
      t.cls.set('Corporate'); t.ticker.set('CORP-GS-20360315'); t.side = 'Buy'; t.quantity = 100_000; t.limitPrice = 0.991230; } },
  { label: 'Swap — USD SOFR, pay fixed 4.2%, 10mm 5Y', apply: t => {
      t.cls.set('Swap'); t.payReceive = 'Pay'; t.notional = 10_000_000; t.fixedRate = 0.042;
      t.effectiveDate = iso(2); t.maturityDate = iso(2 + 365 * 5); t.conventions = 'USD-SOFR-1Y-ACT360'; } },
  { label: 'Swap — GBP with no sequenced rate → PRICE_MISSING', apply: t => {
      t.cls.set('Swap'); t.payReceive = 'Pay'; t.notional = 5_000_000; t.fixedRate = 0.045;
      t.effectiveDate = iso(2); t.maturityDate = iso(2 + 365 * 3); t.conventions = 'GBP-SONIA-1Y-ACT365F'; } },
  { label: 'Swaption — European into 5Y USD payer', apply: t => {
      t.cls.set('Swaption'); t.payReceive = 'Pay'; t.notional = 10_000_000; t.fixedRate = 0.042;
      t.effectiveDate = iso(365); t.maturityDate = iso(365 + 365 * 5); t.conventions = 'USD-SOFR-1Y-ACT360';
      t.expiryDate = iso(363); t.exerciseStyle = 'European'; } },
  // The algo demo is two beats, because the seed book is consumable: first post the liquidity the
  // slices will cross (priced off the LIVE feed, just through the touch), then run the parent.
  // The slices are live limit orders, not fire-and-forget — a parent whose slices price below the
  // book simply rests, which is correct and is its own (labelled) story.
  { label: 'Algo 1/2 — post the bid the slices will cross (buy 100 IBM)', apply: t => {
      t.cls.set('Equity'); t.execMode = 'Direct'; t.accountId = 42422; t.ticker.set('IBM');
      t.side = 'Buy'; t.quantity = 100;
      // +5 through the touch: the simulated feed walks ~1%/min, and the slices price off the
      // LIVE feed up to durationSeconds later — a tight bid loses that race and rests.
      t.limitPrice = Math.round(((t.api.prices()['IBM']?.price ?? 182) + 5) * 100) / 100; } },
  { label: 'Algo 2/2 — TWAP sell 30 IBM over 30s (slices cross that bid)', apply: t => {
      t.cls.set('Equity'); t.execMode = 'TWAP'; t.accountId = 22214; t.ticker.set('IBM');
      t.side = 'Sell'; t.quantity = 30; t.durationSeconds = 30; t.bucketSeconds = 10; } },
];

@Component({
  selector: 'ticket-panel',
  imports: [FormsModule, NgTemplateOutlet, HelpTip],
  template: `
    <div class="card-head">
      <h2>Order entry</h2>
      <help-tip text="One ticket per instrument class, all submitted through the same gateway and sequenced through the same consensus log. The five classes need genuinely different tickets: equities carry a price, options are identified by their OCC symbol alone, bonds are priced as a fraction of par with quantity in USD face value, and swaps carry contractual terms rather than any price. Demo presets fill the whole ticket for each story — Submit is the only remaining click." />
    </div>

    <label class="field">Demo preset
      <select [ngModel]="''" (ngModelChange)="usePreset($event)" name="preset">
        <option value="" disabled>choose a story…</option>
        @for (p of presets; track p.label; let i = $index) { <option [value]="i">{{ p.label }}</option> }
      </select>
    </label>

    <div class="tabs">
      @for (c of classes; track c) {
        <button type="button" [class.on]="cls() === c" (click)="cls.set(c)">{{ c }}</button>
      }
    </div>

    <!-- Live context for whatever the ticket is pointed at: the price the book is actually
         seeing, and (for a bond) the terms that decide how it accrues. Ported from the older
         app's ticket, which showed this and made the newer one look blind by comparison. -->
    @if (livePrice(); as p) {
      <div class="live">
        <span class="lv" [class.up]="p.dir === 1" [class.down]="p.dir === -1">{{ priceLabel() }}</span>
        <span class="sub">live price</span>
        @if (bondInfo(); as b) {
          <span class="sub">· coupon {{ b.fixedInterest?.couponRatePercent ?? b.zeroCoupon?.couponRatePercent ?? 0 }}%
            · {{ b.dayCount }}</span>
        }
        @if (estimatedValue(); as v) { <span class="est">≈ {{ v }}</span> }
      </div>
    } @else if (currentTicker()) {
      <div class="live"><span class="sub">no live price for {{ currentTicker() }} yet</span></div>
    }
    <!-- The refusal this warning prevents is the one that happens on stage: a plausible price on a
         book whose collar band is anchored somewhere else entirely. -->
    @if (bandWarning(); as w) { <div class="derived bad band">{{ w }}</div> }
    <form (ngSubmit)="submit()">
      <label class="field">Account
        <select [(ngModel)]="accountId" name="acct">
          @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
        </select>
      </label>

      @switch (cls()) {
        @case ('Equity') {
          <label class="field">Ticker
            <select [ngModel]="ticker()" (ngModelChange)="ticker.set($event)" name="ticker">
              @for (i of equities(); track i.instrumentKey) { <option [value]="i.instrumentKey">{{ i.instrumentKey }} — {{ i.displayName }}</option> }
            </select>
          </label>
          <label class="field">Side <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select></label>
          <label class="field">Quantity <input type="number" [(ngModel)]="quantity" name="qty" min="1"></label>
          <label class="field">Execution
            <select [(ngModel)]="execMode" name="exec">
              <option>Direct</option><option>TWAP</option><option>VWAP</option>
            </select>
          </label>
          @if (execMode === 'Direct') {
            <label class="field">Limit price <input type="number" [(ngModel)]="limitPrice" name="px" step="0.01"></label>
          } @else {
            <div class="derived">Parent order — the algo engine slices it into child orders on a
              {{ execMode }} schedule, each child through the same consensus path.
              <help-tip text="TWAP slices the quantity evenly across the duration; VWAP weights each bucket by the expected volume profile. The engine prices each child from the live market with a small offset, so no limit price is entered here. Watch the buckets fill on the Admin page." /></div>
            <label class="field">Duration (seconds) <input type="number" [(ngModel)]="durationSeconds" name="dur" min="10"></label>
            <label class="field">Bucket (seconds) <input type="number" [(ngModel)]="bucketSeconds" name="bucket" min="5"></label>
          }
        }
        @case ('Option') {
          <label class="field">
            <span class="lbl">OCC symbol
              <help-tip text="The industry-standard contract identifier, and the only thing a listed option ticket needs: ROOT + expiry YYMMDD + C or P + strike × 1000 padded to 8 digits. AAPL261218C00260000 is an Apple call expiring 2026-12-18 struck at 260. Everything below is derived from it, so there is no way to enter a contract whose parts disagree. One contract is 100 shares — the multiplier the position and risk sides both apply." />
            </span>
            <input [ngModel]="occSymbol()" (ngModelChange)="occSymbol.set($event)" name="occ" placeholder="AAPL261218C00260000" spellcheck="false">
          </label>
          @if (occ(); as o) {
            <div class="derived">{{ o.underlying }} · {{ o.expiry }} · {{ o.callPut }} · strike {{ o.strike }}
              <help-tip text="Underlying, expiry, call/put and strike are all encoded in the OCC symbol itself, so the ticket derives them rather than asking for them separately — there is exactly one source of truth for the contract's terms." /></div>
          } @else if (occSymbol()) { <div class="derived bad">not a valid OCC symbol</div> }
          <label class="field">Side <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select></label>
          <label class="field">Contracts <input type="number" [(ngModel)]="quantity" name="qty" min="1"></label>
          <label class="field">Limit price <input type="number" [(ngModel)]="limitPrice" name="px" step="0.01"></label>
        }
        @case ('Treasury') { <ng-container *ngTemplateOutlet="bond"></ng-container> }
        @case ('Corporate') { <ng-container *ngTemplateOutlet="bond"></ng-container> }
        @case ('Swap') { <ng-container *ngTemplateOutlet="swap"></ng-container> }
        @case ('Swaption') {
          <ng-container *ngTemplateOutlet="swap"></ng-container>
          <label class="field">Option expiry <input type="date" [(ngModel)]="expiryDate" name="expiry"></label>
          <label class="field">
            <span class="lbl">Exercise style
              <help-tip text="When the holder may exercise the option and enter the underlying swap. European: on the expiry date only. Bermudan: on a set of scheduled dates. American: any time up to expiry. It is a contractual term carried on the booking — this system sequences and records it, and deliberately does not price the optionality it creates." />
            </span>
            <select [(ngModel)]="exerciseStyle" name="style"><option>European</option><option>Bermudan</option><option>American</option></select>
          </label>
        }
      }
      <button class="btn-primary" type="submit" [disabled]="busy()">{{ busy() ? '…' : 'Submit' }}</button>
      @if (last(); as r) {
        <div class="banner" [class.good]="r.ok" [class.bad]="!r.ok">{{ r.text }}</div>
      }
    </form>

    <ng-template #bond>
      <label class="field">Instrument
        <select [ngModel]="ticker()" (ngModelChange)="ticker.set($event)" name="ticker">
          @for (i of bonds(); track i.instrumentKey) { <option [value]="i.instrumentKey">{{ i.shortDisplayName || i.instrumentKey }} — {{ i.displayName }}</option> }
        </select>
      </label>
      @if (bondInfo(); as b) {
        <div class="derived">{{ b.issuer }}@if (b.creditRating) { · {{ b.creditRating }} } · day count {{ b.dayCount }}
          <help-tip text="Issuer, rating and day-count convention come from the reference-data record for this instrument, joined by the system — never parsed out of the ticker. Treasuries accrue on ACT/ACT ICMA, corporates on 30/360; the split is data, not naming convention." /></div>
      }
      <label class="field">Side <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select></label>
      <label class="field">USD face
        <input type="number" [(ngModel)]="quantity" name="qty" min="100" step="100">
      </label>
      <label class="field">Price (fraction of par)
        <input type="number" [(ngModel)]="limitPrice" name="px" step="0.000001" placeholder="0.998780">
      </label>
    </ng-template>

    <ng-template #swap>
      <div class="derived">Terms, not a price
        <help-tip text="A swap is booked as its contractual terms — notional, fixed rate, direction, dates, convention. There is deliberately no NPV or valuation anywhere in this system: it books and sequences contracts, it does not price them." /></div>
      <label class="field">Pay / receive fixed
        <select [(ngModel)]="payReceive" name="payrec"><option>Pay</option><option>Receive</option></select>
      </label>
      <label class="field">
        <span class="lbl">Notional
          <help-tip text="The reference amount the interest payments are calculated on — never exchanged, which is why a swap can carry a very large notional and a small economic risk. It is also what the credit check consumes: a non-USD notional is converted at the sequenced FX rate before it is measured against the account's limit, and a currency with no rate yet is refused PRICE_MISSING rather than guessed." />
        </span>
        <input type="number" [(ngModel)]="notional" name="notional" min="1">
      </label>
      <label class="field">
        <span class="lbl">Fixed rate (fraction: 0.042 = 4.2%)
          <help-tip text="The fixed leg's rate, entered as a fraction: 0.042 is 4.2%. One side pays this, the other pays the floating index named in the convention. It is stored in ticks as an integer, so every member records the identical rate — a float would diverge across members and break the byte-identical end-of-day cut." />
        </span>
        <input type="number" [(ngModel)]="fixedRate" name="rate" step="0.0001">
      </label>
      <label class="field">Effective <input type="date" [(ngModel)]="effectiveDate" name="eff"></label>
      <label class="field">Maturity <input type="date" [(ngModel)]="maturityDate" name="mat"></label>
      <label class="field">
        <span class="lbl">Convention
          <help-tip text="One code fixing the currency, the floating index, the payment frequency and the day-count basis: USD-SOFR-1Y-ACT360 is a US dollar swap against SOFR, paying annually, accruing actual days over a 360-day year. Picking a convention picks the currency, which is why a non-USD one drags in the FX rate the credit check needs." />
        </span>
        <select [(ngModel)]="conventions" name="conv">
          @for (c of conventionList; track c) { <option>{{ c }}</option> }
        </select>
      </label>
    </ng-template>
  `,
  styles: `
    .lbl { display: flex; align-items: center; gap: 5px; }
    .tabs { display: flex; gap: 4px; flex-wrap: wrap; margin: 10px 0; }
    .tabs button { background: #f0f2f5; color: var(--muted); border: none; padding: 4px 11px; border-radius: 6px; font-size: 12.5px; font-weight: 500; }
    .tabs button.on { background: var(--accent-soft); color: var(--accent); }
    form { display: flex; flex-direction: column; gap: 9px; }
    .derived { font-size: 12.5px; color: var(--accent); padding: 6px 9px; background: var(--accent-soft); border-radius: 6px;
               display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .derived.bad { color: var(--bad); background: var(--bad-soft); }
    .band { margin-bottom: 8px; line-height: 1.45; }
    .banner { font-family: var(--mono); font-size: 12.5px; }
    .live { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-bottom: 8px;
            padding: 6px 9px; background: #f8f9fb; border: 1px solid var(--border); border-radius: 6px; }
    .lv { font-family: var(--mono); font-weight: 600; font-size: 13.5px; padding: 0 3px; border-radius: 3px; }
    .lv.up { color: var(--good); background: var(--good-soft); }
    .lv.down { color: var(--bad); background: var(--bad-soft); }
    .est { margin-left: auto; font-family: var(--mono); font-size: 12.5px; color: var(--muted); }
  `,
})
export class TicketPanel {
  readonly api = inject(Api);
  readonly classes: Cls[] = ['Equity', 'Option', 'Treasury', 'Corporate', 'Swap', 'Swaption'];
  readonly conventionList = CONVENTIONS;
  readonly presets = PRESETS;

  readonly cls = signal<Cls>('Equity');
  readonly busy = signal(false);
  readonly last = signal<{ ok: boolean; text: string } | null>(null);

  accountId = 22214;
  // Signals, not plain fields: the live-price strip and bondInfo are computed() and only
  // recompute when a SIGNAL they read changes. As plain properties these silently served a stale
  // instrument — the bond terms simply stopped appearing when the ticket switched instruments.
  readonly ticker = signal('');
  side = 'Buy';
  execMode: 'Direct' | 'TWAP' | 'VWAP' = 'Direct';
  durationSeconds = 60;
  bucketSeconds = 10;
  quantity = 100;
  limitPrice = 0;
  readonly occSymbol = signal('');
  payReceive = 'Pay';
  notional = 10_000_000;
  fixedRate = 0.042;
  effectiveDate = '';
  maturityDate = '';
  expiryDate = '';
  exerciseStyle = 'European';
  conventions = CONVENTIONS[0];

  readonly equities = computed(() =>
    this.api.instruments().filter(i => i.securityType === 'Equity' || i.securityType === 'Fund'));
  readonly bonds = computed(() => {
    const want = this.cls() === 'Treasury' ? 'US_TREASURY' : 'CORPORATE_BOND';
    return this.api.instruments().filter(i => i.assetClass === want);
  });
  readonly occ = computed(() => parseOcc(this.occSymbol()));

  /** Whatever instrument the ticket currently names, whichever tab is showing. */
  readonly currentTicker = computed(() => {
    const c = this.cls();
    if (c === 'Swap' || c === 'Swaption') return '';
    return c === 'Option' ? this.occSymbol().trim().toUpperCase() : this.ticker();
  });

  readonly livePrice = computed(() => {
    const t = this.currentTicker();
    return t ? this.api.prices()[t] ?? null : null;
  });

  /** Bonds quote as a fraction of par; options and equities in dollars. */
  readonly priceLabel = computed(() => {
    const p = this.livePrice();
    if (!p) return '';
    const c = this.cls();
    if (c === 'Treasury' || c === 'Corporate') return `${(p.price * 100).toFixed(3)}% of par`;
    return p.price.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 3 });
  });

  /** What the order is worth at the entered limit, using the system's own multiplier convention:
   *  100 for an option contract, 1 for a bond (quantity is already USD face) or a share. */
  readonly estimatedValue = computed(() => {
    const c = this.cls();
    if (c === 'Swap' || c === 'Swaption' || !this.quantity || !this.limitPrice) return '';
    const mult = c === 'Option' ? 100 : 1;
    const value = this.quantity * this.limitPrice * mult;
    return value.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 });
  });
  readonly bondInfo = computed(() =>
    this.api.instruments().find(i => i.instrumentKey === this.currentTicker())?.debtEconomics);

  /**
   * Warn before the refusal rather than explaining it afterwards. Only for books measured
   * mis-anchored (accepted and refused prices disjoint) — an overlap means refusals came from
   * something else and would make this a false alarm.
   */
  readonly bandWarning = computed(() => {
    const b = this.api.band(this.currentTicker());
    if (!b || b.verdict !== 'anchored-elsewhere') return '';
    const live = this.livePrice()?.price;
    const near = (v: number | undefined) =>
      v !== undefined && v >= b.acceptedLo * 0.98 && v <= b.acceptedHi * 1.02;
    if (near(live)) return '';
    return `${b.security}: this book's collar band is anchored at `
      + `${b.acceptedLo === b.acceptedHi ? b.acceptedLo : `${b.acceptedLo}–${b.acceptedHi}`}`
      + ` — every order at ${b.rejectedLo}+ has been refused (${b.rejected} of them). An order at the`
      + ` live price will be refused PRICE_COLLAR, and no re-seed repairs it. See Book bands on the Admin page.`;
  });

  constructor() {
    // Cheap (~0.7s for the whole journal) and cached a minute; the ticket is where the warning
    // has to appear, so the ticket is what loads it.
    this.api.loadBands();
    // Keep the ticker pointing at something valid for the selected class.
    effect(() => {
      const list = ['Treasury', 'Corporate'].includes(this.cls()) ? this.bonds() : this.equities();
      if (list.length && !list.some(i => i.instrumentKey === this.ticker())) {
        this.ticker.set(list[0].instrumentKey);
      }
    });
  }

  usePreset(index: string | number): void {
    const p = PRESETS[Number(index)];
    if (p) { p.apply(this); this.last.set(null); }
  }

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
        if (r.status === 200 && r.body?.booked && r.body.contractId) {
          // Nothing else on this tier will show this contract until the next EOD cut, so the
          // console keeps it (see OtcContract).
          this.api.recordContract({
            contractId: r.body.contractId, sequence: r.body.sequence ?? 0,
            accountId: Number(this.accountId), product: c,
            payReceive: this.payReceive, notional: this.notional, fixedRate: this.fixedRate,
            effectiveDate: this.effectiveDate, maturityDate: this.maturityDate,
            conventions: this.conventions,
            expiryDate: c === 'Swaption' ? this.expiryDate : undefined,
            exerciseStyle: c === 'Swaption' ? this.exerciseStyle : undefined,
            bookedAt: new Date().toISOString(),
          } as OtcContract);
        }
        this.report(c === 'Swap' ? 'swap' : 'swaption',
          r.status === 200 && !!r.body?.booked,
          r.status === 404
            ? 'route absent — gateway on this rig predates YU17 swaps'
            : r.body?.contractId
              ? `${r.body.contractId} booked at consensus seq ${r.body.sequence}`
              : r.body?.reason
                ? `REFUSED: ${r.body.reason}`
                : r.body?.error ?? `HTTP ${r.status}`,
          `${this.payReceive} fixed ${this.fixedRate} on ${this.notional} ${this.conventions}`,
          r.body?.reason);
      } else if (c === 'Equity' && this.execMode !== 'Direct') {
        const r = await this.api.post<{ parentOrderId?: string; status?: string; error?: string }>('/algo/orders', {
          accountId: Number(this.accountId), security: this.ticker(), side: this.side,
          quantity: this.quantity, algoType: this.execMode,
          durationSeconds: this.durationSeconds, bucketSeconds: this.bucketSeconds,
        });
        const ok = r.status === 201 && !!r.body?.parentOrderId;
        this.last.set({ ok, text: ok ? `parent ${r.body!.parentOrderId} ${r.body!.status} — buckets on the Admin page` : `HTTP ${r.status}` });
        this.api.log({ kind: 'algo', ok,
          summary: `${this.execMode} ${this.side} ${this.quantity} ${this.ticker()} over ${this.durationSeconds}s → ${ok ? `parent ${r.body!.parentOrderId}` : `HTTP ${r.status}`}` });
      } else {
        const ticker = this.currentTicker();
        const clientOrderId = nextClientOrderId();
        const r = await this.api.post<OrderResult>('/order-matcher/orders', {
          accountId: Number(this.accountId), ticker, side: this.side,
          quantity: this.quantity, limitPrice: this.limitPrice, clientOrderId,
        });
        const ok = r.status === 200;
        this.last.set({ ok, text: ok ? `orderRef ${r.body?.orderRef} accepted`
          : r.body?.reason ? `REJECTED: ${r.body.reason}` : r.body?.error ?? `HTTP ${r.status}` });
        this.api.log({
          kind: 'order', ok, reason: r.body?.reason,
          summary: `${this.side} ${this.quantity} ${ticker} @ ${this.limitPrice} → ${this.last()!.text}`,
          orderRef: r.body?.orderRef, clientOrderId,
          traceId: traceIdFor(clientOrderId, r.body?.orderRef),
        });
      }
    } finally { this.busy.set(false); }
  }

  private report(kind: 'order' | 'swap' | 'swaption', ok: boolean, text: string, detail: string, reason?: string): void {
    this.last.set({ ok, text });
    this.api.log({ kind, ok, summary: `${detail} → ${text}`, reason, detail });
  }
}
