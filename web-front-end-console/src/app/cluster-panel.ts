import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Api, MemberHealth } from './api';
import { HelpTip } from './help';

/**
 * How long the sequence must be still before "advancing" is withdrawn.
 *
 * MUST EXCEED THE PRODUCER'S OWN BEAT, and the first version did not. The feed adapter batches:
 * `FEED_FLUSH_MS=15000` on the feed-adapter deployment, read off the rig. A threshold of ~10s
 * against a 15s flush does not detect stillness — it detects the GAP BETWEEN BEATS, so a perfectly
 * healthy cluster drops the claim once per flush cycle, forever. Measured independently on this rig:
 * `applied` held the same value for 40s+ with the adapter Running, 0 restarts and no drops.
 *
 * 45s is three flush intervals. The coupling is deliberate and stated because the interval is a
 * property of the PRODUCER and is not exposed on any HTTP surface the browser can read: if
 * FEED_FLUSH_MS changes, this has to change with it, and a reader of either needs to know that.
 *
 * The same shape cost another lane a day: a stillness gate of 4-6s against a 10s sweep went quiet
 * deterministically for any epoch larger than one page.
 */
const FEED_FLUSH_MS = 15000;
const STALE_AFTER_MS = FEED_FLUSH_MS * 3;

@Component({
  selector: 'cluster-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>Aeron cluster</h2>
      <help-tip text="The matching engine runs as three replicated members under Raft consensus. Every order is sequenced through the cluster, and each member applies the same events in the same order — so their counters should always be identical. This table reads each member's own health endpoint directly. If a member is killed, the other two keep serving and the counters stay in lockstep." />
    </div>
    <div class="banner" [class.good]="agreed()" [class.bad]="!agreed()">
      @if (agreed()) { ✓ members agree — applied {{ members()[0]?.applied }} }
      @else { ⚠ members diverge or unreachable }
    </div>
    <div class="banner" [class]="book().tone">
      {{ book().text }}
      <help-tip text="Each member serves its own view of the book. Three matching reads are only a consensus claim if they are at the SAME applied sequence AND that sequence is advancing — three identical reads of a stopped cluster agree by coincidence. Books are compared only when the members are at the same sequence, because at different sequences a difference is skew, not disagreement. Quiet is normal: the feed batches every 15s and an idle rig sequences nothing at all, so a quiet reading means agreement has not been re-tested lately — not that anything is wrong." />
    </div>
    <table>
      <thead><tr><th></th><th>role</th><th class="num">applied</th><th class="num">engineApplied</th><th class="num">trades</th><th class="num">snapshots</th></tr></thead>
      <tbody>
        @for (m of members(); track $index) {
          <tr>
            <td>member-{{ $index }}</td>
            @if (m) {
              <td><span class="pill" [class.warn]="m.role === 'LEADER'">{{ m.role }}</span></td>
              <td class="num">{{ m.applied }}</td><td class="num">{{ m.engineApplied }}</td>
              <td class="num">{{ m.trades }}</td><td class="num">{{ m.snapshots }}</td>
            } @else { <td colspan="5" class="faint">unreachable</td> }
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `.banner { margin-bottom: 10px; font-family: var(--mono); font-size: 12.5px; }`,
})
export class ClusterPanel implements OnInit, OnDestroy {
  private api = inject(Api);
  private timer: ReturnType<typeof setInterval> | undefined;

  readonly members = signal<(MemberHealth | null)[]>([null, null, null]);
  /** Per member: its applied sequence and a digest of its book, or null if unreadable. */
  readonly bookReads = signal<({ applied: number; digest: string; books: number } | null)[]>([]);
  /** The highest applied seen so far, to tell "advancing" from "identical because stopped". */
  private highWater = signal<number | null>(null);
  private advancing = signal(false);
  /** When the sequence last moved. Wall-clock, not a poll count: the threshold is a property of the
   *  producer's flush interval, and tying it to this panel's poll rate couples two unrelated things. */
  private lastAdvanceAt: number | null = null;
  readonly quietMs = signal(0);
  readonly agreed = computed(() => {
    const ms = this.members();
    return ms.every(m => m !== null)
      && new Set(ms.map(m => m!.applied)).size === 1
      && new Set(ms.map(m => m!.trades)).size === 1;
  });

  /**
   * Whether the three members agree ON THE BOOK, and whether that means anything yet.
   *
   * Three identical reads are NOT a consensus claim on their own: a stopped cluster agrees with
   * itself perfectly, and a panel that goes green for that is the vacuous pass this project keeps
   * paying for. So the claim is pinned to the sequence — same `applied` across members, and that
   * `applied` advancing between polls — and the wording says which of those is missing rather than
   * collapsing to a single tick.
   *
   * Books are compared ONLY at a matching sequence. Members legitimately sit at different points
   * for a moment, and a difference read across two sequences is SKEW, not disagreement; calling it
   * disagreement would cry wolf on every busy poll.
   */
  readonly book = computed<{ text: string; tone: string }>(() => {
    const reads = this.bookReads().filter(r => r !== null) as { applied: number; digest: string; books: number }[];
    if (reads.length < 2) { return { text: '· book agreement: need at least two members to compare', tone: 'banner' }; }
    const seqs = new Set(reads.map(r => r.applied));
    if (seqs.size > 1) {
      return { text: `· members are at different sequences (${[...seqs].sort((a, b) => a - b).join(', ')}) — skew, not disagreement; not comparing books`, tone: 'banner' };
    }
    const applied = reads[0].applied;
    const same = new Set(reads.map(r => r.digest)).size === 1;
    if (!same) {
      return { text: `⚠ members DISAGREE on the book at the same applied sequence ${applied}`, tone: 'banner bad' };
    }
    if (!this.advancing()) {
      return { text: `· ${reads.length} members hold an identical book (${reads[0].books} securities) at ${applied} · quiet ${Math.round(this.quietMs() / 1000)}s`, tone: 'banner' };
    }
    return { text: `✓ ${reads.length} members agree on the book (${reads[0].books} securities) at applied ${applied}, and advancing`, tone: 'banner good' };
  });

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 2000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  /**
   * Read each member's own book. The digest is order-insensitive on purpose: `/bbo` makes no
   * ordering promise, so comparing the raw response would report a reshuffle as a divergence.
   */
  private async pollBooks(count: number): Promise<void> {
    const reads = await Promise.all(
      Array.from({ length: count }, async (_, i) => {
        const r = await this.api.fetchJson<{ applied: number; books: { ticker: string; mark: number; ref: number }[] }>(`/m${i}/bbo`);
        if (!r.ok || !Array.isArray(r.value.books)) { return null; }
        const digest = r.value.books
          .map(b => `${b.ticker}:${b.mark}:${b.ref}`)
          .sort()
          .join('|');
        return { applied: r.value.applied, digest, books: r.value.books.length };
      }));
    this.bookReads.set(reads);

    // "Advancing" means the cluster has moved RECENTLY, not that it moved between the last two
    // samples. Keyed on a single poll-to-poll delta the claim flickers: two 2s reads can legitimately
    // land on the same sequence during a quiet moment, and the banner then alternates between
    // "agreeing and advancing" and "not yet a consensus reading" every few seconds — which reads as
    // the cluster faltering when nothing has happened. Measured: it oscillated on a healthy rig.
    //
    // So: count polls since the last increase, and allow a few before withdrawing the claim. A
    // high-water mark rather than the raw reading, because it cannot go backwards — a late reply
    // from a lagging member must not read as the cluster rewinding.
    const top = Math.max(...reads.filter(r => r !== null).map(r => r!.applied), -1);
    if (top < 0) { return; }
    const now = Date.now();
    const prev = this.highWater();
    if (prev === null) { this.highWater.set(top); this.lastAdvanceAt = now; return; }
    if (top > prev) { this.lastAdvanceAt = now; this.advancing.set(true); }
    else if (this.lastAdvanceAt !== null && now - this.lastAdvanceAt >= STALE_AFTER_MS) {
      this.advancing.set(false);
    }
    this.quietMs.set(this.lastAdvanceAt === null ? 0 : now - this.lastAdvanceAt);
    this.highWater.set(Math.max(prev, top));
  }

  private async poll(): Promise<void> {
    // ASK how many members there are rather than assuming three. An Aeron cluster is normally 3 or
    // 5, and the count is a configuration — hardcoding it showed three rows on a five-member cluster
    // and two permanently-dead rows on a shrunk one. /members returns each pod's own /health.
    const mem = await this.api.load<{ count: number; members: { ordinal: number; code: number; health: MemberHealth }[] }>('/members');
    // A missing route falls through to the SPA and returns 200 HTML — require the actual member
    // shape, not just a 200.
    const list = mem.status === 200 && mem.body?.members ? mem.body.members : [];
    void this.pollBooks(list.length || 3);
    this.members.set(list.map(m =>
      m.code === 200 && m.health && typeof m.health === 'object' && 'applied' in (m.health as object)
        ? m.health : null));
  }
}
