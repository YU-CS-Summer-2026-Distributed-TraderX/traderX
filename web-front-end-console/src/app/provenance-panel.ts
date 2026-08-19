import { Component, OnInit, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

interface Cut {
  path: string; date: string; version: string;
  seq?: string; rows?: string; sha256?: string; content?: string; open?: boolean;
}

@Component({
  selector: 'provenance-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>EOD cut provenance</h2>
      <help-tip text="At end of day the cluster produces a risk extract — a cut of every position at an exact consensus sequence number, against a specific published price version. Because the engine is deterministic, all three members produce byte-identical files; the SHA-256 shown here is the fingerprint of that determinism claim. These are the actual archived objects from the Google Cloud Storage bucket, fetched read-only through the developer's own credentials." />
      <span class="spacer"></span>
      <span class="faint">{{ bucket() }}</span>
    </div>
    @if (error()) { <div class="banner bad">{{ error() }}</div> }
    <table>
      <thead><tr><th>session date</th><th>price version</th><th>consensus seq</th><th>rows</th><th>sha-256</th><th></th></tr></thead>
      <tbody>
        @for (c of cuts(); track c.path) {
          <tr>
            <td>{{ c.date }}</td><td>{{ c.version }}</td>
            <td class="num">{{ c.seq ?? '…' }}</td><td class="num">{{ c.rows ?? '…' }}</td>
            <td class="sha">{{ c.sha256 ? c.sha256.slice(0, 16) + '…' : '…' }}</td>
            <td><button (click)="toggle(c)">{{ c.open ? 'hide' : 'view' }}</button></td>
          </tr>
          @if (c.open && c.content) {
            <tr><td colspan="6"><pre class="cut">{{ c.content }}</pre></td></tr>
          }
        } @empty { <tr><td colspan="6" class="faint">{{ error() ? '' : 'loading archive…' }}</td></tr> }
      </tbody>
    </table>
  `,
  styles: `
    .spacer { flex: 1; }
    .sha { font-family: var(--mono); font-size: 11.5px; color: var(--muted); }
    .cut { font-family: var(--mono); font-size: 11.5px; color: var(--muted); background: #f8f9fb;
           border-radius: 6px; padding: 8px; max-height: 220px; overflow: auto; margin: 4px 0; }
  `,
})
export class ProvenancePanel implements OnInit {
  private api = inject(Api);
  readonly cuts = signal<Cut[]>([]);
  readonly bucket = signal('');
  readonly error = signal('');

  async ngOnInit(): Promise<void> {
    const r = await this.api.load<{ bucket: string; files: string[]; error?: string }>('/gcs/extracts');
    if (r.status !== 200 || !r.body?.files) {
      this.error.set(r.body?.error ?? 'archive unreachable (dev proxy + gcloud required)');
      return;
    }
    this.bucket.set(r.body.bucket);
    const cuts: Cut[] = r.body.files.filter(f => f.endsWith('.cut')).map(path => {
      const m = /\/(\d{4}-\d{2}-\d{2})\/(v\d+)\/[^/]+$/.exec(path);
      return { path, date: m?.[1] ?? '?', version: m?.[2] ?? '?' };
    });
    this.cuts.set(cuts);
    for (const c of cuts) this.enrich(c);
  }

  private async enrich(c: Cut): Promise<void> {
    const r = await this.api.load<{ sha256: string; content: string }>(`/gcs/read?path=${encodeURIComponent(c.path)}`);
    if (r.status !== 200 || !r.body) return;
    // header: "#cut schema=1 seq=394 sessionDateEpochDay=20657 priceVersion=1 rows=8"
    const h = Object.fromEntries([...r.body.content.split('\n')[0].matchAll(/(\w+)=(\S+)/g)].map(m => [m[1], m[2]]));
    this.cuts.update(list => list.map(x => x.path === c.path
      ? { ...x, seq: h['seq'], rows: h['rows'], sha256: r.body!.sha256, content: r.body!.content } : x));
  }

  toggle(c: Cut): void {
    this.cuts.update(list => list.map(x => x.path === c.path ? { ...x, open: !x.open } : x));
  }
}
