// PROOF DRIVER — disposable local fixture rig only (see ../README.md for the lease/layout).
// Drives the SHIPPED console (server.mjs + production build) in headless Chrome against the real
// generated position-service / trade-processor / account-service on a disposable MariaDB, with a
// real NATS. Registry phases and rows are SQL fixtures (labelled); the console itself never changes
// the active scope. Writes PNGs + a JSON log to OUT and exits non-zero on the first failed check.
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const OUT = process.env.OUT ?? '/private/tmp/traderx-mui-proof/evidence';
const CONSOLE = process.env.CONSOLE_URL ?? 'http://localhost:26800/';
const SQL = process.env.SQL_CONTAINER ?? 'traderx-mui-sql';
const NATS_MON = process.env.NATS_MON ?? 'http://127.0.0.1:26823';
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
fs.mkdirSync(OUT, { recursive: true });
const log = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const fail = m => { log.push({ FAIL: m }); fs.writeFileSync(path.join(OUT, 'log.json'), JSON.stringify(log, null, 1)); console.error('FAIL', m); process.exit(1); };
const check = (ok, m) => { log.push({ check: m, ok }); console.log(ok ? 'ok  ' : 'FAIL', m); if (!ok) fail(m); };
const sql = f => execFileSync('docker', ['exec', '-i', SQL, 'mariadb', '-utraderx', '-ptraderx', 'traderx'], { input: fs.readFileSync(path.join(HERE, f)) });
const subs = async () => [...new Set(((await (await fetch(`${NATS_MON}/subsz?subs=1`)).json()).subscriptions_list ?? [])
  .map(s => s.subject).filter(s => s.includes('/accounts/')))].sort();
const publish = subject => new Promise(res => {
  const s = net.connect(26822, '127.0.0.1', () => s.write(`CONNECT {"verbose":false}\r\nPUB ${subject} 2\r\n{}\r\nPING\r\n`));
  s.on('data', d => { if (String(d).includes('PONG')) { s.end(); res(Date.now()); } });
});

// ---- minimal CDP ----
const chrome = spawn(CHROME, ['--headless=new', '--remote-debugging-port=26850', `--user-data-dir=${OUT}/chrome-profile`,
  '--no-first-run', '--window-size=1280,1000', 'about:blank'], { stdio: 'ignore' });
process.on('exit', () => { chrome.kill(); for (const c of ['unpause', 'start']) { try { execFileSync('docker', [c, SQL], { stdio: 'ignore' }); } catch { /* not needed */ } } });
let targets; for (let i = 0; i < 50 && !targets; i++) { try { targets = await (await fetch('http://127.0.0.1:26850/json')).json(); } catch { await sleep(200); } }
const page = targets.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let id = 0; const waiting = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); waiting.get(m.id)?.(m); waiting.delete(m.id); });
const cdp = (method, params = {}) => new Promise(r => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const js = async expr => { const r = await cdp('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) fail('js: ' + JSON.stringify(r.result.exceptionDetails)); return r.result?.result?.value; };
const shot = async name => {
  const rect = await js(`const r=document.querySelector('blotter-panel').closest('.card')?.getBoundingClientRect() ?? document.querySelector('blotter-panel').getBoundingClientRect(); return {x:r.x,y:r.y+scrollY,w:r.width,h:r.height};`);
  const r = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: rect.x, y: rect.y, width: rect.w, height: Math.min(rect.h, 1400), scale: 1 } });
  fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(r.result.data, 'base64'));
  log.push({ screenshot: name + '.png', subs: await subs() });
};
const text = () => js(`return document.querySelector('blotter-panel').innerText`);
const pick = (sel, match) => js(`const s=document.querySelector('blotter-panel ${sel}'); const o=[...s.options].find(o=>o.text.includes(${JSON.stringify(match)})||o.value===${JSON.stringify(match)}); if(!o) throw new Error('no option ${match}'); s.value=o.value; s.dispatchEvent(new Event('change')); return o.text;`);
const until = async (pred, what, ms = 8000) => { const end = Date.now() + ms; let t; while (Date.now() < end) { t = await text(); if (pred(t)) return t; await sleep(250); } fail(`timeout: ${what}\n${t}`); };

await cdp('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false });
await cdp('Page.enable'); await cdp('Page.navigate', { url: CONSOLE }); await sleep(2500);

// 1. Fresh install: legacy-unknown only (unmanaged-compatible subjects).
await pick('.acct select', '(22214)');
let t = await until(x => x.includes('Active run: legacy-unknown'), 'legacy active');
check(t.includes('selected pointer, GET /v2/projections/active') && t.includes('TRADE-22214-AABBCC'), 'legacy rows shown, active scope from the server pointer');
await sleep(1500);
check(JSON.stringify(await subs()) === JSON.stringify(['/accounts/22214/positions', '/accounts/22214/trades']), 'legacy subjects subscribed');
await shot('01-legacy-active');

// 2a. Frozen window: the pointer still names legacy-unknown, so the view stays confirmed on it.
sql('fixture-freeze-legacy.sql');
await pick('.acct select', '(10031)');
t = await until(x => x.includes('Active run: legacy-unknown') && x.includes('no positions'), 'legacy during freeze');
check(t.includes('selected pointer'), 'during the frozen window the active scope still comes from the pointer');
await shot('02a-frozen-window-pointer-legacy');

// 2b. Dangling pointer (503): nothing shown, nothing inferred, nothing subscribed, no actions.
await pick('.acct select', '(22214)');
await until(x => x.includes('TRADE-22214-AABBCC'), 'legacy rows back');
sql('fixture-dangling-pointer.sql');
t = await until(x => x.includes('active run: HTTP 503'), 'pointer 503 shown');
check(t.includes('not confirmed') && !t.includes('TRADE-22214-AABBCC') && t.includes('polling'), 'pointer 503: no rows, no inference, polling');
check(await js(`return document.querySelectorAll('blotter-panel button.cancel').length`) === 0, 'no actions while the pointer is unavailable');
await sleep(800);
check((await subs()).length === 0, 'no subscription while the pointer is unavailable');
await shot('02b-dangling-pointer-503');
sql('fixture-restore-legacy-pointer.sql');

// 3. Selection to run_a underneath the open page.
await until(x => x.includes('Active run: legacy-unknown') && x.includes('TRADE-22214-AABBCC'), 'legacy again');
sql('fixture-select-run-a.sql'); sql('fixture-rows-run-a.sql');
t = await until(x => x.includes('Active run changed: legacy-unknown → run_a') && x.includes('e1-run_a-22214-B'), 'change notice + run_a rows');
check(!t.includes('TRADE-22214-AABBCC') && !t.includes('GOOG'), 'no legacy rows and no other-account rows after the switch');
await sleep(1000);
check(JSON.stringify(await subs()) === JSON.stringify(['/v2/projections/run_a/accounts/22214/positions', '/v2/projections/run_a/accounts/22214/trades']), 'managed run_a subjects only');
await shot('03-active-changed-run_a');

// 4. Notifications: only the current scope+account subject triggers an off-timer read.
await js(`window.__reads=[]; new PerformanceObserver(l=>{for(const e of l.getEntries()) if(e.name.includes('/position-service/v2/projections/run_a/accounts/22214/trades')) window.__reads.push(Math.round(performance.timeOrigin+e.startTime));}).observe({type:'resource'}); return 1`);
await sleep(3200);
const pubs = {};
for (const s of ['/accounts/22214/trades', '/v2/projections/run_b/accounts/22214/trades', '/v2/projections/run_a/accounts/11413/trades', '/v2/projections/run_a/accounts/22214/trades']) {
  pubs[s] = await publish(s); await sleep(1100);
}
const reads = await js(`return window.__reads`);
const near = t0 => reads.some(r => r >= t0 && r - t0 < 250);
log.push({ publishes: pubs, reads });
check(near(pubs['/v2/projections/run_a/accounts/22214/trades']), 'current-scope event triggers a read within 250ms');
check(!near(pubs['/accounts/22214/trades']) && !near(pubs['/v2/projections/run_b/accounts/22214/trades'])
  && !near(pubs['/v2/projections/run_a/accounts/11413/trades']), 'legacy / other-run / other-account events trigger no read');

// 5. Selection to run_b; active view offers actions.
sql('fixture-select-run-b.sql'); sql('fixture-rows-run-b.sql');
t = await until(x => x.includes('Active run changed: run_a → run_b') && x.includes('e1-run_b-22214-B'), 'run_b');
check(!t.includes('e1-run_a-'), 'no run_a rows in the run_b active view');
check(await js(`return document.querySelectorAll('blotter-panel button.cancel').length`) === 1, 'active view offers cancel on its open order');
await shot('04-active-run_b');

// 6. Named historical run: read-only, scoped rows, scoped subjects.
await pick('[data-testid=run-select]', 'run_a');
t = await until(x => x.includes('HISTORICAL · READ-ONLY — run run_a') && x.includes('e1-run_a-22214-B'), 'history run_a');
check(!t.includes('e1-run_b-') && !t.includes('MSFT'), 'history view holds only run_a rows');
await js(`[...document.querySelectorAll('blotter-panel tr.rowlink')][0].click(); return 1`); await sleep(500);
const h = await js(`const b=document.querySelector('blotter-panel'); return {cancel:b.querySelectorAll('button.cancel').length, settle:[...b.querySelectorAll('button')].filter(x=>x.textContent.includes('Force settle')).length}`);
check(h.cancel === 0 && h.settle === 0, 'no cancel / force-settle on historical rows');
await sleep(800);
check(JSON.stringify(await subs()) === JSON.stringify(['/v2/projections/run_a/accounts/22214/positions', '/v2/projections/run_a/accounts/22214/trades']), 'history subscribes to its own scope');
await shot('05-history-run_a');

// 7. Account switch inside history.
await pick('.acct select', '(11413)');
t = await until(x => x.includes('GOOG') && x.includes('e1-run_a-11413-B'), 'history run_a acct 11413');
check(!t.includes('22214-B') && !t.includes('NVDA'), 'account switch in history: only 11413 run_a rows');
await sleep(800);
check(JSON.stringify(await subs()) === JSON.stringify(['/v2/projections/run_a/accounts/11413/positions', '/v2/projections/run_a/accounts/11413/trades']), 'subjects follow the account');
await shot('06-history-run_a-account-11413');

// 8. Legacy-unknown as a sealed historical run keeps the legacy subjects.
await pick('[data-testid=run-select]', 'legacy-unknown');
t = await until(x => x.includes('HISTORICAL · READ-ONLY — run legacy-unknown'), 'history legacy');
await sleep(800);
check(JSON.stringify(await subs()) === JSON.stringify(['/accounts/11413/positions', '/accounts/11413/trades']), 'legacy-unknown history uses legacy subjects');
await shot('07-history-legacy-unknown');

// 9. Named run view of the ACTIVE run is labelled as such, still read-only.
await pick('[data-testid=run-select]', 'run_b');
t = await until(x => x.includes('NAMED RUN (registry phase ACTIVE) · READ-ONLY — run run_b'), 'named active');
check(await js(`return document.querySelectorAll('blotter-panel button.cancel').length`) === 0, 'named view of active run is read-only too');
await shot('08-named-active-run_b');

// 10. Registry/readers failing (database stopped): the active view shows no rows, says why, and
// does not fall back to the unversioned reads or another run.
await pick('[data-testid=run-select]', '');
await until(x => x.includes('Active run: run_b'), 'back to active');
execFileSync('docker', ['stop', SQL]);
try {
  t = await until(x => x.includes('read failed') && (x.includes('run registry: HTTP') || x.includes('timed out after')), 'refusal shown', 45000);
  log.push({ step10Reason: t.includes('timed out after') ? 'deadline (backend slower than deadline to fail)' : 'HTTP failure' });
  check(!t.includes('NVDA') && !t.includes('e1-run_b-') && t.includes('read failed'), 'failed managed read shows no rows and no fallback');
  await sleep(600);
  check((await subs()).length === 0, 'no subscription while reads fail');
  await shot('09-registry-unavailable');
} finally { execFileSync('docker', ['start', SQL]); }
t = await until(x => x.includes('e1-run_b-11413-B'), 'recovers after restart', 60000);
check(true, 'view recovers on the next read once the backend answers');

// 11. A HUNG read (database paused: connections wait, nothing fails): the per-poll deadline fails
// the view closed; rows and actions go away and come back only after a confirmed read.
check(await js(`return document.querySelectorAll('blotter-panel button.cancel').length`) === 1, 'actions available before the hang');
execFileSync('docker', ['pause', SQL]);
try {
  t = await until(x => x.includes('timed out after'), 'deadline fires on a hung read', 20000);
  check(!t.includes('e1-run_b-') && t.includes('read failed'), 'hung read: no rows shown');
  check(await js(`return document.querySelectorAll('blotter-panel button.cancel').length`) === 0, 'hung read: no actions');
  await sleep(600);
  check((await subs()).length === 0, 'hung read: no subscription');
  await shot('10-hung-read-timed-out');
} finally { execFileSync('docker', ['unpause', SQL]); }
await until(x => x.includes('e1-run_b-11413-B'), 'recovers after unpause', 60000);
check(true, 'view recovers once a read completes inside the deadline');

fs.writeFileSync(path.join(OUT, 'log.json'), JSON.stringify(log, null, 1));
console.log('ALL CHECKS PASSED'); process.exit(0);
