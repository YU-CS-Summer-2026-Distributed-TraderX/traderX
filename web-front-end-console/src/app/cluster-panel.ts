import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Api, GatewayHealth, MemberHealth } from './api';

@Component({
  selector: 'cluster-panel',
  template: `
    <h2>Aeron cluster <span class="sub">3 members, Raft consensus</span></h2>
    <div class="agree" [class.split]="!agreed()">
      @if (agreed()) { ✓ members agree — applied {{ members()[0]?.applied }} }
      @else { ⚠ members diverge or unreachable }
    </div>
    <table>
      <thead><tr><th></th><th>role</th><th>applied</th><th>engineApplied</th><th>trades</th><th>snapshots</th></tr></thead>
      <tbody>
        @for (m of members(); track $index) {
          <tr [class.dead]="!m" [class.leader]="m?.role === 'LEADER'">
            <td>member-{{ $index }}</td>
            @if (m) {
              <td>{{ m.role }}</td><td>{{ m.applied }}</td><td>{{ m.engineApplied }}</td>
              <td>{{ m.trades }}</td><td>{{ m.snapshots }}</td>
            } @else { <td colspan="5">unreachable</td> }
          </tr>
        }
        <tr class="gw">
          <td>gateway</td>
          @if (gateway(); as g) {
            <td colspan="5">{{ g.connected ? 'connected' : 'DISCONNECTED' }} · noAckStreak {{ g.noAckStreak }}</td>
          } @else { <td colspan="5">unreachable</td> }
        </tr>
      </tbody>
    </table>
  `,
  styles: `
    .agree { padding: 4px 8px; margin: 4px 0; border-radius: 3px; background: #143d14; color: #7be07b; font-variant-numeric: tabular-nums; }
    .agree.split { background: #4d1414; color: #ff9d9d; }
    tr.leader td { color: #ffd479; }
    tr.dead td, tr.gw td { color: #999; }
  `,
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
