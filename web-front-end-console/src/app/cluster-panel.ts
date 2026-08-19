import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Api, GatewayHealth, MemberHealth } from './api';
import { HelpTip } from './help';

@Component({
  selector: 'cluster-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>Aeron cluster</h2>
      <help-tip text="The matching engine runs as three replicated members under Raft consensus. Every order is sequenced through the cluster, and each member applies the same events in the same order — so their counters should always be identical. This table reads each member's own health endpoint directly. If a member is killed, the other two keep serving and the counters stay in lockstep." />
    </div>
    <div class="banner" [class.good]="agreed()" [class.bad]="!agreed()">
      @if (agreed()) { ✓ members agree — applied {{ members()[0]?.applied }} }
      @else { ⚠ members diverge or unreachable }
    </div>
    <table>
      <thead><tr><th></th><th>role</th><th class="num">applied</th><th class="num">engineApplied</th><th class="num">trades</th><th class="num">snapshots</th></tr></thead>
      <tbody>
        @for (m of members(); track $index) {
          <tr>
            <td>member-{{ $index }}</td>
            @if (m) {
              <td><span class="pill" [class.warn]="m.role === 'LEADER'">{{ m.role }}</span></td>
              <td class="num">{{ m.applied }}</td><td class="num">{{ m.engineApplied }}</td>
              <td class="num">{{ m.trades }}</td><td class="num">{{ m.snapshots }}</td>
            } @else { <td colspan="5" class="faint">unreachable</td> }
          </tr>
        }
        <tr>
          <td>gateway</td>
          @if (gateway(); as g) {
            <td colspan="5">{{ g.connected ? 'connected' : 'DISCONNECTED' }} · noAckStreak {{ g.noAckStreak }}</td>
          } @else { <td colspan="5" class="faint">unreachable</td> }
        </tr>
      </tbody>
    </table>
  `,
  styles: `.banner { margin-bottom: 10px; font-family: var(--mono); font-size: 12.5px; }`,
})
export class ClusterPanel implements OnInit, OnDestroy {
  private api = inject(Api);
  private timer: ReturnType<typeof setInterval> | undefined;

  readonly members = signal<(MemberHealth | null)[]>([null, null, null]);
  readonly gateway = signal<GatewayHealth | null>(null);
  readonly agreed = computed(() => {
    const ms = this.members();
    return ms.every(m => m !== null)
      && new Set(ms.map(m => m!.applied)).size === 1
      && new Set(ms.map(m => m!.trades)).size === 1;
  });

  ngOnInit(): void {
    this.poll();
    this.timer = setInterval(() => this.poll(), 2000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  private async poll(): Promise<void> {
    const [m0, m1, m2, gw] = await Promise.all([
      this.api.load<MemberHealth>('/m0/health'),
      this.api.load<MemberHealth>('/m1/health'),
      this.api.load<MemberHealth>('/m2/health'),
      this.api.load<GatewayHealth>('/order-matcher/health'),
    ]);
    // A missing /mN proxy route falls through to the SPA and returns 200 HTML — require the
    // actual member shape, not just a 200.
    this.members.set([m0, m1, m2].map(r =>
      r.status === 200 && r.body && typeof r.body === 'object' && 'applied' in (r.body as object)
        ? r.body : null));
    this.gateway.set(gw.status === 200 || gw.status === 503 ? gw.body : null);
  }
}
