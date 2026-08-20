import { Component } from '@angular/core';
import { ClusterPanel } from './cluster-panel';
import { TicketPanel } from './ticket-panel';
import { BlotterPanel } from './blotter-panel';
import { MetricsPanel } from './metrics-panel';
import { ActivityPanel } from './activity-panel';
import { EodPanel } from './eod-panel';
import { AdminPanel } from './admin-panel';
import { ProvenancePanel } from './provenance-panel';
import { KdbPanel } from './kdb-panel';
import { StatusPanel } from './status-panel';
import { DemoSession } from './demo-session';
import { AccountsPanel } from './accounts-panel';

@Component({
  selector: 'trading-page',
  imports: [TicketPanel, BlotterPanel, ActivityPanel],
  template: `
    <div class="cols">
      <section class="card ticket"><ticket-panel /></section>
      <div class="stack">
        <section class="card"><blotter-panel /></section>
        <section class="card"><activity-panel /></section>
      </div>
    </div>
  `,
  styles: `
    .cols { display: grid; grid-template-columns: 380px 1fr; gap: 14px; align-items: start; }
    .stack { display: grid; gap: 14px; }
    @media (max-width: 950px) { .cols { grid-template-columns: 1fr; } }
  `,
})
export class TradingPage {}

@Component({
  selector: 'system-page',
  imports: [ClusterPanel, MetricsPanel, ProvenancePanel, StatusPanel],
  template: `
    <div class="stack">
      <section class="card"><cluster-panel /></section>
      <section class="card"><metrics-panel /></section>
      <section class="card"><status-panel /></section>
      <section class="card"><provenance-panel /></section>
    </div>
  `,
  styles: `.stack { display: grid; gap: 14px; max-width: 980px; }`,
})
export class SystemPage {}

@Component({
  selector: 'eod-page',
  imports: [EodPanel],
  template: `<section class="card" style="max-width: 980px"><eod-panel /></section>`,
})
export class EodPage {}

@Component({
  selector: 'admin-page',
  imports: [AdminPanel, DemoSession],
  template: `
    <div class="stack">
      <section class="card"><demo-session /></section>
      <section class="card"><admin-panel /></section>
    </div>
  `,
  styles: `.stack { display: grid; gap: 14px; max-width: 980px; }`,
})
export class AdminPage {}

@Component({
  selector: 'accounts-page',
  imports: [AccountsPanel],
  template: `<section class="card" style="max-width: 820px"><accounts-panel /></section>`,
})
export class AccountsPage {}

@Component({
  selector: 'kdb-page',
  imports: [KdbPanel],
  template: `<section class="card" style="max-width: 980px"><kdb-panel /></section>`,
})
export class KdbPage {}
