import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

/**
 * Service-by-service reachability, the same idea as the older app's status page — but pointed at
 * this tier's services, through the edge proxy the console already speaks to, and including the
 * three cluster members and the gateway's readiness route (which is the only one here whose 503
 * carries a meaning beyond "process is down": it means the gateway cannot commit).
 */
interface Check { id: string; name: string; path: string; expect: number[]; note: string; }

const CHECKS: Check[] = [
  { id: 'gateway', name: 'Cluster gateway', path: '/order-matcher/ready', expect: [200],
    note: 'ready = it can commit to the log, not merely that its socket is open' },
  { id: 'm0', name: 'Cluster member 0', path: '/m0/health', expect: [200], note: 'per-pod health' },
  { id: 'm1', name: 'Cluster member 1', path: '/m1/health', expect: [200], note: 'per-pod health' },
  { id: 'm2', name: 'Cluster member 2', path: '/m2/health', expect: [200], note: 'per-pod health' },
  { id: 'reference-data', name: 'Reference data', path: '/reference-data/stocks', expect: [200],
    note: 'the instrument catalog every ticket resolves against' },
  { id: 'account-service', name: 'Account service', path: '/account-service/account/', expect: [200],
    note: 'the account directory' },
  { id: 'position-service', name: 'Position service', path: '/position-service/health/alive', expect: [200],
    note: 'the blotter read model' },
  { id: 'trade-service', name: 'Trade service', path: '/trade-service/v3/api-docs', expect: [200],
    note: 'no health route; its OpenAPI document answers instead' },
  { id: 'trade-processor', name: 'Trade processor', path: '/trade-processor/actuator/health', expect: [200],
    note: 'settlement, TCA, recon and the order read model' },
  { id: 'people-service', name: 'People service', path: '/people-service/People/GetPerson?LogonId=user01', expect: [200],
    note: 'upstream service, carried unchanged' },
  { id: 'price-publisher', name: 'Price publisher', path: '/price-publisher/health', expect: [200],
    note: 'the source of the live prices this console marks positions against' },
  { id: 'algo-engine', name: 'Execution algo engine', path: '/algo/orders', expect: [200],
    note: 'TWAP/VWAP parents; the proof suite parks it at zero replicas' },
];

interface Row extends Check {
  status: number | null; up: boolean; latencyMs: number | null; checkedAt: number; upSince: number;
}

@Component({
  selector: 'status-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>Service status</h2>
      </button>
      <help-tip text="One request per service, from the browser, through the same edge proxy every other panel uses — so a green row means the path this console actually depends on is working end to end, not that something somewhere reported itself healthy. Refreshes every 30 seconds. Latency is round-trip from the browser and includes the proxy hop, so it is a reachability figure, not a measure of the system's internal latency (the panel above measures that)." />
      <span class="spacer"></span>
      <span class="pill" [class.good]="downCount() === 0" [class.bad]="downCount() > 0">
        {{ rows().length - downCount() }}/{{ rows().length }} up</span>
      <button (click)="refresh()" [disabled]="checking()">{{ checking() ? '…' : 'Refresh' }}</button>
    </div>
    @if (open()) {
    <table>
      <thead><tr><th>service</th><th>state</th><th class="num">http</th><th class="num">latency</th>
        <th>checked</th><th>up since</th></tr></thead>
      <tbody>
        @for (r of rows(); track r.id) {
          <tr>
            <td>{{ r.name }}<div class="sub">{{ r.path }} — {{ r.note }}</div></td>
            <td><span class="pill" [class.good]="r.up" [class.bad]="!r.up && r.checkedAt > 0">
              {{ r.checkedAt === 0 ? '…' : r.up ? 'up' : 'down' }}</span></td>
            <td class="num">{{ r.status === 0 ? 'no answer' : r.status ?? '—' }}</td>
            <td class="num">{{ r.latencyMs === null ? '—' : r.latencyMs + ' ms' }}</td>
            <td class="sub">{{ ago(r.checkedAt) }}</td>
            <td class="sub">{{ r.upSince ? ago(r.upSince) : '—' }}</td>
          </tr>
        }
      </tbody>
    </table>
    }
  `,
  styles: `
    .spacer { flex: 1; }
    td .sub { font-size: 11.5px; }
  `,
})
export class StatusPanel implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  readonly rows = signal<Row[]>(CHECKS.map(c => ({
    ...c, status: null, up: false, latencyMs: null, checkedAt: 0, upSince: 0,
  })));
  readonly gatewayCount = signal(0);
  readonly open = signal(true);
  readonly checking = signal(false);
  readonly downCount = signal(0);
  private timer: ReturnType<typeof setInterval> | undefined;
  /** Re-render the relative times without re-probing anything. */
  private ticker: ReturnType<typeof setInterval> | undefined;
  readonly now = signal(Date.now());

  ngOnInit(): void {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 30_000);
    this.ticker = setInterval(() => this.now.set(Date.now()), 1000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); clearInterval(this.ticker); }

  /**
   * How many gateways there are is a scaling decision, not a constant — this rig runs three and the
   * count can change without the console being rebuilt. So the single static 'Cluster gateway' row
   * is replaced at refresh time by one row per running gateway, probed on its own /gw/<n> route.
   * A hardcoded row would keep reporting "1 gateway, up" while two others were down.
   */
  private async expandGateways(): Promise<void> {
    try {
      const res = await this.api.load<{ count: number }>('/gateways');
      const n = res.status === 200 && res.body?.count ? res.body.count : 0;
      if (!n || n === this.gatewayCount()) return;
      this.gatewayCount.set(n);
      const gwRows: Row[] = Array.from({ length: n }, (_, i) => ({
        id: `gw${i}`, name: `Cluster gateway ${i}`, path: `/gw/${i}/ready`, expect: [200],
        note: 'ready = it can commit to the log, not merely that its socket is open',
        status: null, up: false, latencyMs: null, checkedAt: 0, upSince: 0,
      }));
      this.rows.set([...gwRows, ...this.rows().filter(r => r.id !== 'gateway' && !r.id.startsWith('gw'))]);
    } catch { /* leave the static row in place — a discovery failure must not blank the panel */ }
  }

  async refresh(): Promise<void> {
    this.checking.set(true);
    await this.expandGateways();
    const probed = await Promise.all(this.rows().map(async r => {
      const started = Date.now();
      const res = await this.api.load<unknown>(r.path);
      const up = r.expect.includes(res.status);
      return {
        ...r, status: res.status, up, latencyMs: Date.now() - started, checkedAt: Date.now(),
        // Cleared on any failure, so "up since" always means an unbroken run of green checks.
        upSince: up ? (r.upSince || Date.now()) : 0,
      };
    }));
    this.rows.set(probed);
    this.downCount.set(probed.filter(r => !r.up).length);
    this.checking.set(false);
  }

  ago(at: number): string {
    if (!at) return '—';
    const s = Math.max(0, Math.round((this.now() - at) / 1000));
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    return `${Math.floor(s / 3600)}h ago`;
  }
}
