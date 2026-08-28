import { Component, computed, inject, signal, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { QResult, runQ } from './qeval';
import { SecHead, SecPager, Section } from './section';

/** `/sandbox/results` — the console's join of the sandbox blotter against the tape journal. */
interface SymbolRow {
  ticker: string; trades: number; quantity: number; notional: number; firstMs: number; lastMs: number;
}
interface DayRow {
  date: string; trades: number; rejected: number; canceled: number; notional: number;
  symbols: SymbolRow[];
}
interface TradeRow {
  tapeDate: string; ms: number; tradeId: string | null; orderId: string | null;
  security: string; side: string; quantity: number; price: number; accountId: number; assumed: boolean;
}
interface Results {
  lastResetSeq: number;
  fromSeq: number;
  observedFromMs: number | null;
  assumedSpan: boolean;
  unattributed: number;
  segmentsDropped: boolean;
  days: DayRow[];
  trades: TradeRow[];
  error?: string;
  cause?: string;
}

/**
 * ADR-073 — what this throwaway session actually EXECUTED, by the tape day it was executed against.
 *
 * <p><b>Driven by executions, never by the corpus.</b> A day the tape holds and never played is
 * absent; a day it played through with nothing trading is absent too; a symbol that was quoted but
 * never filled does not appear with a zero. That is the whole point of the view — "we replayed
 * three days in March" and "three days in March produced trades" are different claims, and only
 * the second one is evidence of anything.
 *
 * <p><b>The dating is the load-bearing part.</b> A record carries a wall-clock timestamp; the tape
 * day it belongs to is recovered from the replay driver's session journal, which stores the origin
 * that was in force over each span. Asking the live clock instead would return a confident wrong
 * answer after any seek, so this panel deliberately has no clock arithmetic in it at all — the
 * server does the join and this renders the result.
 *
 * <p><b>Assumed spans are shown as assumed.</b> The origin outlives the driver pod, so after a
 * restart the journal can date instants it never watched. Those are labelled rather than folded in
 * silently, because a results view that cannot say which half it observed is the vacuous kind.
 */
@Component({
  selector: 'sandbox-results-panel',
  imports: [FormsModule, SecHead, SecPager],
  template: `
    <header class="head">
      <h2>Session results</h2>
      @if (totalTrades(); as n) { <span class="pill good">{{ n }} trades</span> }
      <button type="button" class="link" (click)="refresh()" [disabled]="busy()">Refresh</button>
    </header>

    @if (error(); as e) { <p class="err">{{ e }}</p> }

    @if (loaded() && !days().length && !error()) {
      <p class="muted">
        @if (results()?.lastResetSeq) {
          <!-- "yet" would be wrong here and misread as data loss: the venue HAS traded, this
               session just has not. Say which of the two it is. -->
          Nothing has traded since the reset. Resume the tape on the Sandbox tab and this fills in as
          orders execute. Earlier sessions are still in the engine's audit log — a reset clears the
          book, not the log.
        } @else {
          Nothing has traded in this sandbox yet. Start the tape on the Sandbox tab — this view fills
          in as orders execute, and stays empty for any day the tape passed through without trading.
        }
      </p>
    }

    @if (notes().length) {
      <ul class="notes">
        @for (n of notes(); track n) { <li>{{ n }}</li> }
      </ul>
    }

    <sec-head [s]="daysSec" label="By tape day" />
    @if (daysSec.open() && days().length) {
      <sec-pager [s]="daysSec" />
      <table class="days">
        <thead>
          <tr><th>Tape day</th><th class="n">Trades</th><th class="n">Rejected</th>
              <th class="n">Canceled</th><th class="n">Notional</th><th class="n">Symbols</th><th></th></tr>
        </thead>
        <tbody>
          @for (d of daysSec.view(); track d.date) {
            <tr [class.sel]="d.date === selected()">
              <td class="mono">{{ d.date }}</td>
              <td class="n mono">{{ d.trades }}</td>
              <td class="n mono">{{ d.rejected }}</td>
              <td class="n mono">{{ d.canceled }}</td>
              <td class="n mono">{{ money(d.notional) }}</td>
              <td class="n mono">{{ d.symbols.length }}</td>
              <td><button type="button" class="link" (click)="select(d.date)">
                {{ d.date === selected() ? 'Hide' : 'Symbols' }}</button></td>
            </tr>
            @if (d.date === selected()) {
              <tr class="detail"><td colspan="7">
                <table class="syms">
                  <thead><tr><th>Symbol</th><th class="n">Trades</th><th class="n">Quantity</th>
                             <th class="n">Notional</th><th>Tape span</th></tr></thead>
                  <tbody>
                    @for (s of d.symbols; track s.ticker) {
                      <tr>
                        <td class="mono">{{ s.ticker }}</td>
                        <td class="n mono">{{ s.trades }}</td>
                        <td class="n mono">{{ s.quantity }}</td>
                        <td class="n mono">{{ money(s.notional) }}</td>
                        <td class="mono muted">{{ clock(s.firstMs) }} → {{ clock(s.lastMs) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </td></tr>
            }
          }
        </tbody>
      </table>
    }

    <sec-head [s]="tradesSec" label="Executions" />
    @if (tradesSec.open() && shownTrades().length) {
      <sec-pager [s]="tradesSec" />
      <table class="trades">
        <thead><tr><th>Tape day</th><th>Booked</th><th>Symbol</th><th>Side</th>
                   <th class="n">Qty</th><th class="n">Price</th><th>Account</th><th>Trade</th></tr></thead>
        <tbody>
          @for (t of tradesSec.view(); track t.tradeId ?? t.ms) {
            <tr [class.assumed]="t.assumed">
              <td class="mono">{{ t.tapeDate }}</td>
              <td class="mono muted">{{ clock(t.ms) }}</td>
              <td class="mono">{{ t.security }}</td>
              <td>{{ t.side }}</td>
              <td class="n mono">{{ t.quantity }}</td>
              <td class="n mono">{{ t.price }}</td>
              <td class="mono muted">{{ t.accountId }}</td>
              <td class="mono muted">{{ t.tradeId ?? '—' }}</td>
            </tr>
          }
        </tbody>
      </table>
    }

    <!-- q over THIS session's own rows. Same evaluator the kdb panel uses, so a statement it
         cannot really run names itself as unsupported instead of returning a plausible number.
         The tables are what this view is showing: bound at the last reset, like everything above. -->
    <sec-head [s]="qSec" label="Query this session (q)" />
    @if (qSec.open()) {
      <textarea class="qin" rows="2" spellcheck="false"
        [ngModel]="freeQ()" (ngModelChange)="freeQ.set($event)"></textarea>
      <div class="qbar">
        <button type="button" (click)="runFree()">Run</button>
        @for (e of examples; track e.label) {
          <button type="button" (click)="freeQ.set(e.q); runFree()">{{ e.label }}</button>
        }
        <span class="muted mono">txExec · txDays</span>
      </div>
      @if (qErr()) { <p class="err">{{ qErr() }}</p> }
      @if (qOut(); as o) {
        <table>
          <thead><tr>@for (c of o.columns; track c) { <th>{{ c }}</th> }</tr></thead>
          <tbody>
            @for (r of o.rows; track $index) {
              <tr>@for (v of r; track $index) { <td class="mono">{{ v }}</td> }</tr>
            } @empty { <tr><td [attr.colspan]="o.columns.length" class="muted">no rows</td></tr> }
          </tbody>
        </table>
        <div class="muted">{{ o.rows.length }} row{{ o.rows.length === 1 ? '' : 's' }}</div>
      }
    }
  `,
  styles: `
    .qin { width: 100%; font-family: var(--mono, ui-monospace, monospace); font-size: 12px; }
    .qbar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin: 5px 0; }
    .head { display: flex; align-items: baseline; gap: 10px; }
    .pill { font-size: 11px; letter-spacing: .06em; padding: 2px 8px; border-radius: 999px;
            border: 1px solid var(--line); }
    .pill.good { color: var(--good); border-color: var(--good); }
    table { width: 100%; border-collapse: collapse; margin: 10px 0; }
    th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--line); }
    th.n, td.n { text-align: right; }
    tr.sel > td { background: var(--sel, rgba(127,127,127,.10)); }
    tr.detail > td { padding: 0 0 8px 18px; border-bottom: none; }
    .syms { margin: 0; }
    .mono { font-variant-numeric: tabular-nums; font-family: var(--mono, ui-monospace, monospace); }
    .muted { color: var(--muted); }
    .err { color: var(--bad); }
    .link { background: none; border: none; color: var(--link, inherit); cursor: pointer;
            text-decoration: underline; padding: 0; font: inherit; }
    .notes { margin: 8px 0; padding-left: 18px; color: var(--muted); }
    tr.assumed td { opacity: .72; }
  `,
})
export class SandboxResultsPanel implements OnDestroy {
  private readonly api = inject(Api);

  readonly results = signal<Results | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly selected = signal('');

  readonly days = computed(() => this.results()?.days ?? []);
  readonly trades = computed(() => this.results()?.trades ?? []);
  readonly totalTrades = computed(() => this.days().reduce((a, d) => a + d.trades, 0));
  // Selecting a day filters the executions too, so "only that day's info" is one click, not a
  // second control the operator has to remember to move as well.
  readonly shownTrades = computed(() => {
    const d = this.selected();
    return d ? this.trades().filter((t) => t.tapeDate === d) : this.trades();
  });

  /** Every caveat the server reported, said plainly. Empty when there is nothing to qualify. */
  readonly notes = computed(() => {
    const r = this.results();
    if (!r) { return [] as string[]; }
    const out: string[] = [];
    // Say which session this is. Without it an empty view reads as "nothing ever traded here"
    // rather than "nothing since you reset", and those send you to different places.
    if (r.lastResetSeq > 0) {
      out.push(`Showing this session only — everything after the reset at sequence ${r.lastResetSeq}. `
        + 'Earlier sessions stay in the engine\'s audit log; a reset clears the book, not the log.');
    }
    if (r.assumedSpan) {
      out.push('Some of this session was replayed before the current replay driver started. '
        + 'Those rows are dated from the origin it inherited rather than one it watched, and are dimmed.');
    }
    if (r.unattributed) {
      out.push(`${r.unattributed} record(s) fall outside every replayed span — booked while the tape `
        + 'was paused, or by hand. They are counted here and excluded from the days above.');
    }
    if (r.segmentsDropped) {
      out.push('The replay driver dropped its oldest journal segments, so the earliest part of this '
        + 'session can no longer be dated.');
    }
    return out;
  });

  // Collapse + 10-per-page with the ‹ / › pager, the same Section the blotter and ticket use.
  readonly daysSec = new Section(this.days, (d) => d.date);
  readonly tradesSec = new Section(this.shownTrades, (t) => String(t.tradeId ?? t.ms));
  readonly qSec = new Section<unknown>(signal([]), () => '');

  readonly freeQ = signal('select trades:count i, qty:sum quantity by security from txExec');
  readonly qOut = signal<QResult | null>(null);
  readonly qErr = signal('');
  readonly examples = [
    { label: 'by symbol', q: 'select trades:count i, qty:sum quantity, vwap:(sum price*quantity)%sum quantity by security from txExec' },
    { label: 'by day', q: 'select trades:sum trades, notional:sum notional by date from txDays' },
    { label: 'buys only', q: 'select trades:count i, qty:sum quantity by security from txExec where side="Buy"' },
  ];

  runFree(): void {
    try {
      this.qErr.set('');
      this.qOut.set(runQ(this.freeQ(), {
        txExec: this.trades() as unknown as Record<string, string | number>[],
        // The per-day aggregates, without their nested symbol lists — runQ works over flat rows.
        txDays: this.days().map((d) => ({ date: d.date, trades: d.trades, rejected: d.rejected,
          canceled: d.canceled, notional: d.notional, symbols: d.symbols.length })),
      }));
    } catch (e) {
      this.qOut.set(null);
      this.qErr.set(e instanceof Error ? e.message : String(e));
    }
  }

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    void this.refresh();
    this.timer = setInterval(() => void this.refresh(), 10_000);
  }

  ngOnDestroy(): void { if (this.timer) { clearInterval(this.timer); } }

  select(date: string): void { this.selected.set(this.selected() === date ? '' : date); }

  money(n: number): string {
    return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  /** Wall clock of the booking, which is what the blotter carries. Not tape time. */
  clock(ms: number): string {
    return new Date(ms).toLocaleTimeString(undefined, { hour12: false });
  }

  async refresh(): Promise<void> {
    this.busy.set(true);
    const r = await this.api.load<Results>('/sandbox/results');
    this.busy.set(false);
    this.loaded.set(true);
    if (r.status === 401) { this.error.set('Sign in to read the sandbox session.'); return; }
    if (r.status !== 200 || !r.body) {
      const b = r.body as Results | null;
      // Report what the server said. "The sandbox engine did not return its report (…)" sends the
      // reader to the right pod; a generic failure sends them to all four.
      this.error.set(b?.error ? `${b.error}${b.cause ? ` — ${b.cause}` : ''}` : `results unavailable (${r.status})`);
      return;
    }
    this.error.set(null);
    this.results.set(r.body);
  }
}
