/**
 * Which run's projection the blotter is showing, and how the console knows.
 *
 * Contract read from source, not assumed (YU18 RI-06, active reader integrated at 3f8db417):
 *  - position-service `GET /v2/projections` → rows of the run registry, each exactly
 *    {projection_scope, cluster_epoch, event_id_scheme, descriptor_hash, phase, checkpoint_seq}.
 *  - position-service `GET /v2/projections/active` → {"projectionScope": s} read from the
 *    projection_active pointer joined to a registered run; missing/dangling → 503.
 *  - The unversioned reads (`/positions/{a}`, `/trades/{a}`, `/accounts/{a}/orders`) resolve the
 *    active scope server-side, and every row carries `projectionScope`.
 *  - `/v2/projections/{scope}/accounts/{a}/positions|trades` (position-service) and `/orders`
 *    (trade-processor) read one named scope; an unregistered scope is 404.
 *  - Notifications: `/v2/projections/{scope}/accounts/{a}/trades|positions`; `legacy-unknown`
 *    keeps `/accounts/{a}/trades|positions`.
 *
 * Pure functions only, so the rules that keep one run's rows out of another are unit-testable.
 */

export const LEGACY_SCOPE = 'legacy-unknown';

export interface RunRow {
  projection_scope: string;
  cluster_epoch: string | null;
  event_id_scheme: string;
  descriptor_hash: string | null;
  phase: string;
  checkpoint_seq: number;
}

/** What the registry read said. `unmanaged` only when the route itself does not exist (404). */
export type Registry =
  | { kind: 'managed'; runs: RunRow[] }
  | { kind: 'unmanaged' }
  | { kind: 'unavailable'; error: string };

export function readRegistry(status: number, body: unknown): Registry {
  // A pre-RI06 position-service has no /v2 route: its 404 is the one legitimate unmanaged signal.
  // Anything else that is not a well-formed list is a FAILED managed read, never a fallback.
  if (status === 404) return { kind: 'unmanaged' };
  if (status === 0) return { kind: 'unavailable', error: 'run registry: no response' };
  if (status !== 200) return { kind: 'unavailable', error: `run registry: HTTP ${status}` };
  if (!Array.isArray(body) || !body.every(isRunRow)) {
    return { kind: 'unavailable', error: 'run registry: response is not a registry list' };
  }
  return { kind: 'managed', runs: body };
}

function isRunRow(r: unknown): r is RunRow {
  const o = r as RunRow;
  return !!o && typeof o.projection_scope === 'string' && typeof o.phase === 'string';
}

/** The active view's scope and the evidence for it. `null` scope = not confirmed, said so on screen. */
export interface ActiveScope { scope: string | null; basis: string; }

export const ACTIVE_URL = '/position-service/v2/projections/active';
export const REGISTRY_URL = '/position-service/v2/projections';

/**
 * The server's selected scope, from `GET /v2/projections/active` → exactly `{"projectionScope": s}`
 * (a missing or dangling pointer is a 503). This is the ONLY source of the active scope for a
 * managed backend: no inference from rows or phases, and any failure is "not confirmed".
 */
export function readActive(status: number, body: unknown): { ok: true; scope: string } | { ok: false; error: string } {
  if (status === 0) return { ok: false, error: 'active run: no response' };
  if (status !== 200) return { ok: false, error: `active run: HTTP ${status}` };
  const s = (body as { projectionScope?: unknown } | null)?.projectionScope;
  if (!body || typeof body !== 'object' || typeof s !== 'string' || Object.keys(body).length !== 1) {
    return { ok: false, error: 'active run: response is not {projectionScope}' };
  }
  return { ok: true, scope: s };
}

/** Scopes named by the rows of one read. Rows from a pre-RI06 service carry none: legacy. */
export function scopesOf(...lists: unknown[][]): Set<string> {
  const out = new Set<string>();
  for (const list of lists) for (const r of list) {
    const s = (r as { projectionScope?: unknown })?.projectionScope;
    out.add(typeof s === 'string' ? s : LEGACY_SCOPE);
  }
  return out;
}

/** Notification subjects for one scope + account (TradeService.projectionTopic). */
export function topicsFor(scope: string, account: number): string[] {
  const prefix = scope === LEGACY_SCOPE ? '' : `/v2/projections/${scope}`;
  return [`${prefix}/accounts/${account}/trades`, `${prefix}/accounts/${account}/positions`];
}

/** What the blotter is looking at: the server's active run, or one named historical run. */
export type View = { kind: 'active' } | { kind: 'history'; scope: string };

/** Read URLs for a view. Scope is URI-encoded; the server also refuses anything unregistered. */
export function urlsFor(view: View, account: number, allOrders: boolean) {
  const q = allOrders ? '?status=all' : '';
  if (view.kind === 'active') {
    return {
      positions: `/position-service/positions/${account}`,
      trades: `/position-service/trades/${account}`,
      orders: `/trade-processor/accounts/${account}/orders${q}`,
    };
  }
  const s = encodeURIComponent(view.scope);
  return {
    positions: `/position-service/v2/projections/${s}/accounts/${account}/positions`,
    trades: `/position-service/v2/projections/${s}/accounts/${account}/trades`,
    orders: `/trade-processor/v2/projections/${s}/accounts/${account}/orders${q}`,
  };
}

/**
 * The fence against a stale answer: every read and every subscription is stamped with the context
 * it was issued for, and anything arriving for an older context is dropped. A counter, not a
 * comparison of (account, scope) — switching A→B→A must still drop A's first, slower response.
 */
export class Context {
  private n = 0;
  get current(): number { return this.n; }
  next(): number { return ++this.n; }
  isCurrent(stamp: number): boolean { return stamp === this.n; }
}

export type Loader = (url: string) => Promise<{ status: number; body: unknown }>;

/**
 * An account's trades from the ACTIVE run, for panels that show only that (Admin's trade list).
 * Same rules as the blotter: registry 404 → unmanaged unversioned read; otherwise the server
 * pointer, the scoped reader, the pointer again, and every row in that scope — or a refusal.
 */
export async function readActiveRunTrades(load: Loader, account: number):
    Promise<{ ok: true; scope: string; managed: boolean; rows: unknown[] } | { ok: false; error: string }> {
  const [reg, act] = await Promise.all([load(REGISTRY_URL), load(ACTIVE_URL)]);
  const registry = readRegistry(reg.status, reg.body);
  if (registry.kind === 'unavailable') return { ok: false, error: registry.error };
  let scope: string | null = null;
  if (registry.kind === 'managed') {
    const a = readActive(act.status, act.body);
    if (!a.ok) return { ok: false, error: a.error };
    if (!registry.runs.some(r => r.projection_scope === a.scope)) return { ok: false, error: `active run ${a.scope} is not in the registry list` };
    scope = a.scope;
  }
  const url = scope === null ? urlsFor({ kind: 'active' }, account, false).trades : urlsFor({ kind: 'history', scope }, account, false).trades;
  const t = await load(url);
  if (t.status !== 200 || !Array.isArray(t.body)) return { ok: false, error: `trades HTTP ${t.status}` };
  if (scope !== null) {
    const r = await load(ACTIVE_URL);
    const again = readActive(r.status, r.body);
    if (!again.ok) return { ok: false, error: again.error };
    if (again.scope !== scope) return { ok: false, error: `active run changed during the read (${scope} → ${again.scope})` };
  }
  const expected = scope ?? LEGACY_SCOPE;
  if ([...scopesOf(t.body)].some(s => s !== expected)) return { ok: false, error: `refused: rows outside run ${expected}` };
  return { ok: true, scope: expected, managed: scope !== null, rows: t.body };
}
