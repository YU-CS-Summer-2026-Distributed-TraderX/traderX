import { RouterLink } from '@angular/router';
import { Component, inject, input, signal } from '@angular/core';

import { HelpTip } from '../../../web-front-end-console/src/app/help';
import { ACCOUNTS, EOD_CHAIN, MEMBERS, SERVICES, USERS } from './fixtures';
import { featuresOn } from './features';

/**
 * A privileged action in the prototype. It never sends anything: it says what would happen and
 * what the server would require. Kept as one component so no admin button can quietly do more.
 */
@Component({
  selector: 'proto-action',
  template: `
    <button type="button" [class.danger]="danger()" (click)="said.set(true)" [attr.data-testid]="'act-' + label()">{{ label() }}</button>
    @if (said()) {
      <span class="said" role="status">Not sent (prototype). The server would require an admin session and record this in the audit log.</span>
    }
  `,
})
export class ProtoAction {
  readonly label = input.required<string>();
  readonly danger = input(false);
  readonly said = signal(false);
}

/** The features this tab carries, with where each one lives in today's console. */
@Component({
  selector: 'placed-list',
  template: `
    <details class="placed"><summary>What this tab holds, and where it is today</summary>
      <ul>@for (f of list(); track f.id) { <li><b>{{ f.title }}</b> — {{ f.purpose }} <span class="faint">Today: {{ f.from }}.</span>
        @if (f.built !== 'fixture') { <em>{{ f.built === 'proposed' ? 'proposed' : 'placed, not rebuilt' }}</em> }</li> }</ul>
    </details>
  `,
})
export class PlacedList {
  readonly tab = input.required<string>();
  list() { return featuresOn(this.tab()); }
}

@Component({
  selector: 'operations-page',
  imports: [ProtoAction, PlacedList, HelpTip],
  template: `
<div class="page-head"><h1>End of day</h1><span class="spacer"></span><placed-list tab="operations" /></div>
<section class="card" aria-labelledby="ch-h">
  <div class="card-head"><h2 id="ch-h">Chain for 2026-09-24</h2>
    <help-tip text="Each step starts the next. Publishing prices starts valuation, the cut and the risk calculation, and a published version cannot be taken back; a correction is a new version." /></div>
  <ol class="flow">
    @for (s of chain; track s.step) { <li [class]="s.state"><b>{{ s.step }}</b><span class="sub">{{ s.detail }}</span></li> }
  </ol>
</section>
<section class="card" aria-labelledby="px-h">
  <div class="card-head"><h2 id="px-h">Price session</h2></div>
  <p class="state">Draft and published versions, quality codes per instrument, overrides and the publish check live here, as on today's End of day tab. Placed, not rebuilt.</p>
  <div class="acts"><proto-action label="Close session" [danger]="true" /><proto-action label="Publish prices" [danger]="true" /></div>
</section>
<section class="card"><div class="card-head"><h2>Risk jobs and cut provenance</h2></div>
  <p class="state">Coordinator jobs with attempts, and every cut with its SHA-256 and archive copy. Placed, not rebuilt.</p></section>
  `,
})
export class OperationsPage { readonly chain = EOD_CHAIN; }

@Component({
  selector: 'health-page',
  imports: [PlacedList, RouterLink],
  template: `
<div class="page-head"><h1>Operations</h1><span class="spacer"></span><placed-list tab="health" /></div>
<div class="tiles">
  <div class="tile"><span>Cluster members</span><b>{{ members.length }}</b><span>Illustrative local status</span></div>
  <div class="tile"><span>Services responding</span><b>{{ responding() }} / {{ services.length }}</b><span>Fixture responses</span></div>
  <div class="tile warn"><span>Replication</span><b>Behind</b><span>Member 2 is three entries behind</span></div>
  <a class="tile" routerLink="/admin/tools/recovery"><span>Run history</span><b>Recovery</b><span>Inspect run boundaries →</span></a>
</div>
<section class="card" aria-labelledby="mb-h">
  <div class="card-head"><h2 id="mb-h">Cluster members</h2><span class="spacer"></span>
    <span class="pill warn">member 2 is 3 behind</span></div>
  <table data-testid="members">
    <thead><tr><th scope="col">Member</th><th scope="col">Role</th><th scope="col" class="num">Applied</th><th scope="col" class="num">Engine applied</th><th scope="col" class="num">Trades</th></tr></thead>
    <tbody>@for (m of members; track m.id) {
      <tr><td>{{ m.id }}</td><td>{{ m.role.toLowerCase() }}</td><td class="num">{{ m.applied }}</td><td class="num">{{ m.engineApplied }}</td><td class="num">{{ m.trades }}</td></tr>
    }</tbody>
  </table>
</section>
<section class="card" aria-labelledby="sv-h">
  <div class="card-head"><h2 id="sv-h">Services</h2></div>
  <table>
    <thead><tr><th scope="col">Service</th><th scope="col">Checked path</th><th scope="col">Answer</th></tr></thead>
    <tbody>@for (s of services; track s.name) {
      <tr><td>{{ s.name }}</td><td class="faint">{{ s.path }}</td>
        <td><span class="pill" [class.good]="s.status === 200" [class.bad]="s.status !== 200">{{ s.status === 200 ? 'answering' : 'HTTP ' + s.status }}</span></td></tr>
    }</tbody>
  </table>
</section>
<section class="card"><div class="card-head"><h2>Latency and throughput</h2></div>
  <p class="state">Consensus p50/p99 with their sample counts, as on today's System tab. Placed, not rebuilt.</p></section>
  `,
})
export class HealthPage { readonly members = MEMBERS; readonly services = SERVICES; responding() { return this.services.filter(s => s.status === 200).length; } }

@Component({
  selector: 'accounts-admin-page',
  imports: [ProtoAction, PlacedList, HelpTip],
  template: `
<div class="page-head"><h1>Accounts</h1><span class="spacer"></span><placed-list tab="accounts" /></div>
<section class="card" aria-labelledby="ac-h">
  <div class="card-head"><h2 id="ac-h">Accounts</h2>
    <help-tip text="An account must exist in the account directory AND be admitted to the engine's risk state. One without the other lists normally and rejects every order." /></div>
  <table>
    <thead><tr><th scope="col">Account</th><th scope="col">Directory</th><th scope="col">Engine</th><th scope="col"><span class="sr">Actions</span></th></tr></thead>
    <tbody>@for (a of accounts; track a.id) {
      <tr><td>{{ a.name }} ({{ a.id }})</td><td>listed</td><td>admitted</td><td class="acts"><proto-action [label]="'Suspend ' + a.id" [danger]="true" /></td></tr>
    }</tbody>
  </table>
  <proto-action label="Create account" />
</section>
<section class="card" aria-labelledby="en-h">
  <div class="card-head"><h2 id="en-h">Who may use which account</h2><span class="pill warn">proposed</span></div>
  <p class="state">No such screen exists today: every signed-in console user acts under one shared admin identity.
    The design gives each user a list of accounts, enforced by the server on every request and subscription.</p>
  <table data-testid="entitlements">
    <thead><tr><th scope="col">User</th><th scope="col">Roles</th><th scope="col">Accounts</th></tr></thead>
    <tbody>@for (u of users; track u.id) { <tr><td>{{ u.name }} <span class="faint">{{ u.id }}</span></td><td>{{ u.roles.join(', ') }}</td><td>{{ u.accounts.join(', ') }}</td></tr> }</tbody>
  </table>
</section>
  `,
})
export class AccountsAdminPage { readonly accounts = ACCOUNTS; readonly users = USERS; }

@Component({
  selector: 'controls-page',
  imports: [ProtoAction, PlacedList],
  template: `
<div class="page-head"><h1>Controls</h1><span class="spacer"></span><placed-list tab="controls" /></div>
<p class="banner warn" role="note">These change shared state for every user. Each needs an admin session on the server.</p>
<section class="card"><div class="card-head"><h2>Risk control</h2></div>
  <p class="state">Halt or resume trading on one account. Sequenced through consensus like an order.</p>
  <div class="acts"><proto-action label="Halt account" [danger]="true" /><proto-action label="Resume account" /></div></section>
<section class="card"><div class="card-head"><h2>Overrides</h2></div>
  <p class="state">Force a trade past its settlement cycle, cancel any order by reference, or sweep orphaned reconciliation rows.</p>
  <div class="acts"><proto-action label="Force settle" [danger]="true" /><proto-action label="Cancel by reference" [danger]="true" /><proto-action label="Orphan sweep" [danger]="true" /></div></section>
<section class="card"><div class="card-head"><h2>Order flow generator and book bands</h2></div>
  <p class="state">The live trading session (several accounts sending orders at a set rate) and the band-and-refusal check per book, as on today's Admin tab. Placed, not rebuilt.</p></section>
  `,
})
export class ControlsPage {}

@Component({
  selector: 'monitoring-page',
  imports: [PlacedList],
  template: `
<div class="page-head"><h1>Monitoring</h1><span class="spacer"></span><placed-list tab="observability" /></div>
<section class="card"><div class="card-head"><h2>Dashboards, traces and tick capture</h2></div>
  <p class="state">The 18 Grafana dashboards open in a new tab, order traces open from Orders, and the kdb capture views sit here.
    Placed, not rebuilt: they need the rig.</p></section>
  `,
})
export class MonitoringPage {}
