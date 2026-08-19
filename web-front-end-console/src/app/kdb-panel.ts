import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

// txtrade capture row: seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs
interface CapTrade { seq: number; tradeSeq: number; account: number; sym: string; side: string; qty: number; px: number; tsMs: number; }
interface MemberCap { member: number; files: { name: string; rows: number }[]; trades: CapTrade[]; orderRows: number; }

@Component({
  selector: 'kdb-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>KDB-X capture tap</h2>
      <help-tip text="The analytical path: on each output event the cluster leader appends a row to a tickerplant capture log on its own volume — off the consensus path, non-blocking, drops visibly under flood rather than silently thinning. These are the files q loads directly (tick-store/kdb/txstore.q). The consensus journal stays the authoritative record; this tap costs analytics, never correctness. Rows are epoch-qualified and carry the member id, so all members' captures load together without collision." />
      <span class="spacer"></span>
      <span class="faint">KDB_TAP_DIR=/data/kdb-capture · via dev bridge</span>
    </div>

    @if (error()) { <div class="banner bad">{{ error() }}</div> }

    <h3>Capture files per member <span class="sub">(leader-side tap — a member's rows reflect its leadership windows)</span></h3>
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

    <h3>VWAP by symbol <span class="sub">computed from txtrade exactly as the q view does</span></h3>
    <table>
      <thead><tr><th>sym</th><th class="num">trades</th><th class="num">volume</th><th class="num">VWAP</th><th class="num">last px</th></tr></thead>
      <tbody>
        @for (v of vwap(); track v.sym) {
          <tr><td>{{ v.sym }}</td><td class="num">{{ v.n }}</td><td class="num">{{ v.vol }}</td>
              <td class="num">{{ v.vwap.toFixed(6) }}</td><td class="num">{{ v.last.toFixed(6) }}</td></tr>
        } @empty { <tr><td colspan="5" class="faint">no captured trades yet — book a crossing order</td></tr> }
      </tbody>
    </table>

    <h3>Latest captured trades</h3>
    <table>
      <thead><tr><th class="num">seq</th><th class="num">tradeSeq</th><th class="num">account</th><th>sym</th><th>side</th><th class="num">qty</th><th class="num">px</th><th>time</th></tr></thead>
      <tbody>
        @for (t of recent(); track t.tradeSeq + '-' + t.account) {
          <tr><td class="num">{{ t.seq }}</td><td class="num">{{ t.tradeSeq }}</td><td class="num">{{ t.account }}</td>
              <td>{{ t.sym }}</td><td>{{ t.side }}</td><td class="num">{{ t.qty }}</td>
              <td class="num">{{ t.px.toFixed(6) }}</td>
              <td class="sub">{{ time(t.tsMs) }}</td></tr>
        } @empty { <tr><td colspan="8" class="faint">none</td></tr> }
      </tbody>
    </table>
  `,
  styles: `
    .spacer { flex: 1; }
    h3 { margin: 14px 0 3px; font-size: 12.5px; font-weight: 600; color: var(--muted); }
    .mono { font-family: var(--mono); font-size: 11.5px; }
  `,
})
export class KdbPanel implements OnInit, OnDestroy {
  private api = inject(Api);
  readonly members = signal<MemberCap[]>([]);
  readonly error = signal('');
  private timer: ReturnType<typeof setInterval> | undefined;

  /** All members' trade rows deduped by (epoch, tradeSeq, account) — the file's own collision key. */
  readonly allTrades = computed(() => {
    const seen = new Set<string>();
    const out: CapTrade[] = [];
    for (const m of this.members()) {
      for (const t of m.trades) {
        const k = `${t.tradeSeq}-${t.account}-${t.seq}`;
        if (!seen.has(k)) { seen.add(k); out.push(t); }
      }
    }
    return out.sort((a, b) => a.tradeSeq - b.tradeSeq);
  });

  readonly vwap = computed(() => {
    const by = new Map<string, { n: number; vol: number; pv: number; last: number }>();
    for (const t of this.allTrades()) {
      const e = by.get(t.sym) ?? { n: 0, vol: 0, pv: 0, last: 0 };
      e.n++; e.vol += t.qty; e.pv += t.qty * t.px; e.last = t.px;
      by.set(t.sym, e);
    }
    return [...by.entries()].map(([sym, e]) => ({ sym, n: e.n, vol: e.vol, vwap: e.pv / e.vol, last: e.last }))
      .sort((a, b) => b.vol - a.vol);
  });

  readonly recent = computed(() => [...this.allTrades()].slice(-15).reverse());

  time(ms: number): string { return new Date(ms).toTimeString().slice(0, 8); }

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 10_000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  private async poll(): Promise<void> {
    const r = await this.api.load<{ members: { member: number; capture: string }[] }>('/kdbtap');
    if (r.status !== 200 || !r.body?.members) {
      this.error.set('capture bridge unreachable (dev proxy + kubectl required)');
      return;
    }
    this.error.set('');
    this.members.set(r.body.members.map(m => this.parse(m.member, m.capture)));
  }

  private parse(member: number, capture: string): MemberCap {
    const files: { name: string; rows: number }[] = [];
    const trades: CapTrade[] = [];
    let orderRows = 0;
    let inTrades = false;
    for (const line of capture.split('\n')) {
      if (line.startsWith('==FILE ')) {
        const [, path, rows] = line.split(' ');
        const name = path.split('/').pop()!;
        files.push({ name, rows: Number(rows) - 1 });   // minus header
        inTrades = name.startsWith('txtrade');
        if (name.startsWith('txorder')) orderRows = Number(rows) - 1;
        continue;
      }
      if (!inTrades || !line || line.startsWith('seq,')) continue;
      const c = line.split(',');
      if (c.length < 9) continue;
      trades.push({ seq: +c[0], tradeSeq: +c[2], account: +c[3], sym: c[4], side: c[5], qty: +c[6], px: +c[7], tsMs: +c[8] });
    }
    return { member, files, trades, orderRows };
  }
}
