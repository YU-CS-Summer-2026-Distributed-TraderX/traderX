import { InjectionToken } from '@angular/core';
import { ACTIVE_URL, REGISTRY_URL, LEGACY_SCOPE, readActive, readRegistry, scopesOf, urlsFor, View, Loader } from '../../../web-front-end-console/src/app/run-scope';

// Tests opt into this explicitly. The running application provides true; no network failure can select fixtures.
export const CONNECTED_RIG = new InjectionToken<boolean>('connected rig', {providedIn:'root',factory:()=>false});
export async function readRigAccount(load: Loader, account: number, view: View) {
  const reg = await load(REGISTRY_URL);
  const registry = readRegistry(reg.status, reg.body);
  if (registry.kind === 'unavailable') throw Error(registry.error);
  let scope: string | null = null;
  if (registry.kind === 'managed') {
    if (view.kind === 'history') scope = view.scope;
    else {
      const r = await load(ACTIVE_URL); const a = readActive(r.status,r.body);
      if (!a.ok) throw Error(a.error); scope = a.scope;
    }
    if (!registry.runs.some(r=>r.projection_scope===scope)) throw Error('Selected run is not registered.');
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
