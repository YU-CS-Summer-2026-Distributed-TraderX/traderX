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
      <div class="tile"><div class="v">{{ inflight() }}</div><div class="k">in flight</div></div>
      <div class="tile"><div class="v">{{ accepted() }}</div><div class="k">orders accepted</div></div>
      <div class="tile"><div class="v">{{ fills() }}</div><div class="k">fills</div></div>
      <div class="tile"><div class="v">{{ p50() }}</div><div class="k">consensus p50 µs (n={{ n() }})</div></div>
      <div class="tile"><div class="v">{{ p99() }}</div><div class="k">consensus p99 µs (n={{ n() }})</div></div>
    </div>
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
  readonly inflight = signal('—');
  readonly accepted = signal('—');
  readonly fills = signal('—');
  readonly p50 = signal('—');
  readonly p99 = signal('—');
  readonly n = signal(0);
  readonly latency = signal('');

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 2000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

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
      this.inflight.set(String(p['traderx_gateway_inflight_orders'] ?? '—'));
      this.accepted.set(String(p['traderx_order_events_total{event="accepted"}'] ?? '—'));
      this.fills.set(String(p['traderx_order_events_total{event="fill"}'] ?? '—'));
    }
    // /latency answers 503 with an informative body when LATENCY_DECOMP is off — show it either way.
    if (typeof l.body === 'string' && l.body) {
      this.latency.set(l.body.trim());
      const p = parseProm(l.body);
      // Sampled 1-in-2^mask, single-order path only — counts stay 0 until serial traffic flows.
      const count = p['traderx_gateway_latency_count{segment="cluster"}'] ?? 0;
      if (count) {
        this.n.set(count);
        this.p50.set(p['traderx_gateway_latency_us{segment="cluster",pct="p50"}']?.toFixed(0) ?? '—');
        this.p99.set(p['traderx_gateway_latency_us{segment="cluster",pct="p99"}']?.toFixed(0) ?? '—');
      }
    } else if (l.body && typeof l.body === 'object') this.latency.set(JSON.stringify(l.body, null, 1));
  }
}
