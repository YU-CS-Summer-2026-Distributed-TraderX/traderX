// PROOF DRIVER — REAL RI-06 transition, disposable local rig only. Mirrors the setup of
// RunMigrationLiveIT (two single-member Aeron runs + gateways, real trade-processor controller,
// position/account services, NATS, MariaDB) and drives the five durable phases DIRECTLY against the
// controller as the operator would — never through the console, whose proxies refuse these routes.
// At each phase it checks and screenshots what the shipped console shows. Trades are real orders
// crossed on the real gateways, not SQL fixtures. Needs: SQL container with the generated ConfigMap
// schema loaded, NATS (ws 8080, monitor 8222), built jars, matcher classpath file.
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const need = k => process.env[k] ?? (() => { throw new Error(`set ${k}`); })();
const GEN = need('GEN');                         // …/code/target-generated
const CP = fs.readFileSync(need('MATCHER_CLASSPATH_FILE'), 'utf8').trim();
const JAVA = need('JAVA');                        // Java 21 binary
const OUT = need('OUT');
const HERE = path.dirname(new URL(import.meta.url).pathname);
const CONSOLE_ROOT = path.resolve(HERE, '../../../../../web-front-end-console');
const TOKEN = 'mui-proof-token';
const NATS = 'nats://127.0.0.1:26822';
const DB = { DATABASE_PG_PORT: '26806' };
const CONTROLLER = 'http://127.0.0.1:26891';
const OLD = { name: 'old', base: 26830, health: 26840, gw: 26841, epoch: 'oldlive' };
const FRESH = { name: 'fresh', base: 26810, health: 26845, gw: 26846, epoch: 'freshlive' };
const gwUrl = r => `http://127.0.0.1:${r.gw}`;
fs.mkdirSync(OUT, { recursive: true });
const log = [];
const children = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const done = code => { fs.writeFileSync(path.join(OUT, 'log.json'), JSON.stringify(log, null, 1)); for (const c of children.reverse()) { try { c.kill(); } catch {} } process.exit(code); };
const fail = m => { log.push({ FAIL: m }); console.error('FAIL', m); done(1); };
const check = (ok, m) => { log.push({ check: m, ok }); console.log(ok ? 'ok  ' : 'FAIL', m); if (!ok) fail(m); };
const sha = s => createHash('sha256').update(s).digest('hex');
const sql = q => execFileSync('docker', ['exec', 'traderx-mui-sql', 'mariadb', '-N', '-utraderx', '-ptraderx', 'traderx', '-e', q]).toString().trim();
const until = async (pred, what, ms = 40000) => { const end = Date.now() + ms; while (Date.now() < end) { try { if (await pred()) return; } catch {} await sleep(200); } fail('timeout: ' + what); };
const ok200 = async u => (await fetch(u, { signal: AbortSignal.timeout(1000) })).status === 200;

function launch(name, args, env, cwd) {
  const out = fs.openSync(path.join(OUT, name + '.log'), 'w');
  const c = spawn(args[0], args.slice(1), { env: { PATH: process.env.PATH, ...env }, stdio: ['ignore', out, out], cwd });
  children.push(c);
  c.on('exit', code => { if (!quitting) log.push({ exited: name, code }); });
  return c;
}
let quitting = false;
// Chrome forks helpers that outlive a parent kill; sweep everything using this run's profile.
const sweepChrome = () => { try { execFileSync('pkill', ['-f', `user-data-dir=${OUT}/chrome-profile`]); } catch { /* none */ } };
process.on('exit', () => { quitting = true; for (const c of children) { try { c.kill(); } catch {} } sweepChrome(); });
const jar = s => fs.readdirSync(path.join(GEN, s, 'build/libs')).map(f => path.join(GEN, s, 'build/libs', f)).find(f => f.endsWith('.jar') && !f.endsWith('-plain.jar'));
const javaMain = (name, main, env) => launch(name, [JAVA, '-Xms64m', '-Xmx384m', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
  '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED', '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED', '-cp', CP, main], env);

// Initializer demo rows are in no archive; verify would refuse them (see the fixture file).
execFileSync('docker', ['exec', '-i', 'traderx-mui-sql', 'mariadb', '-utraderx', '-ptraderx', 'traderx'],
  { input: fs.readFileSync(path.join(HERE, 'fixture-empty-legacy-history.sql')) });
log.push({ fixture: 'fixture-empty-legacy-history.sql applied before start' });

// ---- services, edge, console ----
launch('trade-processor', [JAVA, '-Xmx384m', '-jar', jar('trade-processor')], { ...DB, NATS_ADDRESS: NATS, TRADE_PROCESSOR_SERVICE_PORT: '26891',
  RISK_CONTROL_TOKEN: TOKEN, ORDER_MATCHER_BASE_URL: 'http://127.0.0.1:1', REFERENCE_DATA_SERVICE_URL: 'http://127.0.0.1:1', RECON_POLL_INTERVAL_MS: '3600000', SERVER_ERROR_INCLUDE_MESSAGE: 'always' });
launch('position-service', [JAVA, '-Xmx384m', '-jar', jar('position-service')], { ...DB, NATS_ADDRESS: NATS, POSITION_SERVICE_PORT: '26890' });
launch('account-service', [JAVA, '-Xmx256m', '-jar', jar('account-service')], { ...DB, ACCOUNT_SERVICE_PORT: '26892' });
launch('edge', [process.execPath, path.join(HERE, 'fixture-edge.mjs')], {});
launch('console', [process.execPath, path.join(CONSOLE_ROOT, 'server.mjs')], { PORT: '26800', EDGE_PROXY: '127.0.0.1:26870',
  STATIC_ROOT: path.join(CONSOLE_ROOT, 'dist/web-front-end-console/browser') });
await until(() => ok200(`${CONTROLLER}/accounts/1/orders`), 'trade-processor up', 120000);
await until(() => ok200('http://127.0.0.1:26800/position-service/v2/projections/active'), 'console → position-service up', 120000);

// ---- the two runs (setup mirrors RunMigrationLiveIT) ----
const EVIDENCE = 'synthetic archived legacy source evidence';
const descriptor = (epoch, scope, legacy) => JSON.stringify({ schema: 'traderx.run.v1', epoch, eventIdScheme: legacy ? 'legacy-v0' : 'epoch-v1',
  storageLineage: scope + '-storage', projectionScope: scope, adoptionEvidenceSha256: legacy ? sha(EVIDENCE) : null });
const LEGACY_D = descriptor(OLD.epoch, 'legacy-unknown', true);
const FRESH_D = descriptor(FRESH.epoch, 'fresh-live', false);
async function startRun(r, raw) {
  const storage = path.join(OUT, r.name + '-storage'); fs.mkdirSync(storage, { recursive: true });
  const identity = path.join(storage, 'run-identity.json'); fs.writeFileSync(identity, raw);
  if (r.name === 'old') fs.writeFileSync(path.join(storage, 'run-adoption-evidence.json'), EVIDENCE);
  const common = { RUN_DESCRIPTOR_PATH: identity, CLUSTER_EPOCH: r.epoch, RISK_CONTROL_TOKEN: TOKEN };
  javaMain(r.name + '-node', 'finos.traderx.ordermatcher.cluster.ClusterNodeMain', { ...common, CLUSTER_MEMBER_ID: '0', CLUSTER_HOSTNAMES: 'localhost',
    CLUSTER_PORT_BASE: String(r.base), CLUSTER_BASE_DIR: storage, CLUSTER_AERON_DIR: path.join(OUT, r.name + '-aeron'), HEALTH_PORT: String(r.health),
    CLUSTER_IDLE_SLEEP_MS: '1', RECON_BLOTTER_CAPACITY: '1000', RECON_FULL_HISTORY_MAX: '1000', REGULATORY_MAX_RECORDS: '1000', TRADE_BRIDGE_NATS_URL: NATS });
  await until(() => ok200(`http://127.0.0.1:${r.health}/health`), r.name + ' node health', 60000);
  javaMain(r.name + '-gateway', 'finos.traderx.ordermatcher.cluster.ClusterGatewayMain', { ...common, GATEWAY_INGRESS_ENDPOINTS: `0=localhost:${r.base + 2}`,
    GATEWAY_HTTP_PORT: String(r.gw), GATEWAY_PROBE_PORT: String(r.gw + 1), GATEWAY_MEMBER_HEALTH_PORT: String(r.health),
    GATEWAY_AERON_DIR: path.join(OUT, r.name + '-gateway-aeron'), RUN_PROJECTION_CONTROL_URL: CONTROLLER });
  await until(() => ok200(`${gwUrl(r)}/run/status`), r.name + ' gateway /run/status', 60000);
}
const post = async (base, p, body) => {
  const r = await fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Risk-Control-Token': TOKEN, 'X-Risk-Operator': 'mui-proof' },
    body: JSON.stringify(body), signal: AbortSignal.timeout(60000) });
  const text = await r.text(); if (r.status !== 200) fail(`${p} → ${r.status} ${text}`); return JSON.parse(text);
};
const BUY = 22214, SELL = 11413;
const seed = async r => { for (const a of [BUY, SELL]) await post(gwUrl(r), '/seed', { accountId: a, tickers: 'RESERVED,IBM', price: 100 }); };
const cross = async (r, q, key) => {
  await post(gwUrl(r), '/orders', { accountId: BUY, security: 'IBM', side: 'Buy', quantity: q, limitPrice: 100, clientOrderId: key + 'buy' });
  await post(gwUrl(r), '/orders', { accountId: SELL, security: 'IBM', side: 'Sell', quantity: q, limitPrice: 100, clientOrderId: key + 'sell' });
};

await startRun(OLD, LEGACY_D); await startRun(FRESH, FRESH_D);
log.push({ runs: { old: OLD, fresh: FRESH, legacyDescriptorSha256: sha(LEGACY_D), freshDescriptorSha256: sha(FRESH_D) } });

// ---- console in headless Chrome ----
const chrome = launch('chrome', [process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '--headless=new',
  '--remote-debugging-port=26850', `--user-data-dir=${OUT}/chrome-profile`, '--no-first-run', '--window-size=1280,1000', 'about:blank'], {});
let targets; for (let i = 0; i < 50 && !targets; i++) { try { targets = await (await fetch('http://127.0.0.1:26850/json')).json(); } catch { await sleep(200); } }
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let id = 0; const waiting = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); waiting.get(m.id)?.(m); waiting.delete(m.id); });
const cdp = (method, params = {}) => new Promise(r => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const js = async expr => { const r = await cdp('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) fail('js: ' + JSON.stringify(r.result.exceptionDetails)); return r.result?.result?.value; };
const subs = async () => [...new Set(((await (await fetch('http://127.0.0.1:26823/subsz?subs=1')).json()).subscriptions_list ?? [])
  .map(s => s.subject).filter(s => s.includes('/accounts/')))].sort();
const shot = async (name, sel = 'blotter-panel') => {
  const rect = await js(`const r=document.querySelector('${sel}').closest('.card')?.getBoundingClientRect() ?? document.querySelector('${sel}').getBoundingClientRect(); return {x:r.x,y:r.y+scrollY,w:r.width,h:r.height};`);
  const r = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: rect.x, y: rect.y, width: rect.w, height: Math.min(rect.h, 1400), scale: 1 } });
  fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(r.result.data, 'base64'));
  log.push({ screenshot: name + '.png', subs: await subs(), registry: sql('SELECT projection_scope,phase FROM projection_runs ORDER BY 1'), pointer: sql('SELECT projection_scope FROM projection_active') });
};
const text = () => js(`return document.querySelector('blotter-panel').innerText`);
const see = async (pred, what, ms = 20000) => { let t; await until(async () => pred(t = await text()), what + '\n' + t, ms); return t; };
const pick = (sel, match) => js(`const s=document.querySelector('blotter-panel ${sel}'); const o=[...s.options].find(o=>o.text.includes(${JSON.stringify(match)})||o.value===${JSON.stringify(match)}); if(!o) throw new Error('no option ${match}'); s.value=o.value; s.dispatchEvent(new Event('change')); return o.text;`);
// A trade ROW whose id cell is exactly this id (ids can be substrings of each other: 1-B vs e1-freshlive-1-B).
const hasRow = (t, rowId) => t.split('\n').some(l => l.split(/[\t ]/)[0] === rowId);
const cancels = () => js(`return document.querySelectorAll('blotter-panel button.cancel').length`);
const phase = t => sql(`SELECT phase FROM projection_transitions WHERE transition_id='${t}'`);

await cdp('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1000, deviceScaleFactor: 1, mobile: false });
await cdp('Page.enable'); await cdp('Page.navigate', { url: 'http://localhost:26800/' }); await sleep(2500);
await pick('.acct select', `(${BUY})`);

// 1. Old (legacy) run live: a real crossed order books into legacy-unknown and shows as active.
await seed(OLD); await cross(OLD, 10, 'old-');
await until(() => sql(`SELECT count(*) FROM trades WHERE projectionscope='legacy-unknown' AND clusterepoch IS NULL AND accountid=${BUY} AND security='IBM' AND quantity=10`) === '1'
  || sql(`SELECT count(*) FROM trades WHERE accountid=${BUY} AND security='IBM' AND quantity=10`) === '1', 'old trade booked');
const oldTradeId = sql(`SELECT id FROM trades WHERE accountid=${BUY} AND security='IBM' AND quantity=10`);
let t = await see(x => x.includes('Active run: legacy-unknown') && hasRow(x, oldTradeId), 'old trade visible in active legacy view');
check(t.includes('selected pointer'), `old run: real trade ${oldTradeId} shown in the active legacy-unknown view`);
await sleep(800);
check(JSON.stringify(await subs()) === JSON.stringify([`/accounts/${BUY}/positions`, `/accounts/${BUY}/trades`]), 'legacy subjects while legacy-unknown is selected');
await shot('r1-old-run-active');

// 2. adopt-legacy + prepare: fresh run PREPARED; the pointer still names legacy-unknown.
await post(CONTROLLER, '/v2/projection-control/adopt-legacy', { descriptorJson: LEGACY_D, oldEndpoint: gwUrl(OLD) });
const req = { transitionId: 'mui-transition', oldScope: 'legacy-unknown', descriptorJson: FRESH_D, oldEndpoint: gwUrl(OLD), newEndpoint: gwUrl(FRESH) };
check((await post(CONTROLLER, '/v2/projection-control/prepare', req)).phase === 'PREPARED', 'controller: PREPARED');
await seed(FRESH);
t = await see(x => x.includes('fresh-live (PREPARED'), 'fresh run listed');
check(t.includes('Active run: legacy-unknown') && hasRow(t, oldTradeId), 'PREPARED: still legacy-unknown, rows kept');
await shot('r2-prepared');

// 3. freeze: old scope DRAINING; still selected.
check((await post(CONTROLLER, '/v2/projection-control/freeze', req)).phase === 'FROZEN', 'controller: FROZEN');
t = await see(x => x.includes('legacy-unknown (DRAINING'), 'draining listed');
check(t.includes('Active run: legacy-unknown') && hasRow(t, oldTradeId) && (await cancels()) === 0, 'FROZEN: pointer still legacy-unknown, old rows shown');
await shot('r3-frozen');

// 4. verify: old scope SEALED; still selected until select.
check((await post(CONTROLLER, '/v2/projection-control/verify', req)).phase === 'VERIFIED', 'controller: VERIFIED');
t = await see(x => x.includes('legacy-unknown (SEALED'), 'sealed listed');
check(t.includes('Active run: legacy-unknown'), 'VERIFIED: pointer still legacy-unknown');
await shot('r4-verified');

// 5. select: the pointer moves to fresh-live under the open page.
check((await post(CONTROLLER, '/v2/projection-control/select', req)).phase === 'SELECTED', 'controller: SELECTED');
t = await see(x => x.includes('Active run changed: legacy-unknown → fresh-live') && x.includes('Active run: fresh-live'), 'change announced');
check(!hasRow(t, oldTradeId), 'SELECTED: no legacy rows in the fresh active view');
await sleep(800);
check(JSON.stringify(await subs()) === JSON.stringify([`/v2/projections/fresh-live/accounts/${BUY}/positions`, `/v2/projections/fresh-live/accounts/${BUY}/trades`]), 'managed fresh-live subjects');
await shot('r5-selected');

// 6. activate, then a real trade on the fresh run appears in the active view.
check((await post(CONTROLLER, '/v2/projection-control/activate', req)).phase === 'COMPLETE', 'controller: COMPLETE');
await cross(FRESH, 20, 'fresh-');
await until(() => sql(`SELECT count(*) FROM trades WHERE projectionscope='fresh-live' AND accountid=${BUY}`) === '1', 'fresh trade booked');
const freshTradeId = sql(`SELECT id FROM trades WHERE projectionscope='fresh-live' AND accountid=${BUY}`);
t = await see(x => hasRow(x, freshTradeId), 'fresh trade visible');
check(freshTradeId.startsWith('e1-freshlive-') && !hasRow(t, oldTradeId), `COMPLETE: real fresh trade ${freshTradeId} shown, old trade not`);
check(phase('mui-transition') === 'COMPLETE', 'SQL transition phase COMPLETE');
await shot('r6-complete-fresh-trade');

// 7. The sealed legacy run, browsed as history: its real trade, read-only.
await pick('[data-testid=run-select]', 'legacy-unknown');
t = await see(x => x.includes('HISTORICAL · READ-ONLY — run legacy-unknown') && hasRow(x, oldTradeId), 'history legacy');
check(!hasRow(t, freshTradeId) && (await cancels()) === 0, 'sealed legacy run as history: old trade only, read-only');
await sleep(800);
check(JSON.stringify(await subs()) === JSON.stringify([`/accounts/${BUY}/positions`, `/accounts/${BUY}/trades`]), 'history of legacy-unknown uses legacy subjects');
await shot('r7-history-sealed-legacy');

// 7b. Admin trade list: same authoritative pointer, fresh run's trade only, force-settle available.
await cdp('Page.navigate', { url: 'http://localhost:26800/admin' }); await sleep(2500);
let a = '';
await until(async () => (a = await js(`return document.querySelector('admin-panel')?.innerText ?? ''`)).includes('Active run: fresh-live (selected pointer)') && hasRow(a, freshTradeId), 'admin run line\n' + a);
check(!hasRow(a, oldTradeId), 'Admin trade list: fresh-live via pointer, real fresh trade only');
await shot('r7b-admin-trade-list', 'admin-panel');

// 8. Console never exposes the operator routes it just watched being used.
for (const p of ['/trade-processor/v2/projection-control/select', '/legacy/trade-processor/v2/projection-control/activate', '/order-matcher/run/control']) {
  const r = await fetch('http://127.0.0.1:26800' + p, { method: 'POST', headers: { 'X-Risk-Control-Token': TOKEN, 'X-Risk-Operator': 'x', 'Content-Type': 'application/json' }, body: JSON.stringify(req) });
  check(r.status === 403, `console refuses ${p}`);
}
check(phase('mui-transition') === 'COMPLETE', 'transition state unchanged by the refused console requests');
console.log('ALL CHECKS PASSED'); done(0);
