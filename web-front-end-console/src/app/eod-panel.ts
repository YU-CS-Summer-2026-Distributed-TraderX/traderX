import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { HelpTip } from './help';

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
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>End-of-day session</h2>
      <help-tip text="Every trading day gets an official closing-price session. It starts as a DRAFT: each instrument's close is checked and given a quality code (STALE, SPIKE, MISSING…). A session with flagged instruments cannot be published — the quality gate refuses with an HTTP 409 — until an operator overrides each flagged price, with a recorded reason. A correction never edits in place: it creates a new version, so the full history stays auditable. These operations need an admin token, which the console obtains automatically in a dev environment." />
    </div>

    @if (!token()) {
      <div class="bar">
        @if (minting()) { <span class="sub">obtaining admin token…</span> }
        @else {
          <span class="sub">Auto-mint unavailable — paste the rig's dev-token master secret:</span>
          <input [(ngModel)]="masterSecret" type="password" placeholder="master secret">
          <button (click)="mint()">Mint token</button>
          @if (authError()) { <span class="pill bad">{{ authError() }}</span> }
        }
      </div>
    } @else {
      <div class="bar">
        <input type="date" [(ngModel)]="date">
        <button (click)="loadLatest()">Load</button>
        @if (report(); as r) {
          <span class="pill" [class.good]="r.status === 'PUBLISHED'" [class.warn]="r.status !== 'PUBLISHED'">{{ r.status }}</span>
          <span class="sub">v{{ r.version }} · {{ r.instrumentCount }} instruments ·
            <b [class.flagged]="r.flaggedCount > 0">{{ r.flaggedCount }} flagged</b></span>
          <button (click)="loadVersion(r.version - 1)" [disabled]="r.version <= 1">◀ v{{ r.version - 1 }}</button>
          <button (click)="publish()" [disabled]="r.status === 'PUBLISHED'">Publish</button>
          <help-tip text="Publish asks the session to become the official close. If any instrument is still flagged, the quality gate refuses with HTTP 409 — the refusal you may see here is the control working, not an error." />
        }
        <span class="spacer"></span>
        <span class="faint">token: {{ tokenSubject() }} <a (click)="dropToken()">forget</a></span>
      </div>
      @if (gateMsg(); as g) { <div class="banner" [class.good]="g.ok" [class.bad]="!g.ok">{{ g.text }}</div> }
      @if (report(); as r) {
        <table>
          <thead><tr><th>security</th><th class="num">close</th><th>quality</th><th>override reason</th><th></th></tr></thead>
          <tbody>
            @for (p of visible(); track p.security) {
              <tr>
                <td>{{ p.security }}</td>
                <td class="num">{{ p.closingPrice }}</td>
                <td>
                  @if (p.quality === 'OK') { {{ p.quality }} }
                  @else if (p.quality === 'OVERRIDDEN') { <span class="pill good">{{ p.quality }}</span> }
                  @else { <span class="pill warn">{{ p.quality }}</span> }
                </td>
                <td class="sub">{{ p.overrideReason || '' }}</td>
                <td>@if (p.quality !== 'OK') { <button (click)="startOverride(p)">override…</button> }</td>
              </tr>
            }
          </tbody>
        </table>
        @if (r.instruments.length > visible().length) {
          <div class="faint">showing flagged + overridden + first rows — {{ r.instruments.length }} total</div>
        }
      } @else if (loaded()) { <div class="faint">no session for {{ date }}</div> }

      @if (overriding(); as o) {
        <div class="ovr-form">
          Override <b>{{ o.security }}</b> (was {{ o.closingPrice }}, {{ o.quality }})
          <input type="number" [(ngModel)]="ovrPrice" step="0.000001" placeholder="price">
          <input [(ngModel)]="ovrReason" placeholder="reason (audit trail)">
          <button class="btn-primary" (click)="applyOverride()">Apply — creates v{{ (report()?.version ?? 0) + 1 }}</button>
          <button (click)="overriding.set(null)">cancel</button>
        </div>
      }
    }
  `,
  styles: `
    .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
    .spacer { flex: 1; }
    .flagged { color: var(--warn); }
    .banner { margin-bottom: 8px; }
    .ovr-form { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; background: var(--accent-soft);
                padding: 10px; border-radius: 8px; font-size: 13px; margin-top: 10px; }
    a { color: var(--faint); cursor: pointer; text-decoration: underline; }
  `,
})
export class EodPanel {
  private api = inject(Api);

  masterSecret = '';
  date = new Date().toISOString().slice(0, 10);
  ovrPrice = 0;
  ovrReason = '';

  readonly token = signal<string | null>(sessionStorage.getItem(TOKEN_KEY));
  readonly minting = signal(false);
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

  constructor() {
    // In dev the proxy injects the master secret header, so a no-secret mint succeeds and the
    // panel needs no manual step. The paste box remains as the fallback when that 401s.
    if (!this.token()) this.mint(true);
    else this.loadLatest();
  }

  async mint(auto = false): Promise<void> {
    if (auto) this.minting.set(true);
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.masterSecret) headers['X-Auth-Master-Secret'] = this.masterSecret;
    const r = await this.api.load<string>('/trade-processor/auth/dev-token', {
      method: 'POST', headers,
      body: JSON.stringify({ subject: 'ui-console', admin: true, ttlSeconds: 28800 }),
    });
    this.minting.set(false);
    if (r.status === 200 && typeof r.body === 'string') {
      sessionStorage.setItem(TOKEN_KEY, r.body);
      this.token.set(r.body);
      this.authError.set('');
      this.masterSecret = '';
      this.loadLatest();
    } else if (!auto) {
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
