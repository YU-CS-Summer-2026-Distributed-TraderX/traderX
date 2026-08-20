import { Component, OnInit, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

/** A dashboard shipped in the observability ConfigMap, deep-linked by its own uid. */
interface Dash { uid: string; name: string; note: string; }

const DASHBOARDS: Dash[] = [
  { uid: 'traderx-cluster-support', name: 'Cluster — Support', note: 'the Aeron cluster itself: roles, applied sequences, commit health' },
  { uid: 'traderx-order-matcher-sli', name: 'Order Matcher SLI', note: 'the engine\'s service-level indicators' },
  { uid: 'traderx-risk-gateway', name: 'Risk Gateway (YU03)', note: 'the pre-trade gate: admissions, refusals and their reason codes' },
  { uid: 'traderx-lmax-throughput', name: 'LMAX Throughput', note: 'orders and acks per second through the disruptor path' },
  { uid: 'traderx-lmax-benchmark-throughput', name: 'LMAX Benchmark Throughput', note: 'the banked bench comparison, not live-rig figures' },
  { uid: 'traderx-trades-per-second', name: 'Trades/sec — 009 vs 009b', note: 'the before/after of the sequencer work' },
  { uid: 'traderx-order-management-observability', name: 'Order Management Observability', note: 'order lifecycle end to end across services' },
  { uid: 'traderx-eod-batch-chain', name: 'EOD Price Production & Batch Chain (YU06)', note: 'sessions published, quality flags, accounts marked and halted' },
  { uid: 'traderx-post-trade-compliance', name: 'Post-Trade Compliance (YU05)', note: 'recon cursor, mismatches, orphan sweeps, settlement rate' },
  { uid: 'traderx-pricing-pipeline-health', name: 'Pricing Pipeline Health', note: 'the feed behind every mark this console shows' },
  { uid: 'traderx-obs-012-pricing', name: 'Pricing + Observability', note: 'pricing with the tracing overlay' },
  { uid: 'traderx-nats-messaging-overview', name: 'NATS Messaging Overview', note: 'the message bus the blotter subscribes to' },
  { uid: 'traderx-message-bus-connectivity', name: 'Message Bus Connectivity', note: 'who is connected to the bus and who fell off' },
  { uid: 'traderx-runtime-health', name: 'Runtime Health', note: 'endpoint availability — the same question the Service status panel asks' },
  { uid: 'traderx-spring-service-sli', name: 'Spring Service SLI', note: 'the Java services around the engine' },
  { uid: 'traderx-spring-actuator-overview', name: 'Spring Actuator Overview', note: 'JVM, pools and actuator internals' },
  { uid: 'traderx-obs-control-plane', name: 'Observability Control Plane', note: 'is the observability stack itself scraping' },
  { uid: 'traderx-logs-errors', name: 'Logs & Errors', note: 'Loki log throughput and error rates by service' },
];

@Component({
  selector: 'grafana-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>Grafana observability</h2>
      <help-tip text="The rig runs a full observability stack — Prometheus for metrics, Loki for logs, Tempo for traces — with Grafana over all three and these dashboards provisioned from a ConfigMap. The console's own System page answers a handful of questions live; Grafana is where the history and the cross-service view live. It opens in a new tab rather than embedding here: anonymous access is off and Grafana refuses to be framed, both of which are the correct settings and neither of which this page should work around." />
      <span class="spacer"></span>
      <span class="pill" [class.good]="up() === true" [class.bad]="up() === false">
        {{ up() === null ? 'checking…' : up() ? 'grafana up' : 'grafana unreachable' }}</span>
    </div>

    <div class="hero">
      <div>
        <div class="lead">Open Grafana</div>
        <div class="sub">Sign in with <span class="mono">admin / admin</span> — the dev rig's
          credentials, set in the deployment. Prometheus, Loki and Tempo are wired as datasources.</div>
      </div>
      <a class="btn-primary open" [href]="base" target="_blank" rel="noopener">Open Grafana ↗</a>
    </div>

    <h3>Dashboards <span class="sub">provisioned on this rig — each opens directly</span></h3>
    <div class="grid">
      @for (d of dashboards; track d.uid) {
        <a class="dash" [href]="base + 'd/' + d.uid" target="_blank" rel="noopener">
          <div class="n">{{ d.name }}</div>
          <div class="sub">{{ d.note }}</div>
        </a>
      }
    </div>

    <div class="sub note">Links go through the same edge proxy as everything else
      (<span class="mono">/grafana/</span>, with Grafana configured to serve from that sub-path), so
      they work wherever the console is reachable from. What this page cannot do is show you a panel
      inline — that would need anonymous access enabled and framing allowed, and turning either on
      to decorate a console page is the wrong trade.</div>
  `,
  styles: `
    .spacer { flex: 1; }
    .hero { display: flex; align-items: center; gap: 20px; padding: 14px 16px; border-radius: 10px;
            background: var(--accent-soft); margin-bottom: 6px; }
    .lead { font-size: 15px; font-weight: 600; color: var(--accent); }
    .hero .sub { margin-top: 3px; max-width: 560px; }
    .open { margin-left: auto; text-decoration: none; padding: 8px 16px; border-radius: 8px;
            font-size: 13.5px; white-space: nowrap; }
    h3 { margin: 18px 0 8px; font-size: 12.5px; font-weight: 600; color: var(--muted); }
    h3 .sub { font-weight: 400; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 8px; }
    .dash { display: block; padding: 9px 12px; border: 1px solid var(--border); border-radius: 8px;
            text-decoration: none; background: #fff; }
    .dash:hover { border-color: var(--accent); background: #f8f9fb; }
    .dash .n { font-size: 13px; font-weight: 600; color: var(--text); }
    .dash .sub { margin-top: 2px; }
    .mono { font-family: var(--mono); font-size: 12px; }
    .note { margin-top: 14px; max-width: 760px; }
  `,
})
export class GrafanaPanel implements OnInit {
  private api = inject(Api);
  readonly dashboards = DASHBOARDS;
  /** Same-origin through the dev proxy, so the link works from wherever the console is served. */
  readonly base = '/grafana/';
  readonly up = signal<boolean | null>(null);

  async ngOnInit(): Promise<void> {
    // Grafana answers its own health route without a session. Check the BODY, not just the status:
    // a dev server answers unknown paths with its SPA fallback at 200, so status alone reports a
    // missing proxy route as a healthy Grafana — which is precisely the wrong answer.
    const r = await this.api.load<{ database?: string }>('/grafana/api/health');
    this.up.set(r.status === 200 && !!r.body && typeof r.body === 'object' && 'database' in r.body);
  }
}
