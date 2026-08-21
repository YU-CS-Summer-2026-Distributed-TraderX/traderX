import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Api, bridgeError } from './api';
import { HelpTip } from './help';

interface Artifact { kind: string; label: string; path: string; sha256: string; content: string; }

interface Cut {
  key: string; source: string; date: string; version: string;
  seq: string; rows: string; contracts: string; cutSha: string;
  /** True when every derived artifact names this cut's own sha as the thing it was built from. */
  reproducible: boolean | null;
  artifacts: Artifact[];
  open: string;
}

/** "#cut schema=3 seq=19906 …" / "# consensusSequence=19906" → {schema:'3', seq:'19906', …} */
const kv = (text: string): Record<string, string> =>
  Object.fromEntries([...text.matchAll(/(\w+)=(\S+)/g)].map(m => [m[1], m[2]]));

/**
 * Newest first, and upload proofs last whatever their date.
 *
 * The two sources arrive independently — the rig sink and the bucket resolve in whichever order the
 * network gives them — so ordering has to be applied to the COMBINED list every time either lands,
 * not sorted within one source and concatenated. That is why this is a function rather than a sort
 * at the end of each loader.
 *
 * `proof/<epochMillis>/` objects are write-once checks, not session cuts. They carry no session date
 * and sink to the bottom rather than sorting as "?" among real cuts.
 */
const order = (cuts: Cut[]): Cut[] => [...cuts].sort((a, b) => {
  const pa = a.date === 'upload proof', pb = b.date === 'upload proof';
  if (pa !== pb) return pa ? 1 : -1;
  if (a.date !== b.date) return b.date.localeCompare(a.date);
  return b.version.localeCompare(a.version, undefined, { numeric: true });
});

const headers = (content: string) =>
  kv(content.split('\n').filter(l => l.startsWith('#')).join(' '));

@Component({
  selector: 'provenance-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>EOD cut provenance</h2>
      </button>
      <help-tip text="At end of day the cluster takes a cut: every position at an exact consensus sequence number, against a specific published price version. Because the engine is deterministic, all three members would render byte-identical files from it — but this screen does not check that: the sink holds one copy, so the SHA-256 here fingerprints the file it read, not an agreement between members. What it does check is the link: each derived artifact names the cut it was built from, and the pill compares that against the cut's own sha. Each cut produces two artifacts from one committed source: the netted positions, and the OTC contracts. Both name the cut they were rebuilt from, so 'reproduces from the cut alone' is something you can check on this screen rather than take on faith. Swaps appear only in the contracts file, never as position rows — netting a pay-fixed against a receive-fixed would destroy both rates." />
    </div>

    @if (open()) {
    @if (rigNote()) { <div class="banner warn-note">{{ rigNote() }}</div> }
    @for (c of visible(); track c.key) {
      <div class="cut-head">
        <b>{{ c.date }}</b> <span class="pill">{{ c.version }}</span>
        <span class="sub">seq {{ c.seq || '?' }} · {{ c.source }}</span>
        <span class="spacer"></span>
        @if (c.reproducible === true) {
          <span class="pill good">both artifacts reproduce from this cut</span>
        } @else if (c.reproducible === false) {
          <span class="pill bad">artifact names a different cut</span>
        }
      </div>
      <table class="fixed">
        <thead><tr><th>artifact</th><th class="num">rows</th><th>sha-256</th><th></th></tr></thead>
        <tbody>
          @for (a of c.artifacts; track a.path) {
            <tr>
              <td>{{ a.label }}<div class="sub">{{ a.path }}</div></td>
              <td class="num">{{ rowsOf(a) }}</td>
              <td class="sha">{{ a.sha256 ? a.sha256.slice(0, 16) + '…' : '—' }}</td>
              <td><button (click)="toggle(c, a)">{{ c.open === a.kind ? 'hide' : 'view' }}</button></td>
            </tr>
            @if (c.open === a.kind) {
              <tr><td colspan="4"><pre class="cut">{{ a.content }}</pre></td></tr>
            }
          }
        </tbody>
      </table>
    } @empty {
      <div class="faint">{{ error() || (loaded() ? 'no cuts anywhere' : 'loading cuts…') }}</div>
    }
    @if (hiddenCount() > 0 || showAll()) {
      <button class="more" (click)="showAll.set(!showAll())">
        {{ showAll() ? 'show the latest session only' : 'show ' + hiddenLabel() }}
      </button>
    }
    <!-- A row count here is small because the BOOK is small, not because the file is a sample.
         Two rows read as truncation to yaakov, which is the right instinct about a number with no
         stated grain — so the grain is stated. -->
    <div class="sub note">One row per <b>account and security</b> holding a non-zero position, and
      the file is not netted across accounts — so the row count is the size of the book at that
      consensus sequence, whole. Two rows means two positions, not two of many.</div>
    @if (cuts().length && error()) { <div class="sub err">{{ error() }}</div> }
    }
  `,
  styles: `
    .spacer { flex: 1; }
    /* Fixed layout so the cells cannot be resized by what is inside them; the artifact path column
       takes the slack and the rest stay put whether a block is expanded or not. */
    table.fixed { table-layout: fixed; }
    table.fixed td:first-child, table.fixed th:first-child { width: auto; }
    table.fixed td:nth-child(2), table.fixed th:nth-child(2) { width: 70px; }
    table.fixed td:nth-child(3), table.fixed th:nth-child(3) { width: 170px; }
    table.fixed td:last-child, table.fixed th:last-child { width: 64px; }
    table.fixed td { overflow-wrap: anywhere; }
    .cut-head { display: flex; align-items: center; gap: 8px; margin: 16px 0 4px; font-size: 13px; }
    .cut-head:first-of-type { margin-top: 4px; }
    .sha { font-family: var(--mono); font-size: 11.5px; color: var(--muted); }
    td .sub { font-size: 11px; }
    /* A table cell sizes to its content, so an artifact's long CSV lines dragged the whole table
       — and the page — out to the width of the longest row. Wrapping inside the block keeps the
       expansion vertical, which is the only direction there is room in. */
    .cut { font-family: var(--mono); font-size: 11.5px; color: var(--muted); background: #f8f9fb;
           border-radius: 6px; padding: 8px; max-height: 260px; overflow-y: auto; overflow-x: hidden;
           margin: 4px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
    .err { margin-top: 10px; }
    .more { margin-top: 12px; font-size: 11.5px; }
    .note { margin-top: 10px; max-width: 720px; }
    .warn-note { background: var(--warn-soft); color: var(--warn); font-size: 12.5px; margin-bottom: 6px; }
  `,
})
export class ProvenancePanel implements OnInit {
  private api = inject(Api);
  readonly cuts = signal<Cut[]>([]);
  readonly error = signal('');
  readonly loaded = signal(false);
  readonly open = signal(true);
  readonly rigEmpty = signal(false);
  readonly showAll = signal(false);

  /**
   * Default view: the most recent session date only, without the write-once upload proofs.
   *
   * The bucket accumulates — a July cut and a handful of `proof/<epochMillis>/` objects sit
   * alongside today's — and an operator opening this page wants the session they just published,
   * not the archive. Hidden rather than deleted, and the toggle SAYS HOW MANY are hidden: a view
   * that quietly drops rows is the same failure as a counter that is not rendered.
   */
  private readonly RECENT_DATES = 1;
  readonly visible = computed(() => {
    const all = this.cuts();
    if (this.showAll()) return all;
    const dates = [...new Set(all.filter(c => c.date !== 'upload proof').map(c => c.date))]
      .sort((a, b) => b.localeCompare(a)).slice(0, this.RECENT_DATES);
    return all.filter(c => dates.includes(c.date));
  });
  readonly hiddenCount = computed(() => this.cuts().length - this.visible().length);
  /** Named separately, because "older cuts" and "upload proofs" are different things to go looking for. */
  readonly hiddenLabel = computed(() => {
    const hidden = this.cuts().filter(c => !this.visible().includes(c));
    const proofs = hidden.filter(c => c.date === 'upload proof').length;
    const older = hidden.length - proofs;
    const bits = [];
    if (older) bits.push(`${older} older cut${older === 1 ? '' : 's'}`);
    if (proofs) bits.push(`${proofs} upload proof${proofs === 1 ? '' : 's'}`);
    return bits.join(' and ');
  });

  /**
   * What an empty LOCAL sink means depends on where this rig sends its cuts, and the answer differs
   * per tier — so the panel works it out from what it can see rather than asserting one tier's
   * truth everywhere.
   *
   * The cloud overlay sets RISK_EXTRACT_SINK_URI to a gs:// bucket, so its /data/risk-extracts is
   * empty BY DESIGN and the cuts are the archive rows below. On a rig that writes locally, the same
   * empty directory means the end-of-day chain has produced nothing, which is a real fault. Saying
   * the second on a rig doing the first is the exact mistake this panel exists to avoid — and it is
   * one I made: the previous wording asserted a PVC that only the kind rig has.
   */
  private explainEmptySink(): void {
    if (!this.rigEmpty()) { this.rigNote.set(''); return; }
    const archived = this.cuts().some(c => c.source !== 'rig cut sink');
    this.rigNote.set(archived
      ? 'The local sink on the risk-extract pod is empty, which is expected when the extract is '
        + 'configured to write to a bucket — this rig\'s cuts are the archive rows below, not files '
        + 'on the pod. Nothing is wrong here.'
      : 'No cuts anywhere: the local sink on the risk-extract pod is empty and the archive has '
        + 'nothing either. On a rig that writes locally this means the end-of-day chain has not '
        + 'produced a cut, which is worth chasing — check that the EOD durables are bound and that '
        + 'a session has been published.');
  }
  /** Set by {@link explainEmptySink} once BOTH sources have answered; empty when there is nothing to say. */
  readonly rigNote = signal('');

  async ngOnInit(): Promise<void> {
    // Two independent sources, loaded independently: the rig's own sink is where a cut taken on
    // this rig lands (and the only place the contracts artifact exists), while the GCS bucket holds
    // the uploaded archive. Neither failing should blank the other.
    await Promise.all([this.loadRig(), this.loadArchive()]);
    this.loaded.set(true);
    this.explainEmptySink();
  }

  rowsOf(a: Artifact): string {
    const h = headers(a.content);
    return h['rows'] ?? h['contracts'] ?? '—';
  }

  toggle(c: Cut, a: Artifact): void {
    this.cuts.update(list => list.map(x =>
      x.key === c.key ? { ...x, open: x.open === a.kind ? '' : a.kind } : x));
  }

  private async loadRig(): Promise<void> {
    const r = await this.api.load<{ pod: string; files: { path: string; sha256: string; content: string }[]; error?: string }>('/extracts');
    if (r.status !== 200 || !r.body?.files) {
      this.error.set(bridgeError(r, 'the cut-sink bridge'));
      return;
    }
    if (!r.body.files.length) { this.rigEmpty.set(true); return; }
    this.rigEmpty.set(false);
    const byDir = new Map<string, typeof r.body.files>();
    for (const f of r.body.files) {
      const dir = f.path.slice(0, f.path.lastIndexOf('/'));
      byDir.set(dir, [...(byDir.get(dir) ?? []), f]);
    }
    const cuts: Cut[] = [];
    for (const [dir, files] of byDir) {
      const m = /\/(\d{4}-\d{2}-\d{2})\/(v\d+)$/.exec(dir);
      const cut = files.find(f => f.path.endsWith('.cut'));
      if (!cut) continue;
      const h = headers(cut.content);
      const artifacts: Artifact[] = [
        { kind: 'cut', label: 'cut — the committed source', path: cut.path, sha256: cut.sha256, content: cut.content },
      ];
      const add = (suffix: string, kind: string, label: string) => {
        const f = files.find(x => x.path.endsWith(suffix));
        if (f) artifacts.push({ kind, label, path: f.path, sha256: f.sha256, content: f.content });
      };
      add('-contracts.csv', 'contracts', 'contracts — OTC at contract grain');
      const positions = files.find(f => f.path.endsWith('.csv') && !f.path.endsWith('-contracts.csv'));
      if (positions) {
        artifacts.splice(1, 0, {
          kind: 'positions', label: 'positions — netted, no swap rows',
          path: positions.path, sha256: positions.sha256, content: positions.content,
        });
      }
      // The claim worth checking on screen: each derived artifact carries the sha of the cut it was
      // rebuilt from, so a mismatch means someone regenerated one of them from something else.
      const derived = artifacts.filter(a => a.kind !== 'cut');
      cuts.push({
        key: dir, source: 'rig cut sink', date: m?.[1] ?? '?', version: m?.[2] ?? '?',
        seq: h['seq'] ?? '', rows: h['rows'] ?? '', contracts: h['contracts'] ?? '',
        cutSha: cut.sha256,
        reproducible: derived.length
          ? derived.every(a => headers(a.content)['cutSha256'] === cut.sha256)
          : null,
        artifacts, open: '',
      });
    }
    this.cuts.update(list => order([...cuts, ...list]));
  }

  /** The uploaded GCS archive: older cuts, and no contracts artifact — it predates YU17. */
  private async loadArchive(): Promise<void> {
    const r = await this.api.load<{ bucket: string; files: string[]; error?: string }>('/gcs/extracts');
    if (r.status !== 200 || !r.body?.files) return;
    const cuts = r.body.files.filter(f => f.endsWith('.cut')).map(path => {
      const m = /\/(\d{4}-\d{2}-\d{2})\/(v\d+)\/[^/]+$/.exec(path);
      // The bucket also holds proof/<epochMillis>/ objects — an upload proof's own artifacts, not
      // a session cut. Labelled as what they are rather than rendered as an unparsed date.
      const proof = /\/proof\/(\d+)\//.exec(path);
      return {
        key: path, source: r.body!.bucket,
        date: m?.[1] ?? (proof ? 'upload proof' : '?'),
        version: m?.[2] ?? (proof ? new Date(Number(proof[1])).toISOString().slice(0, 10) : '?'),
        seq: '', rows: '', contracts: '', cutSha: '', reproducible: null,
        artifacts: [{ kind: 'cut', label: 'cut — archived object', path, sha256: '', content: '' }],
        open: '',
      } as Cut;
    });
    this.cuts.update(list => order([...list, ...cuts]));
    for (const c of cuts) this.enrichArchive(c);
  }

  private async enrichArchive(c: Cut): Promise<void> {
    const r = await this.api.load<{ sha256: string; content: string }>(
      `/gcs/read?path=${encodeURIComponent(c.artifacts[0].path)}`);
    if (r.status !== 200 || !r.body) return;
    const h = headers(r.body.content);
    this.cuts.update(list => order(list.map(x => x.key === c.key ? {
      ...x, seq: h['seq'] ?? '', rows: h['rows'] ?? '', cutSha: r.body!.sha256,
      artifacts: [{ ...x.artifacts[0], sha256: r.body!.sha256, content: r.body!.content }],
    } : x)));
  }
}
