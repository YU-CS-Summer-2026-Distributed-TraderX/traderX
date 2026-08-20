import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Api, parseProm } from './api';
import { HelpTip } from './help';

@Component({
  selector: 'metrics-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>Latency &amp; throughput</h2>
      <help-tip text="Live from the gateway's /metrics and /latency endpoints — the same process serving the orders. 'Consensus' is the round trip through the replicated cluster: offer the order, reach quorum, receive the committed acknowledgement. Percentiles come from a 1-in-128 sample of single orders, so n says how many samples back the number. These are local-rig figures on a laptop, not the benchmarked production numbers." />
    </div>
    <div class="tiles">
      <div class="tile"><div class="v">{{ throughput() }}</div><div class="k">acks / s</div></div>
      <!-- Both of these are _total counters aggregated across the gateways, so their scope belongs
           on the glass: "8 fills" reads as recent activity, "8 fills since startup" reads as what
           it is. Same rule that renamed the recon pill. -->
      <div class="tile"><div class="v">{{ accepted() }}</div><div class="k">orders accepted<br>since startup</div></div>
      <div class="tile"><div class="v">{{ fills() }}</div><div class="k">fills<br>since startup</div></div>
    </div>

    <!-- PERCENTILES DO NOT SUM. /metrics is aggregated across the gateways because counters add;
         these cannot be, and there are no raw samples exposed to merge — only each gateway's own
         computed p50/p99. So they are shown side by side. Averaging three p99s would produce a
         number that is not any gateway's p99 and not the system's either, and one gateway's alone
         is a sample of a sample: orders spread across all three. -->
    <h3>Consensus latency per gateway <span class="sub">not summable — each gateway's own sample</span></h3>
    <table>
      <thead><tr><th>gateway</th><th class="num">p50 µs</th><th class="num">p99 µs</th>
        <th class="num">samples</th></tr></thead>
      <tbody>
        @for (g of gateways(); track g.ordinal) {
          <tr>
            <td>gateway-{{ g.ordinal }}</td>
            <td class="num">{{ g.n ? g.p50 : '—' }}</td>
            <td class="num">{{ g.n ? g.p99 : '—' }}</td>
            <td class="num" [class.faint]="!g.n">{{ g.n }}</td>
          </tr>
        } @empty { <tr><td colspan="4" class="faint">no gateway answered /latency</td></tr> }
      </tbody>
    </table>
    <div class="sub">Sampled 1-in-128 on the single-order path, so a gateway with
      <b>0 samples</b> has no percentile rather than a fast one — and a batch session moves the
      counters above while leaving every row here untouched.</div>
    <details>
      <summary class="sub">raw latency decomposition</summary>
      <pre class="lat">{{ latency() }}</pre>
    </details>
  `,
  styles: `
    .tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 8px; }
    .tile { background: #f8f9fb; border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; text-align: center; }
    .tile .v { font-size: 22px; font-weight: 600; font-family: var(--mono); color: var(--accent); }
    .tile .k { font-size: 11.5px; color: var(--muted); margin-top: 2px; }
    details { margin-top: 8px; }
    summary { cursor: pointer; }
    .lat { font-family: var(--mono); font-size: 11px; color: var(--muted); white-space: pre-wrap;
           max-height: 180px; overflow-y: auto; background: #f8f9fb; border-radius: 6px; padding: 8px; }
  `,
})
export class MetricsPanel implements OnInit, OnDestroy {
  private api = inject(Api);
  private timer: ReturnType<typeof setInterval> | undefined;
  private lastAcks = -1;
  private lastAt = 0;

  readonly throughput = signal('—');
  readonly accepted = signal('—');
  readonly fills = signal('—');
  readonly latency = signal('');
  /** One row per gateway: percentiles cannot be merged, so they are never merged. */
  readonly gateways = signal<{ ordinal: number; p50: string; p99: string; n: number }[]>([]);

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 2000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  /**
   * Ask every gateway for its own percentiles. The Service load-balances, so a single /latency call
   * answers from one arbitrary pod — a third of the traffic's view, presented as the system's.
   */
  private async pollGateways(): Promise<void> {
    const d = await this.api.load<{ count: number }>('/gateways');
    const n = d.status === 200 && d.body?.count ? d.body.count : 0;
    if (!n) { this.gateways.set([]); return; }
    const rows = await Promise.all(Array.from({ length: n }, async (_, i) => {
      const r = await this.api.load<string>(`/gw/${i}/latency`);
      if (typeof r.body !== 'string') return { ordinal: i, p50: '—', p99: '—', n: 0 };
      const p = parseProm(r.body);
      return {
        ordinal: i,
        p50: p['traderx_gateway_latency_us{segment="cluster",pct="p50"}']?.toFixed(0) ?? '—',
        p99: p['traderx_gateway_latency_us{segment="cluster",pct="p99"}']?.toFixed(0) ?? '—',
        n: p['traderx_gateway_latency_count{segment="cluster"}'] ?? 0,
      };
    }));
    this.gateways.set(rows);
  }

  private async poll(): Promise<void> {
    const [m, l] = await Promise.all([
      this.api.load<string>('/order-matcher/metrics'),
      this.api.load<string>('/order-matcher/latency'),
    ]);
    if (m.status === 200 && typeof m.body === 'string') {
      const p = parseProm(m.body);
      const acks = p['traderx_gateway_pipeline_total{stage="ack_completed"}'] ?? 0;
      const now = Date.now();
      if (this.lastAcks >= 0 && now > this.lastAt) {
        this.throughput.set(((acks - this.lastAcks) / ((now - this.lastAt) / 1000)).toFixed(1));
      }
      this.lastAcks = acks; this.lastAt = now;
      this.accepted.set(String(p['traderx_order_events_total{event="accepted"}'] ?? '—'));
      this.fills.set(String(p['traderx_order_events_total{event="fill"}'] ?? '—'));
    }
    // /latency answers 503 with an informative body when LATENCY_DECOMP is off — show it either way.
    if (typeof l.body === 'string' && l.body) this.latency.set(l.body.trim());
    else if (l.body && typeof l.body === 'object') this.latency.set(JSON.stringify(l.body, null, 1));
    await this.pollGateways();
  }
}
