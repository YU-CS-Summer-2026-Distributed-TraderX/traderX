import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, bridgeError } from './api';
import { HelpTip } from './help';
import { SecHead, SecPager, Section } from './section';
import { QResult, runQ } from './qeval';

// Capture rows, positional against KdbTapWriter's own headers:
//   txtrade: seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs
//   txorder: seq,epoch,ref,account,sym,side,qty,remaining,limitPx,status,lastExecPx,lastFillQty,
//            createdMs,updatedMs
interface CapTrade { seq: number; epoch: number; tradeSeq: number; account: number; sym: string; side: string; qty: number; px: number; tsMs: number; }
interface CapOrder {
  seq: number; epoch: number; ref: number; account: number; sym: string; side: string;
  qty: number; remaining: number; limitPx: number; status: string; updatedMs: number;
}
interface MemberCap { member: number; files: { name: string; rows: number }[]; trades: CapTrade[]; orders: CapOrder[]; }

/** Final state per order, keyed (epoch, ref) — see the q comment quoted in the panel. */
interface OrderState {
  key: string; epoch: number; ref: number; sym: string; account: number; side: string;
  qty: number; remaining: number; filled: number; status: string; events: number; updatedMs: number;
}

interface Gap { from: number; to: number; missing: number; }

@Component({
  selector: 'kdb-panel',
  imports: [FormsModule, HelpTip, SecHead, SecPager],
  template: `
    <div class="card-head">
      <h2>KDB-X capture tap</h2>
      <help-tip text="The analytical path: on each output event the cluster leader appends a row to a tickerplant capture log on its own volume — off the consensus path, non-blocking, dropping visibly under flood rather than silently thinning. These are the files q loads directly (tick-store/kdb/txstore.q). The consensus journal stays the authoritative record; this tap costs analytics, never correctness. Rows are epoch-qualified and carry the member id, so all members' captures load together without collision. Every view below is the console computing exactly what the matching q function computes, with that function's source shown beside it." />
      <span class="spacer"></span>
      <label class="qtog">
        <input type="checkbox" [ngModel]="showQ()" (ngModelChange)="showQ.set($event)"> show q source
      </label>
      <span class="faint">KDB_TAP_DIR=/data/kdb-capture · the bridge tails the last 300 rows per file</span>
    </div>

    @if (error()) { <div class="banner bad">{{ error() }}</div> }

    <div class="tiles">
      <!-- These count PARSED rows, and the bridge hands back a tail — so "300 executions captured"
           against 1790 engine trades reads as catastrophic loss when nothing was lost at all. The
           file row counts are the real totals and the panel already has them; the completeness
           banner below has always used them. Both numbers, and which is which. -->
      <div class="tile"><div class="v">{{ allTrades().length }}</div>
        <div class="k">executions in view<br>of {{ fileRows().trade }} captured</div></div>
      <div class="tile"><div class="v">{{ allOrders().length }}</div>
        <div class="k">order events in view<br>of {{ fileRows().order }} captured</div></div>
      <div class="tile"><div class="v">{{ orderStates().length }}</div><div class="k">distinct orders</div></div>
      <div class="tile"><div class="v">{{ epochs().length }}</div><div class="k">epoch{{ epochs().length === 1 ? '' : 's' }} in the store</div></div>
    </div>

    @if (capture(); as c) {
      <div class="banner"
        [class.good]="c.verdict === 'complete'"
        [class.warn-note]="c.verdict !== 'complete' && c.verdict !== 'unknown'"
        [class.faint-note]="c.verdict === 'unknown'">
        @switch (c.verdict) {
          @case ('complete') {
            ✓ capture complete for epoch {{ c.epoch }} — {{ c.captured }} captured row{{ c.captured === 1 ? '' : 's' }}
            across the members equals the engine's own {{ c.engine }} trade{{ c.engine === 1 ? '' : 's' }}.
            The VWAP below is over every fill, not a sample of them. <b>Count is what is verified</b>;
            price fidelity rests on each row carrying its own px and qty.
          }
          @case ('since-restart') {
            ✓ capture complete <b>since it began</b>, at trade {{ c.from }} — {{ c.captured }} row{{ c.captured === 1 ? '' : 's' }}
            captured with no holes, against {{ c.engine }} the engine has seen this epoch.
            The earlier {{ c.from - 1 }} were captured and then lost: the members' capture volume does
            not survive a restart, while the engine's counter does, via journal restore. So the VWAP
            below is exact for trades {{ c.from }} onward and blind before that — which is a different
            failure from the tap shedding under load, and total for its window rather than partial.
          }
          @case ('dropped') {
            ⚠ capture has holes inside its own range for epoch {{ c.epoch }} — {{ c.captured }} rows
            against the engine's {{ c.engine }}, missing rows scattered rather than a clean prefix.
            That is the tap shedding under sustained load, which it does by design rather than
            back-pressuring the engine. The VWAP below is over an incomplete set.
          }
          @case ('excess') {
            ⚠ more captured rows ({{ c.captured }}) than the engine reports trades ({{ c.engine }}) for
            epoch {{ c.epoch }}. That should not happen — the same trade captured twice, or files from
            an epoch this counter does not describe.
          }
          @case ('short') {
            ⚠ capture is short for epoch {{ c.epoch }} — {{ c.captured }} rows against the engine's
            {{ c.engine }}. This bridge is showing a truncated tail of the files, so which rows are
            missing cannot be read from here, and restart loss cannot be told from flood loss.
          }
          @default {
            capture holds {{ c.captured }} row{{ c.captured === 1 ? '' : 's' }} for epoch {{ c.epoch }},
            but the members do not agree on a trade count, so completeness cannot be checked.
          }
        }
        @if (c.older) { <span class="sub"> · {{ c.older }} older epoch{{ c.older === 1 ? '' : 's' }} on
          disk, excluded — the counter describes this epoch only.</span> }
      </div>
    }

    <sec-head [s]="filesSec" label="Capture files per member">
      <help-tip text="Only the leader writes, so a member's row count is the size of its leadership windows, not its share of the work. A member that has never led has no file at all, and that is correct rather than missing data." />
    </sec-head>
    @if (filesSec.open()) {
      <table>
        <thead><tr><th>member</th><th>file</th><th class="num">rows</th></tr></thead>
        <tbody>
          @for (m of members(); track m.member) {
            @for (f of m.files; track f.name) {
              <tr><td>member-{{ m.member }}</td><td class="mono">{{ f.name }}</td><td class="num">{{ f.rows }}</td></tr>
            } @empty { <tr><td>member-{{ m.member }}</td><td colspan="2" class="faint">no capture files</td></tr> }
          }
        </tbody>
      </table>
    }

    <sec-head [s]="vwapSec" label="Fill VWAP by symbol">
      <help-tip text="Our own fill VWAP: unlike a VWAP over a market tape, every one of these rows was emitted by the engine itself. Whether it is complete is not assumed — the banner at the top of this page compares the captured row count against the engine's own replicated trade counter and says which of two things is true. The two ways it can be short are different and matter differently: a RESTART takes everything written before it, because the capture volume is not durable, so the loss is total for that window and the VWAP is simply blind before the first captured trade. A FLOOD makes the tap shed rows scattered through the range, by design rather than back-pressuring the engine, and the VWAP is then wrong by an unknown amount everywhere. A missing prefix is the first; holes inside the range are the second." />
    </sec-head>
    @if (vwapSec.open()) {
      @if (showQ()) { <pre class="q">{{ Q.fills }}</pre> }
      <table>
        <thead><tr><th>sym</th><th class="num">execs</th><th class="num">volume</th><th class="num">VWAP</th><th class="num">first px</th><th class="num">last px</th></tr></thead>
        <tbody>
          @for (v of vwapSec.view(); track v.sym) {
            <tr><td>{{ v.sym }}</td><td class="num">{{ v.n }}</td><td class="num">{{ v.vol }}</td>
                <td class="num">{{ v.vwap.toFixed(6) }}</td><td class="num">{{ v.first.toFixed(6) }}</td>
                <td class="num">{{ v.last.toFixed(6) }}</td></tr>
          } @empty { <tr><td colspan="6" class="faint">no captured executions yet — book a crossing order</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="vwapSec" />
    }

    <sec-head [s]="ordersSec" label="Order final state">
      <help-tip text="One row per order, folded from its whole lifecycle. Keyed on (epoch, ref) and never on ref alone: orderRef restarts at 1 on a fresh cluster incarnation, so a bare ref silently merges two different orders from two different epochs into one. That is the kind of mistake an analytical store makes once and then answers wrongly forever." />
    </sec-head>
    @if (ordersSec.open()) {
      @if (showQ()) { <pre class="q">{{ Q.orders }}</pre> }
      <table>
        <thead><tr><th>epoch·ref</th><th>sym</th><th class="num">account</th><th>side</th>
          <th class="num">qty</th><th class="num">filled</th><th class="num">remaining</th>
          <th>status</th><th class="num">events</th></tr></thead>
        <tbody>
          @for (o of ordersSec.view(); track o.key) {
            <tr>
              <td class="mono">{{ o.epoch }}·{{ o.ref }}</td>
              <td>{{ o.sym }}</td><td class="num">{{ o.account }}</td><td>{{ o.side }}</td>
              <td class="num">{{ o.qty }}</td>
              <td class="num" [class.pos]="o.filled > 0">{{ o.filled }}</td>
              <td class="num">{{ o.remaining }}</td>
              <td>{{ o.status }}</td><td class="num">{{ o.events }}</td>
            </tr>
          } @empty { <tr><td colspan="9" class="faint">no captured order events</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="ordersSec" />
    }

    <sec-head [s]="gapsSec" label="Sequence gaps">
      <help-tip text="Consensus sequences that produced no captured row. READ THIS BEFORE READING IT AS DATA LOSS: every applied input consumes a sequence, but only order-lifecycle and trade outputs are captured, so control events — account and security enablement, price ticks, symbol registration — leave legitimate holes. A gap is a question, not a verdict: expected around seeding and price feeds, suspicious in the middle of a burst of order traffic. The authoritative drop signal is the tap's own counter, not this view." />
    </sec-head>
    @if (gapsSec.open()) {
      @if (showQ()) { <pre class="q">{{ Q.gaps }}</pre> }
      <div class="sub warnline">{{ gaps().length }} gap{{ gaps().length === 1 ? '' : 's' }} across
        {{ missingTotal() }} sequence{{ missingTotal() === 1 ? '' : 's' }} — a question, not a verdict.
        Control events legitimately produce holes.</div>
      <div class="sub">Read from seq <b>{{ gapFloor() }}</b> only: this bridge tails each capture
        file separately, so below the point both tails cover, a hole is the tail's edge rather than
        the capture's. q's <span class="mono">.tx.gaps</span> runs over the whole loaded store and
        has no such floor.</div>
      <table>
        <thead><tr><th class="num">from seq</th><th class="num">to seq</th><th class="num">missing</th></tr></thead>
        <tbody>
          @for (g of gapsSec.view(); track g.from) {
            <tr><td class="num">{{ g.from }}</td><td class="num">{{ g.to }}</td>
                <td class="num" [class.neg]="g.missing > 20">{{ g.missing }}</td></tr>
          } @empty { <tr><td colspan="3" class="faint">no gaps in the captured range</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="gapsSec" />
    }

    <sec-head [s]="sessionSec" label="Session playback">
      <help-tip text="One merged, time-ordered stream of the captured session: order lifecycle events and executions interleaved in consensus order. This is the analytical playback content — a faithful record of what the engine decided, and deliberately NOT a mechanism for rebuilding engine state. Rebuilding is the Aeron Archive's job and only its job." />
    </sec-head>
    @if (sessionSec.open()) {
      @if (showQ()) { <pre class="q">{{ Q.session }}</pre> }
      <table>
        <thead><tr><th class="num">seq</th><th>kind</th><th>sym</th><th class="num">account</th>
          <th>side</th><th class="num">qty</th><th class="num">px</th><th>status</th><th>time</th></tr></thead>
        <tbody>
          @for (r of sessionSec.view(); track r.seq + r.kind + r.id) {
            <tr [class.exec]="r.kind === 'exec'">
              <td class="num">{{ r.seq }}</td>
              <td><span class="pill" [class.good]="r.kind === 'exec'">{{ r.kind }}</span></td>
              <td>{{ r.sym }}</td><td class="num">{{ r.account }}</td><td>{{ r.side }}</td>
              <td class="num">{{ r.qty }}</td><td class="num">{{ r.px.toFixed(6) }}</td>
              <td>{{ r.status }}</td><td class="sub">{{ time(r.ts) }}</td>
            </tr>
          } @empty { <tr><td colspan="9" class="faint">nothing captured yet</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="sessionSec" />
    }

    <sec-head [s]="querySec" label="Query the capture">
      <help-tip text="Ask the captured store your own question. The controls build a q select — shown below them, so what you get back and what you would type into a q session are visibly the same statement. Two honest limits: this evaluates in the browser over the rows the bridge holds (the tail of each file), not in a q process over the whole store, and it offers the aggregates txstore.q's own views use rather than arbitrary expressions. For anything beyond that, run the real thing: TXSTORE_DIR=/data/kdb-capture q txstore.q" />
    </sec-head>
    @if (querySec.open()) {
      <div class="qbar">
        <label class="field">from
          <select [ngModel]="qFrom()" (ngModelChange)="qFrom.set($event)">
            <option value="txTrade">txTrade</option><option value="txOrder">txOrder</option>
          </select>
        </label>
        <label class="field">where sym
          <input [ngModel]="qSym()" (ngModelChange)="qSym.set($event)" placeholder="any" spellcheck="false">
        </label>
        <label class="field">account
          <input [ngModel]="qAccount()" (ngModelChange)="qAccount.set($event)" placeholder="any">
        </label>
        <label class="field">side
          <select [ngModel]="qSide()" (ngModelChange)="qSide.set($event)">
            <option value="">any</option><option value="B">B</option><option value="S">S</option>
          </select>
        </label>
        <label class="field">by
          <select [ngModel]="qBy()" (ngModelChange)="qBy.set($event)">
            <option value="">(none)</option><option value="sym">sym</option>
            <option value="account">account</option><option value="side">side</option>
            <option value="epoch">epoch</option>
          </select>
        </label>
        <button type="button" (click)="resetQuery()">reset</button>
      </div>
      <pre class="q built">{{ builtQ() }}</pre>
      <table>
        <thead><tr>
          @if (qBy()) { <th>{{ qBy() }}</th> }
          <th class="num">rows</th><th class="num">qty</th><th class="num">vwap</th>
          <th class="num">min px</th><th class="num">max px</th></tr></thead>
        <tbody>
          @for (r of querySec.view(); track r.key) {
            <tr>
              @if (qBy()) { <td>{{ r.key }}</td> }
              <td class="num">{{ r.n }}</td><td class="num">{{ r.qty }}</td>
              <td class="num">{{ r.vwap ? r.vwap.toFixed(6) : '—' }}</td>
              <td class="num">{{ r.minPx.toFixed(6) }}</td><td class="num">{{ r.maxPx.toFixed(6) }}</td>
            </tr>
          } @empty { <tr><td [attr.colspan]="qBy() ? 6 : 5" class="faint">no rows match</td></tr> }
        </tbody>
      </table>
      <sec-pager [s]="querySec" />
      <div class="sub">{{ qMatched() }} of {{ qTotal() }} captured
        {{ qFrom() === 'txTrade' ? 'executions' : 'order events' }} matched.
        vwap weights <span class="mono">{{ qFrom() === 'txTrade' ? 'px' : 'limitPx' }}</span> by qty.</div>
    }

    <sec-head [s]="freeSec" label="Run q against the capture">
      <help-tip text="Type the q yourself. It evaluates in the browser over the rows the bridge holds, not in a q process over the whole store — so it runs the select statements txstore.q is built from, and REFUSES anything outside that rather than approximating it. A box that quietly returned a plausible number for a statement it had not really understood would be worse than no box: every other panel here is careful about the line between a measurement and a guess. Supported: select with count/sum/avg/min/max/first/last, the (sum a*b)%sum c weighted-average idiom, by grouping, and where with = <> < > <= >= and like. Tables are txTrade and txOrder. Anything else names itself as unsupported." />
    </sec-head>
    @if (freeSec.open()) {
      <textarea class="qin" rows="3" spellcheck="false"
        [ngModel]="freeQ()" (ngModelChange)="freeQ.set($event)"></textarea>
      <div class="qbar">
        <button class="btn-primary" (click)="runFree()">Run</button>
        @for (e of examples; track e.label) {
          <button type="button" (click)="freeQ.set(e.q); runFree()">{{ e.label }}</button>
        }
        <span class="spacer"></span>
        <span class="sub mono">txTrade · txOrder</span>
      </div>
      @if (freeErr()) { <div class="banner bad">{{ freeErr() }}</div> }
      @if (freeOut(); as o) {
        <table>
          <thead><tr>@for (c of o.columns; track c) { <th [class.num]="o.rows.length > 0 && isNum(o.rows[0][$index])">{{ c }}</th> }</tr></thead>
          <tbody>
            @for (r of o.rows; track $index) {
              <tr>@for (v of r; track $index) { <td [class.num]="isNum(v)">{{ v }}</td> }</tr>
            } @empty { <tr><td [attr.colspan]="o.columns.length" class="faint">no rows</td></tr> }
          </tbody>
        </table>
        <div class="sub">{{ o.rows.length }} row{{ o.rows.length === 1 ? '' : 's' }}</div>
      }
    }
  `,
  styles: `
    .spacer { flex: 1; }
    .qtog { display: flex; align-items: center; gap: 5px; font-size: 12px; color: var(--muted); }
    .qtog input { width: auto; margin: 0; }
    .qbar { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; margin: 6px 0 4px; }
    .qbar input { width: 110px; }
    .q.built { border-left-color: var(--good); color: var(--text); }
    .qin { width: 100%; font-family: var(--mono); font-size: 12px; line-height: 1.5; resize: vertical;
           margin: 4px 0 6px; }
    .mono { font-family: var(--mono); font-size: 11.5px; }
    .tiles { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 6px; }
    .tile { background: #f8f9fb; border: 1px solid var(--border); border-radius: 8px; padding: 9px 12px; text-align: center; }
    .tile .v { font-size: 20px; font-weight: 600; font-family: var(--mono); color: var(--accent); }
    .tile .k { font-size: 11.5px; color: var(--muted); margin-top: 2px; }
    .q { font-family: var(--mono); font-size: 11px; color: var(--muted); background: #f8f9fb;
         border-left: 3px solid var(--accent); border-radius: 0 6px 6px 0; padding: 7px 10px;
         margin: 2px 0 6px; white-space: pre-wrap; overflow-x: auto; }
    .warnline { color: var(--warn); margin-bottom: 4px; }
    .warn-note { background: var(--warn-soft); color: var(--warn); }
    .faint-note { background: #f0f2f5; color: var(--muted); }
    tr.exec td { background: var(--good-soft); }
    .pos { color: var(--good); } .neg { color: var(--bad); }
  `,
})
export class KdbPanel implements OnInit, OnDestroy {
  private api = inject(Api);
  readonly members = signal<MemberCap[]>([]);
  /** The engine's own trade counter, agreed by the members. Empty if they disagree or are down. */
  readonly engineTrades = signal<number | null>(null);
  readonly error = signal('');
  private timer: ReturnType<typeof setInterval> | undefined;

  /** The q each view reproduces, quoted from tick-store/kdb/txstore.q so the two can be compared. */
  readonly Q = {
    fills: `.tx.fills:{[] 0!select execs:count i, volume:sum qty, vwap:(sum px*qty)%sum qty,
         first_px:first px, last_px:last px by sym from txTrade}`,
    orders: `.tx.orders:{[] 0!select events:count i, last status, last remaining, qty:last qty,
         sym:last sym, account:last account, side:last side, filled:last[qty]-last remaining
         by epoch,ref from txOrder}`,
    gaps: `.tx.gaps:{[] s:asc distinct txOrder[\`seq],txTrade\`seq; d:1_deltas s; i:where d>1;
         ([]from:s i; to:s i+1; missing:d[i]-1)}`,
    session: `.tx.session:{[] o:select seq,ts,kind:\`order,sym,account,side,qty,px:limitPx,id:ref,status from txOrder;
         t:select seq,ts,kind:\`exec,sym,account,side,qty,px,id:tradeSeq,status:\`BOOKED from txTrade;
         \`ts\`seq xasc o,t}`,
  };

  /** All members' trade rows deduped on the file's own collision key — epoch included, because
   *  tradeSeq restarts with a fresh cluster incarnation exactly as orderRef does. */
  readonly allTrades = computed(() => {
    const seen = new Set<string>();
    const out: CapTrade[] = [];
    for (const m of this.members()) {
      for (const t of m.trades) {
        const k = `${t.epoch}-${t.tradeSeq}-${t.account}`;
        if (!seen.has(k)) { seen.add(k); out.push(t); }
      }
    }
    return out.sort((a, b) => a.seq - b.seq);
  });

  readonly allOrders = computed(() => {
    const seen = new Set<string>();
    const out: CapOrder[] = [];
    for (const m of this.members()) {
      for (const o of m.orders) {
        // seq is the consensus position of the input that produced the row, so it is unique per
        // event within an epoch — the right dedup key across members that both led at some point.
        const k = `${o.epoch}-${o.seq}-${o.ref}`;
        if (!seen.has(k)) { seen.add(k); out.push(o); }
      }
    }
    return out.sort((a, b) => a.seq - b.seq);
  });

  readonly epochs = computed(() =>
    [...new Set([...this.allTrades().map(t => t.epoch), ...this.allOrders().map(o => o.epoch)])].sort());

  readonly vwap = computed(() => {
    const by = new Map<string, { n: number; vol: number; pv: number; first: number; last: number }>();
    for (const t of this.allTrades()) {
      const e = by.get(t.sym) ?? { n: 0, vol: 0, pv: 0, first: t.px, last: 0 };
      e.n++; e.vol += t.qty; e.pv += t.qty * t.px; e.last = t.px;
      by.set(t.sym, e);
    }
    return [...by.entries()].map(([sym, e]) => ({ sym, n: e.n, vol: e.vol, vwap: e.pv / e.vol, first: e.first, last: e.last }))
      .sort((a, b) => b.vol - a.vol);
  });

  /** .tx.orders[] — fold each order's events into its final state, keyed (epoch, ref). */
  readonly orderStates = computed(() => {
    const by = new Map<string, OrderState>();
    for (const o of this.allOrders()) {
      const key = `${o.epoch}·${o.ref}`;
      const prev = by.get(key);
      by.set(key, {
        key, epoch: o.epoch, ref: o.ref, sym: o.sym, account: o.account, side: o.side,
        qty: o.qty, remaining: o.remaining, filled: o.qty - o.remaining, status: o.status,
        events: (prev?.events ?? 0) + 1, updatedMs: o.updatedMs,
      });
    }
    return [...by.values()].sort((a, b) => b.updatedMs - a.updatedMs);
  });

  /**
   * The sequence from which a gap means anything here.
   *
   * The bridge tails each capture file independently (300 rows apiece), so the two tails start at
   * different sequences — and the union of them opens an enormous hole between the older file's
   * last row and the newer file's first, which is an artefact of the tail and not a gap in the
   * capture. Measured before this guard: 23 "gaps" over 13,979 sequences, almost all of it one
   * fictitious hole. Only the range both tails cover can be read at all.
   */
  readonly gapFloor = computed(() => {
    const o = this.allOrders(), t = this.allTrades();
    if (!o.length) return t.length ? t[0].seq : 0;
    if (!t.length) return o[0].seq;
    return Math.max(o[0].seq, t[0].seq);
  });

  /** .tx.gaps[] — consensus sequences with no captured row. A question, not a verdict. */
  readonly gaps = computed<Gap[]>(() => {
    const floor = this.gapFloor();
    const s = [...new Set([...this.allOrders().map(o => o.seq), ...this.allTrades().map(t => t.seq)])]
      .filter(v => v >= floor)
      .sort((a, b) => a - b);
    const out: Gap[] = [];
    for (let i = 1; i < s.length; i++) {
      if (s[i] - s[i - 1] > 1) out.push({ from: s[i - 1], to: s[i], missing: s[i] - s[i - 1] - 1 });
    }
    return out.reverse();
  });
  readonly missingTotal = computed(() => this.gaps().reduce((n, g) => n + g.missing, 0));

  /** .tx.session[] — orders and executions interleaved in consensus order, newest first. */
  readonly session = computed(() => {
    const rows = [
      ...this.allOrders().map(o => ({
        seq: o.seq, ts: o.updatedMs, kind: 'order', sym: o.sym, account: o.account,
        side: o.side, qty: o.qty, px: o.limitPx, id: o.ref, status: o.status,
      })),
      ...this.allTrades().map(t => ({
        seq: t.seq, ts: t.tsMs, kind: 'exec', sym: t.sym, account: t.account,
        side: t.side, qty: t.qty, px: t.px, id: t.tradeSeq, status: 'BOOKED',
      })),
    ];
    return rows.sort((a, b) => b.seq - a.seq || b.ts - a.ts);
  });

  // ---- query the capture -----------------------------------------------------------------------
  //
  // A builder rather than a text box: without a q process there is nothing to evaluate a typed
  // expression against, and a box that silently accepts q and answers with something else would be
  // worse than no box. The controls compose a real select, the select is shown, and the same
  // statement is what you would type into `q txstore.q` — with the caveat, stated in the UI, that
  // this runs over the tail the bridge holds rather than the whole store.
  readonly qFrom = signal<'txTrade' | 'txOrder'>('txTrade');
  readonly qSym = signal('');
  readonly qAccount = signal('');
  readonly qSide = signal('');
  readonly qBy = signal('');

  resetQuery(): void {
    this.qFrom.set('txTrade'); this.qSym.set(''); this.qAccount.set(''); this.qSide.set(''); this.qBy.set('');
  }

  /** Rows from the chosen table, normalised to the columns the aggregates need. */
  private readonly qRows = computed(() => this.qFrom() === 'txTrade'
    ? this.allTrades().map(t => ({ sym: t.sym, account: t.account, side: t.side, epoch: t.epoch, qty: t.qty, px: t.px }))
    : this.allOrders().map(o => ({ sym: o.sym, account: o.account, side: o.side, epoch: o.epoch, qty: o.qty, px: o.limitPx })));

  private readonly qFiltered = computed(() => {
    const sym = this.qSym().trim().toUpperCase();
    const acct = this.qAccount().trim();
    const side = this.qSide();
    return this.qRows().filter(r =>
      (!sym || r.sym.toUpperCase().includes(sym))
      && (!acct || String(r.account) === acct)
      && (!side || r.side === side));
  });

  readonly qTotal = computed(() => this.qRows().length);
  readonly qMatched = computed(() => this.qFiltered().length);

  readonly qResult = computed(() => {
    const by = this.qBy();
    const groups = new Map<string, { key: string; n: number; qty: number; pv: number; minPx: number; maxPx: number }>();
    for (const r of this.qFiltered()) {
      const key = by ? String((r as Record<string, unknown>)[by]) : 'all';
      const g = groups.get(key) ?? { key, n: 0, qty: 0, pv: 0, minPx: Infinity, maxPx: -Infinity };
      g.n++; g.qty += r.qty; g.pv += r.qty * r.px;
      g.minPx = Math.min(g.minPx, r.px); g.maxPx = Math.max(g.maxPx, r.px);
      groups.set(key, g);
    }
    return [...groups.values()]
      .map(g => ({ ...g, vwap: g.qty ? g.pv / g.qty : 0, minPx: g.minPx === Infinity ? 0 : g.minPx, maxPx: g.maxPx === -Infinity ? 0 : g.maxPx }))
      .sort((a, b) => b.qty - a.qty);
  });

  /** The q the controls compose — shown so the answer and the statement are visibly the same. */
  readonly builtQ = computed(() => {
    const px = this.qFrom() === 'txTrade' ? 'px' : 'limitPx';
    const where: string[] = [];
    if (this.qSym().trim()) where.push(`sym like "${this.qSym().trim().toUpperCase()}*"`);
    if (this.qAccount().trim()) where.push(`account=${this.qAccount().trim()}`);
    if (this.qSide()) where.push(`side="${this.qSide()}"`);
    const by = this.qBy() ? ` by ${this.qBy()}` : '';
    return `select rows:count i, qty:sum qty, vwap:(sum ${px}*qty)%sum qty, minPx:min ${px}, maxPx:max ${px}`
      + `${by} from ${this.qFrom()}${where.length ? ' where ' + where.join(', ') : ''}`;
  });

  // ---- free-form q ------------------------------------------------------------------------------
  readonly freeQ = signal(
    '.tx.fills:{[] 0!select execs:count i, volume:sum qty, vwap:(sum px*qty)%sum qty, '
    + 'first_px:first px, last_px:last px by sym from txTrade}');
  readonly freeOut = signal<QResult | null>(null);
  readonly freeErr = signal('');

  readonly examples = [
    { label: 'fills by sym', q: '.tx.fills:{[] 0!select execs:count i, volume:sum qty, vwap:(sum px*qty)%sum qty, first_px:first px, last_px:last px by sym from txTrade}' },
    { label: 'by account', q: 'select execs:count i, volume:sum qty, vwap:(sum px*qty)%sum qty by account from txTrade' },
    { label: 'buys only', q: 'select execs:count i, volume:sum qty by sym from txTrade where side="B"' },
    { label: 'orders by status', q: 'select orders:count i, qty:sum qty by status from txOrder' },
  ];

  isNum(v: unknown): boolean { return typeof v === 'number'; }

  runFree(): void {
    try {
      this.freeErr.set('');
      this.freeOut.set(runQ(this.freeQ(), {
        // The q names, not the console's — someone typing `sym` should get sym.
        txTrade: this.allTrades() as unknown as Record<string, string | number>[],
        txOrder: this.allOrders() as unknown as Record<string, string | number>[],
      }));
    } catch (e) {
      this.freeOut.set(null);
      this.freeErr.set(e instanceof Error ? e.message : String(e));
    }
  }

  readonly showQ = signal(false);
  readonly querySec = new Section(this.qResult, r => r.key);
  readonly freeSec = new Section<unknown>(signal([]), () => '');
  readonly filesSec = new Section<unknown>(signal([]), () => '');
  readonly vwapSec = new Section(this.vwap, v => v.sym);
  readonly ordersSec = new Section(this.orderStates, o => o.key);
  readonly gapsSec = new Section(this.gaps, g => String(g.from));
  readonly sessionSec = new Section(this.session, r => `${r.seq}-${r.kind}-${r.id}`);

  time(ms: number): string { return new Date(ms).toTimeString().slice(0, 8); }

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 10_000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  /**
   * COMPLETENESS, against the one reference that cannot itself be short.
   *
   * The obvious check — compare the captures with the `trades` table — is circular: the projection
   * is exactly what goes silently short when the read path breaks, so a wedged rig would show both
   * missing the same fills and the check would go green. The engine's own trade counter is
   * replicated state agreed by all three members, with nothing downstream able to drop a row.
   *
   * Summing across members is not a workaround, it is the correct arithmetic: the tap writes only
   * on the leader, so after a failover the record is split across whoever led at the time, and each
   * trade is captured exactly once. A follower with a header-only file is a follower, not a fault.
   *
   * This verifies COUNT, which is what capture loss destroys. It says nothing about price fidelity
   * — but each captured row carries its own px and qty, so count-completeness plus per-row values
   * is what a VWAP needs. The page says count, and only count.
   */
  /**
   * True row counts, read from the files rather than from what was parsed. The bridge tails each
   * file, so every list on this page is a window onto a larger capture — the views below compute
   * over the window (which is correct and is what they say), while "how much was captured" is this.
   */
  readonly fileRows = computed(() => {
    let trade = 0, order = 0;
    for (const m of this.members()) {
      for (const f of m.files) {
        if (f.name.startsWith('txtrade')) trade += f.rows;
        else if (f.name.startsWith('txorder')) order += f.rows;
      }
    }
    return { trade, order };
  });

  readonly capture = computed(() => {
    // Row counts come from the FILES, not from the parsed rows: the bridge tails each file, so the
    // parsed list can be shorter than the capture while the capture is complete.
    const byEpoch = new Map<number, number>();
    for (const m of this.members()) {
      for (const f of m.files) {
        const e = /^txtrade-(\d+)-/.exec(f.name);
        if (e) byEpoch.set(+e[1], (byEpoch.get(+e[1]) ?? 0) + f.rows);
      }
    }
    if (!byEpoch.size) return null;
    const epoch = Math.max(...byEpoch.keys());
    const captured = byEpoch.get(epoch) ?? 0;
    const engine = this.engineTrades();
    const older = byEpoch.size - 1;

    // WHERE the missing rows sit says WHICH failure this was, and the two have different shapes
    // and different fixes. A missing PREFIX with a contiguous run after it is restart-induced:
    // the members' capture volume is not durable, so a restart takes everything written before it
    // while the engine's own counter survives via journal restore — total loss of a window. HOLES
    // inside the captured range are the tap shedding under flood — partial loss, by design, rather
    // than back-pressuring the deterministic core.
    //
    // Only claimable when the tail shows the WHOLE file: otherwise the lowest tradeSeq on screen is
    // the tail's edge, not the capture's beginning, and "missing prefix" would be the bridge's
    // truncation misread as data loss.
    const seqs = this.allTrades().filter(t => t.epoch === epoch).map(t => t.tradeSeq).sort((a, b) => a - b);
    const whole = seqs.length === captured;
    const contiguous = seqs.length > 0 && seqs[seqs.length - 1] - seqs[0] + 1 === seqs.length;
    const from = seqs.length ? seqs[0] : 0;

    const verdict = engine === null ? 'unknown' as const
      : captured === engine ? 'complete' as const
      : captured > engine ? 'excess' as const
      : whole && contiguous && from > 1 ? 'since-restart' as const
      : whole && !contiguous ? 'dropped' as const
      : 'short' as const;

    return { epoch, captured, engine, older, from, verdict };
  });

  private async poll(): Promise<void> {
    const mem = await this.api.load<{ members: { health?: { trades?: number } }[] }>('/members');
    const counts = mem.status === 200 && Array.isArray(mem.body?.members)
      ? mem.body.members.map(m => m.health?.trades).filter((v): v is number => typeof v === 'number')
      : [];
    // Only an AGREED counter is a reference. Divergent members are their own problem and must not
    // be silently resolved by picking one.
    this.engineTrades.set(counts.length && new Set(counts).size === 1 ? counts[0] : null);
    const r = await this.api.load<{ members: { member: number; capture: string }[] }>('/kdbtap');
    if (r.status !== 200 || !r.body?.members) {
      // The bridge says why it failed; this used to throw that away and substitute a guess.
      this.error.set(bridgeError(r, 'the capture bridge'));
      return;
    }
    this.error.set('');
    this.members.set(r.body.members.map(m => this.parse(m.member, m.capture)));
  }

  private parse(member: number, capture: string): MemberCap {
    const files: { name: string; rows: number }[] = [];
    const trades: CapTrade[] = [];
    const orders: CapOrder[] = [];
    let kind: 'trade' | 'order' | '' = '';
    for (const line of capture.split('\n')) {
      if (line.startsWith('==FILE ')) {
        const [, path, rows] = line.split(' ');
        const name = path.split('/').pop()!;
        files.push({ name, rows: Math.max(0, Number(rows) - 1) });   // minus header
        kind = name.startsWith('txtrade') ? 'trade' : name.startsWith('txorder') ? 'order' : '';
        continue;
      }
      if (!kind || !line || line.startsWith('seq,')) continue;
      const c = line.split(',');
      if (kind === 'trade' && c.length >= 9) {
        trades.push({ seq: +c[0], epoch: +c[1], tradeSeq: +c[2], account: +c[3], sym: c[4],
          side: c[5], qty: +c[6], px: +c[7], tsMs: +c[8] });
      } else if (kind === 'order' && c.length >= 14) {
        orders.push({ seq: +c[0], epoch: +c[1], ref: +c[2], account: +c[3], sym: c[4], side: c[5],
          qty: +c[6], remaining: +c[7], limitPx: +c[8], status: c[9], updatedMs: +c[13] });
      }
    }
    return { member, files, trades, orders };
  }
}
