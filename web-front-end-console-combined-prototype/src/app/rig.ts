import { InjectionToken } from '@angular/core';
import { ACTIVE_URL, REGISTRY_URL, LEGACY_SCOPE, readActive, readRegistry, scopesOf, urlsFor, View, Loader } from '../../../web-front-end-console/src/app/run-scope';

// Tests opt into this explicitly. The running application provides true; no network failure can select fixtures.
export const CONNECTED_RIG = new InjectionToken<boolean>('connected rig', {providedIn:'root',factory:()=>false});
/** The Desk trades only inside a managed run; a stock bring-up provisions none. */
export const NO_MANAGED_RUN = 'No managed run on this rig: the projection registry (position-service /v2/projections) is empty and the gateway has no run descriptor (RUN_DESCRIPTOR_PATH). The Desk trades only inside a managed run; on a plain bring-up, sign in with the operator password (or trade from the Demo console).';

/** operator: FR-CUI-23, an operator session reads the legacy projection of a plain bring-up (empty registry). */
export async function readRigAccount(load: Loader, account: number, view: View, operator = false) {
  const reg = await load(REGISTRY_URL);
  const registry = readRegistry(reg.status, reg.body);
  if (registry.kind === 'unavailable') throw Error(registry.error);
  let scope: string | null = null;
  if (registry.kind === 'managed') {
    // A plain bring-up (kind or GKE, 2026-10-26) registers no run: the registry is `[]` and the
    // active pointer is a 503. Say what is missing rather than 'active run: HTTP 503'.
    if (registry.runs.length === 0) { if (!operator || view.kind === 'history') throw Error(NO_MANAGED_RUN); }
    else if (view.kind === 'history') scope = view.scope;
    else {
      const r = await load(ACTIVE_URL); const a = readActive(r.status,r.body);
      if (!a.ok) throw Error(a.error); scope = a.scope;
    }
    if (registry.runs.length && !registry.runs.some(r=>r.projection_scope===scope)) throw Error('Selected run is not registered.');
  } else if (view.kind === 'history') throw Error('This rig does not support historical runs.');
  const urls = urlsFor(scope === null ? {kind:'active'} : {kind:'history',scope},account,true);
  const [positions,trades,orders] = await Promise.all([load(urls.positions),load(urls.trades),load(urls.orders)]);
  for (const [name,r] of [['positions',positions],['trades',trades],['orders',orders]] as const) {
    if (r.status !== 200 || !Array.isArray(r.body)) throw Error(`${name}: ${r.status ? 'HTTP '+r.status : 'no response'}`);
    if (r.body.some(row=>row.accountId !== undefined && Number(row.accountId)!==account)) throw Error('Response belongs to another account.');
  }
  if (view.kind === 'active' && scope !== null) {
    const r=await load(ACTIVE_URL), again=readActive(r.status,r.body);
    if (!again.ok || again.scope!==scope) throw Error('Active run changed during the read. Refresh before trading.');
  }
  const expected=scope??LEGACY_SCOPE;
  if ([...scopesOf(positions.body as unknown[],trades.body as unknown[],orders.body as unknown[])].some(s=>s!==expected)) throw Error('Response contains rows from another run.');
  return {scope, runs:registry.kind==='managed'?registry.runs:[], positions:positions.body as any[],trades:trades.body as any[],orders:orders.body as any[]};
}

/** Fence even transports that ignore AbortSignal. */
export function bounded<T>(pending: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve,reject)=>{
    const abort=()=>reject(Error('Account read timed out or context changed.'));
    if(signal.aborted) {abort();return;}
    signal.addEventListener('abort',abort,{once:true});
    pending.then(resolve,reject).finally(()=>signal.removeEventListener('abort',abort));
  });
}

export type OtcContract = { contractId:number; ref:string; accountId:number; product:'SWAP'|'SWAPTION'; payReceive:string; notional:number;
  fixedRate:string; convention:string; effectiveDate:string; maturityDate:string; expiryDate?:string; exerciseStyle?:string };
/** FR-CUI-24: booked swaps and swaptions from the engine (any member: they agree). null = no member answered.
 *  Engine state, not run-scoped, so it is read beside the account view and never fails it. */
export async function readContracts(load: Loader, account: number, members = 3): Promise<OtcContract[] | null> {
  for (let m = 0; m < members; m++) {
    const r = await load(`/mem/${m}/otc/contracts?account=${account}`).catch(() => null);
    const rows = r?.status === 200 ? (r.body as any)?.contracts : undefined;
    if (Array.isArray(rows)) return rows.filter((c: OtcContract) => Number(c.accountId) === account);
  }
  return null;
}
