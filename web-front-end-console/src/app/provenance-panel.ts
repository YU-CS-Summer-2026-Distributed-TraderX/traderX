import { Component, OnInit, inject, signal } from '@angular/core';
import { Api } from './api';
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

const headers = (content: string) =>
  kv(content.split('\n').filter(l => l.startsWith('#')).join(' '));

@Component({
  selector: 'provenance-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>EOD cut provenance</h2>
      <help-tip text="At end of day the cluster takes a cut: every position at an exact consensus sequence number, against a specific published price version. Because the engine is deterministic, all three members render byte-identical files from it — the SHA-256 here is the fingerprint of that claim. Each cut produces two artifacts from one committed source: the netted positions, and the OTC contracts. Both name the cut they were rebuilt from, so 'reproduces from the cut alone' is something you can check on this screen rather than take on faith. Swaps appear only in the contracts file, never as position rows — netting a pay-fixed against a receive-fixed would destroy both rates." />
    </div>

    @if (rigNote()) { <div class="banner warn-note">{{ rigNote() }}</div> }
    @for (c of cuts(); track c.key) {
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
      <table>
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
    @if (cuts().length && error()) { <div class="sub err">{{ error() }}</div> }
  `,
  styles: `
    .spacer { flex: 1; }
    .cut-head { display: flex; align-items: center; gap: 8px; margin: 16px 0 4px; font-size: 13px; }
    .cut-head:first-of-type { margin-top: 4px; }
    .sha { font-family: var(--mono); font-size: 11.5px; color: var(--muted); }
    td .sub { font-size: 11px; }
    .cut { font-family: var(--mono); font-size: 11.5px; color: var(--muted); background: #f8f9fb;
           border-radius: 6px; padding: 8px; max-height: 260px; overflow: auto; margin: 4px 0; }
    .err { margin-top: 10px; }
    .warn-note { background: var(--warn-soft); color: var(--warn); font-size: 12.5px; margin-bottom: 6px; }
  `,
})
export class ProvenancePanel implements OnInit {
  private api = inject(Api);
  readonly cuts = signal<Cut[]>([]);
  readonly error = signal('');
  readonly loaded = signal(false);
  /**
   * An empty sink has to announce itself. The extract's volume is an emptyDir (deliberately —
   * durability is the GCS sink's job on the GKE overlay), so rescheduling deploy/risk-extract
   * silently deletes every cut on the epoch. Without this line the panel would simply render the
   * archive rows and look fine, which is exactly how "it was full an hour ago" happens mid-demo.
   */
  readonly rigNote = signal('');

  async ngOnInit(): Promise<void> {
    // Two independent sources, loaded independently: the rig's own sink is where a cut taken on
    // this rig lands (and the only place the contracts artifact exists), while the GCS bucket holds
    // the uploaded archive. Neither failing should blank the other.
    await Promise.all([this.loadRig(), this.loadArchive()]);
    this.loaded.set(true);
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
      this.error.set(r.body?.error ?? 'rig cut sink unreachable (dev proxy + kubectl required)');
      return;
    }
    if (!r.body.files.length) {
      this.rigNote.set('No cuts on the rig sink. risk-extract mounts an emptyDir, so rescheduling '
        + 'that pod deletes every cut written on this epoch — nothing older survives a restart. '
        + 'Take a fresh cut before demonstrating this panel, and leave the pod alone afterwards.');
      return;
    }
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
    cuts.sort((a, b) => (a.date === b.date ? a.version.localeCompare(b.version) : a.date.localeCompare(b.date)));
    this.cuts.update(list => [...cuts.reverse(), ...list]);
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
    this.cuts.update(list => [...list, ...cuts]);
    for (const c of cuts) this.enrichArchive(c);
  }

  private async enrichArchive(c: Cut): Promise<void> {
    const r = await this.api.load<{ sha256: string; content: string }>(
      `/gcs/read?path=${encodeURIComponent(c.artifacts[0].path)}`);
    if (r.status !== 200 || !r.body) return;
    const h = headers(r.body.content);
    this.cuts.update(list => list.map(x => x.key === c.key ? {
      ...x, seq: h['seq'] ?? '', rows: h['rows'] ?? '', cutSha: r.body!.sha256,
      artifacts: [{ ...x.artifacts[0], sha256: r.body!.sha256, content: r.body!.content }],
    } : x));
  }
}
