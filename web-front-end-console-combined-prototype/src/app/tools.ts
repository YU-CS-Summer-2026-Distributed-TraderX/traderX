import { Component, computed, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { feature } from './features';
import { Desk, SCENARIOS } from './desk';
import { Session } from './session';

/** A ☰ menu destination. Fixture-built tools render here; mapped ones say where the feature lives today. */
@Component({
  selector: 'tool-page',
  imports: [FormsModule],
  template: `
@if (f(); as f) {
  <div class="page-head"><h1>{{ f.title }}</h1></div>
  <p class="sub">{{ f.purpose }} <span class="faint">Today: {{ f.from }}.</span></p>
  @switch (f.id) {
    @case ('algos') {
      <section class="card"><div class="card-head"><h2>Algo parents on this account</h2></div>
        <table>
          <thead><tr><th scope="col">Parent</th><th scope="col">Started</th><th scope="col">Instrument</th><th scope="col">Side</th><th scope="col" class="num">Quantity</th><th scope="col">Schedule</th></tr></thead>
          <tbody>@for (a of algos(); track a.id) {
            <tr><td>{{ a.id }}</td><td class="faint">{{ a.at }}</td><td>{{ a.key }}</td><td>{{ a.side }}</td><td class="num">{{ a.quantity }}</td>
              <td>{{ a.mode }} over {{ a.durationS }}s, a slice every {{ a.bucketS }}s</td></tr>
          } @empty { <tr><td colspan="6" class="faint">No algo parents on this account.</td></tr> }</tbody>
        </table>
        <p class="faint">The fixture records parents but does not slice them. Today's console shows each bucket filling.</p>
      </section>
    }
    @case ('settings') {
      <section class="card"><div class="card-head"><h2>Your workspace</h2></div>
        <p>Signed in as <b>{{ session.user()?.name }}</b>. These settings are stored under your user only.</p>
        <dl class="kv">
          <dt>Watchlist</dt><dd>{{ session.prefs().watchlist.join(', ') || 'empty' }}</dd>
          <dt>Orders shown</dt><dd>{{ session.prefs().orderFilter === 'all' ? 'all states' : 'working only' }}</dd>
          <dt>Opens on</dt><dd>{{ session.prefs().lastTraderTab }}</dd>
        </dl>
        <form class="inline" (ngSubmit)="save()">
          <label>Save the current order view as <input name="n" [(ngModel)]="filterName" placeholder="e.g. Morning check"></label>
          <button type="submit" [disabled]="!filterName">Save view</button>
        </form>
        <ul>@for (s of session.prefs().savedFilters; track s.name) { <li>{{ s.name }} · {{ s.orderFilter === 'all' ? 'all states' : 'working only' }}</li> }</ul>
        <button type="button" (click)="session.resetPrefs()" data-testid="reset-prefs">Reset my workspace</button>
      </section>
    }
    @case ('data') {
      <section class="card"><div class="card-head"><h2>Fixture scenario for this tab</h2></div>
        <p class="state">For design review only. Each scenario shows one state the real desk must draw clearly.</p>
        <fieldset class="radios"><legend class="sr">Scenario</legend>
          @for (s of scenarios; track s.id) {
            <label><input type="radio" name="sc" [value]="s.id" [checked]="desk.scenario() === s.id" (change)="desk.setScenario(s.id)"
              [attr.data-testid]="'sc-' + s.id"> {{ s.label }}</label>
          }
        </fieldset>
        <button type="button" (click)="session.expireNow()" data-testid="expire">End my session now (as if it expired)</button>
      </section>
    }
    @case ('recovery') {
      <section class="card"><div class="card-head"><h2>Run registry</h2></div>
        <table>
          <thead><tr><th scope="col">Run</th><th scope="col">Phase</th><th scope="col">Epoch</th><th scope="col" class="num">Checkpoint</th></tr></thead>
          <tbody>@for (r of desk.runs; track r.projection_scope) {
            <tr><td>{{ r.projection_scope }}</td><td>{{ r.phase.toLowerCase() }}</td><td>{{ r.cluster_epoch }}</td><td class="num">{{ r.checkpoint_seq }}</td></tr>
          }</tbody>
        </table>
        <p class="faint">The active pointer is set by the server. This page never changes it.</p>
      </section>
    }
    @default {
      <section class="card">
        <p class="state">{{ f.built === 'proposed' ? 'Proposed: no such screen exists today.' : 'Placed here, not rebuilt in the prototype. It keeps today\\'s behaviour and needs the rig.' }}</p>
        @if (f.id === 'legacy' || f.id === 'legacy-admin') {
          <p>The current console is unchanged. Run it as before with <code>npm start</code> in <code>web-front-end-console</code>.</p>
        }
      </section>
    }
  }
} @else {
  <section class="card"><p class="banner bad" role="alert">No tool called "{{ id() }}".</p></section>
}
  `,
})
export class ToolPage {
  readonly desk = inject(Desk);
  readonly session = inject(Session);
  readonly id = input.required<string>();
  readonly f = computed(() => feature(this.id()));
  readonly scenarios = SCENARIOS;
  readonly algos = computed(() => this.desk.algos().filter(a => a.account === this.session.account()));
  filterName = '';
  save(): void {
    const p = this.session.prefs();
    this.session.updatePrefs({ savedFilters: [...p.savedFilters.filter(s => s.name !== this.filterName),
      { name: this.filterName, orderFilter: p.orderFilter, instrument: '' }] });
    this.filterName = '';
  }
}
