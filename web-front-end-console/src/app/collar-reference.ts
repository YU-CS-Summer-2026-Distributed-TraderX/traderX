import { Component, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';
import { SecPager, Section } from './section';

/** Field positions in a `txtrade` capture row; see capturedPrints() for why they are positional. */
const SYM_AT = 4;
const TRADE_FIELDS = 9;

/**
 * Which securities the capture WITNESSED printing, and how many rows that was.
 *
 * Rows are read POSITIONALLY, against KdbTapWriter's own header — the same convention
 * `kdb-panel.ts` already uses on this bridge:
 *
 *     txtrade: seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs
 *
 * <p><b>NOT by looking `sym` up in line 0.</b> The bridge hands back `tail -n 300` per file, so the
 * moment a capture passes 300 lines its header scrolls out of the window and line 0 is a data row.
 * `indexOf('sym')` then returns -1 and the guard on it drops EVERY row without a word.
 *
 * <p>Measured on the rig 2026-08-26, the first time trade volume was high enough to reach it: with
 * ADR-072's replayed flow live, this panel reported **0 of 68 books printed against 2,476 booked
 * trades** and rendered all 68 as never-printed — the wrong one of the two states it exists to
 * separate, for every row at once. It looked like a quiet venue rather than a broken parser, which
 * is why a header-lookup survives: its failure mode is a confident empty answer.
 */
export function capturedPrints(members: { capture: string }[]): { symbols: Set<string>; rows: number } {
  const symbols = new Set<string>();
  let rows = 0;
  for (const m of members) {
    for (const part of (m.capture || '').split(/==FILE\s+/).filter(x => x.trim())) {
      const nl = part.indexOf('\n');
      const path = (nl < 0 ? part : part.slice(0, nl)).trim().split(/\s+/)[0] || '';
      if (path.indexOf('txtrade') < 0) { continue; }
      const lines = (nl < 0 ? '' : part.slice(nl + 1)).split('\n').map(l => l.trim()).filter(Boolean);
      for (const line of lines) {
        if (line.startsWith('seq,')) { continue; }   // the header, when it is still in the window
        const cells = line.split(',');
        if (cells.length >= TRADE_FIELDS && cells[SYM_AT]) { symbols.add(cells[SYM_AT]); rows++; }
      }
    }
  }
  return { symbols, rows };
}

export interface Book { ticker: string; mark: number; ref: number; tickPx?: number; }
interface Bbo { member: number; applied: number; books: Book[]; }

export interface Row extends Book { hasPrinted: boolean; gapPct: number | null; offReference: boolean; }

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
      <div class="sub note" [class.warn]="!captureAgrees()">{{ printedLine() }}</div>

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
                <!-- "seen", not "here", once the capture stops accounting for every trade: the
                     panel then knows only that it did not WITNESS a print, which is a different
                     claim and the one it can actually support. -->
                @if (!r.hasPrinted) {
                  <span class="pill">{{ captureAgrees() ? 'no print here' : 'no print seen' }}</span>
                }
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
    .note.warn { color: var(--warn); }
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
    const { symbols, rows } = capturedPrints(cap.value.members ?? []);
    this.printed.set(symbols);
    this.capturedTrades.set(rows);
  }

  readonly capturedTrades = signal<number | null>(null);

  /** The capture is a tail; when its row count matches the engine's counter it saw everything. */
  readonly captureAgrees = computed(() =>
    this.engineTrades() !== null && this.capturedTrades() === this.engineTrades());

  readonly printedLine = computed(() =>
    printedSummary(this.rows().length, this.printed().size, this.capturedTrades(), this.engineTrades()));

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

  readonly rows = computed<Row[]>(() => rankBooks(this.bbo()?.books ?? [], this.printed()));

  readonly sec = new Section<Row>(this.rows, r => r.ticker);
}

/**
 * How many books have printed — and, when that number is only a floor, SAYING SO.
   *
 * <p><b>This used to fail by omission and that was not good enough.</b> The reconciliation was a
 * clause appended when the capture accounted for every trade and dropped when it did not, so a
 * reader learned the count was untrustworthy by noticing an absent phrase. Nobody notices an
 * absent phrase.
   *
 * <p>It stopped being hypothetical the moment ADR-072's replayed order flow went live: the
 * capture is `tail -n 300` per member, and the leader's file measured 1,625 lines against 1,502
 * booked trades — **the panel could see 18% of them**. Every book whose prints fell outside that
 * window renders as never-printed, which is the WRONG one of the two states this panel exists to
 * separate, and it sorts such a book to the bottom of the list precisely because it looks
 * uninteresting. A printed-and-drifted book — the only row a collar is about — can hide there.
   *
 * <p>So the shortfall is now stated in numbers, and the row pill weakens from "no print here" to
 * "no print seen". Neither is a fix: recovering the truth needs a durable per-security trade
 * source, and the read model is per-account. This makes the panel's uncertainty legible instead
 * of invisible, which is the honest thing a view can do about a limit it cannot lift.
 */
export function printedSummary(
  books: number, printedN: number, seen: number | null, engine: number | null): string {
  if (engine !== null && seen === engine) {
    return `${printedN} of ${books} books have printed here `
      + `(${engine} trades booked cluster-wide, which the capture accounts for).`;
  }
  if (engine === null || seen === null) {
    return `${printedN} of ${books} books seen printing here — the trade capture could not be `
      + 'reconciled, so this is a floor.';
  }
  return `At least ${printedN} of ${books} books have printed here — the capture holds ${seen} of `
    + `${engine} booked trades, so this is a floor and a book can read as never-printed when its `
    + 'prints have scrolled out.';
}

/**
 * The row order, and the reason paging is safe.
 *
 * Printed-and-drifted first, because that is the only row a collar is actually about. **Every
 * printed row outranks every never-printed one**, unconditionally — that is what makes showing
 * fifteen of a hundred and forty-five honest rather than a truncation: the rows a reader would act
 * on cannot be pushed off page one by a never-printed row with a bigger number, and there are many
 * of those. Exported so that invariant has a test rather than a comment.
 *
 * Within each group it is gap MAGNITUDE, not sign. A mark far below the reference is the same
 * signal as far above, and the live example is below: nothing has printed TSLA here, so its seed
 * sits under a tape that has moved on.
 */
export function rankBooks(books: Book[], printed: Set<string>): Row[] {
  return books
    .map(b => {
      const gapPct = b.ref ? ((b.mark - b.ref) / b.ref) * 100 : null;
      return { ...b, hasPrinted: printed.has(b.ticker), gapPct, offReference: Math.abs(gapPct ?? 0) >= 10 };
    })
    .sort((a, b) => {
      const rank = (x: Row) => (x.hasPrinted ? 1e6 : 0) + Math.abs(x.gapPct ?? 0);
      return rank(b) - rank(a);
    });
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
