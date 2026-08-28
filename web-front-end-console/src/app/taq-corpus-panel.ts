import { Component, computed, inject, signal, OnDestroy } from '@angular/core';
import { Api } from './api';

interface Meta {
  source: string; windowSeconds: number; sessionSeconds: number; compression: number;
  windowsPerDay: number; days: string[]; symbols: string[]; positions: number;
}
interface SliceRow { ticker: string; price: number; open: number; changePct: number; }
interface Slice {
  pos: number; dayIndex: number; windowIndex: number; date: string; atMs: number;
  windowsPerDay: number; rows: SliceRow[];
}
interface Series { ticker: string; days: string[]; series: number[][]; }

/**
 * The TAQ corpus as DATA — every symbol, every day, no replay involved.
 *
 * <p><b>Nothing here starts, moves or reads a replay.</b> The corpus is a file; this is a view of
 * the file. That separation is the point of the tab: "what is in the tape" is answerable without
 * first playing any of it, and looking at it cannot perturb the sandbox or the live venue.
 *
 * <p><b>The slider addresses the corpus, not a clock.</b> A position is (day × windows/day +
 * window) over the whole corpus, and the server refuses one that is out of range rather than
 * clamping — a clamped slider reports the last window for everything past the end, which reads as a
 * tape that stopped rather than a request that was wrong.
 *
 * <p><b>Displayed behind sign-in.</b> ADR-068's open question 1: the corpus may be shown on the
 * educational basis and only to a signed-in operator. The server enforces that on every tape
 * surface; this panel is simply one of its consumers.
 */
@Component({
  selector: 'taq-corpus-panel',
  template: `
    <header class="head">
      <h2>Tape corpus</h2>
      @if (meta(); as m) {
        <span class="muted mono">{{ m.source }} · {{ m.symbols.length }} symbols · {{ m.days.length }} days</span>
      }
    </header>

    @if (error(); as e) { <p class="err">{{ e }}</p> }

    @if (meta(); as m) {
      <div class="scrub">
        <input type="range" min="0" [max]="m.positions - 1" [value]="pos()"
               (input)="scrub(+$any($event.target).value)" aria-label="Position in the corpus" />
        <div class="readout mono">
          <strong>{{ slice()?.date ?? '—' }}</strong>
          <span class="muted">window {{ slice()?.windowIndex ?? '—' }} of {{ m.windowsPerDay }}</span>
          <span>{{ slice() ? clock(slice()!.atMs) : '—' }}</span>
        </div>
      </div>

      <div class="jump">
        <label>Day
          <select [value]="dayIndex()" (change)="jumpDay(+$any($event.target).value)">
            @for (d of m.days; track d; let i = $index) { <option [value]="i">{{ d }}</option> }
          </select>
        </label>
        <label>Chart
          <select [value]="ticker()" (change)="pickTicker($any($event.target).value)">
            <option value="">—</option>
            @for (s of m.symbols; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
      </div>
    }

    @if (series(); as s) {
      <figure class="chart">
        <figcaption class="muted">
          {{ s.ticker }} — every window of the corpus, {{ s.days.length }} sessions end to end.
          <span class="mono">low {{ lo() }} · high {{ hi() }}</span>
        </figcaption>
        <svg viewBox="0 0 1000 160" preserveAspectRatio="none" role="img"
             [attr.aria-label]="s.ticker + ' price across the whole corpus'">
          <polyline [attr.points]="path()" fill="none" stroke="currentColor" stroke-width="1.5" />
          <line [attr.x1]="marker()" y1="0" [attr.x2]="marker()" y2="160"
                stroke="currentColor" stroke-width="1" opacity=".45" />
        </svg>
      </figure>
    }

    @if (slice(); as sl) {
      <table>
        <thead><tr><th>Symbol</th><th class="n">Price</th><th class="n">Day open</th>
                   <th class="n">Change</th></tr></thead>
        <tbody>
          @for (r of sl.rows; track r.ticker) {
            <tr [class.sel]="r.ticker === ticker()">
              <td><button type="button" class="link mono" (click)="pickTicker(r.ticker)">{{ r.ticker }}</button></td>
              <td class="n mono">{{ r.price }}</td>
              <td class="n mono">{{ r.open }}</td>
              <td class="n mono" [class.up]="r.changePct > 0" [class.down]="r.changePct < 0">
                {{ r.changePct > 0 ? '+' : '' }}{{ r.changePct.toFixed(2) }}%
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    .head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
    .scrub { margin: 12px 0 6px; }
    .scrub input { width: 100%; }
    .readout { display: flex; gap: 14px; align-items: baseline; }
    .jump { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 10px; }
    .chart { margin: 10px 0; }
    .chart svg { width: 100%; height: 160px; display: block; border: 1px solid var(--line); }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 3px 8px; border-bottom: 1px solid var(--line); }
    th.n, td.n { text-align: right; }
    tr.sel > td { background: var(--sel, rgba(127,127,127,.10)); }
    .up { color: var(--good); } .down { color: var(--bad); }
    .mono { font-variant-numeric: tabular-nums; font-family: var(--mono, ui-monospace, monospace); }
    .muted { color: var(--muted); }
    .err { color: var(--bad); }
    .link { background: none; border: none; color: inherit; cursor: pointer; padding: 0;
            font: inherit; text-decoration: underline; }
  `,
})
export class TaqCorpusPanel implements OnDestroy {
  private readonly api = inject(Api);

  readonly meta = signal<Meta | null>(null);
  readonly slice = signal<Slice | null>(null);
  readonly series = signal<Series | null>(null);
  readonly error = signal<string | null>(null);
  readonly pos = signal(0);
  readonly ticker = signal('');

  readonly dayIndex = computed(() => this.slice()?.dayIndex ?? 0);

  /** Flattened low/high across the whole corpus for this symbol — the chart's own scale. */
  private readonly flat = computed(() => (this.series()?.series ?? []).flat());
  readonly lo = computed(() => (this.flat().length ? Math.min(...this.flat()) : 0));
  readonly hi = computed(() => (this.flat().length ? Math.max(...this.flat()) : 0));

  readonly path = computed(() => {
    const v = this.flat();
    if (v.length < 2) { return ''; }
    const lo = this.lo(), hi = this.hi();
    const span = hi - lo || 1;
    // Downsample to at most 1000 columns: 4800 points into a 1000-unit viewBox would draw several
    // segments per pixel, which costs time and shows nothing the sampled line does not.
    const step = Math.max(1, Math.floor(v.length / 1000));
    const out: string[] = [];
    for (let i = 0; i < v.length; i += step) {
      const x = (i / (v.length - 1)) * 1000;
      const y = 160 - ((v[i] - lo) / span) * 150 - 5;
      out.push(`${x.toFixed(1)},${y.toFixed(1)}`);
    }
    return out.join(' ');
  });

  /** Where the slider sits on the chart, in the chart's own coordinates. */
  readonly marker = computed(() => {
    const m = this.meta();
    if (!m || m.positions < 2) { return 0; }
    return ((this.pos() / (m.positions - 1)) * 1000).toFixed(1);
  });

  // A drag fires input per frame; without this every frame would be a request. The slice is a
  // slice of a cached extract server-side, so this is about not flooding the socket, not cost.
  private debounce: ReturnType<typeof setTimeout> | null = null;

  constructor() { void this.init(); }

  ngOnDestroy(): void { if (this.debounce) { clearTimeout(this.debounce); } }

  private async init(): Promise<void> {
    const m = await this.api.load<Meta>('/taq/meta');
    if (m.status === 401) { this.error.set('Sign in to view the tape corpus.'); return; }
    if (m.status !== 200 || !m.body) {
      const b = m.body as unknown as { error?: string; cause?: string } | null;
      this.error.set(b?.error ? `${b.error}${b.cause ? ` — ${b.cause}` : ''}` : `the corpus is unreadable (${m.status})`);
      return;
    }
    this.meta.set(m.body);
    await this.fetchSlice(0);
  }

  scrub(pos: number): void {
    this.pos.set(pos);
    if (this.debounce) { clearTimeout(this.debounce); }
    this.debounce = setTimeout(() => void this.fetchSlice(pos), 120);
  }

  jumpDay(dayIndex: number): void {
    const m = this.meta();
    if (!m) { return; }
    this.scrub(dayIndex * m.windowsPerDay);
  }

  async pickTicker(t: string): Promise<void> {
    this.ticker.set(t);
    if (!t) { this.series.set(null); return; }
    const r = await this.api.load<Series>(`/taq/series?ticker=${encodeURIComponent(t)}`);
    if (r.status !== 200 || !r.body) {
      const b = r.body as unknown as { error?: string } | null;
      this.error.set(b?.error ?? `no series for ${t} (${r.status})`);
      this.series.set(null);
      return;
    }
    this.error.set(null);
    this.series.set(r.body);
  }

  private async fetchSlice(pos: number): Promise<void> {
    const r = await this.api.load<Slice>(`/taq/slice?pos=${pos}`);
    if (r.status !== 200 || !r.body) {
      const b = r.body as unknown as { error?: string } | null;
      this.error.set(b?.error ?? `no data at position ${pos} (${r.status})`);
      return;
    }
    this.error.set(null);
    this.slice.set(r.body);
  }

  clock(ms: number): string {
    return new Date(ms).toLocaleString(undefined, { hour12: false, timeZone: 'UTC' }) + ' UTC';
  }
}
