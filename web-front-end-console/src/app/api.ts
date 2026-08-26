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

/** A live mark and where it came from. */
export interface PriceMark {
  price: number; dir: 1 | -1 | 0;
  /** The publisher's own `source` string, verbatim — never a guess, absent if the tick omitted it. */
  source?: string;
  /** Tape time on replayed names, wall-clock on the rest. Not comparable across sources. */
  asOf?: string;
}

/**
 * How to READ a `source`, without pretending there are only two kinds.
 *
 * The obvious design is a binary tape/synthetic chip. Measured against the rig that is wrong twice
 * over: treasuries come from FRED and are real but not tape, and the two names deliberately excluded
 * from the replay publish `previous-close` — carried forward, which is neither a tape price nor a
 * moving simulation. Both would be mislabelled by a two-way split, and mislabelled in the direction
 * that overclaims.
 */
export type Provenance = 'tape' | 'reference' | 'model' | 'simulated' | 'carried' | 'unknown';

export function provenanceOf(source: string | undefined): Provenance {
  if (!source) { return 'unknown'; }
  if (source.startsWith('taq-replay')) { return 'tape'; }
  if (source.startsWith('fred-')) { return 'reference'; }
  if (source === 'black-scholes') { return 'model'; }
  if (source.startsWith('simulated-')) { return 'simulated'; }
  if (source === 'previous-close') { return 'carried'; }
  return 'unknown';
}

/** Short label for a chip. The exact source string belongs in the title, not the chip. */
export const PROVENANCE_LABEL: Record<Provenance, string> = {
  tape: 'tape', reference: 'FRED', model: 'model',
  simulated: 'sim', carried: 'prev close', unknown: 'source?',
};

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
 * `anchored-elsewhere` means no refused price lies INSIDE the accepted range — the signature of a
 * collar band anchored away from the market. `other-refusal` means some refusal landed among the
 * accepted prices, so the same region was both accepted and refused and the cause is not the band.
 *
 * This deliberately is NOT a disjointness test, and the difference is the whole point of the panel:
 * a collar refuses on BOTH sides of its band, so the refused min/max straddles the accepted prices
 * and the two ranges always overlap. Disjointness therefore reports a band's own signature as
 * "some other cause" — measured on EXC (accepted 150, refused 100 and 180-210) it inverted the
 * answer for exactly the case this panel exists to catch. Containment handles one-sided and
 * two-sided bands alike. Note the report carries no reason code on
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

/**
 * The trace id the gateway will stamp for an order, derived from the CLIENT ORDER ID.
 *
 * Verified end to end against Tempo once tracing was switched on: a rejected order submitted with
 * clientOrderId `clord-check-1787286337815` produced trace `fc81f3c0f256ae46191ea232619e9b05`, which
 * is exactly what this returns, and it resolves.
 *
 * The `orderRef` fallback is GONE, and deliberately returns undefined rather than a plausible id.
 * Measured on the same rig: order ref 2720's real trace is `b12477501b62717b510f7069987442b5` while
 * mix(2720) gives `d74f929ffa736c9545ab2aa44692ec76` — the gateway does not derive from the ref, so
 * that branch produced ids for traces that never existed. A wrong id is worse than no id here,
 * because the lookup 404s and the panel then blames head sampling for a broken derivation: an
 * answer-is-no wearing a no-answer's clothes.
 */
export function traceIdFor(clientOrderId: string | undefined, _orderRef?: number): string | undefined {
  if (!clientOrderId) return undefined;
  const key = fnv64(clientOrderId);
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

  /**
   * `load` with the status check built in, so a non-2xx cannot be parsed as data.
   *
   * `load` returns `{status, body}` and trusts the caller to look at the status. Twice in one week
   * that trust was misplaced in the same way: a 401 from `/order-matcher/regulatory/report` was read
   * as an empty result and reported as "kind has no data", and an unproxied route's 200 of
   * index.html was read as a clock. Neither was a lapse of care — a body is right there and looks
   * like an answer, and `.json()` on a refusal is silent by default.
   *
   * So the shape stops offering the mistake: this returns a value or a reason, never both, and
   * there is no field to forget to check. Prefer it for new reads.
   */
  async fetchJson<T>(url: string, init?: RequestInit): Promise<{ ok: true; value: T } | { ok: false; error: string }> {
    const r = await this.load<T>(url, init);
    if (r.status === 0) { return { ok: false, error: 'no response' }; }
    if (r.status < 200 || r.status >= 300) {
      const said = (r.body as { error?: string } | null)?.error;
      return { ok: false, error: said ? `HTTP ${r.status} — ${said}` : `HTTP ${r.status}` };
    }
    // A 2xx whose body did not parse is a route answering with something that is not data at all —
    // usually a dev server's SPA fallback, which is a page wearing a success code.
    if (r.body === null || typeof r.body === 'string') {
      return { ok: false, error: 'this route did not return data — likely not proxied here' };
    }
    return { ok: true, value: r.body as T };
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

  /**
   * Order ref -> trace id: the console's own record of what it submitted, and the FALLBACK behind
   * the read model's own `traceId` column.
   *
   * It was once the only source. The orderbook projection carried no client order id and no trace
   * id — orderid/accountid/security/side/quantity/remainingquantity/limitprice/status/createdat/
   * updatedat/lastexecutionprice/lastfillquantity — and a trace id derives from the CLIENT order
   * id, which the client invents and nothing downstream kept. The engine now stamps the derived id
   * on the order's own egress and the projection persists it, so an order this browser never sent
   * is traceable from its row. This map still covers the gap in the other direction: an order this
   * session submitted that the engine did not stamp.
   *
   * Prefer the row's field where both exist. It arrives attached to the row and so cannot be
   * answering about a different epoch, which is the hazard this map needs `noteEpoch` to guard.
   *
   * Persisted for the same reason `contracts` is: the activity log lives in memory and a refresh
   * emptied it, which took the trace link with it — the order was still resting, still had a
   * trace, and the page could no longer say which. sessionStorage, not localStorage: a trace only
   * resolves while its epoch's spans are in Tempo, so surviving a browser restart would mostly
   * offer links that 404.
   */
  readonly orderTraces = signal<Record<string, string>>(
    JSON.parse(sessionStorage.getItem('traderx-console-order-traces') ?? '{}'));

  /** The trace for an order ref, or undefined — never a guess. */
  traceForOrderRef(ref: number | null | undefined): string | undefined {
    if (ref === null || ref === undefined || ref === 0) return undefined;
    return this.orderTraces()[String(ref)];
  }

  /**
   * Witness the epoch the rig is currently on, and DROP the map if it has changed.
   *
   * Order refs are not unique across epochs: `MatchingEngineClusteredService.nextOrderRef` starts
   * at 1 and is restored only from a snapshot, and a fresh-epoch roll wipes the PVCs — so epoch 2
   * issues ref 7 again. sessionStorage outlives a refresh, so without this a map keyed on the bare
   * ref hands epoch 1's trace to epoch 2's order 7: a REAL trace for the WRONG order, which is
   * worse than no link. A wrong id resolves, renders five convincing spans, and says nothing about
   * the order you are looking at.
   *
   * Witnessed on READ rather than recorded on write because the console cannot know the epoch when
   * it logs — the gateway's accept response carries `orderRef` and nothing else. Every order and
   * trade it renders is `<epoch>-…`, so the epoch arrives with the data.
   *
   * Clearing is deliberately blunt: entries carry no epoch of their own, so the whole map goes.
   * Losing a link the operator could still have used is the safe direction — the panel then says
   * it cannot name the id, which is true.
   */
  noteEpoch(epoch: number): void {
    if (!Number.isFinite(epoch) || epoch <= 0) return;
    const known = Number(sessionStorage.getItem('traderx-console-order-trace-epoch') ?? 0);
    if (known === epoch) return;
    sessionStorage.setItem('traderx-console-order-trace-epoch', String(epoch));
    if (!known) return;                                   // first sighting: adopt, do not discard
    sessionStorage.removeItem('traderx-console-order-traces');
    this.orderTraces.set({});
  }

  log(e: Omit<ActivityEntry, 'at'>): void {
    // Recorded HERE rather than at each submit site so a new caller cannot forget it: every path
    // that already logs an order with both a ref and a trace persists the pair for free.
    if (e.orderRef && e.traceId) {
      this.orderTraces.update(m => {
        const next = { ...m, [String(e.orderRef)]: e.traceId! };
        // Bounded: a long demo session should not grow this without limit. Object.keys on
        // integer-like keys enumerates ASCENDING NUMERICALLY, not by insertion — which happens to
        // be oldest-first here because refs are monotonic within an epoch. Stated exactly because
        // re-keying this map on anything non-numeric (e.g. "<epoch>-<ref>") silently switches it
        // to insertion order and this line stops meaning what it says.
        const keys = Object.keys(next);
        for (const k of keys.slice(0, Math.max(0, keys.length - 500))) delete next[k];
        sessionStorage.setItem('traderx-console-order-traces', JSON.stringify(next));
        return next;
      });
    }
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

  /**
   * ticker -> {price, dir, source, asOf} from price-publisher's `pricing.<ticker>` ticks.
   *
   * `source` and `asOf` were on the wire all along and were being dropped here. They matter now
   * that not every price comes from the same place: measured live, one rig publishes five distinct
   * provenances at once — `taq-replay-2025-02` (a real 2025 tape), `fred-us-treasury-cmt-curve`
   * (real), `black-scholes` (derived from the underlying), `simulated-corporate-credit-spread`, and
   * `previous-close` (carried forward, not moving). A price is not meaningful without knowing which
   * of those produced it, and a number that looks live but is a carried-forward close is the kind
   * of thing an audience reads wrongly.
   *
   * `asOf` is the TAPE's timestamp on replayed names and wall-clock on the rest, so the two are not
   * comparable and nothing here should difference them.
   */
  readonly prices = signal<Record<string, PriceMark>>({});
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
          return { ...m, [p.ticker]: {
            price: p.price, dir: dir as 1 | -1 | 0,
            source: typeof p.source === 'string' ? p.source : undefined,
            asOf: typeof p.asOf === 'string' ? p.asOf : undefined,
          } };
        });
      } catch { /* not a price tick */ }
    });
  }

  // ---- admin token (shared by the EOD and Admin pages) ----------------------------------------

  /**
   * THERE IS NO CLIENT-HELD ADMIN TOKEN ANY MORE, deliberately.
   *
   * The console used to mint one from a master secret it was handed, and inject it on every
   * /trade-processor call. That made it a confused deputy: it held a powerful credential and would
   * issue a full admin:true JWT to anyone who loaded the page — a token that outlives the tab, works
   * against anything sharing the JWT secret, and records nothing about who took it. The mint now
   * answers 403 `mint_disabled` to everyone.
   *
   * The server authenticates on the caller's behalf instead, attaching Authorization to
   * /trade-processor/* and /order-matcher/regulatory* itself, and OVERWRITING whatever the client
   * sent. So reads stay open to anonymous viewers — which is the point, the read-only panels are
   * for them — while changes stay gated by ADMIN_MUTATIONS, which refuses before the proxy runs.
   *
   * Consequence for this file: no adminToken, no mintAdminToken, no authHeaders, and nothing in the
   * UI may gate on holding a token. Three panels did, and each rendered a dead end once minting
   * stopped: an instruction to visit a page that would mint (it cannot), and a box asking the
   * operator to paste the master secret (it would be refused, and the console should not be asking
   * for one at all).
   */

  // ---- book-band screen ------------------------------------------------------------------------
  //
  // The price collar is a band anchored on the FIRST limit that entered a security's book, not a
  // percentage around the mark — so a book anchored by a stray order hours ago refuses every
  // realistic price for the rest of the epoch, and nothing repairs it in place: a /seed cannot move
  // a mark that has already printed (ADR-051), and the band is not derived from the mark anyway.
  //
  // The method matters as much as the finding: a refused price INSIDE the accepted range means the
  // band cannot be what refused it (unknown account, credit, quantity), while refusals lying only
  // outside that range are the band's own signature. NOT a disjointness test — a collar refuses on
  // both sides, so the ranges overlap even when the band is exactly the cause. Most securities
  // showing refusals are not mis-anchored, and reading "rejected" as "mis-anchored" would condemn
  // all of them.
  readonly bands = signal<BandCheck[]>([]);

  /**
   * Whether the screen can see at all. `disabled` is the regulatory projection being off — on the
   * cloud rig RECON_BLOTTER_CAPACITY is 0 by default, a deliberate throughput trade, and the route
   * answers 503. A panel that renders "no refusals on record" in that state is asserting an
   * all-clear it has no basis for.
   */
  readonly bandsState = signal<'loading' | 'ok' | 'disabled' | 'no-credential' | 'absent' | 'unreachable'>('loading');
  private bandsAt = 0;

  /** Refresh at most once a minute. Sends no credential — the console's server attaches one. */
  async loadBands(force = false): Promise<void> {
    if (!force && Date.now() - this.bandsAt < 60_000) return;
    // No credential is sent: the console's own server attaches one for this path. A 401 here is
    // therefore a statement about the RIG (a tier whose server does not attach it), never about a
    // token this page is holding — which is why the retry-after-mint that used to live here is gone
    // rather than merely unused.
    const r = await this.load<RegulatoryEvent[]>('/order-matcher/regulatory/report');
    if (r.status !== 200 || !Array.isArray(r.body)) {
      // WHY the distinction matters: with the projection off, an empty band list is not "no
      // mis-anchored books", it is "this console cannot see". Reporting the first when the truth is
      // the second is the same fault as a status page that goes green because its producer is
      // missing — the exact class that hid the kdb tap being disabled on this tier.
      //
      // And WHICH way it cannot see is worth separating, because each sends the reader somewhere
      // different: 503 is the projection deliberately off, 404 is a build that never registered the
      // route, 401 means this rig's server did not attach a credential for the console, and only
      // what is left is actually the route being unreachable.
      this.bandsState.set(
        r.status === 503 ? 'disabled'
        : r.status === 404 ? 'absent'
        : r.status === 401 ? 'no-credential'
        : 'unreachable');
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
        // WHICH TEST: "no refused price lies INSIDE the accepted range", not "the two ranges are
        // disjoint". A collar refuses on BOTH sides of its band, so the refused min/max straddles
        // the accepted prices and the ranges always overlap — the disjointness test then reports
        // the band's own signature as "some other cause". Measured on EXC: accepted 150, refused
        // 100 and 180-210, which is a band around 150 read as unrelated. Containment gets it right
        // both ways, and still says "other" the moment a refusal lands among the accepted prices,
        // which is the thing that genuinely rules the band out.
        verdict: !accepted.length ? 'never-accepted'
          : rejected.every(p => p < aLo || p > aHi) ? 'anchored-elsewhere'
          : 'other-refusal',
        // One sample either side is an anecdote: with one accepted and one refused price, the
        // refusal can fall outside the accepted range by luck rather than by a band. Said out loud
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

  /** Clear the token this console used to mint, in case a session still carries one. */
  dropAdminToken(): void { sessionStorage.removeItem('traderx-console-eod-token'); }
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
