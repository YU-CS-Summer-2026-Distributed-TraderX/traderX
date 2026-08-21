import { Component } from '@angular/core';
import { ClusterPanel } from './cluster-panel';
import { TicketPanel } from './ticket-panel';
import { BlotterPanel } from './blotter-panel';
import { MetricsPanel } from './metrics-panel';
import { ActivityPanel } from './activity-panel';
import { EodPanel } from './eod-panel';
import { EodChain } from './eod-chain';
import { AdminPanel } from './admin-panel';
import { ProvenancePanel } from './provenance-panel';
import { KdbPanel } from './kdb-panel';
import { StatusPanel } from './status-panel';
import { DemoSession } from './demo-session';
import { AccountsPanel } from './accounts-panel';
import { FixPanel } from './fix-panel';
import { BandsPanel } from './bands-panel';
import { GrafanaPanel } from './grafana-panel';
import { LegacyPanel } from './legacy-panel';

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
  imports: [ClusterPanel, MetricsPanel, StatusPanel],
  template: `
    <div class="stack">
      <section class="card"><cluster-panel /></section>
      <section class="card"><metrics-panel /></section>
      <section class="card"><status-panel /></section>
    </div>
  `,
  styles: `.stack { display: grid; gap: 14px; max-width: 980px; }`,
})
export class SystemPage {}

@Component({
  selector: 'eod-page',
  imports: [EodChain, EodPanel, ProvenancePanel],
  template: `
    <div class="stack">
      <!-- The chain first: it is the story the other two panels are chapters of. Prices and the
           extract were always shown as unrelated, and publishing the price version is what starts
           the extract — an operator who does not know that reads a missing cut as a broken
           extract when nobody had published. -->
      <section class="card"><eod-chain /></section>
      <section class="card"><eod-panel /></section>
      <!-- The cut is the artifact of the session above it: same day, same version chain. It sat on
           the System page beside cluster health, which is where it was built rather than where it
           belongs. -->
      <section class="card"><provenance-panel /></section>
    </div>
  `,
  styles: `.stack { display: grid; gap: 14px; max-width: 980px; }`,
})
export class EodPage {}

@Component({
  selector: 'admin-page',
  imports: [AdminPanel, DemoSession, BandsPanel],
  template: `
    <div class="stack">
      <section class="card"><demo-session /></section>
      <section class="card"><bands-panel /></section>
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
  selector: 'fix-page',
  imports: [FixPanel],
  template: `<section class="card" style="max-width: 980px"><fix-panel /></section>`,
})
export class FixPage {}

@Component({
  selector: 'kdb-page',
  imports: [KdbPanel],
  template: `<section class="card" style="max-width: 1080px"><kdb-panel /></section>`,
})
export class KdbPage {}

@Component({
  selector: 'grafana-page',
  imports: [GrafanaPanel],
  template: `<section class="card" style="max-width: 980px"><grafana-panel /></section>`,
})
export class GrafanaPage {}

@Component({
  selector: 'legacy-page',
  imports: [LegacyPanel],
  template: `<section class="card" style="max-width: 900px"><legacy-panel /></section>`,
})
export class LegacyPage {}
