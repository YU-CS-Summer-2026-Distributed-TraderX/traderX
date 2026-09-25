import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PriceChip } from '../../../web-front-end-console/src/app/price-chip';
import { HelpTip } from '../../../web-front-end-console/src/app/help';
import { ORDER_TYPES, OrderType, TIFS_FOR, Tif, TypedTicket, defaultTif, validateTicket, trailHint } from '../../../web-front-end-console/src/app/order-types';
import { RiskPage as ConnectedRiskPage } from '../../../web-front-end-console/src/app/risk-page';
import riskFixture from '../../../web-front-end-console/test-fixtures/risk-demo-synthetic.json';
import { AssetClass, INSTRUMENTS, FixtureInstrument } from './fixtures';
import { Desk, Freshness, fmtPrice, fmtQty, fmtUsd, reasonText } from './desk';
import { Session } from './session';

const inst = (k: string) => INSTRUMENTS.find(i => i.key === k)!;

/** How old a price is, in words. Live, stale with its age, or none at all: three different things. */
@Component({
  selector: 'fresh',
  template: `
    @switch (f().kind) {
      @case ('live') { <span class="fresh live" title="Received {{ age() }} ago">{{ desk.connected ? 'received' : 'simulated' }} · {{ age() }}</span> }
      @case ('stale') { <span class="fresh stale" title="No update for {{ age() }}">stale · {{ age() }} old</span> }
      @default { <span class="fresh none">no price</span> }
    }
  `,
})
export class Fresh {
  readonly desk = inject(Desk);
  readonly f = input.required<Freshness>();
  readonly age = computed(() => {
    const f = this.f();
    if (f.kind === 'none') return '';
    return f.ageS < 60 ? `${f.ageS}s` : `${Math.floor(f.ageS / 60)}m ${String(f.ageS % 60).padStart(2, '0')}s`;
  });
}

/** Loading, unavailable and refused are drawn here once, so every account-scoped card says them the same way. */
@Component({
  selector: 'read-gate',
  template: `
    @switch (desk.readState()) {
      @case ('loading') { <p class="state" role="status" data-state="loading">Loading {{ what() }}…</p> }
      @case ('unavailable') {
        <p class="banner bad" role="alert" data-state="unavailable">Could not load {{ what() }}: {{ desk.error() || 'the service did not answer' }}
          Nothing older is shown in their place. <button type="button" (click)="desk.reload()">Try again</button></p>
      }
      @case ('refused') { <p class="banner bad" role="alert" data-state="refused">You are not permitted to view this account.</p> }
      @default { <ng-content /> }
    }
  `,
})
export class ReadGate {
  readonly desk = inject(Desk);
  readonly what = input('data');
}

// ================================= Overview =================================

@Component({
  selector: 'overview-page',
  imports: [RouterLink, PriceChip, Fresh, ReadGate],
  template: `
<div class="page-head"><div><p class="eyebrow">YOUR DESK</p><h1>Trading overview</h1></div><span class="spacer"></span><a class="btn-primary btnlink" routerLink="/desk/orders">New order</a></div>
<read-gate what="account data">
  <div class="tiles">
    <a class="tile" routerLink="/desk/orders"><span>Working orders</span><b>{{ desk.workingOrders().length }}</b><span>{{ desk.runView().kind === 'history' ? 'Read-only run' : 'Selected account' }}</span></a>
    <a class="tile" routerLink="/desk/positions"><span>Open positions</span><b>{{ desk.positionRows().length }}</b><span>Selected account and run</span></a>
    <a class="tile" routerLink="/desk/positions"><span>Executions</span><b>{{ desk.accountTrades().length }}</b><span>Recorded executions</span></a>
    <a class="tile risk-tile" routerLink="/desk/risk"><span>Portfolio risk</span><b>Unavailable</b><span>No account-bound result</span></a>
  </div>
  <div class="desk-overview">
    <div class="stack">
      <section class="card"><div class="card-head"><h2>Working orders</h2><span class="spacer"></span><a routerLink="/desk/orders">All orders →</a></div>
        <table><thead><tr><th>Instrument</th><th>Side</th><th class="num">Quantity</th><th>Type</th><th>Status</th></tr></thead><tbody>
          @for (o of desk.workingOrders().slice(0,4); track o.ref) { <tr><td><b>{{ o.key }}</b></td><td>{{ o.side }}</td><td class="num">{{ o.quantity }}</td><td>{{ o.orderType }} · {{ o.tif }}</td><td>{{ o.status === 'PENDING_TRIGGER' ? 'Waiting for trigger' : 'Working' }}</td></tr> }
          @empty { <tr><td colspan="5" class="state">No working orders in this view.</td></tr> }
        </tbody></table>
      </section>
      <section class="card" aria-labelledby="wl-h"><div class="card-head"><h2 id="wl-h">Your watchlist</h2><span class="spacer"></span><a routerLink="/desk/markets">Browse markets →</a></div>
        <table><thead><tr><th>Instrument</th><th class="num">Reference price</th><th>Unit</th><th>Source</th><th>Update age</th></tr></thead><tbody>
          @for (k of session.prefs().watchlist; track k) { <tr><td><b>{{ k }}</b></td><td class="num">{{ desk.marks()[k] ? price(k) : '—' }}</td><td>{{ inst(k).unit }}</td><td><price-chip [source]="desk.connected ? desk.api.prices()[k]?.source : inst(k).source" /></td><td><fresh [f]="desk.freshness(k)" /></td></tr> }
          @empty { <tr><td colspan="5">Add instruments on Markets.</td></tr> }
        </tbody></table>
      </section>
    </div>
    <div class="stack">
      <section class="card"><div class="card-head"><h2>Needs attention</h2></div>
        <p class="banner warn">No portfolio risk result is available for this account.</p><a routerLink="/desk/risk">Review coverage →</a>
        @for (a of desk.alerts(); track a.text) { <p class="banner" [class]="a.tone">{{ a.text }}</p> }
      </section>
      <section class="card"><div class="card-head"><h2>Recent activity</h2></div>
        @for (e of desk.activity().slice(0,5); track $index) { <p class="act" [class.bad]="!e.ok"><span class="faint">{{ e.at }}</span> {{ e.text }}</p> }
        @empty { <p class="state">No orders submitted in this session.</p> }
      </section>
    </div>
  </div>
</read-gate>
  `,
})
export class OverviewPage {
  readonly desk = inject(Desk);
  readonly session = inject(Session);
  readonly inst = (key:string)=>this.desk.instrument(key);
  readonly fillsToday = computed(() => this.desk.accountTrades().filter(t => t.bookedAt.startsWith('2026-09-25')).length);
  readonly notLive = computed(() => this.session.prefs().watchlist.filter(k => this.desk.freshness(k).kind !== 'live').length);
  price(k: string): string { return fmtPrice(this.desk.marks()[k].price, this.desk.instrument(k)); }
}

// ================================= Markets =================================

@Component({
  selector: 'markets-page',
  imports: [RouterLink, PriceChip, Fresh, HelpTip, FormsModule],
  template: `
<div class="page-head"><h1>Markets</h1>
  <help-tip text="Prices come from several sources that are not interchangeable: replayed tape, a published reference curve, a model, a simulation, or yesterday's close carried forward. The chip says which; the age says how old it is." /></div>
<div class="cols-markets">
  <section class="card" aria-labelledby="inst-h">
    <div class="card-head"><h2 id="inst-h">Instruments</h2><input aria-label="Find instrument" placeholder="Find instrument" [ngModel]="search()" (ngModelChange)="search.set($event)"><span class="spacer"></span>
      <div class="seg" role="group" aria-label="Asset class">
        @for (c of classes; track c) { <button type="button" [class.on]="cls() === c" [attr.aria-pressed]="cls() === c" (click)="cls.set(c)">{{ c }}</button> }
      </div>
    </div>
    <table>
      <thead><tr><th scope="col"><span class="sr">Watch</span></th><th scope="col">Instrument</th><th scope="col" class="num">Last</th>
        <th scope="col">Unit</th><th scope="col">Source</th><th scope="col">Age</th></tr></thead>
      <tbody>
        @for (i of list(); track i.key) {
          <tr [class.hit]="selected() === i.key">
            <td><button type="button" class="star" [class.on]="watching(i.key)" (click)="toggleWatch(i.key)"
                  [attr.aria-pressed]="watching(i.key)" [attr.aria-label]="(watching(i.key) ? 'Remove ' : 'Add ') + i.key + (watching(i.key) ? ' from' : ' to') + ' watchlist'">{{ watching(i.key) ? '★' : '☆' }}</button></td>
            <td><button type="button" class="linkish" (click)="selected.set(i.key)">{{ i.key }}</button><span class="faint gap">{{ i.name }}</span></td>
            <td class="num">{{ desk.marks()[i.key] ? price(i) : '—' }}</td>
            <td class="faint">{{ i.unit }}</td>
            <td><price-chip [source]="desk.connected ? desk.api.prices()[i.key]?.source : i.source" /></td>
            <td><fresh [f]="desk.freshness(i.key)" /></td>
          </tr>
        }
      </tbody>
    </table>
    <p class="faint">★ marks your watchlist. It belongs to you, not to the account, and other users do not see it.</p>
  </section>
  @if (detail(); as d) {
    <section class="card" aria-labelledby="det-h" data-testid="instrument-detail">
      <div class="card-head"><h2 id="det-h">{{ d.key }}</h2></div>
      <p>{{ d.name }}</p>
      <dl class="kv">
        <dt>Class</dt><dd>{{ d.cls }}</dd>
        <dt>Last</dt><dd>{{ desk.marks()[d.key] ? price(d) : 'no price' }} <span class="faint">{{ d.unit }}</span></dd>
        <dt>Source</dt><dd><price-chip [source]="desk.connected ? desk.api.prices()[d.key]?.source : d.source" /> <span class="faint">{{ sourceWords(d) }}</span></dd>
        <dt>Age</dt><dd><fresh [f]="desk.freshness(d.key)" /></dd>
        <dt>Quantity in</dt><dd>{{ d.qtyUnit }}@if (d.multiplier !== 1) { · multiplier {{ d.multiplier }} }</dd>
        @if (d.terms) { <dt>Terms</dt><dd>{{ d.terms }}</dd> }
      </dl>
      <a class="btn-primary btnlink" [routerLink]="['/desk/orders']" [queryParams]="{ i: d.key }">Trade {{ d.key }}</a>
    </section>
  }
</div>
  `,
})
export class MarketsPage {
  readonly desk = inject(Desk);
  readonly session = inject(Session);
  readonly classes: ('All' | AssetClass)[] = ['All', 'Equity', 'Option', 'Treasury', 'Corporate'];
  readonly search = signal('');
  readonly cls = signal<'All' | AssetClass>('All');
  readonly selected = signal('IBM');
  readonly list = computed(() => this.desk.instrumentList().filter(i => (this.cls() === 'All' || i.cls === this.cls()) && (i.key+' '+i.name).toLowerCase().includes(this.search().toLowerCase())));
  readonly detail = computed(() => this.desk.instrument(this.selected()));
  watching(k: string): boolean { return this.session.prefs().watchlist.includes(k); }
  toggleWatch(k: string): void {
    const w = this.session.prefs().watchlist;
    this.session.updatePrefs({ watchlist: w.includes(k) ? w.filter(x => x !== k) : [...w, k] });
  }
  price(i: FixtureInstrument): string { return fmtPrice(this.desk.marks()[i.key].price, i); }
  sourceWords(i: FixtureInstrument): string {
    const s = i.source;
    return s.startsWith('taq-replay') ? 'recorded tape, replayed' : s.startsWith('fred-') ? 'published reference curve'
      : s === 'black-scholes' ? 'model price from the underlying' : s.startsWith('simulated-') ? 'simulated, not observed'
      : s === 'previous-close' ? 'yesterday\'s close carried forward; it does not move' : s;
  }
}

// ================================= Orders =================================

@Component({
  selector: 'orders-page',
  imports: [FormsModule, PriceChip, Fresh, HelpTip],
  template: `
<div class="page-head"><h1>Orders</h1><span class="spacer"></span><span class="state">{{ desk.runView().kind === 'history' ? 'Previous run · read only' : 'Active run' }}</span></div>
@if (desk.runView().kind === 'history') { <p class="banner warn">Previous run selected. Order actions are disabled.</p> }
<div class="cols">
  <section class="card ticket" aria-labelledby="tk-h">
    <div class="card-head"><h2 id="tk-h">New order</h2>
      <help-tip text="Checks that need no market state run here before sending. Checks that need the book (the last trade, the peg reference, the business day) are the venue's, and its reason is shown as given." /></div>
    <div class="seg wide" role="group" aria-label="Product">
      @for (p of products; track p) { <button type="button" [class.on]="product() === p" [attr.aria-pressed]="product() === p" (click)="setProduct(p)">{{ p }}</button> }
    </div>
    @if (product() === 'Swap' || product() === 'Swaption') {
      <a href="http://127.0.0.1:4321" target="_blank" rel="noopener">Open Demo console ↗</a><p class="state">Swap and swaption tickets are available in Demo console. Use its contract form to enter dates, notional and conventions.</p>
    } @else {
      <form (ngSubmit)="submit()" aria-describedby="tk-check">
        <label class="field">Instrument
          <select name="i" [ngModel]="key()" (ngModelChange)="setInstrument($event)" data-testid="tk-instrument">
            @for (i of productList(); track i.key) { <option [value]="i.key">{{ i.key }} — {{ i.name }}</option> }
          </select>
        </label>
        <div class="tk-live">
          @if (desk.marks()[key()]; as m) { <b>{{ fmtPrice(m.price, current()) }}</b> <span class="faint">{{ current().unit }}</span> }
          @else { <span class="faint">no price</span> }
          <price-chip [source]="current().source" /> <fresh [f]="desk.freshness(key())" />
        </div>
        <div class="row2">
          <label class="field">Side
            <select name="side" [ngModel]="side()" (ngModelChange)="side.set($event)"><option>Buy</option><option>Sell</option></select></label>
          <label class="field"><span>Quantity <span class="faint">({{ current().qtyUnit }})</span></span>
            <input name="q" type="number" min="1" [ngModel]="qty()" (ngModelChange)="qty.set($event)" data-testid="tk-qty"></label>
        </div>
        @if (current().cls === 'Equity') {
          <label class="field">Execution
            <select name="exec" [ngModel]="exec()" (ngModelChange)="exec.set($event)"><option>Direct</option><option>TWAP</option><option>VWAP</option></select></label>
        }
        @if (exec() !== 'Direct' && current().cls === 'Equity') {
          <p class="state">A {{ exec() }} parent goes to the algo engine, which sends child orders on a schedule. Parents and their slices are under ☰ More › Algo orders.</p>
          <div class="row2">
            <label class="field">Duration (s)<input name="dur" type="number" min="10" [ngModel]="dur()" (ngModelChange)="dur.set($event)"></label>
            <label class="field">Slice every (s)<input name="bkt" type="number" min="5" [ngModel]="bucket()" (ngModelChange)="bucket.set($event)"></label>
          </div>
        } @else {
          <div class="row2">
            <label class="field">Order type
              <select name="ot" [ngModel]="type()" (ngModelChange)="setType($event)" data-testid="tk-type">
                @for (o of types; track o) { <option [value]="o">{{ typeLabel(o) }}</option> }
              </select></label>
            <label class="field">Time in force
              <select name="tif" [ngModel]="tif()" (ngModelChange)="tif.set($event)">
                @for (t of tifs(); track t) { <option [value]="t">{{ t }}</option> }
              </select></label>
          </div>
          @if (uses('limitPrice')) { <label class="field"><span>Limit price <span class="faint">({{ current().unit }})</span></span><input name="lp" type="number" step="any" [ngModel]="limit()" (ngModelChange)="limit.set($event)" data-testid="tk-limit"></label> }
          @if (uses('stopPrice')) { <label class="field">Stop price<input name="sp" type="number" step="any" [ngModel]="stop()" (ngModelChange)="stop.set($event)"></label> }
          @if (uses('displayQuantity')) { <label class="field">Shown quantity<input name="dq" type="number" [ngModel]="display()" (ngModelChange)="display.set($event)"></label> }
          @if (type() === 'PEGGED') {
            <div class="row2">
              <label class="field">Peg to<select name="pr" [ngModel]="peg()" (ngModelChange)="peg.set($event)"><option value="PRIMARY">Same-side best (this venue)</option><option value="MIDPOINT">Midpoint (this venue)</option></select></label>
              <label class="field">Offset (ticks)<input name="po" type="number" [ngModel]="pegOffset()" (ngModelChange)="pegOffset.set($event)"></label>
            </div>
          }
          @if (type() === 'TRAILING_STOP') {
            <div class="row2">
              <label class="field">Trail amount<input name="ta" type="number" step="any" [ngModel]="trail()" (ngModelChange)="trail.set($event)"></label>
              <label class="field">or trail (bps)<input name="tb" type="number" [ngModel]="trailBps()" (ngModelChange)="trailBps.set($event)"></label>
            </div>
          }
          @if (estimate(); as e) { <p class="faint">Estimated value {{ e }} at the current price.</p> }
        }
        <p id="tk-check" class="check" [class.bad]="!!problem()" aria-live="polite" data-testid="tk-check">
          {{ problem() ? 'Cannot send yet: ' + problem() + '.' : 'Ready to send.' }}</p>
        <button class="btn-primary" type="submit" [disabled]="!!problem()" data-testid="tk-submit">Submit order</button>
        @if (desk.connected && desk.freshness(key()).kind !== 'live') { <p class="banner warn">The displayed price is stale or missing. Your explicit limit is sent to the venue for validation.</p> }
        <p class="faint">{{ desk.connected ? 'Orders are sent to the local rig. An acceptance is not a fill; check the order and execution records.' : 'Prototype: orders remain in memory.' }}</p>
        @if (result(); as r) { <p class="banner" [class.good]="r.ok" [class.bad]="!r.ok" role="status" data-testid="tk-result">{{ r.text }}</p> }
      </form>
    }
  </section>

  <div class="stack">
    <section class="card" aria-labelledby="wo-h">
      <div class="card-head"><h2 id="wo-h">{{ filterAll() ? 'All orders' : 'Working orders' }}</h2><span class="spacer"></span>
        <div class="seg" role="group" aria-label="Which orders">
          <button type="button" [class.on]="!filterAll()" [attr.aria-pressed]="!filterAll()" (click)="setFilter('working')">Working</button>
          <button type="button" [class.on]="filterAll()" [attr.aria-pressed]="filterAll()" (click)="setFilter('all')" data-testid="all-states">All states</button>
        </div>
      </div>
      @if(desk.readState()!=='ready') { <p class="banner warn">{{ desk.readState()==='loading' ? 'Loading orders…' : desk.error() || 'Orders are unavailable.' }}</p> } @else {
      @if(desk.connected && !desk.canChangeExisting()) { <p class="state">This rig has no confirmed run identity. Existing orders are read-only here.</p> }
      <table data-testid="orders">
        <thead><tr><th scope="col">Ref</th><th scope="col">Time</th><th scope="col">Instrument</th><th scope="col">Side</th>
          <th scope="col" class="num">Filled / qty</th><th scope="col">Type</th><th scope="col" class="num">Price</th><th scope="col">Status</th><th scope="col"><span class="sr">Actions</span></th></tr></thead>
        <tbody>
          @for (o of shown(); track o.ref) {
            <tr>
              <td class="num">{{ o.ref }}</td><td class="faint">{{ o.updatedAt.slice(11) }}</td><td>{{ o.key }}</td><td>{{ o.side }}</td>
              <td class="num">{{ fmtQty(o.filled) }} / {{ fmtQty(o.quantity) }}</td>
              <td>{{ o.orderType === 'UNTYPED' ? 'Limit' : typeLabel(o.orderType) }} · {{ o.tif }}</td>
              <td class="num">{{ o.limitPrice ?? '' }}{{ o.stopPrice ? ' stop ' + o.stopPrice : '' }}</td>
              <td><span class="st" [class]="o.status">{{ statusWords(o.status) }}</span>
                @if (o.reason) { <div class="faint">{{ reasonText(o.reason) }}</div> }</td>
              <td class="acts">
                @if (desk.canChangeExisting() && live(o.status) && desk.runView().kind === 'active' && desk.readState() === 'ready') {
                  @if (o.orderType === 'LIMIT' || o.orderType === 'UNTYPED') { <button type="button" (click)="startEdit(o.ref, o.quantity, o.limitPrice)" [attr.aria-label]="'Change order ' + o.ref">Change</button> }
                  <button type="button" (click)="desk.cancel(o.ref)" [attr.aria-label]="'Cancel order ' + o.ref">Cancel</button>
                }
              </td>
            </tr>
            @if (editing() === o.ref) {
              <tr class="edit"><td colspan="9">
                <form class="inline" (ngSubmit)="saveEdit(o.ref)">
                  <label>Quantity <input name="eq" type="number" [ngModel]="editQty()" (ngModelChange)="editQty.set($event)"></label>
                  <label>Limit price <input name="ep" type="number" step="any" [ngModel]="editPx()" (ngModelChange)="editPx.set($event)"></label>
                  <button class="btn-primary" type="submit">Save change</button><button type="button" (click)="editing.set(null)">Keep as is</button>
                  @if (editErr()) { <span class="bad">{{ editErr() }}</span> }
                </form></td></tr>
            }
          } @empty { <tr><td colspan="9" class="faint">{{ filterAll() ? 'No orders on this account.' : 'No working orders on this account.' }}</td></tr> }
        </tbody>
      </table> }
    </section>
    <section class="card" aria-labelledby="ac-h">
      <div class="card-head"><h2 id="ac-h">This session</h2></div>
      @for (e of desk.activity(); track $index) { <p class="act" [class.bad]="!e.ok"><span class="faint">{{ e.at }}</span> {{ e.text }}</p> }
      @empty { <p class="state">Orders you send appear here with the answer that came back.</p> }
    </section>
  </div>
</div>
  `,
})
export class OrdersPage {
  readonly desk = inject(Desk);
  readonly session = inject(Session);
  readonly fmtPrice = fmtPrice; readonly fmtQty = fmtQty; readonly reasonText = reasonText;
  readonly products = ['Equity', 'Option', 'Treasury', 'Corporate', 'Swap', 'Swaption'] as const;
  readonly types = ORDER_TYPES;
  readonly i = input<string>();                                   // ?i=KEY from Markets › Trade

  readonly busy = signal(false);
  readonly product = signal<(typeof this.products)[number]>('Equity');
  readonly key = signal('IBM');
  readonly side = signal<'Buy' | 'Sell'>('Buy');
  readonly qty = signal(100);
  readonly exec = signal<'Direct' | 'TWAP' | 'VWAP'>('Direct');
  readonly dur = signal(60); readonly bucket = signal(10);
  readonly type = signal<OrderType>('LIMIT');
  readonly tif = signal<Tif>('GTC');
  readonly limit = signal<number | undefined>(undefined);
  readonly stop = signal<number | undefined>(undefined);
  readonly display = signal<number | undefined>(undefined);
  readonly peg = signal<'PRIMARY' | 'MIDPOINT'>('PRIMARY');
  readonly pegOffset = signal(0);
  readonly trail = signal<number | undefined>(undefined);
  readonly trailBps = signal<number | undefined>(undefined);
  readonly result = signal<{ ok: boolean; text: string } | null>(null);

  readonly editing = signal<number | null>(null);
  readonly editQty = signal(0); readonly editPx = signal<number | undefined>(undefined); readonly editErr = signal('');

  readonly current = computed(() => this.desk.instrument(this.key()));
  readonly productList = computed(() => this.desk.instrumentList().filter(i => i.cls === this.product()));
  readonly tifs = computed(() => TIFS_FOR[this.type()]);
  readonly filterAll = computed(() => this.session.prefs().orderFilter === 'all');
  readonly shown = computed(() => this.filterAll() ? this.desk.accountOrders() : this.desk.workingOrders());

  readonly ticket = computed<TypedTicket>(() => ({
    orderType: this.type(), timeInForce: this.tif(), side: this.side(), quantity: Number(this.qty()),
    limitPrice: num(this.limit()), stopPrice: num(this.stop()), displayQuantity: num(this.display()),
    pegReference: this.type() === 'PEGGED' ? this.peg() : undefined, pegOffset: this.type() === 'PEGGED' ? Number(this.pegOffset()) : undefined,
    trailAmount: num(this.trail()), trailPercentBps: num(this.trailBps()),
  }));
  readonly problem = computed(() => {
    if (!this.desk.instrumentList().some(i=>i.key===this.key() && i.cls===this.product())) return 'Choose an instrument in this product. Other tickets are available in Demo console.';
    if (this.busy() || this.desk.actionBusy()) return 'sending…';
    if (this.desk.runView().kind === 'history') return 'previous runs are read-only';
    if (this.desk.readState() !== 'ready') return 'account data is not available';
    if (this.desk.connected && !this.desk.typedOrdersEnabled && (this.type()!=='LIMIT' || this.tif()!=='GTC')) return 'Typed-order support is not verified on this rig. Use Limit / GTC.';
    if ((!this.desk.connected || this.type()!=='LIMIT') && this.desk.freshness(this.key()).kind !== 'live') return 'a recent price update is required';
    if (this.exec() !== 'Direct' && this.current().cls === 'Equity') return Number(this.qty()) > 0 ? '' : 'quantity must be positive';
    return plain(validateTicket(this.ticket()) || trailHint(this.ticket(), this.desk.marks()[this.key()]?.price));
  });
  readonly estimate = computed(() => {
    const m = this.desk.marks()[this.key()];
    const q = Number(this.qty());
    return m && this.desk.freshness(this.key()).kind==='live' && q > 0 ? fmtUsd(q * m.price * this.current().multiplier) : '';
  });

  constructor() {
    effect(() => { this.session.generation(); this.desk.runView(); untracked(() => this.clearTicket()); });
    queueMicrotask(() => {
      const k = this.i();
      const found = k && this.desk.instrumentList().find(x => x.key === k);
      if (found) { this.product.set(found.cls); this.key.set(found.key); }
    });
  }

  setProduct(p: (typeof this.products)[number]): void {
    this.product.set(p); this.clearTicket();
    const first = this.desk.instrumentList().find(i => i.cls === p);
    this.key.set(first?.key ?? '');
    if (p !== 'Equity') this.exec.set('Direct');
  }
  clearTicket(): void {
    this.limit.set(undefined); this.stop.set(undefined); this.display.set(undefined);
    this.trail.set(undefined); this.trailBps.set(undefined); this.pegOffset.set(0);
    this.qty.set(100); this.result.set(null); this.editing.set(null); this.editErr.set('');
  }
  setInstrument(k: string): void { this.clearTicket(); this.key.set(k); }
  setType(t: OrderType): void { this.type.set(t); this.tif.set(defaultTif(t)); }
  uses(field: 'limitPrice' | 'stopPrice' | 'displayQuantity'): boolean {
    const t = this.type();
    return field === 'limitPrice' ? ['LIMIT', 'STOP_LIMIT', 'ICEBERG', 'PEGGED'].includes(t)
      : field === 'stopPrice' ? t === 'STOP' || t === 'STOP_LIMIT' : t === 'ICEBERG';
  }
  typeLabel(t: string): string {
    return ({ MARKET: 'Market', LIMIT: 'Limit', STOP: 'Stop', STOP_LIMIT: 'Stop limit', ICEBERG: 'Iceberg',
      PEGGED: 'Pegged (this venue)', TRAILING_STOP: 'Trailing stop' } as Record<string, string>)[t] ?? t;
  }
  statusWords(s: string): string {
    return ({ NEW: 'Working', PARTIALLY_FILLED: 'Part filled', PENDING_TRIGGER: 'Waiting for trigger', FILLED: 'Filled',
      CANCELED: 'Cancelled', REJECTED: 'Rejected', SUSPENDED: 'Suspended' } as Record<string, string>)[s] ?? s;
  }
  live(s: string): boolean { return ['NEW', 'PARTIALLY_FILLED', 'PENDING_TRIGGER', 'SUSPENDED'].includes(s); }
  setFilter(f: 'working' | 'all'): void { this.session.updatePrefs({ orderFilter: f }); }

  async submit(): Promise<void> {
    if (this.problem()) return;
    if (this.desk.connected) {
      const generation=this.session.generation(), view=this.desk.runView(); this.busy.set(true);
      try {
        const r=this.exec()!=='Direct' && this.current().cls==='Equity'
          ? await this.desk.rigAction('/algo/orders',{accountId:this.session.account(),security:this.key(),side:this.side(),quantity:Number(this.qty()),algoType:this.exec(),durationSeconds:Number(this.dur()),bucketSeconds:Number(this.bucket())},'parentOrderId')
          : await this.desk.submitRig(this.key(),this.ticket());
        if(generation===this.session.generation() && view===this.desk.runView()) this.result.set(r);
      } finally {this.busy.set(false);} return;
    }
    if (this.exec() !== 'Direct' && this.current().cls === 'Equity') {
      this.result.set(this.desk.submitAlgo(this.key(), this.side(), Number(this.qty()), this.exec() as 'TWAP' | 'VWAP', Number(this.dur()), Number(this.bucket())));
      return;
    }
    this.result.set(this.desk.submit(this.key(), this.ticket()));
  }
  startEdit(ref: number, q: number, px: number | undefined): void { this.editing.set(ref); this.editQty.set(q); this.editPx.set(px); this.editErr.set(''); }
  async saveEdit(ref: number): Promise<void> {
    if(this.desk.connected) {
      if(this.busy()) return;
      this.busy.set(true);
      try {const r=await this.desk.replaceRig(ref,Number(this.editQty()),num(this.editPx()));this.editErr.set(r.ok?'':r.text);if(r.ok)this.editing.set(null);} finally {this.busy.set(false);} return;
    }
    const err = this.desk.replace(ref, Number(this.editQty()), num(this.editPx()));
    this.editErr.set(err);
    if (!err) this.editing.set(null);
  }
}

/** The shared validation text names wire fields; say them as words before showing them. */
const FIELD_WORDS: Record<string, string> = { limitPrice: 'limit price', stopPrice: 'stop price', displayQuantity: 'shown quantity',
  pegReference: 'peg', pegOffset: 'peg offset', trailAmount: 'trail amount', trailPercentBps: 'trail (bps)',
  timeInForce: 'time in force', orderType: 'order type' };
export const plain = (s: string) => s.replace(/\b(limitPrice|stopPrice|displayQuantity|pegReference|pegOffset|trailAmount|trailPercentBps|timeInForce|orderType)\b/g, m => FIELD_WORDS[m]);

const num = (v: unknown): number | undefined => v === undefined || v === null || v === '' ? undefined : Number(v);

// ================================= Positions =================================

@Component({
  selector: 'positions-page',
  imports: [FormsModule, PriceChip, Fresh, HelpTip, ReadGate],
  template: `
<div class="page-head"><h1>Positions</h1><span class="state">Holdings and executions for the selected account and run</span></div>
@if (desk.runView().kind === 'history') {
  <p class="banner warn" role="note" data-testid="history-banner">Read-only: run {{ viewKey() }} has ended. These rows cannot be cancelled, settled or changed.
    Prices shown are today's, for reference. <button type="button" (click)="setView('')">Back to the active run</button></p>
}
<section class="card" aria-labelledby="pos-h">
  <div class="card-head"><h2 id="pos-h">Holdings</h2>
    <help-tip text="Market value is quantity × last price × contract multiplier. Unrealized P&L is quantity × (last price − average cost) × multiplier. Bonds are priced as a fraction of par, so their quantity is USD face. With no price there is no value: the cell stays empty rather than using an old number." /></div>
  <read-gate what="positions">
    <table data-testid="positions">
      <thead><tr><th scope="col">Instrument</th><th scope="col" class="num">Quantity</th><th scope="col" class="num">Average cost</th>
        <th scope="col" class="num">Last price</th><th scope="col">Source · age</th><th scope="col" class="num">Market value (USD)</th><th scope="col" class="num">Unrealized P&amp;L (USD)</th></tr></thead>
      <tbody>
        @for (p of desk.positionRows(); track p.key) {
          <tr [class.stale]="p.fresh.kind !== 'live'">
            <td>{{ p.key }}</td>
            <td class="num">{{ fmtQty(p.quantity) }} <span class="faint">{{ p.inst.qtyUnit }}</span></td>
            <td class="num">{{ fmtPrice(p.avgCost, p.inst) }}</td>
            <td class="num">{{ p.mark ? fmtPrice(p.mark.price, p.inst) : '—' }}</td>
            <td><price-chip [source]="desk.connected ? desk.api.prices()[p.key]?.source : p.inst.source" /> <fresh [f]="p.fresh" /></td>
            <td class="num">{{ p.value !== undefined ? fmtUsd(p.value) : '—' }}</td>
            <td class="num" [class.pos]="(p.upnl ?? 0) > 0" [class.neg]="(p.upnl ?? 0) < 0">{{ p.upnl !== undefined ? fmtUsd(p.upnl) : '—' }}</td>
          </tr>
        } @empty { <tr><td colspan="7" class="faint">No open positions on this account in this run.</td></tr> }
      </tbody>
      @if (totals(); as t) {
        <tfoot><tr><td colspan="5"><b>Total</b><span class="faint gap">{{ t.priced }} of {{ t.n }} positions priced@if (t.stale) { · {{ t.stale }} on stale prices }</span></td>
          <td class="num"><b>{{ fmtUsd(t.value) }}</b></td><td class="num" [class.pos]="t.upnl > 0" [class.neg]="t.upnl < 0"><b>{{ fmtUsd(t.upnl) }}</b></td></tr></tfoot>
      }
    </table>
  </read-gate>
</section>
<section class="card" aria-labelledby="fl-h">
  <div class="card-head"><h2 id="fl-h">Fills</h2>
    <help-tip text="A fill stays Processing until its settlement date passes. That is the settlement cycle, not a stuck trade." /></div>
  <read-gate what="fills">
    <table data-testid="fills">
      <thead><tr><th scope="col">Trade</th><th scope="col">Booked</th><th scope="col">Instrument</th><th scope="col">Side</th>
        <th scope="col" class="num">Quantity</th><th scope="col" class="num">Price</th><th scope="col">Settlement</th><th scope="col">From order</th></tr></thead>
      <tbody>
        @for (t of desk.accountTrades(); track t.id) {
          <tr><td>{{ t.id }}</td><td class="faint">{{ t.bookedAt }}</td><td>{{ t.key }}</td><td>{{ t.side }}</td>
            <td class="num">{{ fmtQty(t.quantity) }}</td><td class="num">{{ fmtPrice(t.price, inst(t.key)) }}</td>
            <td>{{ t.state }}</td><td class="faint">{{ t.sourceOrder }}</td></tr>
        } @empty { <tr><td colspan="8" class="faint">No fills on this account in this run.</td></tr> }
      </tbody>
    </table>
  </read-gate>
</section>
<section class="card"><div class="card-head"><h2>Booked swaps and swaptions</h2></div>
  <p class="state">Kept at contract level, never netted into a position. Same list as today's blotter section; placed here, not rebuilt in the prototype.</p></section>
  `,
})
export class PositionsPage {
  readonly desk = inject(Desk);
  readonly inst = (key:string)=>this.desk.instrument(key); readonly fmtQty = fmtQty; readonly fmtPrice = fmtPrice; readonly fmtUsd = fmtUsd;
  readonly viewKey = computed(() => { const v = this.desk.runView(); return v.kind === 'history' ? v.scope : ''; });
  readonly totals = computed(() => {
    const rows = this.desk.positionRows();
    if (!rows.length) return null;
    const priced = rows.filter(r => r.value !== undefined);
    return { n: rows.length, priced: priced.length, stale: priced.filter(r => r.fresh.kind === 'stale').length,
      value: priced.reduce((s, r) => s + r.value!, 0), upnl: priced.reduce((s, r) => s + r.upnl!, 0) };
  });
  setView(scope: string): void {
    this.desk.runView.set(scope ? { kind: 'history', scope } : { kind: 'active' });
    this.desk.reload();
  }
}

// ================================= Risk =================================

type Job = (typeof riskFixture)['jobs'][number];
const CALCS: [string, string][] = [['npv', 'Present value (NPV)'], ['accruedInterest', 'Accrued interest'],
  ['rateSensitivity', 'Rate sensitivity'], ['rateGamma', 'Rate gamma'], ['theta', 'Theta'], ['vega', 'Vega'], ['varEs', 'VaR / ES']];

@Component({
  selector: 'risk-page',
  imports: [ConnectedRiskPage,HelpTip, ReadGate],
  template: `
<div class="page-head"><h1>Risk</h1><span class="spacer"></span><span class="state">Read only</span></div>
<p class="banner warn"><b>No portfolio risk result is available.</b> Reference examples below do not value your holdings.</p>
<read-gate what="portfolio inputs">
<div class="risk-summary">
<section class="card"><div class="card-head"><h2>{{ desk.accountName() }}</h2></div>
<p class="state">Selected account and run</p>
<table><thead><tr><th>Instrument</th><th class="num">Signed quantity</th><th>Result</th></tr></thead><tbody>
@for(p of desk.positionRows();track p.key){<tr><td>{{p.key}}</td><td class="num">{{p.quantity}} {{p.inst.qtyUnit}}</td><td>No connected result</td></tr>}
@empty {<tr><td colspan="3">No holdings in this view.</td></tr>}
</tbody></table></section>
<section class="card"><div class="card-head"><h2>Calculation coverage</h2></div>
<table><thead><tr><th>Measure</th><th>Availability</th></tr></thead><tbody><tr><td>NPV and accrued interest</td><td>No account-bound result</td></tr><tr><td>Sensitivities</td><td>No connected result</td></tr><tr><td>VaR / expected shortfall</td><td>Unavailable</td></tr></tbody></table>
<p class="state">A result must match the account, run, valuation date and inputs before it is displayed here.</p></section>
</div></read-gate>
@if (desk.connected) { <details class="reference-examples" open><summary>Rig integration · jobs and Treasury demo</summary><p class="state">These results carry their own portfolio and cut identity; they are not the valuation of the account selected above.</p><risk-page /></details> } @else { <details class="reference-examples"><summary>Reference examples · synthetic Treasury bill and note</summary>
<p class="banner warn" role="note"><b>Synthetic pricing results.</b> Assumed flat 3% curve, business date {{ fx.businessDate }}. Not usable for production risk.
  Portfolio VaR/ES is not available.</p>
@if (desk.scenario() === 'refused') {
  <section class="card" data-testid="risk-refused">
    <div class="card-head"><h2>No result to show</h2></div>
    <p class="banner bad" role="alert">The latest risk result failed its integrity check, so it is not shown. No earlier result is shown in its place.</p>
    <p class="state">An operator can see the job and its attempts in Admin › End of day.</p>
  </section>
} @else {
  <section class="card" aria-labelledby="rs-h">
    <div class="card-head"><h2 id="rs-h">Calculation status</h2></div>
    <table>
      <thead><tr><th scope="col">Instrument</th><th scope="col">Status</th><th scope="col">Business date</th><th scope="col">Checked</th><th scope="col">Where it ran</th></tr></thead>
      <tbody>
        @for (j of jobs; track j.jobId) {
          <tr><td>{{ name(j) }}</td><td><span class="pill good">Priced and checked</span></td><td>{{ j.cut.sessionDate }}</td>
            <td class="faint">{{ j.validatedAt.replace('T', ' ').slice(0, 16) }} UTC</td><td>{{ fx.producerExecution.location.toLowerCase() }}</td></tr>
        }
      </tbody>
    </table>
  </section>
  <section class="card" aria-labelledby="cv-h">
    <div class="card-head"><h2 id="cv-h">What was calculated</h2>
      <help-tip text="Not supported means the pricing engine cannot calculate this measure yet. Not applicable means the measure does not exist for this instrument (a bill has no vega). Neither is shown as zero." /></div>
    <table data-testid="coverage">
      <thead><tr><th scope="col">Measure</th>@for (j of jobs; track j.jobId) { <th scope="col">{{ name(j) }}</th> }</tr></thead>
      <tbody>
        @for (c of calcs; track c[0]) {
          <tr><td>{{ c[1] }}</td>@for (j of jobs; track j.jobId) { <td><span class="cov" [class]="cov(j, c[0]).tone">{{ cov(j, c[0]).text }}</span></td> }</tr>
        }
      </tbody>
    </table>
  </section>
  <section class="card" aria-labelledby="cmp-h">
    <div class="card-head"><h2 id="cmp-h">Present value against the independent check</h2></div>
    <table>
      <thead><tr><th scope="col">Instrument</th><th scope="col">Position</th><th scope="col" class="num">Pricing result (USD)</th>
        <th scope="col" class="num">Independent check (USD)</th><th scope="col" class="num">Difference (USD)</th><th scope="col">Within tolerance</th></tr></thead>
      <tbody>
        @for (j of jobs; track j.jobId) { @for (p of j.positions; track p.side) {
          <tr><td>{{ name(j) }}</td><td>{{ p.side }} {{ usd(p.signedFaceUsd, 0) }} face</td>
            <td class="num">{{ usd(p.npv.alexUsd) }}</td><td class="num">{{ usd(p.npv.referenceUsd) }}</td>
            <td class="num">{{ Number(p.npv.differenceUsd).toExponential(1) }}</td>
            <td>{{ p.npv.withinTolerance ? 'Yes (±' + p.npv.toleranceUsd + ')' : 'No' }}</td></tr>
        } }
      </tbody>
    </table>
  </section>
}
</details> }
  `,
})
export class RiskPage {
  readonly desk = inject(Desk);
  readonly fx = riskFixture;
  readonly jobs: Job[] = riskFixture.jobs;
  readonly calcs = CALCS;
  readonly Number = Number;
  name(j: Job): string { return j.instrument === 'bill' ? 'Treasury bill' : 'Treasury note'; }
  usd(v: string, dp = 2): string { return Number(v).toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp }); }
  cov(j: Job, calc: string): { text: string; tone: string } {
    const c = (j.coverage.byCalculation as Record<string, Record<string, number>>)[calc];
    const n = j.coverage.itemCount;
    if (!c) return { text: 'Not reported', tone: 'bad' };
    if (c['ok'] === n) return { text: 'Calculated', tone: 'good' };
    if (c['notApplicable'] === n) return { text: 'Not applicable', tone: 'na' };
    if (c['unsupported'] === n) return { text: 'Not supported yet', tone: 'warn' };
    return { text: `${c['ok']} of ${n} calculated`, tone: 'warn' };
  }
}
