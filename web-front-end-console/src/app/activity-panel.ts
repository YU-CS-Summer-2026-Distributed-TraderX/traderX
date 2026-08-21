import { Component, inject, signal } from '@angular/core';
import { ActivityEntry, Api } from './api';
import { HelpTip } from './help';
import { TraceView } from './trace-view';


@Component({
  selector: 'activity-panel',
  imports: [HelpTip, TraceView],
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
              @if (e.ok && e.orderRef) { <button (click)="cancel(e)">Cancel order</button> }
            </div>
            <!-- One trace-view PER ENTRY, which also fixes a smaller bug: the panel had a single
                 spans() signal, so opening a second entry's trace replaced the first entry's table
                 in place while the first entry still looked like the one being viewed. -->
            @if (e.traceId) { <trace-view [traceId]="e.traceId" derivedFrom="order" /> }
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

  toggle(e: ActivityEntry): void {
    // Nothing to reset: each entry's trace-view owns its own state now.
    this.open.set(this.open() === e ? null : e);
  }

  async cancel(e: ActivityEntry): Promise<void> {
    const r = await this.api.post<{ canceled?: boolean }>('/order-matcher/cancel', { orderRef: e.orderRef });
    this.api.log({ kind: 'cancel', ok: r.status === 200, summary: `cancel orderRef ${e.orderRef} → HTTP ${r.status}` });
  }


}
