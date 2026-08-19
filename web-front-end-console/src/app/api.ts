import { Injectable, signal } from '@angular/core';

// ---- Shapes measured against the live rig (kind-traderx-yu12-cluster), not invented. ----

export interface Account { id: number; displayName: string; }

export interface Instrument {
  instrumentKey: string;
  displayName: string;
  shortDisplayName?: string;
  assetClass: string;          // Stock | ETF | US_TREASURY | CORPORATE_BOND
  currency: string;
  securityType: string;        // Equity | Fund | Debt
  debtEconomics?: {
    debtType: string;
    issuer: string;
    creditRating?: string;
    dayCount: string;          // "ACT/ACT ICMA" (treasury) vs "30/360" (corporate) — from the join
    fixedInterest?: { couponRatePercent: number };
    zeroCoupon?: { couponRatePercent: number };
  };
}

export interface MemberHealth {
  memberId: number; role: string; started: boolean;
  applied: number; engineApplied: number; trades: number; snapshots: number;
}

export interface GatewayHealth { connected: boolean; noAckStreak: number; }

export interface OrderResult {
  orderRef?: number; kind?: number; reason?: string; error?: string;
  contractId?: string; sequence?: number; booked?: boolean;
  canceled?: boolean; replaced?: boolean;
  httpStatus: number;
}

export interface Position {
  accountId: number; security: string; quantity: number;
  averageCostBasis: number; updated: string;
}

export interface BlotterTrade {
  id: string; accountId: number; security: string; side: string; state: string;
  quantity: number; price: number; updated: string; created: string;
  rejectionReason: string | null;
}

export interface EodRow {
  security: string; price: number; quality: string;      // OK|STALE|SPIKE|MISSING|OVERRIDDEN
  overriddenBy?: string; overrideReason?: string; previousPrice?: number;
}

// EodReport shape is rendered from whatever the gateway returns; keys below are the ones the
// panel binds. Unknown extras are preserved on the raw object.
export interface EodReport {
  sessionDate: string; version: number; status: string;  // DRAFT | PUBLISHED
  flaggedCount?: number; prices?: EodRow[]; [k: string]: unknown;
}

export interface ActivityEntry {
  at: Date; kind: 'order' | 'swap' | 'swaption' | 'cancel' | 'replace' | 'eod';
  summary: string; ok: boolean; reason?: string; detail?: string;
}

// OCC option symbol: ROOT + YYMMDD + C/P + strike*1000 (8 digits). All fields derivable.
export function parseOcc(sym: string): { underlying: string; expiry: string; callPut: string; strike: number } | null {
  const m = /^([A-Z]{1,6})(\d{6})([CP])(\d{8})$/.exec(sym.trim().toUpperCase());
  if (!m) return null;
  const [, root, d, cp, k] = m;
  return {
    underlying: root,
    expiry: `20${d.slice(0, 2)}-${d.slice(2, 4)}-${d.slice(4, 6)}`,
    callPut: cp === 'C' ? 'Call' : 'Put',
    strike: Number(k) / 1000,
  };
}

// Prometheus text format -> {name{labels}: value}
export function parseProm(text: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const line of text.split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const sp = line.lastIndexOf(' ');
    if (sp < 0) continue;
    const v = Number(line.slice(sp + 1));
    if (!Number.isNaN(v)) out[line.slice(0, sp)] = v;
  }
  return out;
}

@Injectable({ providedIn: 'root' })
export class Api {
  readonly accounts = signal<Account[]>([]);
  readonly instruments = signal<Instrument[]>([]);
  readonly activity = signal<ActivityEntry[]>([]);

  async load<T>(url: string, init?: RequestInit): Promise<{ status: number; body: T | null }> {
    try {
      // no-store: a pre-deploy 200 (e.g. the SPA fallthrough that /mN returned before the proxy
      // route existed) is otherwise replayed from the browser HTTP cache indefinitely.
      const r = await fetch(url, { cache: 'no-store', ...init });
      const text = await r.text();
      let body: T | null = null;
      try { body = text ? JSON.parse(text) : null; } catch { body = text as unknown as T; }
      return { status: r.status, body };
    } catch {
      return { status: 0, body: null };
    }
  }

  async init(): Promise<void> {
    const [a, i] = await Promise.all([
      this.load<Account[]>('/account-service/account/'),
      this.load<Instrument[]>('/reference-data/instruments'),
    ]);
    if (Array.isArray(a.body)) this.accounts.set(a.body);
    if (Array.isArray(i.body)) this.instruments.set(i.body);
  }

  log(e: Omit<ActivityEntry, 'at'>): void {
    this.activity.update(list => [{ at: new Date(), ...e }, ...list].slice(0, 200));
  }

  post<T>(url: string, body: unknown): Promise<{ status: number; body: T | null }> {
    return this.load<T>(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  }
}
