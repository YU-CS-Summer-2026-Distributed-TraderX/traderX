import { Injectable, computed, signal } from '@angular/core';
import { FixtureUser, Role, USERS } from './fixtures';

/**
 * PROTOTYPE SESSION — NOT SECURITY.
 *
 * Signing in here picks a fixture user in the browser. No password is checked and nothing is
 * enforced: anyone with devtools can become anyone. It exists so the design's session behaviour
 * (sign-in, sign-out, expiry, switching workspace, several tabs) can be reviewed. The real design,
 * with the rules on the server, is in components/trader-workspace-claude/plan.md.
 *
 * Two kinds of state, kept apart on purpose:
 *  - the SESSION (who is signed in) is shared by every tab of this browser, as a cookie would be;
 *  - the WORKSPACE (tabs, watchlist, saved filters, selected account) is scoped to one identity,
 *    and the selected account additionally to one browser tab.
 */

export type Workspace = 'trader' | 'admin';

export interface SessionRecord { userId: string; issuedAt: number; expiresAt: number; }

export interface Preferences {
  watchlist: string[];
  orderFilter: 'working' | 'all';
  lastTraderTab: string;
  lastAdminTab: string;
  savedFilters: { name: string; orderFilter: 'working' | 'all'; instrument: string }[];
}

export const SESSION_KEY = 'tx-combined:session';
export const SESSION_TTL_MS = 8 * 60 * 60 * 1000;
export const DEFAULT_PREFS: Preferences = {
  watchlist: ['IBM', 'AAPL', 'UST-20351115'],
  orderFilter: 'working',
  lastTraderTab: 'overview',
  lastAdminTab: 'health',
  savedFilters: [],
};

/** Preferences live under the identity, never under a shared key. */
export const prefsKey = (userId: string) => `tx-combined:prefs:v1:${userId}`;
/** The selected account is per identity AND per tab (sessionStorage), so two tabs can differ. */
export const accountKey = (userId: string) => `tx-combined:account:v1:${userId}`;

export const userById = (id: string | null | undefined): FixtureUser | undefined =>
  USERS.find(u => u.id === id);

/** A stored record is usable only if it names a known user and has not expired. */
export function readSession(raw: string | null, now: number): SessionRecord | null {
  if (!raw) return null;
  try {
    const s = JSON.parse(raw) as SessionRecord;
    if (!userById(s.userId) || typeof s.expiresAt !== 'number' || s.expiresAt <= now) return null;
    return s;
  } catch { return null; }
}

export function readPrefs(raw: string | null): Preferences {
  if (!raw) return { ...DEFAULT_PREFS, watchlist: [...DEFAULT_PREFS.watchlist], savedFilters: [] };
  try { return { ...DEFAULT_PREFS, ...(JSON.parse(raw) as Partial<Preferences>) }; }
  catch { return { ...DEFAULT_PREFS, watchlist: [...DEFAULT_PREFS.watchlist], savedFilters: [] }; }
}

/**
 * Which account a tab shows. A remembered account the identity is no longer entitled to is
 * dropped rather than shown: the entitlement list wins over the preference.
 */
export function chooseAccount(user: FixtureUser, remembered: number | null): number | null {
  if (remembered !== null && user.accounts.includes(remembered)) return remembered;
  return user.accounts[0] ?? null;
}

export const canUse = (user: FixtureUser | undefined, ws: Workspace): boolean =>
  !!user && user.roles.includes(ws as Role);

const safe = <T>(f: () => T, fallback: T): T => { try { return f(); } catch { return fallback; } };

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly record = signal<SessionRecord | null>(
    readSession(safe(() => localStorage.getItem(SESSION_KEY), null), Date.now()));
  readonly user = computed(() => userById(this.record()?.userId));
  readonly expired = signal(false);
  readonly workspace = signal<Workspace>('trader');
  readonly prefs = signal<Preferences>(DEFAULT_PREFS);
  readonly account = signal<number | null>(null);
  /** Bumped on every identity or account change; the desk drops caches and stale replies on it. */
  readonly generation = signal(0);

  constructor() {
    this.loadWorkspace();
    // Another tab signed in, out, or as someone else: this tab follows, and never keeps showing
    // the previous identity's data. The browser fires `storage` only in the OTHER tabs.
    window.addEventListener('storage', e => {
      if (e.key !== SESSION_KEY) return;
      const next = readSession(e.newValue, Date.now());
      if (next?.userId !== this.record()?.userId) { this.record.set(next); this.loadWorkspace(); }
    });
  }

  signIn(userId: string): void {
    const now = Date.now();
    const rec: SessionRecord = { userId, issuedAt: now, expiresAt: now + SESSION_TTL_MS };
    safe(() => localStorage.setItem(SESSION_KEY, JSON.stringify(rec)), undefined);
    this.expired.set(false);
    this.record.set(rec);
    this.workspace.set('trader');
    this.loadWorkspace();
  }

  signOut(): void {
    safe(() => localStorage.removeItem(SESSION_KEY), undefined);
    this.record.set(null);
    this.loadWorkspace();
  }

  /** Prototype control: behave as if the server had refused the session cookie. */
  expireNow(): void {
    this.signOut();
    this.expired.set(true);
  }

  /** True if the session is still valid; otherwise ends it (a real client learns this from a 401). */
  check(): boolean {
    const r = this.record();
    if (r && r.expiresAt > Date.now()) return true;
    if (r) this.expireNow();
    return false;
  }

  switchWorkspace(ws: Workspace): boolean {
    if (!canUse(this.user(), ws)) return false;
    this.workspace.set(ws);
    return true;
  }

  selectAccount(id: number): boolean {
    const u = this.user();
    if (!u || !u.accounts.includes(id)) return false;   // UI mirror of the server rule, not the rule
    safe(() => sessionStorage.setItem(accountKey(u.id), String(id)), undefined);
    this.account.set(id);
    this.generation.update(n => n + 1);
    return true;
  }

  updatePrefs(change: Partial<Preferences>): void {
    const u = this.user();
    if (!u) return;
    const next = { ...this.prefs(), ...change };
    this.prefs.set(next);
    safe(() => localStorage.setItem(prefsKey(u.id), JSON.stringify(next)), undefined);
  }

  resetPrefs(): void {
    const u = this.user();
    if (!u) return;
    safe(() => localStorage.removeItem(prefsKey(u.id)), undefined);
    this.prefs.set(readPrefs(null));
  }

  /** Everything identity-scoped is re-read, and the generation bump tells the desk to drop caches. */
  private loadWorkspace(): void {
    const u = this.user();
    this.prefs.set(readPrefs(u ? safe(() => localStorage.getItem(prefsKey(u.id)), null) : null));
    const remembered = u ? Number(safe(() => sessionStorage.getItem(accountKey(u.id)), null)) || null : null;
    this.account.set(u ? chooseAccount(u, remembered) : null);
    if (!canUse(u, this.workspace())) this.workspace.set('trader');
    this.generation.update(n => n + 1);
  }
}
