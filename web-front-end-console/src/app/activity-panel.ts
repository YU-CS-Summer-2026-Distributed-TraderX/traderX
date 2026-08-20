import { Component, inject, signal } from '@angular/core';
import { ActivityEntry, Api } from './api';
import { HelpTip } from './help';
import { Gated } from './gated';

interface SpanRow { service: string; name: string; startNs: bigint; durUs: number; }

@Component({
  selector: 'activity-panel',
  imports: [HelpTip, Gated],
  template: `
    <div class="card-head">
      <h2>Activity &amp; rejections</h2>
      <help-tip text="Everything submitted from this console, with its outcome. The system is fail-closed: when it cannot prove an order is safe to accept, it refuses, and every refusal carries a stable reason code. Click an order to see its details — including its distributed trace: the trace id is derived deterministically from the order itself, and rejected orders are always traced, so the exact path of a refusal through gateway and cluster is one click away." />
    </div>
    <div class="feed">
      @for (e of api.activity(); track e.at.getTime()) {
        <div class="entry" [class.bad]="!e.ok" [class.click]="e.kind === 'order'"
             (click)="e.kind === 'order' ? toggle(e) : null">
          <span class="t">{{ e.at.toTimeString().slice(0, 8) }}</span>
          <span class="k">{{ e.kind }}</span>
          @if (e.reason) { <span class="pill bad">{{ e.reason }}</span> }
          <span class="s">{{ e.summary }}</span>
          @if (e.kind === 'order') { <span class="more">{{ open() === e ? '▾' : '▸' }}</span> }
        </div>
        @if (open() === e) {
          <div class="details">
            <div class="kv">
              @if (e.orderRef) { <span>orderRef <b>{{ e.orderRef }}</b></span> }
              @if (e.clientOrderId) { <span>clientOrderId <b>{{ e.clientOrderId }}</b></span> }
              @if (e.traceId) { <span>trace <b class="mono">{{ e.traceId }}</b></span> }
              @if (e.ok && e.orderRef) { <button (click)="cancel(e)">Cancel order</button> <gated /> }
              @if (e.traceId) { <button (click)="loadTrace(e)">{{ spans() ? 'refresh trace' : 'view trace' }}</button> }
            </div>
            @if (traceMsg()) { <div class="faint">{{ traceMsg() }}</div> }
            @if (spans(); as ss) {
              <table class="spans">
                <thead><tr><th>service</th><th>span</th><th class="num">start +µs</th><th class="num">duration µs</th></tr></thead>
                <tbody>
                  @for (s of ss; track $index) {
                    <tr><td>{{ s.service }}</td><td>{{ s.name }}</td>
                        <td class="num">{{ rel(s, ss) }}</td><td class="num">{{ s.durUs.toFixed(0) }}</td></tr>
                  }
                </tbody>
              </table>
            }
          </div>
        }
      } @empty { <div class="faint">no activity yet — submit an order</div> }
    </div>
  `,
  styles: `
    .feed { max-height: 300px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
    .entry { font-size: 12.5px; display: flex; gap: 8px; align-items: baseline; padding: 3px 6px; border-radius: 5px; }
    .entry.bad { background: var(--bad-soft); }
    .entry.click { cursor: pointer; }
    .entry.click:hover { background: #f0f2f5; }
    .entry.bad.click:hover { background: #fbe4e2; }
    .t { color: var(--faint); font-family: var(--mono); font-size: 11.5px; }
    .k { color: var(--accent); min-width: 52px; font-weight: 500; }
    .s { color: var(--text); }
    .more { color: var(--faint); margin-left: auto; }
    .details { background: #f8f9fb; border: 1px solid var(--border); border-radius: 7px; padding: 8px 10px; margin: 2px 0 6px; }
    .kv { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; font-size: 12.5px; color: var(--muted); }
    .kv b { color: var(--text); font-weight: 600; }
    .mono { font-family: var(--mono); font-size: 11.5px; }
    .spans { margin-top: 6px; }
    .spans td, .spans th { font-size: 12px; }
  `,
})
export class ActivityPanel {
  readonly api = inject(Api);
  readonly open = signal<ActivityEntry | null>(null);
  readonly spans = signal<SpanRow[] | null>(null);
  readonly traceMsg = signal('');

  toggle(e: ActivityEntry): void {
    this.spans.set(null);
    this.traceMsg.set('');
    this.open.set(this.open() === e ? null : e);
  }

  async cancel(e: ActivityEntry): Promise<void> {
    const r = await this.api.post<{ canceled?: boolean }>('/order-matcher/cancel', { orderRef: e.orderRef });
    this.api.log({ kind: 'cancel', ok: r.status === 200, summary: `cancel orderRef ${e.orderRef} → HTTP ${r.status}` });
  }

  async loadTrace(e: ActivityEntry): Promise<void> {
    this.traceMsg.set('fetching trace…');
    const r = await this.api.load<any>(`/tempo/api/traces/${e.traceId}`);
    if (r.status !== 200 || !r.body?.batches) {
      this.traceMsg.set(r.status === 404
        ? 'trace not in Tempo — accepted orders are head-sampled (1-in-N); rejected orders are always traced'
        : `Tempo unreachable (HTTP ${r.status})`);
      this.spans.set(null);
      return;
    }
    const rows: SpanRow[] = [];
    for (const b of r.body.batches) {
      const service = b.resource?.attributes?.find((a: any) => a.key === 'service.name')?.value?.stringValue ?? '?';
      for (const ss of b.scopeSpans ?? []) {
        for (const sp of ss.spans ?? []) {
          rows.push({
            service, name: sp.name,
            startNs: BigInt(sp.startTimeUnixNano ?? 0),
            durUs: Number(BigInt(sp.endTimeUnixNano ?? 0) - BigInt(sp.startTimeUnixNano ?? 0)) / 1000,
          });
        }
      }
    }
    rows.sort((a, b) => (a.startNs < b.startNs ? -1 : 1));
    this.spans.set(rows);
    this.traceMsg.set(rows.length ? '' : 'trace exists but has no spans yet');
  }

  rel(s: SpanRow, all: SpanRow[]): string {
    return (Number(s.startNs - all[0].startNs) / 1000).toFixed(0);
  }
}
