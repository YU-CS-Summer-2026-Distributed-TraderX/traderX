// COMBINED ACCEPTANCE — new disposable fixture DB only, real generated services.
// Extends real-transition-proof.mjs: nonempty legacy bootstrap -> managed run -> actual
// consumer outage/archive recovery -> second managed run, with both production UI pages open.
// Single-member Aeron runs; no HA claim. Setup uses generated 900-migrations.sql on a NEW DB,
// then ri06.sql and ri06-event-recovery.sql. It never executes demo initializer/deletes history.
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';

const need = k => process.env[k] ?? (() => { throw new Error(`set ${k}`); })();
const GEN = need('GEN');                         // …/code/target-generated
const CP = fs.readFileSync(need('MATCHER_CLASSPATH_FILE'), 'utf8').trim();
const JAVA = need('JAVA');                        // Java 21 binary
const OUT = need('OUT');
const HERE = path.dirname(new URL(import.meta.url).pathname);
const CONSOLE_ROOT = path.resolve(HERE, '../../../../../web-front-end-console');
const TOKEN = 'mui-proof-token';
const NATS = 'nats://127.0.0.1:27822';
const DB = { DATABASE_PG_PORT: '27806' };
const CONTROLLER = 'http://127.0.0.1:27891';
const OLD = { name: 'old', base: 27830, health: 27840, gw: 27841, epoch: 'oldlive' };
const NEXT = { name: 'next', base: 27910, health: 27945, gw: 27946, epoch: 'nextlive' };
const FRESH = { name: 'fresh', base: 27810, health: 27845, gw: 27846, epoch: 'freshlive' };
const gwUrl = r => `http://127.0.0.1:${r.gw}`;
fs.mkdirSync(OUT, { recursive: true });
const log = [];
const fatal=e=>{console.error(e);log.push({fatal:String(e?.stack??e)});void done(1);};
process.on('uncaughtException', fatal);process.on('unhandledRejection', fatal);
const children = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));
let finishing=false;
const done = async code => {
 if(finishing)return;finishing=true;quitting=true;
 for(const c of [...children].reverse()) {try{c.kill();}catch{}}
 await Promise.all(children.map(c=>new Promise(resolve=>{
  if(c.exitCode!==null||c.signalCode!==null)return resolve();
  const force=setTimeout(()=>{log.push({cleanupForcedPid:c.pid});c.kill('SIGKILL');},8000);
  c.once('exit',()=>{clearTimeout(force);resolve();});
 })));
 sweepChrome();fs.writeFileSync(path.join(OUT,'log.json'),JSON.stringify(log,null,1));process.exit(code);
};
const fail = m => { log.push({ FAIL: m }); console.error('FAIL', m); throw new Error(m); };
const check = (ok, m) => { log.push({ check: m, ok }); console.log(ok ? 'ok  ' : 'FAIL', m); if (!ok) fail(m); };
const sha = s => createHash('sha256').update(s).digest('hex');
const sql = q => execFileSync('docker', ['exec', 'traderx-ma-20260925-sql', 'mariadb', '-N', '-utraderx', '-ptraderx', 'traderx', '-e', q]).toString().trim();
const until = async (pred, what, ms = 40000) => { const end = Date.now() + ms; while (Date.now() < end) { try { if (await pred()) return; } catch {} await sleep(200); } fail('timeout: ' + what); };
const ok200 = async u => (await fetch(u, { signal: AbortSignal.timeout(1000) })).status === 200;

function launch(name, args, env, cwd) {
  const out = fs.openSync(path.join(OUT, name + '.log'), 'w');
  const c = spawn(args[0], args.slice(1), { env: { PATH: process.env.PATH, ...env }, stdio: ['ignore', out, out], cwd });
  children.push(c);
  fs.appendFileSync(path.join(OUT,'owned-processes.jsonl'),JSON.stringify({name,pid:c.pid,command:args,env})+'\n');
  c.on('exit', code => { if (!quitting) log.push({ exited: name, code }); });
  return c;
}
let quitting = false;
// Chrome forks helpers that outlive a parent kill; sweep everything using this run's profile.
const sweepChrome = () => { try { execFileSync('pkill', ['-f', `user-data-dir=${OUT}/chrome-profile`]); } catch { /* none */ } };
process.on('exit', () => { quitting = true; for (const c of children) { try { c.kill(); } catch {} } sweepChrome(); });
const jar = s => fs.readdirSync(path.join(GEN, s, 'build/libs')).map(f => path.join(GEN, s, 'build/libs', f)).find(f => f.endsWith('.jar') && !f.endsWith('-plain.jar'));
// Freeze the exact executable identity before any process launch.
const identity={java:execFileSync(JAVA,['--version']).toString(),revision:execFileSync('git',['rev-parse','HEAD'],{cwd:CONSOLE_ROOT}).toString().trim(),files:[]};
function inventory(file) {
 const stat=fs.statSync(file); if(stat.isDirectory()){for(const n of fs.readdirSync(file).sort())inventory(path.join(file,n));}
 else identity.files.push({path:file,sha256:sha(fs.readFileSync(file))});
}
for(const entry of CP.split(path.delimiter))inventory(entry);
for(const service of ['trade-processor','position-service','account-service'])inventory(jar(service));
inventory(path.join(CONSOLE_ROOT,'server.mjs'));inventory(new URL(import.meta.url).pathname);
fs.writeFileSync(path.join(OUT,'executable-identity.json'),JSON.stringify(identity,null,2));
const javaMain = (name, main, env) => launch(name, [JAVA, '-Xms64m', '-Xmx384m', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
  '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED', '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED', '-cp', CP, main], env);

// The launcher installs NEW empty fixture schema plus both additive RI06 migrations.
check(sql('SELECT (SELECT count(*) FROM trades)+(SELECT count(*) FROM positions)+(SELECT count(*) FROM orderbook)') === '0', 'NEW database has zero projection rows before services');
// Independent real-NATS observer: broad subject avoids appearing as a UI account subscription.
const notificationFile=fs.openSync(path.join(OUT,'nats.raw'),'w');
let notificationWire='';
const observer=net.connect(27822,'127.0.0.1',()=>observer.write('CONNECT {"verbose":false}\r\nSUB > 1\r\nPING\r\n'));
observer.on('data',b=>{fs.writeSync(notificationFile,b);notificationWire+=b.toString();if(b.toString().includes('PING\r\n'))observer.write('PONG\r\n');});
observer.on('error',e=>fail('NATS observer: '+e.message));
// ---- services, edge, console ----
const startConsumer = name => launch(name, [JAVA, '-Xmx384m', '-jar', jar('trade-processor')], { ...DB, NATS_ADDRESS: NATS, TRADE_PROCESSOR_SERVICE_PORT: '27891',
  RISK_CONTROL_TOKEN: TOKEN, ORDER_MATCHER_BASE_URL: 'http://127.0.0.1:1', REFERENCE_DATA_SERVICE_URL: 'http://127.0.0.1:1', RECON_POLL_INTERVAL_MS: '3600000', SERVER_ERROR_INCLUDE_MESSAGE: 'always' });
let consumer = startConsumer('trade-processor');
launch('position-service', [JAVA, '-Xmx384m', '-jar', jar('position-service')], { ...DB, NATS_ADDRESS: NATS, POSITION_SERVICE_PORT: '27890' });
launch('account-service', [JAVA, '-Xmx256m', '-jar', jar('account-service')], { ...DB, ACCOUNT_SERVICE_PORT: '27892' });
launch('edge', [process.execPath, path.join(HERE, 'fixture-edge.mjs')], { EDGE_PORT:'27870', POSITION_PORT:'27890', TRADE_PROCESSOR_PORT:'27891', ACCOUNT_PORT:'27892', NATS_WS_PORT:'27880' });
launch('console', [process.execPath, path.join(CONSOLE_ROOT, 'server.mjs')], { PORT: '27800', EDGE_PROXY: '127.0.0.1:27870',
  STATIC_ROOT: path.join(CONSOLE_ROOT, 'dist/web-front-end-console/browser') });
await until(() => ok200(`${CONTROLLER}/accounts/1/orders`), 'trade-processor up', 120000);
await until(() => ok200('http://127.0.0.1:27800/position-service/v2/projections/active'), 'console → position-service up', 120000);
const index=await (await fetch('http://127.0.0.1:27800/')).text();
const assets=[...index.matchAll(/(?:src|href)="([^"?]+\.(?:js|css))"/g)].map(m=>m[1]);
check(assets.length>0,'served production index names compiled assets');
for(const asset of assets) { const served=Buffer.from(await (await fetch('http://127.0.0.1:27800/'+asset)).arrayBuffer());
 const built=fs.readFileSync(path.join(CONSOLE_ROOT,'dist/web-front-end-console/browser',asset));
 check(served.equals(built),'served asset equals built '+asset);log.push({asset,sha256:sha(served)}); }


// ---- the two runs (setup mirrors RunMigrationLiveIT) ----
const EVIDENCE = 'synthetic archived legacy source evidence';
const descriptor = (epoch, scope, legacy) => JSON.stringify({ schema: 'traderx.run.v1', epoch, eventIdScheme: legacy ? 'legacy-v0' : 'epoch-v1',
  storageLineage: scope + '-storage', projectionScope: scope, adoptionEvidenceSha256: legacy ? sha(EVIDENCE) : null });
const LEGACY_D = descriptor(OLD.epoch, 'legacy-unknown', true);
const FRESH_D = descriptor(FRESH.epoch, 'fresh-live', false);
const NEXT_D = descriptor(NEXT.epoch, 'next-live', false);
async function startRun(r, raw) {
  const storage = path.join(OUT, r.name + '-storage'); fs.mkdirSync(storage, { recursive: true });
  const identity = path.join(storage, 'run-identity.json');
  if (r.name === 'old') fs.writeFileSync(identity, raw);
  else { const input = path.join(OUT, r.name + '-descriptor.json'); fs.writeFileSync(input, raw);
    log.push({ provision: execFileSync('python3', [path.join(GEN, 'recovery-identity/provision.py'), '--offline', '--storage', storage, '--descriptor', input]).toString() }); }
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
  const text = await r.text(); if (r.status !== 200) fail(`${p} → ${r.status} ${text}`); const result = JSON.parse(text); log.push({ post: base + p, body, result }); return result;
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
  '--remote-debugging-port=27850', `--user-data-dir=${OUT}/chrome-profile`, '--no-first-run', '--window-size=1280,1000', 'about:blank'], {});
let targets; for (let i = 0; i < 50 && !targets; i++) { try { targets = await (await fetch('http://127.0.0.1:27850/json')).json(); } catch { await sleep(200); } }
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let id = 0; const waiting = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); waiting.get(m.id)?.(m); waiting.delete(m.id); });
let cdp = (method, params = {}) => new Promise(r => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const js = async expr => { const r = await cdp('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) fail('js: ' + JSON.stringify(r.result.exceptionDetails)); return r.result?.result?.value; };
const subs = async () => [...new Set(((await (await fetch('http://127.0.0.1:27823/subsz?subs=1')).json()).subscriptions_list ?? [])
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
await cdp('Page.enable'); await cdp('Page.navigate', { url: 'http://localhost:27800/' }); await sleep(2500);
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


// Keep Trading and a distinct Admin tab open throughout outage, repair and next transition.
const tradingCdp = cdp;
const created = await cdp('Target.createTarget', { url: 'http://localhost:27800/admin' });
const adminTarget = (await (await fetch('http://127.0.0.1:27850/json')).json()).find(t => t.id === created.result.targetId);
const aws = new WebSocket(adminTarget.webSocketDebuggerUrl);
await new Promise(r => aws.addEventListener('open', r));
let aid=0; const aw=new Map(); aws.addEventListener('message', e=>{const m=JSON.parse(e.data);aw.get(m.id)?.(m);aw.delete(m.id);});
const adminCdp=(method,params={})=>new Promise(r=>{const i=++aid;aw.set(i,r);aws.send(JSON.stringify({id:i,method,params}));});
const adminText=async()=>{cdp=adminCdp;const t=await js(`return document.querySelector('admin-panel')?.innerText ?? ''`);cdp=tradingCdp;return t;};
await until(async()=>hasRow(await adminText(),freshTradeId),'already-open Admin baseline');
cdp=adminCdp; await shot('a1-admin-baseline','admin-panel'); cdp=tradingCdp;
const snapshot = name => {
 const state={}; for(const table of ['trades','orderbook','positions','projection_runs','projection_active','projection_transitions','projection_recovery']) {
  state[table]=sql(`SELECT * FROM ${table} ORDER BY 1,2`);
 } fs.writeFileSync(path.join(OUT,name+'.json'),JSON.stringify(state,null,2)); return state;
};
const retained = sql("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id");
const baseline=snapshot('baseline');
check(sql("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'")==='2','baseline has two managed trade legs');
consumer.kill('SIGTERM'); await new Promise(r=>consumer.once('exit',r));
check(!(await ok200(CONTROLLER+'/accounts/1/orders').catch(()=>false)),'actual consumer process stopped');
await cross(FRESH,30,'missed-');
const source=await (await fetch(gwUrl(FRESH)+'/run/status')).json(); log.push({afterMissedMatch:source});
await sleep(1200);
check(sql("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'")==='2','matching while consumer stopped leaves SQL missing two legs');
snapshot('outage');
consumer=startConsumer('trade-processor-restart');
await until(()=>ok200(CONTROLLER+'/accounts/1/orders'),'consumer restarted',120000);
await sleep(3000);
check(sql("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'")==='2','consumer restart alone does not replay missed core NATS publications');
check(!hasRow(await text(),'e1-freshlive-3-B')&&!hasRow(await adminText(),'e1-freshlive-3-B'),'already-open Trading and Admin still lack missed trade');
await shot('a2-before-catch-up');
const recovery=await post(CONTROLLER,'/v2/projection-recovery/catch-up',{projectionScope:'fresh-live',endpoint:gwUrl(FRESH)});
check(recovery.eventCount>0 && recovery.completeSequence>0,'archive recovery returns nonempty complete-prefix witness');
const expectedTrades='e1-freshlive-1-B\ne1-freshlive-2-S\ne1-freshlive-3-B\ne1-freshlive-4-S';
check(sql("SELECT id FROM trades WHERE projectionscope='fresh-live' ORDER BY id")===expectedTrades,'all four exact expected managed trade IDs recovered');
check(sql("SELECT orderid FROM orderbook WHERE projectionscope='fresh-live' AND status='FILLED' AND remainingquantity=0 ORDER BY orderid")==='freshlive-1\nfreshlive-2\nfreshlive-3\nfreshlive-4','all four exact final orders recovered');
check(sql("SELECT CONCAT(accountid,':',quantity) FROM positions WHERE projectionscope='fresh-live' ORDER BY accountid")==='11413:-50\n22214:50','signed positions are exactly sell -50 and buy +50');
check(sql("SELECT * FROM trades WHERE projectionscope='fresh-live' AND id IN ('e1-freshlive-1-B','e1-freshlive-2-S') ORDER BY id")===retained,'all retained trade columns unchanged after catch-up');
await see(x=>hasRow(x,'e1-freshlive-3-B'),'already-open Trading converges after catch-up');
await until(async()=>hasRow(await adminText(),'e1-freshlive-3-B'),'already-open Admin converges after catch-up');
await shot('a3-after-catch-up');cdp=adminCdp;await shot('a4-admin-after-catch-up','admin-panel');cdp=tradingCdp;
check(notificationWire.includes('MSG /v2/projections/fresh-live/accounts/22214/trades ') && notificationWire.includes('e1-freshlive-3-B'),'actual recovery trade notification captured from NATS');
const repaired=snapshot('repaired');
const oldScoped={};for(const table of ['trades','orderbook','positions']) oldScoped[table]=sql(`SELECT * FROM ${table} WHERE projectionscope='fresh-live' ORDER BY 1,2`);
const again=await post(CONTROLLER,'/v2/projection-recovery/catch-up',{projectionScope:'fresh-live',endpoint:gwUrl(FRESH)});
const retried=snapshot('retried');
check(JSON.stringify(again)===JSON.stringify(recovery),'retry returns identical boundary and witness');
for(const table of ['trades','orderbook','positions','projection_recovery','projection_runs']) check(repaired[table]===retried[table],`retry leaves ${table} unchanged`);

// Real managed-to-managed transition; no SQL phases/pointers are manually updated.
await startRun(NEXT,NEXT_D);
const req2={transitionId:'ma-second-transition',oldScope:'fresh-live',descriptorJson:NEXT_D,oldEndpoint:gwUrl(FRESH),newEndpoint:gwUrl(NEXT)};
for(const [action,expected] of [['prepare','PREPARED'],['freeze','FROZEN'],['verify','VERIFIED'],['select','SELECTED'],['activate','COMPLETE']]) {
 check((await post(CONTROLLER,'/v2/projection-control/'+action,req2)).phase===expected,'managed-to-managed controller '+expected);
 if(action==='prepare') await seed(NEXT);
}
await cross(NEXT,7,'next-');
await until(()=>sql("SELECT count(*) FROM trades WHERE projectionscope='next-live'")==='2','next managed trade booked');
await see(x=>x.includes('Active run: next-live')&&hasRow(x,'e1-nextlive-1-B')&&!hasRow(x,freshTradeId),'already-open Trading switches to next-live');
await until(async()=>{const x=await adminText();return x.includes('Active run: next-live')&&hasRow(x,'e1-nextlive-1-B')&&!hasRow(x,freshTradeId)},'already-open Admin switches to next-live');
await sleep(800);
check(JSON.stringify(await subs())===JSON.stringify([`/v2/projections/next-live/accounts/${BUY}/positions`,`/v2/projections/next-live/accounts/${BUY}/trades`]),'subscriptions move to next-live only');
check(notificationWire.includes('MSG /v2/projections/next-live/accounts/22214/trades ') && notificationWire.includes('e1-nextlive-1-B'),'new managed run publishes its own scoped real trade notification');
await shot('a5-next-active');
for(const table of ['trades','orderbook','positions']) check(sql(`SELECT * FROM ${table} WHERE projectionscope='fresh-live' ORDER BY 1,2`)===oldScoped[table],`sealed ${table} unchanged after next transition`);
check(sql("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id")===repaired.trades.split('\n').filter(l=>l.includes('fresh-live')).join('\n'),'old managed trade history unchanged after next transition');
await pick('[data-testid=run-select]','fresh-live');
t=await see(x=>x.includes('HISTORICAL · READ-ONLY')&&hasRow(x,freshTradeId)&&hasRow(x,'e1-freshlive-3-B'),'sealed managed history readable');
check(!hasRow(t,'e1-nextlive-1-B')&&(await cancels())===0,'history excludes fresh rows and cancel controls');
check(await js(`return [...document.querySelectorAll('blotter-panel button')].filter(b=>/force.?settle|replace|cancel/i.test(b.textContent)).length`)===0,'history has no business mutation buttons');
await shot('a6-managed-history');
await pick('.acct select',`(${SELL})`);
t=await see(x=>hasRow(x,'e1-freshlive-4-S'),'historical account change');
check(!hasRow(t,'e1-freshlive-3-B'),'historical account switch excludes buy account');
await shot('a7-history-other-account');
for(const p of ['/trade-processor/v2/projection-control/select','/legacy/trade-processor/v2/projection-control/activate','/order-matcher/run/control','/trade-processor/v2/projection-recovery/catch-up','/legacy/trade-processor/v2/projection-recovery/catch-up','/gw/0/run/control']) {
 const r=await fetch('http://127.0.0.1:27800'+p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(req2)});
 const body=await r.text(); check(r.status===403&&body.includes('operator_control_refused'),`console refuses ${p}`);
}
check(phase('ma-second-transition')==='COMPLETE','refused console requests leave transition COMPLETE');
snapshot('final');
console.log('ALL CHECKS PASSED');await done(0);
