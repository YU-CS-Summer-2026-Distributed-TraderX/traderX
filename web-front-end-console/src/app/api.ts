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

  // ---- message bus (NATS over the edge proxy's /nats-ws websocket) ----------------------------
  //
  // A deliberate ~60-line NATS client instead of nats.ws: this console only ever SUBs on an
  // unauthenticated local listener and treats every message as "re-read the REST read model", so
  // the whole protocol surface it needs is INFO/CONNECT, PING/PONG, SUB and MSG framing — and the
  // real library's node-crypto fallback fights the bundler for capability we never use.

  private bus: MiniNats | null = null;

  /**
   * Subscribe bus topics; call onMsg per message (payload deliberately unused — a bus event is a
   * trigger to re-read the REST read model, which keeps payload-shape assumptions out of the
   * browser). onStatus reports whether the live feed is up. Returns an unsubscribe.
   */
  busSubscribe(topics: string[], onMsg: () => void, onStatus: (up: boolean) => void): () => void {
    const offs = topics.map(t => this.busClient().subscribe(t, onMsg));
    const offStatus = this.busClient().onStatus(onStatus);
    return () => { offs.forEach(f => f()); offStatus(); };
  }

  private busClient(): MiniNats {
    if (!this.bus) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      this.bus = new MiniNats(`${proto}://${location.host}/nats-ws`);
    }
    return this.bus;
  }

  // ---- live prices ----------------------------------------------------------------------------

  /** ticker -> {price, dir} from price-publisher's pricing.<ticker> ticks; dir is the last move. */
  readonly prices = signal<Record<string, { price: number; dir: 1 | -1 | 0 }>>({});
  private pricesStarted = false;

  /** Start the pricing.> subscription once; the prices signal updates per tick thereafter. */
  watchPrices(): void {
    if (this.pricesStarted) return;
    this.pricesStarted = true;
    this.busClient().subscribe('pricing.>', payload => {
      try {
        // Envelope measured off the wire: {topic, payload: {ticker, price, ...}, from, type}.
        const p = JSON.parse(payload).payload;
        if (!p?.ticker || typeof p.price !== 'number') return;
        this.prices.update(m => {
          const prev = m[p.ticker]?.price;
          const dir = prev === undefined || p.price === prev ? 0 : p.price > prev ? 1 : -1;
          return { ...m, [p.ticker]: { price: p.price, dir: dir as 1 | -1 | 0 } };
        });
      } catch { /* not a price tick */ }
    });
  }

  // ---- admin token (shared by the EOD and Admin pages) ----------------------------------------

  readonly adminToken = signal<string | null>(sessionStorage.getItem('traderx-console-eod-token'));

  /** Mint an admin JWT; without a secret the dev proxy injects the master-secret header. */
  async mintAdminToken(masterSecret?: string): Promise<boolean> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (masterSecret) headers['X-Auth-Master-Secret'] = masterSecret;
    const r = await this.load<string>('/trade-processor/auth/dev-token', {
      method: 'POST', headers,
      body: JSON.stringify({ subject: 'ui-console', admin: true, ttlSeconds: 28800 }),
    });
    if (r.status === 200 && typeof r.body === 'string') {
      sessionStorage.setItem('traderx-console-eod-token', r.body);
      this.adminToken.set(r.body);
      return true;
    }
    return false;
  }

  dropAdminToken(): void {
    sessionStorage.removeItem('traderx-console-eod-token');
    this.adminToken.set(null);
  }

  authHeaders(): Record<string, string> {
    return { Authorization: `Bearer ${this.adminToken()}` };
  }
}

/** Minimal NATS-protocol client over a browser WebSocket: subscribe-only, auto-reconnect. */
class MiniNats {
  private ws: WebSocket | null = null;
  private buf = new Uint8Array(0);
  private pending: { sid: number; need: number } | null = null;
  private subs = new Map<number, { subject: string; cb: (payload: string) => void }>();
  private statusCbs = new Set<(up: boolean) => void>();
  private nextSid = 1;
  private up = false;
  private readonly dec = new TextDecoder();

  constructor(private url: string) { this.connect(); }

  subscribe(subject: string, cb: (payload: string) => void): () => void {
    const sid = this.nextSid++;
    this.subs.set(sid, { subject, cb });
    if (this.ws?.readyState === WebSocket.OPEN && this.up) this.send(`SUB ${subject} ${sid}\r\n`);
    return () => {
      this.subs.delete(sid);
      if (this.ws?.readyState === WebSocket.OPEN && this.up) this.send(`UNSUB ${sid}\r\n`);
    };
  }

  onStatus(cb: (up: boolean) => void): () => void {
    this.statusCbs.add(cb);
    cb(this.up);
    return () => this.statusCbs.delete(cb);
  }

  private setUp(up: boolean): void {
    if (this.up === up) return;
    this.up = up;
    this.statusCbs.forEach(cb => cb(up));
  }

  private connect(): void {
    const ws = new WebSocket(this.url);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onmessage = e => this.feed(new Uint8Array(e.data as ArrayBuffer));
    ws.onclose = ws.onerror = () => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.buf = new Uint8Array(0);
      this.pending = null;
      this.setUp(false);
      setTimeout(() => this.connect(), 2000);
    };
  }

  private send(s: string): void { this.ws?.send(s); }

  /** Byte-accurate framing: MSG payload length is in bytes and may contain CRLF. */
  private feed(chunk: Uint8Array): void {
    const merged = new Uint8Array(this.buf.length + chunk.length);
    merged.set(this.buf); merged.set(chunk, this.buf.length);
    this.buf = merged;
    for (;;) {
      if (this.pending) {
        if (this.buf.length < this.pending.need + 2) return;
        this.subs.get(this.pending.sid)?.cb(this.dec.decode(this.buf.slice(0, this.pending.need)));
        this.buf = this.buf.slice(this.pending.need + 2);
        this.pending = null;
        continue;
      }
      const nl = this.buf.indexOf(13);
      if (nl < 0 || this.buf[nl + 1] !== 10) return;
      const line = this.dec.decode(this.buf.slice(0, nl));
      this.buf = this.buf.slice(nl + 2);
      if (line.startsWith('MSG ')) {
        const parts = line.split(' ');
        this.pending = { sid: Number(parts[2]), need: Number(parts[parts.length - 1]) };
      } else if (line === 'PING') {
        this.send('PONG\r\n');
      } else if (line.startsWith('INFO ')) {
        this.send('CONNECT {"verbose":false,"pedantic":false,"protocol":1,"lang":"console","version":"0.0.1"}\r\n');
        for (const [sid, s] of this.subs) this.send(`SUB ${s.subject} ${sid}\r\n`);
        this.setUp(true);
      }
      // +OK / -ERR / PONG: nothing to do.
    }
  }
}
