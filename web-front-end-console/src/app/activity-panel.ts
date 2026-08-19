import { Component, inject } from '@angular/core';
import { Api } from './api';

@Component({
  selector: 'activity-panel',
  template: `
    <h2>Activity &amp; rejections <span class="sub">fail-closed: every reject carries its reason code</span></h2>
    <div class="feed">
      @for (e of api.activity(); track e.at.getTime()) {
        <div class="entry" [class.bad]="!e.ok">
          <span class="t">{{ e.at.toTimeString().slice(0, 8) }}</span>
          <span class="k">{{ e.kind }}</span>
          @if (e.reason) { <span class="reason">{{ e.reason }}</span> }
          <span class="s">{{ e.summary }}</span>
        </div>
      } @empty { <div class="none">no activity yet — submit an order</div> }
    </div>
  `,
  styles: `
    .feed { max-height: 240px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
    .entry { font-size: 12px; display: flex; gap: 6px; align-items: baseline; padding: 2px 4px; border-radius: 2px; }
    .entry.bad { background: #2e1616; }
    .t { color: #666; font-variant-numeric: tabular-nums; }
    .k { color: #8fb8e8; min-width: 52px; }
    .reason { color: #ff9d9d; font-weight: 600; }
    .s { color: #bbb; }
    .none { color: #666; font-size: 12px; }
  `,
})
export class ActivityPanel {
  readonly api = inject(Api);
}
