import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Api } from './api';

export interface GatewayCount { at: string; ready: number; starting: number; draining: number; }
export interface ScaleChange { at: number; from: number; to: number; }

/** FR-DA09: a change is recorded only when the READY count moves; starting/draining are shown live, not logged. */
export function nextChanges(log: ScaleChange[], prev: number | null, ready: number, at: number): ScaleChange[] {
  return prev === null || prev === ready ? log : [{ at, from: prev, to: ready }, ...log].slice(0, 12);
}

const WINDOW = 200;   // samples at 3 s: the last 10 minutes

@Component({
  selector: 'gateway-scale',
  template: `
    <div class="card-head"><h2>Gateways</h2><span class="spacer"></span>
      <span class="faint">every 3 s · the HPA adds a gateway when their CPU passes 60%</span></div>
    @if (error()) { <div class="banner bad">{{ error() }}</div> }
    <div class="scale">
      <div class="big" data-testid="gateway-ready">{{ now()?.ready ?? '—' }}<span class="unit">ready</span></div>
      <div class="side">
        @if (now()?.starting) { <span class="pill warn" data-testid="gateway-starting">+{{ now()!.starting }} starting</span> }
        @if (now()?.draining) { <span class="pill" data-testid="gateway-draining">{{ now()!.draining }} draining</span> }
        @if (now() && !now()!.starting && !now()!.draining) { <span class="pill good">steady</span> }
      </div>
      <svg class="strip" [attr.viewBox]="'0 0 ' + WINDOW + ' 40'" preserveAspectRatio="none" aria-label="ready gateways, last 10 minutes">
        @for (b of bars(); track $index) { <rect [attr.x]="$index" [attr.y]="40 - b" width="1" [attr.height]="b" /> }
      </svg>
    </div>
    <ol class="changes" data-testid="gateway-changes">
      @for (c of changes(); track c.at) {
        <li><span class="faint">{{ time(c.at) }}</span> <b>{{ c.from }} → {{ c.to }}</b> {{ c.to > c.from ? 'scaled out' : 'scaled in' }}</li>
      } @empty { <li class="faint">No change since this page opened.</li> }
    </ol>
  `,
  styles: `
    .scale { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; }
    .big { font-size: 44px; font-weight: 700; line-height: 1; font-variant-numeric: tabular-nums; }
    .big .unit { font-size: 14px; font-weight: 500; color: var(--muted); margin-left: 6px; }
    .side { display: flex; gap: 6px; }
    .strip { flex: 1 1 240px; height: 40px; min-width: 160px; }
    .strip rect { fill: var(--accent); }
    .changes { margin: 10px 0 0; padding-left: 18px; font-size: 13px; }
  `,
})
export class GatewayScale implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  readonly WINDOW = WINDOW;
  readonly now = signal<GatewayCount | null>(null);
  readonly error = signal('');
  readonly changes = signal<ScaleChange[]>([]);
  private readonly samples = signal<number[]>([]);
  readonly bars = computed(() => {
    const s = this.samples(), max = Math.max(1, ...s);
    return s.map(v => Math.round((v / max) * 38) + 2);
  });
  private timer: ReturnType<typeof setInterval> | undefined;

  ngOnInit(): void { void this.poll(); this.timer = setInterval(() => void this.poll(), 3000); }
  ngOnDestroy(): void { clearInterval(this.timer); }

  time(at: number): string { return new Date(at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' }); }

  async poll(): Promise<void> {
    const r = await this.api.load<GatewayCount>('/gateways/count');
    if (r.status !== 200 || !r.body) { this.error.set(`gateway count unavailable (HTTP ${r.status})`); return; }
    this.error.set('');
    const prev = this.now()?.ready ?? null, c = r.body;
    this.changes.update(log => nextChanges(log, prev, c.ready, Date.now()));
    this.samples.update(s => [...s, c.ready].slice(-WINDOW));
    this.now.set(c);
  }
}
