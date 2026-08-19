import { Component, inject } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

@Component({
  selector: 'activity-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>Activity &amp; rejections</h2>
      <help-tip text="Everything submitted from this console, with its outcome. The system is fail-closed: when it cannot prove an order is safe to accept — no price for the currency, unknown account, price outside the book's band — it refuses, and every refusal carries a stable reason code rather than a generic error." />
    </div>
    <div class="feed">
      @for (e of api.activity(); track e.at.getTime()) {
        <div class="entry" [class.bad]="!e.ok">
          <span class="t">{{ e.at.toTimeString().slice(0, 8) }}</span>
          <span class="k">{{ e.kind }}</span>
          @if (e.reason) { <span class="pill bad">{{ e.reason }}</span> }
          <span class="s">{{ e.summary }}</span>
        </div>
      } @empty { <div class="faint">no activity yet — submit an order</div> }
    </div>
  `,
  styles: `
    .feed { max-height: 260px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
    .entry { font-size: 12.5px; display: flex; gap: 8px; align-items: baseline; padding: 3px 6px; border-radius: 5px; }
    .entry.bad { background: var(--bad-soft); }
    .t { color: var(--faint); font-family: var(--mono); font-size: 11.5px; }
    .k { color: var(--accent); min-width: 52px; font-weight: 500; }
    .s { color: var(--text); }
  `,
})
export class ActivityPanel {
  readonly api = inject(Api);
}
