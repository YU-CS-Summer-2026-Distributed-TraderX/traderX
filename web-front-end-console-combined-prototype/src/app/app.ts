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
      <span class="pill fixture" [title]="desk.connected ? 'Connected to local services' : fixtureLabel">{{ desk.connected ? 'Local rig' : 'synthetic data' }}</span>
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
      <span class="pill fixture" [title]="desk.connected ? 'Connected to local services' : fixtureLabel">{{ desk.connected ? 'Local rig' : 'synthetic data' }}</span>
    }
  </div>
  <div class="context-bar">
    @if (ws() === 'trader') {
      <label>Account <select aria-label="Account" [ngModel]="session.account()" (ngModelChange)="pickAccount($event)" data-testid="account">
        @for (a of myAccounts(); track a.id) { <option [value]="a.id">{{ a.name }} ({{ a.id }})</option> }
      </select></label>
      <button type="button" (click)="showAccountForm.set(!showAccountForm())" data-testid="add-account">+ Add account</button>
      <label>Run <select aria-label="Run" [value]="runScope()" (change)="pickRun($any($event.target).value)" data-testid="run-select">
        <option value="">Active {{ desk.connected ? desk.runLabel() : '· 25 Sep 2026' }}</option>
        @for (r of desk.connected ? desk.runs : desk.runs.slice(1); track r.projection_scope) { <option [value]="r.projection_scope">{{ r.projection_scope }} · read only</option> }
      </select></label>
    } @else { <b>Admin workspace</b><sign-in /><span>Shared services and controls</span> }
    <span class="spacer"></span><span class="context-note">{{ desk.connected ? 'Local demo workspace' : 'Local prototype · no service connections' }}</span>
  </div>
</header>
}

@if(session.connected && session.user() && !session.user()?.accounts?.length && !showAccountForm()){<p class="banner">Welcome. Select <b>+ Add account</b> to create your first trading account.</p>}
@if(showAccountForm() && session.user()) {
  <section class="card account-create" aria-label="Add trading account">
    <h2>Add trading account</h2><form (ngSubmit)="createAccount()">
      <label class="field">Account name<input name="accountName" [(ngModel)]="accountName" maxlength="80" data-testid="account-name"></label>
      <button class="btn-primary" [disabled]="accountBusy() || !accountName.trim()" data-testid="create-account">{{accountBusy()?'Creating…':'Create account'}}</button>
      <button type="button" (click)="showAccountForm.set(false)">Close</button>
    </form>
    @if(accountMessage()){<p role="status">{{accountMessage()}}</p>}
    @if(admitId()){<button (click)="retryAdmission()" [disabled]="accountBusy()">Retry admission</button>}
  </section>
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
  readonly showAccountForm=signal(false);accountName='';readonly accountBusy=signal(false);readonly accountMessage=signal('');readonly admitId=signal<number|null>(null);
  async createAccount():Promise<void>{
    if(this.accountBusy())return;this.accountBusy.set(true);this.accountMessage.set('');
    const userId=this.session.user()?.id;
    try {
      const r=await fetch('/desk-api/accounts',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({displayName:this.accountName,requestId:crypto.randomUUID()}),signal:AbortSignal.timeout(25000)});
      const b=await r.json();if(userId!==this.session.user()?.id)return;
      if(!r.ok){this.accountMessage.set(b.error??'Account creation failed.');return;}
      this.session.setServerUser(b.user);this.session.selectAccount(b.account.id);this.desk.refreshDirectory();this.accountName='';
      this.admitId.set(b.enabled?null:b.account.id);this.accountMessage.set(b.enabled?`Account ${b.account.id} created and admitted for trading.`:b.error);
    }catch{this.accountMessage.set('Outcome unknown. Check the account directory before creating another account.');}
    finally{this.accountBusy.set(false);}
  }
  async retryAdmission():Promise<void>{
    this.accountBusy.set(true);
    try{const r=await fetch('/desk-api/accounts/admit',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({accountId:this.admitId()}),signal:AbortSignal.timeout(12000)});const b=await r.json();this.accountMessage.set(b.enabled?'Account admitted for trading.':b.error);if(b.enabled)this.admitId.set(null);}
    catch{this.accountMessage.set('Admission could not be confirmed.');}finally{this.accountBusy.set(false);}
  }
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
      if (!this.session.restoring() && !this.session.user() && this.url() && !this.url().startsWith('/login')) this.router.navigateByUrl('/login');
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

  async signOut(): Promise<void> {
    this.open.set(null);
    try {await this.session.signOut();if(this.desk.connected)await this.desk.api.checkAuth();this.router.navigateByUrl('/login');}
    catch {this.accountMessage.set('Sign-out could not reach the server. Try again.');this.showAccountForm.set(true);}
  }
}

@Component({
  selector: 'login-page',
  imports:[FormsModule],
  template: `
<div class="login-layout">
  <section class="login-intro">
    <img src="/img/traderx.svg" alt="TraderX" width="150">
    <p class="eyebrow">TRADER WORKSPACE</p>
    <h1>A clear view<br>of your trading day.</h1>
    <p>Markets, orders, positions and risk.<br>One account context across your desk.</p>
    <div class="login-note"><b>Local trading desk</b><p>Sign in with a username to open or create a workspace. Trading accounts and orders use the local rig.</p></div>
  </section>
  <section class="card login-card" aria-labelledby="login-h">
    <h2 id="login-h">Open your workspace</h2>
    @if(session.connected) {
      <form (ngSubmit)="login()">
        <label class="field">Username<input name="username" autocomplete="username" maxlength="48" [(ngModel)]="username" required data-testid="username"></label>
        <label class="field">Admin password <span class="faint">(optional)</span><input name="adminPassword" type="password" autocomplete="current-password" [(ngModel)]="adminPassword" data-testid="admin-password"></label>
        <p class="state">New here? Enter a username to create your workspace. Add trading accounts after signing in.</p>
        <button class="btn-primary" type="submit" [disabled]="busy || !username.trim()" data-testid="login-submit">{{busy?'Opening…':'Open workspace'}}</button>
        @if(error){<p role="alert" class="banner bad">{{error}}</p>}
        <p class="faint">Local demo: usernames are not private credentials. Admin features require the admin password.</p>
      </form>
    } @else {
      <ul class="users">@for(u of users;track u.id){<li><button class="identity" (click)="go(u.id)" [attr.data-testid]="'login-'+u.id">{{u.name}}</button></li>}</ul>
    }

  </section>
</div>
  `,
})
export class LoginPage {
  readonly session = inject(Session);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  readonly users = USERS;

  username='';adminPassword='';busy=false;error='';
  async login():Promise<void>{
    if(this.busy)return;this.busy=true;this.error=await this.session.login(this.username,this.adminPassword);this.adminPassword='';this.busy=false;
    if(!this.error)this.router.navigateByUrl('/desk/overview');
  }
  go(id: string): void {
    this.session.signIn(id);
    const next = this.route.snapshot.queryParamMap.get('next');
    const safe = next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/login') ? next : null;
    this.router.navigateByUrl(safe ?? `/desk/${this.session.prefs().lastTraderTab}`);
  }
}
