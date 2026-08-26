import { Component, inject } from '@angular/core';
import { Api } from './api';
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
import { CollarReference } from './collar-reference';
import { GrafanaPanel } from './grafana-panel';
import { LegacyPanel } from './legacy-panel';
import { ReplayClock } from './replay-clock';

@Component({
  selector: 'trading-page',
  imports: [TicketPanel, BlotterPanel, ActivityPanel, ReplayClock],
  template: `
    <!-- Above the ticket on purpose: it says WHEN the prices below are from, and that has to be
         read before the numbers, not discovered after them. -->
    <section class="card tape"><replay-clock /></section>
    <div class="cols">
      <section class="card ticket"><ticket-panel /></section>
      <div class="stack">
        <section class="card"><blotter-panel /></section>
        <section class="card"><activity-panel /></section>
      </div>
    </div>
  `,
  styles: `
    .tape { padding: 9px 14px; margin-bottom: 12px; }
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
  imports: [AdminPanel, DemoSession, BandsPanel, CollarReference],
  template: `
    <div class="stack">
      <!-- Signed-in only. The session driver submits live orders at a chosen rate, so it is the one
           control on this page that changes the rig on its own after you walk away. This is a UI
           guard, NOT a server one: plain order entry is deliberately ungated (see gated.ts), so the
           same orders can still be posted directly. It stops an accident, not an adversary — do not
           let this read as a security boundary.
           A session ALREADY running is unaffected: SessionDriver is root-provided and the header
           carries its own live pill with Pause/Stop on every page, so signing out cannot strand one
           with no way to stop it. -->
      @if (api.authUser()) {
        <section class="card"><demo-session /></section>
      } @else {
        <section class="card">
          <h2>Live trading session</h2>
          <p class="muted">Submits live orders. Sign in to use it.</p>
          <button type="button" (click)="api.authPrompt.set(true)">Sign in</button>
        </section>
      }
      <!-- Two halves of one question: the journal says what the collar REFUSED, this says where
           the band currently sits against what has printed here. -->
      <section class="card"><collar-reference /></section>
      <section class="card"><bands-panel /></section>
      <section class="card"><admin-panel /></section>
    </div>
  `,
  styles: `
    .stack { display: grid; gap: 14px; max-width: 980px; }
    .muted { color: var(--muted); margin: 4px 0 10px; }
  `,
})
export class AdminPage {
  readonly api = inject(Api);
}

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
