// yu18-console-run-scope.mjs — the browser half of yu18-console-run-scope.sh (FR-MUI01..06, 08, 09).
// The spec pack's own SC-MUI04/06 proof (components/managed-run-ui/proofs/browser-proof.mjs) moved
// onto a GKE side namespace: the SHIPPED console behind its own sign-in, the real trade-processor /
// position-service / NATS, registry phases and scoped rows as the spec's labelled SQL fixtures
// (applied with kubectl exec on the rig's database). Database "stopped" = the Deployment scaled to
// 0; a HUNG read = a session holding WRITE locks on the registry tables (reads wait, nothing fails).
// Env: CONSOLE_URL CONSOLE_USER CONSOLE_PASSWORD NATS_MON NATS_PORT CTX NS DB_USER DB_PASS OUT
//      FIXTURES (the spec's proofs dir) [DESTRUCTIVE=1 for steps 10-11]
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { browser, sleep } from './lib-cdp.mjs';

const E = process.env;
const OUT = E.OUT, FIX = E.FIXTURES, CONSOLE = E.CONSOLE_URL;
const K = ['--context', E.CTX, '-n', E.NS];
const kube = (args, input) => execFileSync('kubectl', [...K, ...args], { input, stdio: ['pipe', 'pipe', 'pipe'] }).toString();
const db = ['exec', '-i', 'deploy/eod-price-db', '-c', E.DB_CONTAINER ?? 'mariadb', '--', 'mariadb', `-u${E.DB_USER}`, `-p${E.DB_PASS}`, 'traderx', '-sN'];
const sqlText = s => kube(db, s);
const sql = f => sqlText(fs.readFileSync(path.join(FIX, f)));
// Subscriptions the browser holds: NATS monitoring, websocket clients only (the services are not).
const subs = async () => {
  const c = await (await fetch(`${E.NATS_MON}/connz?subs=1&limit=1024`)).json();
  const ws = (c.connections ?? []).filter(x => x.type === 'websocket');   // NATS connz: the browser connects over websocket
  return [...new Set(ws.flatMap(x => x.subscriptions_list ?? []).filter(s => s.includes('/accounts/')))].sort();
};
const publish = subject => new Promise(res => {
  const s = net.connect(Number(E.NATS_PORT), '127.0.0.1', () => s.write(`CONNECT {"verbose":false}\r\nPUB ${subject} 2\r\n{}\r\nPING\r\n`));
  s.on('data', d => { if (String(d).includes('PONG')) { s.end(); res(Date.now()); } });
});

const b = await browser({ out: OUT, port: 26852 });
const text = () => b.js(`return document.querySelector('blotter-panel')?.innerText ?? '(no blotter-panel) ' + document.body.innerText.slice(0,800)`);
const until = (pred, what, ms = 12000) => b.until(async () => { const t = await text(); return [pred(t), t]; }, what, ms);
const pick = (sel, match) => b.js(`const s=document.querySelector('blotter-panel ${sel}'); const o=[...s.options].find(o=>o.text.includes(${JSON.stringify(match)})||o.value===${JSON.stringify(match)}); if(!o) throw new Error('no option ${match} in '+[...s.options].map(o=>o.text).join('|')); s.value=o.value; s.dispatchEvent(new Event('change')); return o.text;`);
const cancels = () => b.js(`return document.querySelectorAll('blotter-panel button.cancel').length`);
const same = async (want, m) => { await sleep(1500); const got = await subs(); b.check(JSON.stringify(got) === JSON.stringify(want), `${m} (${JSON.stringify(got)})`); };

// Sign in through the console's own route (same origin, so the browser holds the session cookie).
await b.go(CONSOLE, 1500);
const login = await b.js(`const r=await fetch('/auth/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({user:${JSON.stringify(E.CONSOLE_USER)},password:${JSON.stringify(E.CONSOLE_PASSWORD)}})}); return r.status`);
b.check(login === 200, `console sign-in (${login})`);
await b.go(CONSOLE, 3000);
if (E.BLOTTER_NAV) await b.js(E.BLOTTER_NAV);
await b.until(async () => [await b.js(`return !!document.querySelector('blotter-panel .acct select')`)], 'blotter-panel with an account selector', 15000);

await pick('.acct select', '(22214)');
if (E.MANAGED !== '1') {
  // A registry 404 is the only signal for an unmanaged backend (FR-MUI06): legacy reads, labelled.
  const t0 = await until(x => x.includes('unmanaged backend (no run registry)'), 'unmanaged label on a registry-404 backend');
  b.check(!/Active run: /.test(t0) || t0.includes('unmanaged'), 'FR-MUI06: registry 404 selects unmanaged mode, labelled');
  await same(['/accounts/22214/positions', '/accounts/22214/trades'], 'FR-MUI03: legacy subjects on an unmanaged backend');
  await b.shot('00-unmanaged', 'blotter-panel');
  b.done();
}

// 1. Legacy-unknown is the active scope, from the server pointer (FR-MUI01).
let t = await until(x => x.includes('Active run: legacy-unknown'), 'legacy active');
b.check(t.includes('selected pointer, GET /v2/projections/active'), 'FR-MUI01: the active scope is named from the server pointer');
await same(['/accounts/22214/positions', '/accounts/22214/trades'], 'FR-MUI03: legacy subjects for legacy-unknown');
await b.shot('01-legacy-active', 'blotter-panel');

// 2a. Frozen window: the pointer still names legacy-unknown.
sql('fixture-freeze-legacy.sql');
await pick('.acct select', '(10031)');
t = await until(x => x.includes('Active run: legacy-unknown'), 'legacy during freeze');
b.check(t.includes('selected pointer'), 'FR-MUI01: during the frozen window the scope still comes from the pointer');
// 2b. Dangling pointer: nothing shown, nothing inferred, nothing subscribed, no actions (FR-MUI01/08).
await pick('.acct select', '(22214)');
await until(x => x.includes('Active run: legacy-unknown'), 'legacy back');
sql('fixture-dangling-pointer.sql');
t = await until(x => /active run: HTTP 5\d\d/.test(x), 'pointer failure shown');
b.check(t.includes('not confirmed') && t.includes('polling'), 'FR-MUI01: a dangling pointer is named, nothing inferred');
b.check(await cancels() === 0, 'FR-MUI08: no row actions while the pointer is unavailable');
await same([], 'FR-MUI03/08: no subscription while scope is unknown');
await b.shot('02b-dangling-pointer', 'blotter-panel');
sql('fixture-restore-legacy-pointer.sql');

// 3. Selection to run_a underneath the open page (FR-MUI05).
await until(x => x.includes('Active run: legacy-unknown'), 'legacy again');
sql('fixture-select-run-a.sql'); sql('fixture-rows-run-a.sql');
t = await until(x => x.includes('Active run changed: legacy-unknown → run_a') && x.includes('e1-run_a-22214-B'), 'change notice + run_a rows');
b.check(!t.includes('GOOG'), 'FR-MUI04/05: announced, re-read from run_a, no other-account rows');
await same(['/v2/projections/run_a/accounts/22214/positions', '/v2/projections/run_a/accounts/22214/trades'], 'FR-MUI03: managed run_a subjects only');
await b.shot('03-active-changed-run_a', 'blotter-panel');

// 4. Only the current scope+account event triggers an off-timer read (FR-MUI03/04).
await b.js(`window.__reads=[]; new PerformanceObserver(l=>{for(const e of l.getEntries()) if(e.name.includes('/v2/projections/run_a/accounts/22214/trades')) window.__reads.push(Math.round(performance.timeOrigin+e.startTime));}).observe({type:'resource'}); return 1`);
await sleep(3200);
const pubs = {};
for (const s of ['/accounts/22214/trades', '/v2/projections/run_b/accounts/22214/trades', '/v2/projections/run_a/accounts/11413/trades', '/v2/projections/run_a/accounts/22214/trades']) { pubs[s] = await publish(s); await sleep(1100); }
const reads = await b.js(`return window.__reads`);
const near = t0 => reads.some(r => r >= t0 && r - t0 < 400);
b.note('publishes/reads', { pubs, reads });
b.check(near(pubs['/v2/projections/run_a/accounts/22214/trades']), 'FR-MUI03: a current-scope event triggers a read');
b.check(!near(pubs['/accounts/22214/trades']) && !near(pubs['/v2/projections/run_b/accounts/22214/trades']) && !near(pubs['/v2/projections/run_a/accounts/11413/trades']),
  'FR-MUI04: legacy / other-run / other-account events trigger no read (negative control)');

// 5. Selection to run_b; the active view offers actions on its open order.
sql('fixture-select-run-b.sql'); sql('fixture-rows-run-b.sql');
t = await until(x => x.includes('Active run changed: run_a → run_b') && x.includes('e1-run_b-22214-B'), 'run_b');
b.check(!t.includes('e1-run_a-'), 'FR-MUI04: no run_a rows in the run_b view');
b.check(await cancels() === 1, 'active confirmed view offers cancel on its open order (positive control for the read-only checks)');
await b.shot('04-active-run_b', 'blotter-panel');

// 6. Named historical run: read-only, scoped rows, scoped subjects (FR-MUI02).
await pick('[data-testid=run-select]', 'run_a');
t = await until(x => x.includes('HISTORICAL · READ-ONLY — run run_a') && x.includes('e1-run_a-22214-B'), 'history run_a');
b.check(!t.includes('e1-run_b-') && !t.includes('MSFT'), 'FR-MUI02: history holds only run_a rows');
await b.js(`[...document.querySelectorAll('blotter-panel tr.rowlink')][0]?.click(); return 1`); await sleep(600);
const h = await b.js(`const p=document.querySelector('blotter-panel'); return {cancel:p.querySelectorAll('button.cancel').length, settle:[...p.querySelectorAll('button')].filter(x=>/force settle|replace|settle/i.test(x.textContent)).length}`);
b.check(h.cancel === 0 && h.settle === 0, `FR-MUI02: no cancel/replace/settle on historical rows (${JSON.stringify(h)})`);
await same(['/v2/projections/run_a/accounts/22214/positions', '/v2/projections/run_a/accounts/22214/trades'], 'FR-MUI03: history subscribes to its own scope');
await b.shot('05-history-run_a', 'blotter-panel');

// 7. Account switch inside history.
await pick('.acct select', '(11413)');
t = await until(x => x.includes('GOOG') && x.includes('e1-run_a-11413-B'), 'history run_a acct 11413');
b.check(!t.includes('22214-B') && !t.includes('NVDA'), 'FR-MUI04: account switch in history shows only 11413 run_a rows');
await same(['/v2/projections/run_a/accounts/11413/positions', '/v2/projections/run_a/accounts/11413/trades'], 'FR-MUI03: subjects follow the account');

// 8. Legacy-unknown as a sealed run keeps the legacy subjects.
await pick('[data-testid=run-select]', 'legacy-unknown');
await until(x => x.includes('HISTORICAL · READ-ONLY — run legacy-unknown'), 'history legacy');
await same(['/accounts/11413/positions', '/accounts/11413/trades'], 'FR-MUI03: legacy-unknown history uses legacy subjects');

// 9. A named view of the ACTIVE run is labelled and still read-only.
await pick('[data-testid=run-select]', 'run_b');
await until(x => x.includes('NAMED RUN (registry phase ACTIVE) · READ-ONLY — run run_b'), 'named active');
b.check(await cancels() === 0, 'FR-MUI02: a named view of the active run is read-only too');
await pick('[data-testid=run-select]', '');
await until(x => x.includes('Active run: run_b') && x.includes('e1-run_b-11413-B'), 'back to active');

if (E.DESTRUCTIVE === '1') {
  // 10. Database gone: no rows, the failure named, no fallback (FR-MUI06/08).
  kube(['scale', 'deploy/eod-price-db', '--replicas=0']);
  try {
    t = await until(x => x.includes('read failed'), 'refusal shown', 90000);
    b.check(!t.includes('NVDA') && !t.includes('e1-run_b-') && !t.includes('e1-run_a-'), 'FR-MUI06: a failed managed read shows no rows and falls back to nothing');
    await same([], 'FR-MUI08: no subscription while reads fail');
    await b.shot('10-database-gone', 'blotter-panel');
  } finally { kube(['scale', 'deploy/eod-price-db', '--replicas=1']); kube(['rollout', 'status', 'deploy/eod-price-db', '--timeout=300s']); }
  await until(x => x.includes('e1-run_b-11413-B'), 'recovers after the database returns', 240000);
  b.check(true, 'FR-MUI06: the view recovers on the next read once the backend answers');

  // 11. A HUNG read: the per-poll deadline fails the view closed (FR-MUI09).
  b.check(await cancels() === 1, 'actions available before the hang (so their absence below is the deadline)');
  const hold = spawn('kubectl', [...K, ...db], { stdio: ['pipe', 'ignore', 'ignore'] });
  hold.stdin.write('LOCK TABLES projection_active WRITE, projection_runs WRITE; SELECT SLEEP(40); UNLOCK TABLES;\n'); hold.stdin.end();
  b.onExit(() => hold.kill());
  try {
    t = await until(x => x.includes('timed out after'), 'deadline fires on a hung read', 30000);
    b.check(!t.includes('e1-run_b-') && t.includes('read failed'), 'FR-MUI09: a hung read shows no rows');
    b.check(await cancels() === 0, 'FR-MUI09: a hung read leaves no actions');
    await same([], 'FR-MUI09: a hung read holds no subscription');
    await b.shot('11-hung-read', 'blotter-panel');
  } finally { hold.kill(); }
  await until(x => x.includes('e1-run_b-11413-B'), 'recovers after the lock is released', 90000);
  b.check(true, 'FR-MUI09: the view recovers once a read completes inside the deadline');
}
b.done();
