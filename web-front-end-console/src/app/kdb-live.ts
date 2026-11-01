import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';

interface Preset { id: string; title: string; q: string; }
interface QAnswer { columns?: string[]; rows?: unknown[][]; count?: number; ms?: number; error?: string; code?: string; }

/** FR-DA10: live q against the demo account's KDB-X history (demo-hdb.q behind KDB_Q_URL). */
@Component({
  selector: 'kdb-live',
  imports: [FormsModule],
  template: `
    <div class="card-head"><h2>KDB-X history · live q</h2><span class="spacer"></span>
      <span class="faint">account 99000001 · one year of trades, date-partitioned, queried by a real q process</span></div>
    @if (!configured()) { <div class="banner warn">No KDB-X query service is configured on this console (KDB_Q_URL).</div> }
    <div class="presets">
      @for (p of presets(); track p.id) {
        <button type="button" [class.on]="active() === p.id" (click)="run(p)" [disabled]="busy()" [attr.data-testid]="'kdb-preset-' + p.id">{{ p.title }}</button>
      }
    </div>
    <div class="qbox">
      <textarea rows="2" spellcheck="false" [(ngModel)]="text" data-testid="kdb-q"
        [placeholder]="api.authUser() ? 'select count i by sym from trades where date=last date' : 'sign in to run your own q'"
        [disabled]="!api.authUser()" (keydown.meta.enter)="runText()" (keydown.control.enter)="runText()"></textarea>
      <button type="button" class="btn-primary" (click)="runText()" [disabled]="busy() || !api.authUser() || !text.trim()" data-testid="kdb-run">Run q</button>
    </div>
    @if (answer(); as a) {
      @if (a.error) { <div class="banner bad" data-testid="kdb-error">'{{ a.error }}</div> }
      @else {
        <div class="faint meta" data-testid="kdb-meta">{{ a.count }} row{{ a.count === 1 ? '' : 's' }}@if ((a.count ?? 0) > (a.rows?.length ?? 0)) { (first {{ a.rows?.length }}) } · q {{ a.ms?.toFixed(1) }} ms · round trip {{ rtt() }} ms</div>
        <div class="scroll"><table class="grid" data-testid="kdb-result">
          <thead><tr>@for (c of a.columns; track $index) { <th>{{ c }}</th> }</tr></thead>
          <tbody>@for (r of a.rows; track $index) { <tr>@for (v of r; track $index) { <td>{{ v }}</td> }</tr> }</tbody>
        </table></div>
      }
    }
  `,
  styles: `
    .presets { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
    .presets button.on { border-color: var(--accent); color: var(--accent); }
    .qbox { display: flex; gap: 8px; align-items: stretch; }
    .qbox textarea { flex: 1; font-family: ui-monospace, Menlo, monospace; font-size: 13px; }
    .meta { margin: 8px 0 4px; }
    .scroll { max-height: 420px; overflow: auto; }
    td { font-variant-numeric: tabular-nums; white-space: nowrap; }
  `,
})
export class KdbLive implements OnInit {
  readonly api = inject(Api);
  readonly presets = signal<Preset[]>([]);
  readonly configured = signal(true);
  readonly busy = signal(false);
  readonly active = signal('');
  readonly answer = signal<QAnswer | null>(null);
  readonly rtt = signal(0);
  text = '';

  async ngOnInit(): Promise<void> {
    const r = await this.api.load<{ configured: boolean; presets: Preset[] }>('/kdbq/presets');
    if (r.status === 200 && r.body) { this.presets.set(r.body.presets); this.configured.set(r.body.configured); }
  }

  run(p: Preset): Promise<void> { this.active.set(p.id); this.text = p.q; return this.send({ preset: p.id }); }
  runText(): Promise<void> { this.active.set(''); return this.send({ q: this.text }); }

  private async send(body: object): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    const t0 = performance.now();
    try {
      const r = await this.api.load<QAnswer>('/kdbq', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      this.rtt.set(Math.round(performance.now() - t0));
      this.answer.set(r.body ?? { error: `HTTP ${r.status}` });
      if (r.body?.code === 'admin_auth_required') this.api.authPrompt.set(true);
    } finally { this.busy.set(false); }
  }
}
