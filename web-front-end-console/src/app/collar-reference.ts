import { Component, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';
import { SecPager, Section } from './section';

interface Book { ticker: string; mark: number; ref: number; tickPx?: number; }
interface Bbo { member: number; applied: number; books: Book[]; }

interface Row extends Book { hasPrinted: boolean; gapPct: number | null; offReference: boolean; }

/**
 * The collar's reference against this venue's own mark, per security.
 *
 * `ref` is exogenous — the wider market, now a real 2025 tape for the replayed names. `mark` is the
 * last price PRINTED HERE. The gap between them is what a collar exists to notice.
 *
 * <p><b>Two different rows look identical and mean opposite things, and this view refuses to render
 * them the same.</b> A security that has never traded on this rig still has a `mark`: its opening
 * seed. So a huge divergence is usually "nothing has printed here yet, and the market is elsewhere"
 * — unremarkable — rather than "our last print is far from the market", which is the one worth
 * acting on. Measured while building this: the cluster had booked SIX trades in total against
 * ~60 books, so almost every large divergence on screen was the first kind.
 *
 * <p>Which securities have actually printed is taken from the tick capture, and that is stated
 * rather than assumed: the tap is a bounded, non-durable tail, so absence from it is evidence and
 * not proof. The engine's own trade counter is shown beside it as the check — when the two agree,
 * the capture saw everything.
 *
 * <p><b>Paged, and collapsible</b> — a correctness property here, not a cosmetic one. The
 * interesting rows are the handful that have printed AND drifted; they sort to the top, and
 * rendering sixty-odd never-printed rows underneath them buried the signal in the noise it was
 * sorted away from. That gets worse as the replayed universe grows.
 */
@Component({
  selector: 'collar-reference',
  imports: [HelpTip, SecPager],
  template: `
    <div class="card-head">
      <h2>
        <button type="button" class="tog" (click)="sec.toggle()">
          <span class="arrow">{{ sec.open() ? '▾' : '▸' }}</span>Collar reference — last print here
          against the market reference
        </button>
      </h2>
      <help-tip text="Per security, the collar's exogenous reference against the last price printed on this venue. A security that has never traded here still shows a mark — its opening seed — so a large gap usually means nothing has printed yet rather than a print that has drifted. The two are separated below, because only the second is a signal." />
      <span class="spacer"></span>
      @if (bbo(); as b) { <span class="pill">member {{ b.member }} · applied {{ b.applied }}</span> }
    </div>

    <!-- The provenance line stays visible collapsed: WHEN the reference is from is the first thing
         a reader needs, and it is wrong to make them open a table to learn it. -->
    <div class="sub note">{{ asOfLine() }}</div>

    @if (error(); as e) { <div class="sub note">{{ e }}</div> }

    @if (sec.open() && bbo()) {
      <div class="sub note">
        {{ printed().size }} of {{ rows().length }} books have printed here
        ({{ engineTrades() ?? '—' }} trades booked cluster-wide{{ captureAgrees() ? ', which the capture accounts for' : '' }}).
      </div>

      <table>
        <thead><tr>
          <th>security</th><th class="num">mark</th><th class="num">reference</th>
          <th class="num">gap</th><th>state</th>
        </tr></thead>
        <tbody>
          @for (r of sec.view(); track r.ticker) {
            <tr>
              <td>{{ r.ticker }}</td>
              <td class="num">{{ r.mark.toFixed(r.mark < 2 ? 6 : 3) }}</td>
              <td class="num">{{ r.ref.toFixed(r.ref < 2 ? 6 : 3) }}</td>
              <td class="num" [class.bad]="r.hasPrinted && r.offReference">
                {{ r.gapPct === null ? '—' : (r.gapPct > 0 ? '+' : '') + r.gapPct.toFixed(1) + '%' }}</td>
              <td>
                @if (!r.hasPrinted) { <span class="pill">no print here</span> }
                @else if (r.offReference) { <span class="pill warn">printed, off reference</span> }
                @else { <span class="pill good">printed</span> }
              </td>
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
    .tog { background: none; border: none; padding: 0; font: inherit; color: inherit;
           display: flex; align-items: center; gap: 6px; cursor: pointer; text-align: left; }
    .arrow { display: inline-block; width: 13px; line-height: 1; color: var(--muted); }
  `,
})
export class CollarReference {
  private api = inject(Api);
  readonly bbo = signal<Bbo | null>(null);
  readonly error = signal('');
  readonly printed = signal<Set<string>>(new Set());
  readonly engineTrades = signal<number | null>(null);
  /** The publisher's replay position, for the as-of line. Read, never derived here. */
  private readonly replay = signal<{ tapeDate?: string; asOf?: string; held?: boolean } | null>(null);
  private readonly source = signal<string | null>(null);

  constructor() {
    // The rows that matter sort to the top, so one page of fifteen is the whole signal.
    this.sec.setPerPage(15);
    void this.load();
    setInterval(() => void this.load(), 10000);
  }

  private async load(): Promise<void> {
    const r = await this.api.fetchJson<Bbo>('/m0/bbo');
    if (!r.ok) { this.error.set(`Cannot read the book: ${r.error}`); return; }
    this.error.set('');
    this.bbo.set(r.value);

    // Which securities have actually printed, and the counter that says whether that list is whole.
    const h = await this.api.fetchJson<{ trades?: number }>('/m0/health');
    this.engineTrades.set(h.ok ? (h.value.trades ?? null) : null);

    // WHEN the reference is from. Taken from the publisher's own position, the single place the
    // replay clock is derived — never differenced against this machine's clock.
    const ph = await this.api.fetchJson<{
      taqReplay?: { source?: string; position?: { tapeDate?: string; asOf?: string; held?: boolean } };
    }>('/price-publisher/health');
    this.replay.set(ph.ok ? (ph.value.taqReplay?.position ?? null) : null);
    this.source.set(ph.ok ? (ph.value.taqReplay?.source ?? null) : null);

    const cap = await this.api.fetchJson<{ members: { capture: string }[] }>('/kdbtap');
    if (!cap.ok) { return; }
    const syms = new Set<string>();
    let rows = 0;
    for (const m of cap.value.members ?? []) {
      for (const part of (m.capture || '').split(/==FILE\s+/).filter(x => x.trim())) {
        const nl = part.indexOf('\n');
        const path = (nl < 0 ? part : part.slice(0, nl)).trim().split(/\s+/)[0] || '';
        if (path.indexOf('txtrade') < 0) { continue; }
        const lines = (nl < 0 ? '' : part.slice(nl + 1)).split('\n').map(l => l.trim()).filter(Boolean);
        if (!lines.length) { continue; }
        const cols = lines[0].split(',');
        const symAt = cols.indexOf('sym');
        for (const line of lines.slice(1)) {
          const cells = line.split(',');
          if (symAt >= 0 && cells[symAt]) { syms.add(cells[symAt]); rows++; }
        }
      }
    }
    this.printed.set(syms);
    this.capturedTrades.set(rows);
  }

  private readonly capturedTrades = signal<number | null>(null);

  /** The capture is a tail; when its row count matches the engine's counter it saw everything. */
  readonly captureAgrees = computed(() =>
    this.engineTrades() !== null && this.capturedTrades() === this.engineTrades());

  /**
   * When the reference is from — the question the old header did not answer at all.
   *
   * Deliberately does NOT claim a count. The rig publishes five provenances at once and only the
   * replayed equities carry a tape timestamp; treasuries, options and the carried-forward closes
   * are on this table too and are as of now. Any single number here would have to be one or the
   * other, and would be read as covering both. The per-row chips on the trading pages are where a
   * reader gets provenance per security.
   */
  readonly asOfLine = computed(() => {
    const pos = this.replay();
    if (!pos?.asOf) {
      return 'Reference as published now. No tape is loaded.';
    }
    return `Tape reference as of ${etStamp(pos.asOf)}${pos.held ? ', HELD at the last close' : ''}`
      + ` (${this.source() ?? 'taq replay'}) for the replayed equities;`
      + ' every other class carries its own source, as of now.';
  });

  readonly rows = computed<Row[]>(() => {
    const books = this.bbo()?.books ?? [];
    const printed = this.printed();
    return books
      .map(b => {
        const gapPct = b.ref ? ((b.mark - b.ref) / b.ref) * 100 : null;
        // Magnitude, not sign. A mark far BELOW the reference is the same signal as far above, and
        // the live example is below: nothing has printed TSLA here, so its seed sits under a tape
        // that has moved on.
        return { ...b, hasPrinted: printed.has(b.ticker), gapPct, offReference: Math.abs(gapPct ?? 0) >= 10 };
      })
      // Printed-and-drifted first: that is the only row a collar is actually about.
      .sort((a, b) => {
        const rank = (x: { hasPrinted: boolean; gapPct: number | null }) =>
          x.hasPrinted ? Math.abs(x.gapPct ?? 0) + 1e6 : Math.abs(x.gapPct ?? 0);
        return rank(b) - rank(a);
      });
  });

  readonly sec = new Section<Row>(this.rows, r => r.ticker);
}

/** `2025-02-06 13:11 ET` — formatting only. US equity tape times mean nothing in another zone. */
function etStamp(iso: string): string {
  const d = new Date(iso);
  if (isNaN(d.getTime())) { return iso; }
  const opts: Intl.DateTimeFormatOptions = {
    timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  };
  const p = new Intl.DateTimeFormat('en-CA', opts).formatToParts(d);
  const get = (k: string) => p.find(x => x.type === k)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')} ET`;
}
