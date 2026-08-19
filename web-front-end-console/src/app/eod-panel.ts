import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';

// Shapes from trade-processor's EodReport / EodPrice records.
interface EodPrice {
  security: string; closingPrice: number; quality: string;   // OK|STALE|SPIKE|MISSING|OVERRIDDEN
  sourceTickMillis: number | null; overrideReason: string | null;
}
interface EodReport {
  sessionDate: string; version: number; status: string;      // DRAFT | PUBLISHED
  instrumentCount: number; flaggedCount: number; instruments: EodPrice[];
}

const TOKEN_KEY = 'traderx-console-eod-token';

@Component({
  selector: 'eod-panel',
  imports: [FormsModule],
  template: `
    <h2>EOD session <span class="sub">draft → gate → publish; a correction is a new version (ADR-026)</span></h2>

    @if (!token()) {
      <div class="auth">
        EOD operations need an admin JWT (four-eyes surface).
        <input [(ngModel)]="masterSecret" type="password" placeholder="dev-token master secret">
        <button (click)="mint()">Mint token</button>
        @if (authError()) { <span class="bad">{{ authError() }}</span> }
      </div>
    } @else {
      <div class="bar">
        <input type="date" [(ngModel)]="date">
        <button (click)="loadLatest()">Load</button>
        @if (report(); as r) {
          <span class="pill" [class.pub]="r.status === 'PUBLISHED'">{{ r.status }}</span>
          <span>v{{ r.version }} · {{ r.instrumentCount }} instruments ·
            <b [class.bad]="r.flaggedCount > 0">{{ r.flaggedCount }} flagged</b></span>
          <button (click)="loadVersion(r.version - 1)" [disabled]="r.version <= 1">◀ v{{ r.version - 1 }}</button>
          <button (click)="publish()" [disabled]="r.status === 'PUBLISHED'">Publish</button>
        }
        <span class="sub">token: {{ tokenSubject() }} <a (click)="dropToken()">forget</a></span>
      </div>
      @if (gateMsg(); as g) { <div class="gate" [class.ok]="g.ok">{{ g.text }}</div> }
      @if (report(); as r) {
        <table>
          <thead><tr><th>security</th><th>close</th><th>quality</th><th>override reason</th><th></th></tr></thead>
          <tbody>
            @for (p of visible(); track p.security) {
              <tr [class.flag]="p.quality !== 'OK' && p.quality !== 'OVERRIDDEN'" [class.ovr]="p.quality === 'OVERRIDDEN'">
                <td>{{ p.security }}</td>
                <td class="num">{{ p.closingPrice }}</td>
                <td>{{ p.quality }}</td>
                <td>{{ p.overrideReason || '' }}</td>
                <td>@if (p.quality !== 'OK') { <button (click)="startOverride(p)">override…</button> }</td>
              </tr>
            }
          </tbody>
        </table>
        @if (r.instruments.length > visible().length) {
          <div class="sub">showing flagged + overridden + first rows — {{ r.instruments.length }} total</div>
        }
      } @else if (loaded()) { <div class="sub">no session for {{ date }}</div> }

      @if (overriding(); as o) {
        <div class="ovr-form">
          Override <b>{{ o.security }}</b> (was {{ o.closingPrice }}, {{ o.quality }})
          <input type="number" [(ngModel)]="ovrPrice" step="0.000001" placeholder="price">
          <input [(ngModel)]="ovrReason" placeholder="reason (audit trail)">
          <button (click)="applyOverride()">Apply — creates v{{ (report()?.version ?? 0) + 1 }}</button>
          <button (click)="overriding.set(null)">cancel</button>
        </div>
      }
    }
  `,
  styles: `
    .auth, .bar { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 12px; color: #999; margin: 6px 0; }
    input, button { background: #1a1a1a; color: #eee; border: 1px solid #444; padding: 4px 6px; border-radius: 3px; font-size: 12px; }
    button { cursor: pointer; }
    .pill { padding: 2px 8px; border-radius: 8px; background: #4a3b14; color: #ffd479; font-weight: 600; }
    .pill.pub { background: #143d14; color: #7be07b; }
    .bad { color: #ff9d9d; }
    .gate { padding: 5px 8px; border-radius: 3px; background: #4d1414; color: #ff9d9d; font-size: 12px; margin: 4px 0; }
    .gate.ok { background: #143d14; color: #7be07b; }
    tr.flag td { color: #ffd479; }
    tr.ovr td { color: #8fb8e8; }
    td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .ovr-form { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; background: #16202e; padding: 6px; border-radius: 4px; font-size: 12px; margin-top: 6px; }
    a { color: #667; cursor: pointer; text-decoration: underline; }
  `,
})
export class EodPanel {
  private api = inject(Api);

  masterSecret = '';
  date = new Date().toISOString().slice(0, 10);
  ovrPrice = 0;
  ovrReason = '';

  readonly token = signal<string | null>(sessionStorage.getItem(TOKEN_KEY));
  readonly authError = signal('');
  readonly report = signal<EodReport | null>(null);
  readonly loaded = signal(false);
  readonly gateMsg = signal<{ ok: boolean; text: string } | null>(null);
  readonly overriding = signal<EodPrice | null>(null);

  readonly tokenSubject = computed(() => {
    try { return JSON.parse(atob(this.token()!.split('.')[1])).sub ?? '?'; } catch { return '?'; }
  });
  readonly visible = computed(() => {
    const r = this.report();
    if (!r) return [];
    const interesting = r.instruments.filter(p => p.quality !== 'OK');
    const rest = r.instruments.filter(p => p.quality === 'OK').slice(0, Math.max(0, 12 - interesting.length));
    return [...interesting, ...rest];
  });

  async mint(): Promise<void> {
    const r = await this.api.load<string>('/trade-processor/auth/dev-token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Auth-Master-Secret': this.masterSecret },
      body: JSON.stringify({ subject: 'ui-console', admin: true, ttlSeconds: 28800 }),
    });
    if (r.status === 200 && typeof r.body === 'string') {
      sessionStorage.setItem(TOKEN_KEY, r.body);
      this.token.set(r.body);
      this.authError.set('');
      this.masterSecret = '';
      this.loadLatest();
    } else {
      this.authError.set(r.status === 401 ? 'invalid master secret' : `HTTP ${r.status}`);
    }
  }

  dropToken(): void { sessionStorage.removeItem(TOKEN_KEY); this.token.set(null); }

  private auth(): Record<string, string> { return { Authorization: `Bearer ${this.token()}` }; }

  async loadLatest(): Promise<void> {
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}`, { headers: this.auth() });
    this.loaded.set(true);
    this.gateMsg.set(null);
    if (r.status === 401) { this.dropToken(); return; }
    this.report.set(r.status === 200 ? r.body : null);
  }

  async loadVersion(v: number): Promise<void> {
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}/versions/${v}`, { headers: this.auth() });
    if (r.status === 200) this.report.set(r.body);
  }

  startOverride(p: EodPrice): void {
    this.overriding.set(p);
    this.ovrPrice = p.closingPrice;
    this.ovrReason = '';
  }

  async applyOverride(): Promise<void> {
    const o = this.overriding();
    if (!o) return;
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}/override`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...this.auth() },
      body: JSON.stringify({ security: o.security, price: this.ovrPrice, reason: this.ovrReason }),
    });
    if (r.status === 200 && r.body) {
      this.report.set(r.body);
      this.overriding.set(null);
      this.api.log({ kind: 'eod', ok: true, summary: `override ${o.security} → ${this.ovrPrice} (new version v${r.body.version})` });
    } else {
      this.api.log({ kind: 'eod', ok: false, summary: `override ${o.security} failed: HTTP ${r.status}` });
    }
  }

  async publish(): Promise<void> {
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}/publish`, {
      method: 'POST', headers: this.auth(),
    });
    if (r.status === 409) {
      this.gateMsg.set({ ok: false, text: `409 — quality gate refused: ${r.body?.flaggedCount} flagged instrument(s) must be overridden before this session can publish` });
      this.api.log({ kind: 'eod', ok: false, summary: `publish ${this.date} blocked by quality gate (409, ${r.body?.flaggedCount} flagged)` });
      if (r.body) this.report.set(r.body);
    } else if (r.status === 200 && r.body) {
      this.gateMsg.set({ ok: true, text: `published v${r.body.version}` });
      this.report.set(r.body);
      this.api.log({ kind: 'eod', ok: true, summary: `published ${this.date} v${r.body.version}` });
    } else {
      this.gateMsg.set({ ok: false, text: `publish failed: HTTP ${r.status}` });
    }
  }
}
