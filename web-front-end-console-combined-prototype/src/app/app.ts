import { FormsModule } from '@angular/forms';
import { SignIn } from '../../../web-front-end-console/src/app/sign-in';
import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { ACCOUNTS, FIXTURE_LABEL, USERS } from './fixtures';
import { ADMIN_TABS, TRADER_TABS, menuFor } from './features';
import { Session, Workspace, canUse } from './session';
import { Desk } from './desk';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, SignIn, FormsModule],
  template: `
<a class="skip" href="#main">Skip to content</a>
@if (session.user()) {
<header>
  <div class="inner">
    <a class="brand" [routerLink]="home()" title="Back to {{ ws() === 'admin' ? 'Admin' : 'the trader desk' }}">
      <img class="yu" src="/img/yu-crest.png" alt="Yeshiva University">
      <span class="dot">·</span>
      <img class="tx" src="/img/traderx.svg" alt="TraderX">
      <span class="wordmark console">{{ ws() === 'admin' ? 'Admin' : 'Desk' }}</span>
    </a>
    @if (session.user(); as u) {
      <nav class="primary-nav" aria-label="{{ ws() === 'admin' ? 'Admin' : 'Trader' }} sections">
        @for (t of tabs(); track t.id) {
          <a [routerLink]="['/', ws() === 'admin' ? 'admin' : 'desk', t.id]" routerLinkActive="on"
             ariaCurrentWhenActive="page">{{ t.label }}</a>
        }
      </nav>
      <span class="spacer"></span>
      <span class="pill fixture" [title]="fixtureLabel">{{ desk.connected ? 'Local rig' : 'synthetic data' }}</span>
      <div class="pop-host">
        <button type="button" class="user" (click)="toggle('user')" [attr.aria-expanded]="open() === 'user'"
                aria-haspopup="true" data-testid="user-menu">{{ u.name }} ▾</button>
        @if (open() === 'user') {
          <div class="pop right" role="group" aria-label="Account menu">
            <div class="who"><b>{{ u.name }}</b><span class="faint">{{ u.id }} · {{ u.roles.join(', ') }}</span></div>
            @if (canAdmin()) {
              @if (ws() === 'trader') {
                <button type="button" (click)="switchTo('admin')" data-testid="to-admin">Switch to Admin workspace</button>
              } @else {
                <button type="button" (click)="switchTo('trader')" data-testid="to-desk">Switch to Trader desk</button>
              }
            }
            <button type="button" (click)="signOut()" data-testid="sign-out">Sign out</button>
          </div>
        }
      </div>
      <div class="pop-host">
        <button type="button" class="burger" (click)="toggle('menu')" [attr.aria-expanded]="open() === 'menu'"
                aria-controls="more-menu" aria-label="More tools" data-testid="menu">
          <span aria-hidden="true">☰</span> More</button>
        @if (open() === 'menu') {
          <nav id="more-menu" class="pop right menu" aria-label="More tools">
            <div class="faint head">More {{ ws() === 'admin' ? 'admin' : 'trader' }} tools</div>
            @for (f of menu(); track f.id) {
              <a [routerLink]="['/', ws() === 'admin' ? 'admin' : 'desk', 'tools', f.id]" (click)="open.set(null)">
                <b>{{ f.title }}</b><span>{{ f.purpose }}</span>
                @if (f.built !== 'fixture') { <em [hidden]="desk.connected">{{ f.built === 'proposed' ? 'proposed' : 'placed, not rebuilt' }}</em> }
              </a>
            }
            <a class="demo-console" href="http://127.0.0.1:4321" target="_blank" rel="noopener"><b>Demo console ↗</b><span>Open the original console</span></a>
          </nav>
        }
      </div>
    } @else {
      <span class="spacer"></span>
      <span class="pill fixture" [title]="fixtureLabel">{{ desk.connected ? 'Local rig' : 'synthetic data' }}</span>
    }
  </div>
  <div class="context-bar">
    @if (ws() === 'trader') {
      <label>Account <select aria-label="Account" [ngModel]="session.account()" (ngModelChange)="pickAccount($event)" data-testid="account">
        @for (a of myAccounts(); track a.id) { <option [value]="a.id">{{ a.name }} ({{ a.id }})</option> }
      </select></label>
      <label>Run <select aria-label="Run" [value]="runScope()" (change)="pickRun($any($event.target).value)" data-testid="run-select">
        <option value="">Active {{ desk.connected ? desk.runLabel() : '· 25 Sep 2026' }}</option>
        @for (r of desk.connected ? desk.runs : desk.runs.slice(1); track r.projection_scope) { <option [value]="r.projection_scope">{{ r.projection_scope }} · read only</option> }
      </select></label>
    } @else { <b>Admin workspace</b><sign-in /><span>Shared services and controls</span> }
    <span class="spacer"></span><span class="context-note">{{ desk.connected ? 'Local rig · demo profiles, not authentication' : 'Local prototype · no service connections' }}</span>
  </div>
</header>
}

<main id="main" tabindex="-1" [class.login-main]="!session.user()"><router-outlet /></main>

@if (session.user()) {
<footer class="attrib">
  <span>Rates instruments, when sourced externally, price off the U.S. Treasury constant-maturity
    curve (H.15, Board of Governors of the Federal Reserve System) retrieved via the FRED® API.
    <strong>This product uses the FRED® API but is not endorsed or certified by the Federal Reserve
    Bank of St. Louis.</strong> Use of this application is subject to the
    <a href="https://fred.stlouisfed.org/docs/api/terms_of_use.html" target="_blank" rel="noopener">FRED®
    API Terms of Use<span class="ext" aria-hidden="true">&#8599;</span></a>.</span>
</footer>
}
  `,
})
export class App {
  readonly session = inject(Session);
  private router = inject(Router);
  readonly desk = inject(Desk);   // started with the shell so the fixture feed runs from first paint
  readonly fixtureLabel = FIXTURE_LABEL;
  readonly open = signal<'menu' | 'user' | null>(null);

  private readonly url = toSignal(this.router.events.pipe(
    filter(e => e instanceof NavigationEnd), map(e => (e as NavigationEnd).urlAfterRedirects)), { initialValue: '' });
  readonly ws = computed<Workspace>(() => this.url().startsWith('/admin') ? 'admin' : 'trader');
  readonly tabs = computed(() => this.ws() === 'admin' ? ADMIN_TABS : TRADER_TABS);
  readonly menu = computed(() => menuFor(this.ws()).filter(f=>!['legacy','legacy-admin','data'].includes(f.id)));
  readonly canAdmin = computed(() => canUse(this.session.user(), 'admin'));
  readonly home = computed(() => this.ws() === 'admin' ? '/admin' : '/desk');
  readonly myAccounts = computed(() => this.desk.accounts().filter(a => this.session.user()?.accounts.includes(a.id)));

  constructor() {
    if(this.desk.connected) void this.desk.api.checkAuth();
    // Remember the last tab per workspace, per user.
    effect(() => {
      const m = /^\/(desk|admin)\/([a-z-]+)/.exec(this.url());
      if (!m || m[2] === 'tools') return;
      const key = m[1] === 'admin' ? 'lastAdminTab' : 'lastTraderTab';
      if (this.session.prefs()[key] !== m[2]) this.session.updatePrefs({ [key]: m[2] });
    });
    // Signed out anywhere (this tab, another tab, or expiry): leave the workspace at once.
    effect(() => {
      if (!this.session.user() && this.url() && !this.url().startsWith('/login')) this.router.navigateByUrl('/login');
    });
  }

  toggle(which: 'menu' | 'user'): void { this.open.set(this.open() === which ? null : which); }

  @HostListener('document:keydown.escape') close(): void { this.open.set(null); }
  @HostListener('document:click', ['$event']) outside(e: MouseEvent): void {
    if (!(e.target as HTMLElement).closest('.pop-host')) this.open.set(null);
  }

  readonly runScope = computed(() => { const v = this.desk.runView(); return v.kind === 'history' ? v.scope : ''; });
  pickRun(scope: string): void { this.desk.runView.set(scope ? {kind:'history', scope} : {kind:'active'}); this.desk.reload(); }
  pickAccount(v: string): void { this.session.selectAccount(Number(v)); }

  switchTo(ws: Workspace): void {
    this.open.set(null);
    if (!this.session.switchWorkspace(ws)) return;
    const p = this.session.prefs();
    this.router.navigate(ws === 'admin' ? ['/admin', p.lastAdminTab] : ['/desk', p.lastTraderTab]);
  }

  signOut(): void {
    this.open.set(null);
    if(this.desk.connected) void this.desk.api.logout();
    this.session.signOut();
    this.router.navigateByUrl('/login');
  }
}

@Component({
  selector: 'login-page',
  template: `
<div class="login-layout">
  <section class="login-intro">
    <img src="/img/traderx.svg" alt="TraderX" width="150">
    <p class="eyebrow">TRADER WORKSPACE</p>
    <h1>A clear view<br>of your trading day.</h1>
    <p>Markets, orders, positions and risk.<br>One account context across your desk.</p>
    <div class="login-note"><b>Local trading desk</b><p>These local demo profiles choose workspace preferences. They are not authentication. Orders are sent to the local rig.</p></div>
  </section>
  <section class="card login-card" aria-labelledby="login-h">
    <h2 id="login-h">Open your workspace</h2>
    <p class="state">Choose a profile to explore. No password is required or collected.</p>
    @if (session.expired()) { <p class="banner bad" role="alert" data-testid="expired">Your session ended. Sign in again to continue.</p> }
    <ul class="users">
      @for (u of users; track u.id) {
        <li><button class="identity" type="button" (click)="go(u.id)" [attr.data-testid]="'login-' + u.id">
          <span class="avatar">{{ u.name.split(' ')[0][0] }}{{ u.name.split(' ')[1][0] }}</span>
          <span><b>{{ u.name }}</b><small>{{ u.roles.includes('admin') ? 'Trader + administrator' : 'Local trader profile' }}</small></span>
          <span class="arrow" aria-hidden="true">→</span>
        </button></li>
      }
    </ul>
    <p class="state">Watchlists and filters are saved separately for each demo profile.</p>
  </section>
</div>
  `,
})
export class LoginPage {
  readonly session = inject(Session);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  readonly users = USERS;

  go(id: string): void {
    this.session.signIn(id);
    const next = this.route.snapshot.queryParamMap.get('next');
    const safe = next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/login') ? next : null;
    this.router.navigateByUrl(safe ?? `/desk/${this.session.prefs().lastTraderTab}`);
  }
}
