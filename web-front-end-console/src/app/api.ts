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
  rejectionReason: string | null; sourceOrderId: string | null;
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

/**
 * A booked OTC contract, kept BY THE CONSOLE because nothing else on this tier will show it to
 * you between booking and the next EOD cut. Measured: /swaps and /swaptions are POST-only ingress
 * (the gateway registers no read context for either), no swap or contract table exists in the
 * database, and the regulatory report enumerates order/trade kinds only — a booked contract
 * appears in none of them. It becomes durable in the EOD risk extract's CONTRACTS artifact
 * (contractId,accountId,payFixed,notional,fixedRateTicks,…), rendered byte-identically by all
 * three members at a consensus sequence — but only at the next cut.
 *
 * <p>Deliberately NOT given a position row: a receive-fixed and a pay-fixed at equal notional net
 * to zero at position grain and destroy both rates, which is exactly what yu17-swap-netting step 7
 * exists to catch. OTC is carried at contract grain, so this list is contract grain too.
 */
export interface OtcContract {
  contractId: string; sequence: number; accountId: number;
  product: 'Swap' | 'Swaption';
  payReceive: string; notional: number; fixedRate: number;
  effectiveDate: string; maturityDate: string; conventions: string;
  expiryDate?: string; exerciseStyle?: string;
  bookedAt: string;
}

/**
 * One security's accepted-vs-refused price ranges, from the regulatory report.
 *
 * `anchored-elsewhere` means the two ranges are DISJOINT — every accepted price sits on one side
 * of every refused one, which is what a collar band anchored away from the market looks like.
 * `other-refusal` means they overlap, so the same price was both accepted and refused and the
 * refusal came from something other than the band. Note the report carries no reason code on
 * ORDER_REJECTED (accountId/orderId/security/side/quantity/price/seq/timestamp and nothing else),
 * so the engine's actual reason is not readable here — this is inference from prices, and it is
 * labelled as such rather than presented as the engine's own answer.
 */
export interface BandCheck {
  security: string;
  accepted: number; rejected: number;
  acceptedLo: number; acceptedHi: number; rejectedLo: number; rejectedHi: number;
  verdict: 'anchored-elsewhere' | 'other-refusal' | 'never-accepted';
  thin: boolean;
}

interface RegulatoryEvent { kind: string; security: string; price: number; }

export interface ActivityEntry {
  at: Date; kind: 'order' | 'swap' | 'swaption' | 'cancel' | 'replace' | 'eod' | 'algo';
  summary: string; ok: boolean; reason?: string; detail?: string;
  orderRef?: number; clientOrderId?: string; traceId?: string;
}

// ---- trace-id derivation, bit-for-bit with the gateway's OrderTrace ---------------------------
// KEEP IN SYNC with order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/OrderTrace.java
// (and clientOrderKey in ClusterGatewayMain): this duplicates that FNV/mix math, and a change
// there silently breaks trace lookup here — in a demo, not in review.
// The gateway derives an order's W3C trace id deterministically from its clientOrderId (FNV-1a 64)
// or, key-less, from mix(orderRef). Reproducing that math here means the console can name any
// order's trace without asking anyone. Rejected orders are ALWAYS head-sampled (escalate), so a
// reject's trace is guaranteed to exist in Tempo.
const M64 = (1n << 64n) - 1n;
const TRACE_SALT = 0x5851F42D4C957F2Dn;

function mix64(z: bigint): bigint {
  z = (z + 0x9E3779B97F4A7C15n) & M64;
  z = ((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n) & M64;
  z = ((z ^ (z >> 27n)) * 0x94D049BB133111EBn) & M64;
  return (z ^ (z >> 31n)) & M64;
}

function fnv64(s: string): bigint {
  let h = 0xcbf29ce484222325n;
  for (let i = 0; i < s.length; i++) h = ((h ^ BigInt(s.charCodeAt(i))) * 0x100000001b3n) & M64;
  return h === 0n ? 1n : h;
}

const hex16 = (v: bigint) => v.toString(16).padStart(16, '0');
const nonZero = (v: bigint) => (v === 0n ? 1n : v);

export function traceIdFor(clientOrderId: string | undefined, orderRef?: number): string | undefined {
  const key = clientOrderId ? fnv64(clientOrderId)
    : orderRef && orderRef > 0 ? mix64(BigInt(orderRef)) : 0n;
  if (key === 0n) return undefined;
  return hex16(nonZero(mix64(key))) + hex16(nonZero(mix64(key ^ TRACE_SALT)));
}

let clOrdSeq = 0;
/** Console-generated client order id: feeds idempotency AND the deterministic trace id. */
export function nextClientOrderId(): string {
  return `console-${Date.now()}-${++clOrdSeq}`;
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

/**
 * What to show when one of the four bridges did not answer.
 *
 * Each bridge has two implementations — proxy.conf.mjs's bypasses on a laptop, server.mjs in the
 * cluster — and BOTH report their own failures in `error`, with the detail that is only knowable
 * there ("kubectl exec failed — is the risk-extract pod up?", "gcloud failed — is Workload Identity
 * bound for this pod?"). So the bridge's own words always win, and the console never has to guess
 * which half it is talking to.
 *
 * The fallback fires only when nothing intelligible came back, which is precisely when the console
 * knows least. It names the bridge's ROLE rather than a deployment: these messages used to say
 * "dev proxy + kubectl required", which sends a viewer of the deployed console hunting for a
 * process that does not exist on their machine while the real cause — the console server's bridge,
 * its RBAC, or the upstream pod — goes unexamined. An error that names the wrong subsystem is
 * worse than a generic one: it spends the reader's attention before they have any evidence.
 */
export const bridgeError = (r: { status: number; body: unknown }, role: string): string => {
  const own = (r.body as { error?: string } | null)?.error;
  return own || `${role} did not answer (${r.status ? 'HTTP ' + r.status : 'no response'})`;
};

/**
 * What to show when a risk-control command did not apply.
 *
 * Same rule as {@link bridgeError}: the side that knows does the talking. It matters more here
 * because the obvious guess is wrong — the gateway answers 401 with ONE message for three distinct
 * conditions (`!riskControlToken.equals(token) || operator == null || operator.isBlank()`), so a
 * caller cannot tell a bad token from a missing operator. This used to say "this rig sets its own
 * RISK_CONTROL_TOKEN", which picks one of the three and states it as fact; the gateway's own
 * "invalid risk-control credentials" claims exactly what is knowable and no more.
 */
export const riskControlError = (r: { status: number; body: { error?: string } | null }): string =>
  r.body?.error ? `HTTP ${r.status} — ${r.body.error}` : `HTTP ${r.status}`;

@Injectable({ providedIn: 'root' })
export class Api {
  readonly accounts = signal<Account[]>([]);
  readonly instruments = signal<Instrument[]>([]);
  readonly activity = signal<ActivityEntry[]>([]);

  /** Survives a reload, because a demo that refreshes the page should not lose its bookings. */
  readonly contracts = signal<OtcContract[]>(
    JSON.parse(sessionStorage.getItem('traderx-console-contracts') ?? '[]'));

  recordContract(c: OtcContract): void {
    this.contracts.update(list => {
      const next = [c, ...list.filter(x => x.contractId !== c.contractId)].slice(0, 100);
      sessionStorage.setItem('traderx-console-contracts', JSON.stringify(next));
      return next;
    });
  }

  // ---- sign-in (server-enforced; this is only the UI's view of it) -----------------------------

  /**
   * Who the console believes is signed in. The gate that MATTERS is in server.mjs — these endpoints
   * answer curl whether or not an Angular route rendered — so nothing here is a security control.
   * It exists so an operator sees a sign-in prompt instead of a raw 401.
   */
  readonly authUser = signal<string | null>(null);
  /** Set when a change was refused for want of a sign-in, so one prompt can be shown centrally. */
  readonly authPrompt = signal(false);

  /**
   * Which 401s mean "sign in", keyed on the server's `code` — the only one of the three candidates
   * that is a CONTRACT. Prose gets reworded and paths get rerouted, and neither breaks loudly:
   *
   *   admin_auth_required  an override was refused         → prompt
   *   signed_out           /auth/me with no session        → the resting state, say nothing
   *   bad_credentials      the login form's own business   → the form shows it
   *
   * The path rule is kept only as a fallback for a 401 carrying NO code — an older server, or risk
   * control, which has its own credential (X-Risk-Control-Token) and predates all of this. A
   * codeless 401 outside those paths still prompts, because that degrades to one unnecessary
   * prompt while the reverse degrades to a silently dead button.
   */
  private noteAuth(url: string, status: number, body: unknown): void {
    if (status !== 401) return;
    const code = (body as { code?: string } | null)?.code;
    if (code) {
      if (code !== 'admin_auth_required') return;
    } else if (url.includes('/risk/control/') || url.startsWith('/auth/')) {
      return;
    }
    this.authUser.set(null);
    this.authPrompt.set(true);
  }

  /**
   * Ask the server who we are. Checks the BODY, not just the status: with no /auth route the dev
   * server answers its SPA fallback — 200, with the index page — and a status-only check reads an
   * unauthenticated operator as signed in. Same fallthrough that made /mN and /grafana lie.
   */
  async checkAuth(): Promise<void> {
    const r = await this.load<{ user?: string }>('/auth/me');
    const user = r.status === 200 && r.body && typeof r.body === 'object' ? r.body.user : undefined;
    this.authUser.set(user ?? null);
    if (user) this.authPrompt.set(false);
  }

  /** Returns an error string, or null on success. The password is never stored or echoed. */
  async login(user: string, password: string): Promise<string | null> {
    const r = await this.load<{ user?: string; error?: string }>('/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user, password }),
    });
    if (r.status === 200 && r.body && typeof r.body === 'object' && r.body.user) {
      this.authUser.set(r.body.user);
      this.authPrompt.set(false);
      return null;
    }
    if (r.status === 404 || typeof r.body === 'string') {
      return 'the sign-in endpoint is not reachable from here — the console server serves it, so a '
        + 'dev session needs the /auth proxy route';
    }
    return (r.body && typeof r.body === 'object' && r.body.error) || `sign-in refused (HTTP ${r.status})`;
  }

  async logout(): Promise<void> {
    await this.load('/auth/logout', { method: 'POST' });
    this.authUser.set(null);
  }

  async load<T>(url: string, init?: RequestInit): Promise<{ status: number; body: T | null; headers?: Headers }> {
    try {
      // no-store: a pre-deploy 200 (e.g. the SPA fallthrough that /mN returned before the proxy
      // route existed) is otherwise replayed from the browser HTTP cache indefinitely.
      const r = await fetch(url, { cache: 'no-store', ...init });
      const text = await r.text();
      let body: T | null = null;
      try { body = text ? JSON.parse(text) : null; } catch { body = text as unknown as T; }
      // Headers, not just the body: a server that reports the SCOPE of what it aggregated
      // (X-Traderx-Gateways-Aggregated) is useless if the client throws that away and prints the
      // total under a label that assumes every gateway answered.
      this.noteAuth(url, r.status, body);
      return { status: r.status, body, headers: r.headers };
    } catch {
      return { status: 0, body: null };
    }
  }

  async init(): Promise<void> {
    const [, i] = await Promise.all([
      this.loadAccounts(),
      this.load<Instrument[]>('/reference-data/instruments'),
    ]);
    if (Array.isArray(i.body)) this.instruments.set(i.body);
  }

  async loadAccounts(): Promise<void> {
    const a = await this.load<Account[]>('/account-service/account/');
    if (Array.isArray(a.body)) this.accounts.set(a.body);
  }

  /**
   * An operator control command: sequenced through consensus like an order, so every member applies
   * it at a definite log position. `account` admits (or suspends) an account in the engine's risk
   * state — orders from an account the engine has never been told about are refused UNKNOWN_ACCOUNT,
   * which is why creating one in the account service alone is not enough to trade.
   *
   * The token is the gateway's own default: no rig currently sets RISK_CONTROL_TOKEN, so
   * ClusterGatewayMain's env("RISK_CONTROL_TOKEN", "dev-risk-control") stands on every tier.
   * A rig that does set it will answer 401 here, and the panel says so rather than pretending the
   * control landed. (Said "unset on the kind rig" until 2026-08-20, which read as a kind-only
   * fact and left the cloud tier looking unaccounted for.)
   */
  riskControl<T>(action: string, body: unknown): Promise<{ status: number; body: T | null }> {
    return this.load<T>(`/order-matcher/risk/control/${action}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Risk-Control-Token': 'dev-risk-control',
        'X-Risk-Operator': 'ui-console',
      },
      body: JSON.stringify(body),
    });
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

  /**
   * Mint an admin JWT. Without a secret typed here the console's own back end supplies the master
   * secret: proxy.conf.mjs reads it off the rig with kubectl, server.mjs reads it from the
   * auth-secrets Secret mounted into its pod. Either way the browser never holds it.
   */
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

  // ---- book-band screen ------------------------------------------------------------------------
  //
  // The price collar is a band anchored on the FIRST limit that entered a security's book, not a
  // percentage around the mark — so a book anchored by a stray order hours ago refuses every
  // realistic price for the rest of the epoch, and nothing repairs it in place: a /seed cannot move
  // a mark that has already printed (ADR-051), and the band is not derived from the mark anyway.
  // Measured on this rig: every MSFT order ever accepted is at 180.00 and every order at a
  // realistic MSFT price is refused. A demo driver typing a plausible MSFT order gets a refusal
  // and no way to see why, which is why this screen exists.
  //
  // The method matters as much as the finding. A band problem produces a DISJOINT accepted/rejected
  // price split; an overlap means the refusal came from something else entirely (unknown account,
  // credit, quantity) and says nothing about the band. Six securities on this rig show refusals and
  // only one is mis-anchored — reading "rejected" as "mis-anchored" would have condemned all seven.
  readonly bands = signal<BandCheck[]>([]);
  /**
   * Whether the screen can see at all. `disabled` is the regulatory projection being off — on the
   * cloud rig RECON_BLOTTER_CAPACITY is 0 by default, a deliberate throughput trade, and the route
   * answers 503. A panel that renders "no refusals on record" in that state is asserting an
   * all-clear it has no basis for.
   */
  readonly bandsState = signal<'loading' | 'ok' | 'disabled' | 'no-token' | 'unreachable'>('loading');
  private bandsAt = 0;

  /** Refresh at most once a minute; mints the admin token the regulatory report requires. */
  async loadBands(force = false): Promise<void> {
    if (!force && Date.now() - this.bandsAt < 60_000) return;
    if (!this.adminToken() && !(await this.mintAdminToken())) {
      this.bandsState.set('no-token');
      return;
    }
    const r = await this.load<RegulatoryEvent[]>('/order-matcher/regulatory/report', { headers: this.authHeaders() });
    if (r.status !== 200 || !Array.isArray(r.body)) {
      // WHY the distinction matters: with the projection off, an empty band list is not "no
      // mis-anchored books", it is "this console cannot see". Reporting the first when the truth is
      // the second is the same fault as a status page that goes green because its producer is
      // missing — the exact class that hid the kdb tap being disabled on this tier.
      this.bandsState.set(r.status === 503 ? 'disabled' : 'unreachable');
      this.bands.set([]);
      return;
    }
    this.bandsState.set('ok');
    this.bandsAt = Date.now();
    const acc = new Map<string, number[]>();
    const rej = new Map<string, number[]>();
    for (const e of r.body) {
      const into = e.kind === 'ORDER_ACCEPTED' ? acc : e.kind === 'ORDER_REJECTED' ? rej : null;
      if (into && e.security) into.set(e.security, [...(into.get(e.security) ?? []), Number(e.price)]);
    }
    const out: BandCheck[] = [];
    for (const [security, rejected] of rej) {
      const accepted = acc.get(security) ?? [];
      const rLo = Math.min(...rejected), rHi = Math.max(...rejected);
      const aLo = accepted.length ? Math.min(...accepted) : 0;
      const aHi = accepted.length ? Math.max(...accepted) : 0;
      out.push({
        security, accepted: accepted.length, rejected: rejected.length,
        acceptedLo: aLo, acceptedHi: aHi, rejectedLo: rLo, rejectedHi: rHi,
        verdict: !accepted.length ? 'never-accepted'
          : rLo > aHi || rHi < aLo ? 'anchored-elsewhere'
          : 'other-refusal',
        // One sample either side is an anecdote: the split can be disjoint by luck. Said out loud
        // rather than folded into the verdict, because a thin reading is still worth seeing.
        thin: Math.min(accepted.length, rejected.length) < 3,
      });
    }
    this.bands.set(out.sort((a, b) => a.security.localeCompare(b.security)));
  }

  /** The band verdict for one security, if it has any refusals on record. */
  band(security: string): BandCheck | undefined {
    return this.bands().find(b => b.security === security);
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
