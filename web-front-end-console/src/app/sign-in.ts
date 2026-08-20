import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';

/**
 * The sign-in affordance, and nothing more.
 *
 * **This is not the control.** The gate is in `server.mjs`, which refuses the mutating endpoints
 * without the cookie whether or not an Angular route ever rendered — a UI guard would stop nobody
 * holding curl. What this exists for is that an operator clicking *Force settle* deserves a prompt
 * rather than a raw 401.
 *
 * It lives in the MASTHEAD rather than on the admin page, because the gated actions are not confined
 * to one page: cancel is on the Activity and Blotter panels, force-settle is on the Blotter, and
 * algo order creation is on the trading ticket. A sign-in control only on the admin page would be
 * out of reach from three of the four places that need it.
 *
 * The page stays viewable signed out, deliberately — the requirement is that a *change* needs an
 * identity, not that the screen does.
 */
@Component({
  selector: 'sign-in',
  imports: [FormsModule],
  template: `
    @if (api.authUser(); as who) {
      <span class="pill good" title="changes are signed as {{ who }}">signed in · {{ who }}</span>
      <button (click)="out()">Sign out</button>
    } @else {
      <button [class.btn-primary]="api.authPrompt()" (click)="open.set(!open())">Sign in</button>
    }

    @if (open() && !api.authUser()) {
      <form class="pop" (ngSubmit)="go()">
        @if (api.authPrompt()) {
          <div class="why">That change needs an administrator. Reads stay open — only changes are
            signed.</div>
        }
        <label>User
          <input name="u" [(ngModel)]="user" autocomplete="username" spellcheck="false"></label>
        <label>Password
          <!-- Never bound to a signal, never logged, never put in sessionStorage: it is read from
               the field at submit and handed straight to the server, which answers with an
               HttpOnly cookie the page cannot see. -->
          <input name="p" type="password" [(ngModel)]="password" autocomplete="current-password"></label>
        <div class="row">
          <button type="submit" class="btn-primary" [disabled]="busy() || !user || !password">
            {{ busy() ? 'signing in…' : 'Sign in' }}</button>
          <button type="button" (click)="open.set(false)">Cancel</button>
        </div>
        @if (error()) { <div class="err">{{ error() }}</div> }
      </form>
    }
  `,
  styles: `
    :host { position: relative; display: inline-flex; align-items: center; gap: 6px; }
    .pop { position: absolute; top: 30px; right: 0; z-index: 40; width: 250px; display: grid;
           gap: 7px; padding: 11px; background: #fff; border: 1px solid var(--border);
           border-radius: 8px; box-shadow: 0 6px 22px rgba(16,24,40,.16); }
    .pop label { display: grid; gap: 3px; font-size: 11.5px; color: var(--muted); }
    .pop input { width: 100%; }
    .row { display: flex; gap: 6px; }
    .why { font-size: 11.5px; color: var(--warn); background: var(--warn-soft); padding: 6px 7px;
           border-radius: 6px; }
    .err { font-size: 11.5px; color: var(--bad); }
  `,
})
export class SignIn {
  readonly api = inject(Api);
  readonly open = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  user = 'admin';
  password = '';

  async go(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      const err = await this.api.login(this.user, this.password);
      this.password = '';                       // cleared on both paths, success or failure
      if (err) { this.error.set(err); return; }
      this.open.set(false);
    } finally { this.busy.set(false); }
  }

  async out(): Promise<void> {
    await this.api.logout();
    this.open.set(false);
  }
}
