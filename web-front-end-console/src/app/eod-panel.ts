import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { Gated } from './gated';
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

@Component({
  selector: 'eod-panel',
  imports: [FormsModule, HelpTip, Gated],
  template: `
    <div class="card-head">
      <h2>End-of-day session</h2>
      <help-tip text="Every trading day gets an official closing-price session. It starts as a DRAFT: each instrument's close is checked and given a quality code (STALE, SPIKE, MISSING…). A session with flagged instruments cannot be published — the quality gate refuses with an HTTP 409 — until an operator overrides each flagged price, with a recorded reason. A correction never edits in place: it creates a new version, so the full history stays auditable. These operations need an admin token, which the console obtains automatically in a dev environment." />
    </div>

    <!-- The paste-the-master-secret box that used to sit here is gone with the minting it fed.
         The console holds no credential: its server attaches one for these reads and refuses the
         writes without a sign-in. A page that asks an operator to paste a master secret is asking
         for the wrong thing even when it works. -->
    <div class="bar">
      <input type="date" [(ngModel)]="date">
      <button (click)="loadLatest()">Load</button>
      @if (report(); as r) {
        <span class="pill" [class.good]="r.status === 'PUBLISHED'" [class.warn]="r.status !== 'PUBLISHED'">{{ r.status }}</span>
        <span class="sub">v{{ r.version }} of {{ latestVersion() }} · {{ r.instrumentCount }} instruments ·
          <b [class.flagged]="r.flaggedCount > 0">{{ r.flaggedCount }} flagged</b></span>
        <button (click)="loadVersion(r.version - 1)" [disabled]="r.version <= 1">◀ v{{ r.version - 1 }}</button>
        <button (click)="loadVersion(r.version + 1)" [disabled]="r.version >= latestVersion()">v{{ r.version + 1 }} ▶</button>
        <button (click)="publish()" [disabled]="r.status === 'PUBLISHED' || !isLatest()">Publish</button>
        <gated />
        <help-tip text="Publish asks the session to become the official close. If any instrument is still flagged, the quality gate refuses with HTTP 409 — the refusal you may see here is the control working, not an error." />
      }
      <span class="spacer"></span>
    </div>
    @if (report() && !isLatest()) {
      <div class="banner warn-note">Viewing historical v{{ report()!.version }} — read-only. Corrections always
        apply to the latest version (v{{ latestVersion() }}) and create v{{ latestVersion() + 1 }}.</div>
    }
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
              <td>@if (p.quality !== 'OK' && isLatest()) { <button (click)="startOverride(p)">override…</button> }</td>
            </tr>
          }
        </tbody>
      </table>
      @if (r.instruments.length > visible().length) {
        <button class="showall" (click)="showAll.set(true)">show all {{ r.instruments.length }} instruments
          ({{ visible().length }} shown)</button>
      } @else if (showAll() && r.instruments.length > 12) {
        <button class="showall" (click)="showAll.set(false)">collapse to flagged + first rows</button>
      }
    } @else if (loaded()) { <div class="faint">no session for {{ date }}</div> }

    @if (overriding(); as o) {
      <div class="ovr-form">
        Override <b>{{ o.security }}</b> (was {{ o.closingPrice }}, {{ o.quality }})
        <input type="number" [(ngModel)]="ovrPrice" step="0.000001" placeholder="price">
        <input [(ngModel)]="ovrReason" placeholder="reason (audit trail)">
        <button class="btn-primary" (click)="applyOverride()">Apply — creates v{{ latestVersion() + 1 }}</button>
        <gated />
        <button (click)="overriding.set(null)">cancel</button>
      </div>
    }
  `,
  styles: `
    .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
    .spacer { flex: 1; }
    .flagged { color: var(--warn); }
    .banner { margin-bottom: 8px; }
    .warn-note { background: var(--warn-soft); color: var(--warn); }
    .showall { margin-top: 8px; font-size: 12.5px; color: var(--accent); background: none; border: none; padding: 0;
               text-decoration: underline; }
    .ovr-form { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; background: var(--accent-soft);
                padding: 10px; border-radius: 8px; font-size: 13px; margin-top: 10px; }
    a { color: var(--faint); cursor: pointer; text-decoration: underline; }
  `,
})
export class EodPanel {
  readonly api = inject(Api);

  date = new Date().toISOString().slice(0, 10);
  ovrPrice = 0;
  ovrReason = '';

  readonly report = signal<EodReport | null>(null);
  readonly latestVersion = signal(0);
  readonly loaded = signal(false);
  readonly showAll = signal(false);
  readonly gateMsg = signal<{ ok: boolean; text: string } | null>(null);
  readonly overriding = signal<EodPrice | null>(null);

  readonly isLatest = computed(() => this.report()?.version === this.latestVersion());
  readonly visible = computed(() => {
    const r = this.report();
    if (!r) return [];
    if (this.showAll()) return r.instruments;
    const interesting = r.instruments.filter(p => p.quality !== 'OK');
    const rest = r.instruments.filter(p => p.quality === 'OK').slice(0, Math.max(0, 12 - interesting.length));
    return [...interesting, ...rest];
  });

  constructor() {
    // Straight to the report: no credential to obtain first, because the console holds none and its
    // server authenticates these reads on the caller's behalf.
    this.loadLatest();
  }

  async loadLatest(): Promise<void> {
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}`);
    this.loaded.set(true);
    this.gateMsg.set(null);
    this.overriding.set(null);
    if (r.status === 401) { this.api.dropAdminToken(); return; }
    this.report.set(r.status === 200 ? r.body : null);
    this.latestVersion.set(r.body?.version ?? 0);
  }

  async loadVersion(v: number): Promise<void> {
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}/versions/${v}`);
    if (r.status === 200) { this.report.set(r.body); this.overriding.set(null); }
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
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ security: o.security, price: this.ovrPrice, reason: this.ovrReason }),
    });
    if (r.status === 200 && r.body) {
      this.report.set(r.body);
      this.latestVersion.set(r.body.version);
      this.overriding.set(null);
      this.api.log({ kind: 'eod', ok: true, summary: `override ${o.security} → ${this.ovrPrice} (new version v${r.body.version})` });
    } else {
      this.api.log({ kind: 'eod', ok: false, summary: `override ${o.security} failed: HTTP ${r.status}` });
    }
  }

  async publish(): Promise<void> {
    const r = await this.api.load<EodReport>(`/trade-processor/eod/prices/${this.date}/publish`, {
      method: 'POST',
    });
    if (r.status === 409) {
      this.gateMsg.set({ ok: false, text: `409 — quality gate refused: ${r.body?.flaggedCount} flagged instrument(s) must be overridden before this session can publish` });
      this.api.log({ kind: 'eod', ok: false, summary: `publish ${this.date} blocked by quality gate (409, ${r.body?.flaggedCount} flagged)` });
      if (r.body) { this.report.set(r.body); this.latestVersion.set(r.body.version); }
    } else if (r.status === 200 && r.body) {
      this.gateMsg.set({ ok: true, text: `published v${r.body.version}` });
      this.report.set(r.body);
      this.latestVersion.set(r.body.version);
      this.api.log({ kind: 'eod', ok: true, summary: `published ${this.date} v${r.body.version}` });
    } else if (r.status === 401) {
      // Publishing is an override in the sense the server gates: it is the decision that starts the
      // extract. "HTTP 401" reads as a fault; it is a missing signature.
      this.gateMsg.set({ ok: false, text: 'publishing needs an administrator — sign in from the header, then publish again' });
    } else {
      this.gateMsg.set({ ok: false, text: `publish failed: HTTP ${r.status}` });
    }
  }
}
