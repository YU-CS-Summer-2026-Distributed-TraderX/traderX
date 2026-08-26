import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { HelpTip } from './help';
import { SecPager, Section } from './section';

/** `/taq-tape` — the replay extract summarised to one [open, close] pair per symbol per day. */
interface Tape {
  source: string;
  windowSeconds: number;
  sessionSeconds: number;
  compression: number;
  days: { date: string; openMs: number }[];
  /** ticker -> per-day [open, close], parallel to `days`. */
  symbols: Record<string, [number, number][]>;
}

interface Row {
  ticker: string;
  open: number;
  close: number;
  changePct: number;
  /** Prior day's close into this window's open. Null on day 1 — there is no prior session. */
  gapPct: number | null;
}

/**
 * The tape's real opens, closes and overnight gaps, for any day or span of days.
 *
 * This is the half of ADR-070 that the live clock cannot show. The clock says where the tape is
 * now; the discontinuity ADR-069's rule is written against — Feb 3's close to Feb 4's open — is
 * only visible by putting two sessions side by side. Nobody invented that gap, which is the whole
 * argument, and an argument you cannot point at is a claim.
 *
 * <p><b>No new pipeline, and deliberately so.</b> Every number here is already in the extract the
 * publisher is replaying: open is window 0 of a day, close is its last window. The panel reads the
 * same file the publisher has mounted rather than the bucket copy, because the Secret is fetched
 * once at bring-up and a rebuilt bucket object can be a universe ahead of what is actually on the
 * wire. A day view that disagrees with the clock above it would be worse than no day view.
 *
 * <p>Gaps are computed from the extract's own consecutive days, so a weekend or a holiday is one
 * gap and not three — the tape carries trading days only, exactly as the clock replays them.
 */
@Component({
  selector: 'replay-tape',
  imports: [FormsModule, HelpTip, SecPager],
  template: `
    <div class="card-head">
      <h2>Tape by day — opens, closes and overnight gaps</h2>
      <help-tip text="Real opens and closes from the recorded 2025 sessions, and the gap between one day's close and the next day's open. Pick one day, or a span. Everything shown is already in the replay extract the publisher is reading — the same file, not a second copy." />
      <span class="spacer"></span>
      @if (tape(); as t) {
        <span class="pill">{{ t.days.length }} trading days · {{ symbolCount() }} symbols</span>
      }
    </div>

    @if (error(); as e) {
      <div class="sub note">{{ e }}</div>
    } @else if (!tape()) {
      <div class="sub note">reading the tape…</div>
    } @else {
      <div class="controls">
        <label>from
          <select [ngModel]="fromIdx()" (ngModelChange)="setFrom(+$event)">
            @for (d of tape()!.days; track d.date; let i = $index) {
              <option [value]="i">{{ d.date }}</option>
            }
          </select>
        </label>
        <label>to
          <select [ngModel]="toIdx()" (ngModelChange)="setTo(+$event)">
            @for (d of tape()!.days; track d.date; let i = $index) {
              <option [value]="i">{{ d.date }}</option>
            }
          </select>
        </label>
        <button type="button" (click)="setTo(fromIdx())" [disabled]="fromIdx() === toIdx()">single day</button>
        <button type="button" (click)="wholeTape()">whole tape</button>
        <label>symbol
          <input [ngModel]="filter()" (ngModelChange)="setFilter($event)" placeholder="all"
            spellcheck="false" size="8"></label>
      </div>

      <div class="sub note">{{ span() }}</div>

      <table>
        <thead><tr>
          <th>security</th>
          <th class="num">open {{ tape()!.days[fromIdx()].date }}</th>
          <th class="num">close {{ tape()!.days[toIdx()].date }}</th>
          <th class="num">change</th>
          <th class="num">overnight gap</th>
        </tr></thead>
        <tbody>
          @for (r of sec.view(); track r.ticker) {
            <tr>
              <td>{{ r.ticker }}</td>
              <td class="num">{{ px(r.open) }}</td>
              <td class="num">{{ px(r.close) }}</td>
              <td class="num" [class.bad]="r.changePct < 0" [class.good]="r.changePct > 0">
                {{ pct(r.changePct) }}</td>
              <td class="num" [class.bad]="(r.gapPct ?? 0) < 0" [class.good]="(r.gapPct ?? 0) > 0">
                {{ r.gapPct === null ? '—' : pct(r.gapPct) }}</td>
            </tr>
          }
        </tbody>
      </table>
      <sec-pager [s]="sec" />
    }
  `,
  styles: `
    .spacer { flex: 1; }
    .note { max-width: 720px; margin-bottom: 8px; }
    .controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
    .controls label { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px;
                      color: var(--muted); }
  `,
})
export class ReplayTape {
  private api = inject(Api);
  readonly tape = signal<Tape | null>(null);
  readonly error = signal('');
  readonly fromIdx = signal(0);
  readonly toIdx = signal(0);
  readonly filter = signal('');

  constructor() {
    // 100 symbols at the default 10 a page is ten pages; 25 is one screen and four.
    this.sec.setPerPage(25);
    void this.load();
  }

  private async load(): Promise<void> {
    const r = await this.api.fetchJson<Tape>('/taq-tape');
    if (!r.ok) {
      this.error.set(`Cannot read the replay extract: ${r.error}`);
      return;
    }
    this.tape.set(r.value);
    // Open on the day the tape is actually on, read from the publisher's /health — the one place
    // the position is derived. Falls back to the last day if the clock is unreadable, which is the
    // honest default when nothing can say where the tape is.
    const h = await this.api.fetchJson<{ taqReplay?: { position?: { dayIndex?: number } } }>(
      '/price-publisher/health');
    const last = r.value.days.length - 1;
    const at = h.ok ? h.value.taqReplay?.position?.dayIndex : undefined;
    const day = Math.min(last, Math.max(0, Number.isFinite(at) ? (at as number) : last));
    this.fromIdx.set(day);
    this.toIdx.set(day);
  }

  /** The pickers cannot express a backwards span: moving one end past the other drags the other. */
  setFrom(i: number): void {
    this.fromIdx.set(i);
    if (this.toIdx() < i) { this.toIdx.set(i); }
    this.sec.page.set(0);
  }
  setTo(i: number): void {
    this.toIdx.set(i);
    if (this.fromIdx() > i) { this.fromIdx.set(i); }
    this.sec.page.set(0);
  }
  wholeTape(): void {
    this.fromIdx.set(0);
    this.toIdx.set((this.tape()?.days.length ?? 1) - 1);
    this.sec.page.set(0);
  }
  setFilter(v: string): void {
    this.filter.set(v);
    this.sec.page.set(0);
  }

  readonly symbolCount = computed(() => Object.keys(this.tape()?.symbols ?? {}).length);

  readonly span = computed(() => {
    const t = this.tape();
    if (!t) { return ''; }
    const n = this.toIdx() - this.fromIdx() + 1;
    const one = n === 1;
    return `${one ? t.days[this.fromIdx()].date : `${t.days[this.fromIdx()].date} → ${t.days[this.toIdx()].date}`}`
      + ` · ${n} session${one ? '' : 's'} · open is the first ${t.windowSeconds}s window of the day,`
      + ` close the last · gap is the prior session's close into ${t.days[this.fromIdx()].date}'s open`;
  });

  readonly rows = computed<Row[]>(() => {
    const t = this.tape();
    if (!t) { return []; }
    const from = this.fromIdx();
    const to = this.toIdx();
    const q = this.filter().trim().toUpperCase();
    const out: Row[] = [];
    for (const [ticker, series] of Object.entries(t.symbols)) {
      if (q && !ticker.includes(q)) { continue; }
      const open = series[from]?.[0];
      const close = series[to]?.[1];
      if (!Number.isFinite(open) || !Number.isFinite(close)) { continue; }
      const prevClose = from > 0 ? series[from - 1]?.[1] : undefined;
      out.push({
        ticker,
        open,
        close,
        changePct: ((close - open) / open) * 100,
        gapPct: Number.isFinite(prevClose) ? ((open - prevClose!) / prevClose!) * 100 : null,
      });
    }
    // Biggest movers over the chosen span first. Alphabetical would bury the whole point of the
    // panel on page 1 of 10.
    return out.sort((a, b) => Math.abs(b.changePct) - Math.abs(a.changePct));
  });

  readonly sec = new Section<Row>(this.rows, r => r.ticker);

  px(v: number): string { return v.toFixed(v < 2 ? 6 : 2); }
  pct(v: number): string { return `${v > 0 ? '+' : ''}${v.toFixed(2)}%`; }
}
