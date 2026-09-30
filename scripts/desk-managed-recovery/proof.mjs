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
const RIG=need('DESK_PROOF_NAME');
const GEN = need('GEN');                         // …/code/target-generated
const CP = fs.readFileSync(need('MATCHER_CLASSPATH_FILE'), 'utf8').trim();
const JAVA = need('JAVA');                        // Java 21 binary
const OUT = need('OUT');
const HERE = path.dirname(new URL(import.meta.url).pathname);
const CONSOLE_ROOT = path.resolve(HERE, '../../web-front-end-console');
const TOKEN = 'mui-proof-token';
const NATS = 'nats://127.0.0.1:28822';
const DB = { DATABASE_PG_PORT: '28806' };
const CONTROLLER = 'http://127.0.0.1:28891';
const OLD = { name: 'old', base: 28830, health: 28840, gw: 28841, epoch: 'oldlive' };
const NEXT = { name: 'next', base: 28910, health: 28945, gw: 28946, epoch: 'nextlive' };
const FRESH = { name: 'fresh', base: 28810, health: 28845, gw: 28846, epoch: 'freshlive' };
const gwUrl = r => `http://127.0.0.1:${r.gw}`;
fs.mkdirSync(OUT, { recursive: true });
const log = [];
const fatal=e=>{console.error(e);log.push({fatal:String(e?.stack??e)});void done(1);};
process.on('SIGTERM',()=>void done(143));process.on('SIGINT',()=>void done(130));
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
const sql = q => execFileSync('docker', ['exec', RIG+'-sql', 'mariadb', '-N', '-utraderx', '-ptraderx', 'traderx', '-e', q]).toString().trim();
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
const observer=net.connect(28822,'127.0.0.1',()=>observer.write('CONNECT {"verbose":false}\r\nSUB > 1\r\nPING\r\n'));
observer.on('data',b=>{fs.writeSync(notificationFile,b);notificationWire+=b.toString();if(b.toString().includes('PING\r\n'))observer.write('PONG\r\n');});
observer.on('error',e=>fail('NATS observer: '+e.message));
// ---- services, edge, console ----
fs.writeFileSync(path.join(OUT,'users.json'),JSON.stringify({'user:deskproof':{name:'DeskProof',accounts:[22214],selfMatchGroup:22214,requests:{}},'user:otherproof':{name:'OtherProof',accounts:[11413],selfMatchGroup:11413,requests:{}}}));
launch('reference-data',[process.execPath,path.join(GEN,'reference-data/dist/main.js')],{...DB,NATS_SERVER:NATS,NATS_ADDRESS:NATS,REFERENCE_DATA_SERVICE_PORT:'28893'},path.join(GEN,'reference-data'));

const startConsumer = name => launch(name, [JAVA, '-Xmx384m', '-jar', jar('trade-processor')], { ...DB, NATS_ADDRESS: NATS, TRADE_PROCESSOR_SERVICE_PORT: '28891',
  RISK_CONTROL_TOKEN: TOKEN, ORDER_MATCHER_BASE_URL: 'http://127.0.0.1:1', REFERENCE_DATA_SERVICE_URL: 'http://127.0.0.1:1', RECON_POLL_INTERVAL_MS: '3600000', SERVER_ERROR_INCLUDE_MESSAGE: 'always' });
let consumer = startConsumer('trade-processor');
launch('position-service', [JAVA, '-Xmx384m', '-jar', jar('position-service')], { ...DB, NATS_ADDRESS: NATS, POSITION_SERVICE_PORT: '28890' });
launch('account-service', [JAVA, '-Xmx256m', '-jar', jar('account-service')], { ...DB, ACCOUNT_SERVICE_PORT: '28892' });
launch('edge', [process.execPath, path.join(HERE, 'edge.mjs')], { EDGE_PORT:'28870', POSITION_PORT:'28890', TRADE_PROCESSOR_PORT:'28891', ACCOUNT_PORT:'28892', NATS_WS_PORT:'28880' });
launch('console', [process.execPath, path.join(CONSOLE_ROOT, 'server.mjs')], { PORT: '28800', EDGE_PROXY: '127.0.0.1:28870',
  LOCAL_ONLY:'1', DESK_RISK_CONTROL_TOKEN:TOKEN, DESK_USERS_FILE:path.join(OUT,'users.json'), STATIC_ROOT: path.resolve(CONSOLE_ROOT,'../web-front-end-console-combined-prototype/dist/web-front-end-console-combined-prototype/browser') });
await until(() => ok200(`${CONTROLLER}/accounts/1/orders`), 'trade-processor up', 120000);
await until(() => ok200('http://127.0.0.1:28800/position-service/v2/projections/active'), 'console → position-service up', 120000);
const index=await (await fetch('http://127.0.0.1:28800/')).text();
const assets=[...index.matchAll(/(?:src|href)="([^"?]+\.(?:js|css))"/g)].map(m=>m[1]);
check(assets.length>0,'served production index names compiled assets');
for(const asset of assets) { const served=Buffer.from(await (await fetch('http://127.0.0.1:28800/'+asset)).arrayBuffer());
 const built=fs.readFileSync(path.resolve(CONSOLE_ROOT,'../web-front-end-console-combined-prototype/dist/web-front-end-console-combined-prototype/browser',asset));
 check(served.equals(built),'served asset equals built '+asset);log.push({asset,sha256:sha(served)}); }


// ---- the two runs (setup mirrors RunMigrationLiveIT) ----
const EVIDENCE = 'synthetic archived legacy source evidence';
const descriptor = (epoch, scope, legacy) => JSON.stringify({ schema: 'traderx.run.v1', epoch, eventIdScheme: legacy ? 'legacy-v0' : 'epoch-v1',
  storageLineage: scope + '-storage', projectionScope: scope, adoptionEvidenceSha256: legacy ? sha(EVIDENCE) : null });
const LEGACY_D = descriptor(OLD.epoch, 'legacy-unknown', true);
const FRESH_D = descriptor(FRESH.epoch, 'fresh-live', false);
const NEXT_D = descriptor(NEXT.epoch, 'next-live', false);
async function startRun(r, raw, restart=false) {
  const storage = path.join(OUT, r.name + '-storage'); fs.mkdirSync(storage, { recursive: true });
  const identity = path.join(storage, 'run-identity.json');
  if(restart) {check(fs.readFileSync(identity,'utf8')===raw,'restart retains immutable descriptor');}
  else if (r.name === 'old') fs.writeFileSync(identity, raw);
  else { const input = path.join(OUT, r.name + '-descriptor.json'); fs.writeFileSync(input, raw);
    log.push({ provision: execFileSync('python3', [path.join(GEN, 'recovery-identity/provision.py'), '--offline', '--storage', storage, '--descriptor', input]).toString() }); }
  if (r.name === 'old') fs.writeFileSync(path.join(storage, 'run-adoption-evidence.json'), EVIDENCE);
  const common = { RUN_DESCRIPTOR_PATH: identity, CLUSTER_EPOCH: r.epoch, RISK_CONTROL_TOKEN: TOKEN };
  r.node=javaMain(r.name + '-node'+(restart?'-restart':''), 'finos.traderx.ordermatcher.cluster.ClusterNodeMain', { ...common, CLUSTER_MEMBER_ID: '0', CLUSTER_HOSTNAMES: 'localhost',
    CLUSTER_PORT_BASE: String(r.base), CLUSTER_BASE_DIR: storage, CLUSTER_AERON_DIR: path.join(OUT, r.name + '-aeron'), HEALTH_PORT: String(r.health),
    CLUSTER_IDLE_SLEEP_MS: '1', RECON_BLOTTER_CAPACITY: '1000', RECON_FULL_HISTORY_MAX: '1000', REGULATORY_MAX_RECORDS: '1000', TRADE_BRIDGE_NATS_URL: NATS });
  await until(() => ok200(`http://127.0.0.1:${r.health}/health`), r.name + ' node health', 60000);
  r.gateway=javaMain(r.name + '-gateway'+(restart?'-restart':''), 'finos.traderx.ordermatcher.cluster.ClusterGatewayMain', { ...common, GATEWAY_INGRESS_ENDPOINTS: `0=localhost:${r.base + 2}`,
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

// Bootstrap a nonempty archive and use the accepted durable transition controller.
await seed(OLD);await cross(OLD,10,'bootstrap-');
await until(()=>sql("SELECT count(*) FROM trades WHERE projectionscope='legacy-unknown'")==='2','bootstrap projection');
await post(CONTROLLER,'/v2/projection-control/adopt-legacy',{descriptorJson:LEGACY_D,oldEndpoint:gwUrl(OLD)});
const transition={transitionId:'desk-first',oldScope:'legacy-unknown',descriptorJson:FRESH_D,oldEndpoint:gwUrl(OLD),newEndpoint:gwUrl(FRESH)};
for(const [op,phase] of [['prepare','PREPARED'],['freeze','FROZEN'],['verify','VERIFIED'],['select','SELECTED'],['activate','COMPLETE']]) {
 check((await post(CONTROLLER,'/v2/projection-control/'+op,transition)).phase===phase,'real transition '+phase);
 if(op==='prepare')await seed(FRESH);
}
const base='http://127.0.0.1:28800';
async function login(username) {
 const r=await fetch(base+'/desk-api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username})});
 check(r.status===200,'workspace login '+username);return r.headers.getSetCookie().map(x=>x.split(';')[0]).join('; ');
}
const alice=await login('DeskProof'),bob=await login('OtherProof');
async function action(cookie,action,body) {
 const r=await fetch(base+'/desk-api/orders/'+action,{method:'POST',headers:{'Content-Type':'application/json',cookie},body:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const result={status:r.status,body:await r.json()};log.push({action,request:body,...result});return result;
}
const order=(accountId,side,quantity,clientOrderId)=>({accountId,projectionScope:'fresh-live',ticker:'IBM',side,quantity,limitPrice:100,orderType:'LIMIT',timeInForce:'GTC',clientOrderId});
const buy=await action(alice,'orders',order(BUY,'Buy',20,'desk-partial-buy'));
check(buy.status===200 && buy.body.orderRef>0,'workspace submit reaches engine');const ref=buy.body.orderRef;
const sell=await action(bob,'orders',order(SELL,'Sell',5,'desk-partial-sell'));
check(sell.status===200,'other workspace submits contra order');
await until(()=>sql(`SELECT CONCAT(status,':',remainingquantity) FROM orderbook WHERE orderid='freshlive-${ref}'`)==='PARTIALLY_FILLED:15','partial fill persisted');
check(sql("SELECT quantity FROM positions WHERE projectionscope='fresh-live' AND accountid=22214")==='5','partial fill reaches position projection');
check((await action(bob,'cancel',{accountId:BUY,projectionScope:'fresh-live',orderRef:ref})).status===403,'cross-workspace cancellation refused');
check((await action(alice,'cancel',{accountId:BUY,projectionScope:'legacy-unknown',orderRef:ref})).status===409,'historical collision refused');
check((await action(alice,'replace',{accountId:BUY,projectionScope:'fresh-live',orderRef:ref,quantity:5,limitPrice:100})).status===422,'replacement at filled floor refused');
const replacement=await action(alice,'replace',{accountId:BUY,projectionScope:'fresh-live',orderRef:ref,quantity:25,limitPrice:99,clientOrderId:'desk-replace-one'});
check(replacement.status===200 && replacement.body.replaced,'partial order replacement committed');
await until(()=>sql(`SELECT CONCAT(quantity,':',remainingquantity) FROM orderbook WHERE orderid='freshlive-${ref}'`)==='25:20','replace quantity and remaining persisted');
// Receiving-gateway fence is exercised at the real HTTP endpoint, not only in a stub.
const wrong=await fetch(gwUrl(FRESH)+'/cancel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({orderRef:ref,expectedDescriptorHash:sha(LEGACY_D),expectedProjectionScope:'legacy-unknown'})});
check(wrong.status===409,'actual gateway rejects stale descriptor before dispatch');

// Actual shipped Desk in Chrome, with the same account/session and real service reads.
launch('chrome',[process.env.CHROME??'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome','--headless=new','--remote-debugging-port=28850',`--user-data-dir=${OUT}/chrome-profile`,'--no-first-run','--window-size=1440,1000','about:blank'],{});
let targets;await until(async()=>{try{targets=await(await fetch('http://127.0.0.1:28850/json')).json();return targets.some(t=>t.type==='page');}catch{return false;}},'Chrome');
const ws=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r));
let n=0;const pending=new Map();ws.addEventListener('message',e=>{const m=JSON.parse(e.data);pending.get(m.id)?.(m);pending.delete(m.id);});
const cdp=(method,params={})=>new Promise(resolve=>{const id=++n;pending.set(id,resolve);ws.send(JSON.stringify({id,method,params}));});
const js=async expression=>{const r=await cdp('Runtime.evaluate',{expression:`(async()=>{${expression}})()`,awaitPromise:true,returnByValue:true});if(r.result?.exceptionDetails)throw Error(JSON.stringify(r.result.exceptionDetails));return r.result?.result?.value;};
await cdp('Network.enable');await cdp('Network.setCookie',{name:'tx_desk',value:alice.match(/tx_desk=([^;]+)/)[1],url:base,httpOnly:true,sameSite:'Strict'});
await cdp('Page.navigate',{url:base+'/desk/orders'});
const text=async()=>{const t=await js('return document.body.innerText');fs.writeFileSync(path.join(OUT,'last-browser-text.txt'),t??'');return t;};
await until(async()=>{const t=await text();return t.includes('DeskProof')&&t.includes('Part filled');},'Desk displays persisted partial order',30000);
const shot=async name=>{const r=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});fs.writeFileSync(path.join(OUT,name+'.png'),Buffer.from(r.result.data,'base64'));};
await shot('desk-partial-replaced');
await js(`document.querySelector('[aria-label="Change order ${ref}"]').click()`);
await js(`for(const [name,value] of [['eq','30'],['ep','98']]) {const e=document.querySelector('input[name='+name+']');e.value=value;e.dispatchEvent(new Event('input',{bubbles:true}));} document.querySelector('tr.edit form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));`);
await until(()=>sql(`SELECT CONCAT(quantity,':',remainingquantity) FROM orderbook WHERE orderid='freshlive-${ref}'`)==='30:25','browser replacement persists after partial fill');
await until(async()=>(await text()).includes('Order changed'),'browser replacement receipt');
await shot('desk-browser-replace');
// Retain the same descriptor/archive and restart both actual core and gateway processes.
for(const child of [FRESH.gateway,FRESH.node]) {const exited=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGTERM');const force=setTimeout(()=>child.kill('SIGKILL'),8000);await exited;clearTimeout(force);}
await startRun(FRESH,FRESH_D,true);
const recoveredRun=await(await fetch(gwUrl(FRESH)+'/run/status')).json();
check(recoveredRun.descriptorHash===sha(FRESH_D)&&recoveredRun.runPhase===2,'retained core restart recovers same active identity');
await seed(FRESH);
await until(async()=>!!(await js(`return !!document.querySelector('[aria-label="Cancel order ${ref}"]')`)),'cancel returns after restart');

// Browser cancel clicks the real handler, then polling reconciles the committed projection.
await js(`document.querySelector('[aria-label="Cancel order ${ref}"]').click()`);
await until(()=>sql(`SELECT status FROM orderbook WHERE orderid='freshlive-${ref}'`)==='CANCELED','browser cancel persisted');
await until(async()=>(await text()).includes('Order cancelled'),'browser cancellation receipt');await shot('desk-cancelled');

// Real consumer outage, accepted archive recovery, and page convergence. No custom recovery logic.
consumer.kill('SIGTERM');await new Promise(r=>consumer.once('exit',r));
await cross(FRESH,3,'outage-');await sleep(500);
check(sql("SELECT count(*) FROM trades WHERE projectionscope='fresh-live'")==='2','outage left missing projection events');
consumer=startConsumer('trade-processor-restart');await until(()=>ok200(CONTROLLER+'/accounts/1/orders'),'consumer restart',120000);
await post(CONTROLLER,'/v2/projection-recovery/catch-up',{projectionScope:'fresh-live',endpoint:gwUrl(FRESH)});
check(sql("SELECT quantity FROM positions WHERE projectionscope='fresh-live' AND accountid=22214")==='8','accepted recovery rebuilt exact position');
await js(`document.querySelector('[data-testid="all-states"]').click()`);
await until(async()=>{const t=await text();return t.includes('Filled')&&t.includes('Cancelled');},'open Desk reconciles recovered orders',30000);await shot('desk-recovered');
const retained=sql("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id");
await post(CONTROLLER,'/v2/projection-recovery/catch-up',{projectionScope:'fresh-live',endpoint:gwUrl(FRESH)});
check(retained===sql("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id"),'repeat recovery leaves retained trade economics unchanged');

// Next managed run, real transition. The Desk edge is deliberately pinned to the old gateway:
// selected SQL identity must not authorize that old gateway, even during service routing changes.
await startRun(NEXT,NEXT_D);
const next={transitionId:'desk-second',oldScope:'fresh-live',descriptorJson:NEXT_D,oldEndpoint:gwUrl(FRESH),newEndpoint:gwUrl(NEXT)};
for(const op of ['prepare','freeze','verify','select','activate']){await post(CONTROLLER,'/v2/projection-control/'+op,next);if(op==='prepare')await seed(NEXT);}
check((await action(alice,'cancel',{accountId:BUY,projectionScope:'fresh-live',orderRef:ref})).status===409,'old run mutation refused after real transition');
check((await action(alice,'orders',{...order(BUY,'Buy',1,'wrong-route'),projectionScope:'next-live'})).status===409,'new selected scope cannot submit through old gateway');
await until(async()=>(await text()).includes('next-live'),'open Desk observes next selected run',30000);
await js(`const e=document.querySelector('[data-testid="run-select"]');e.value='fresh-live';e.dispatchEvent(new Event('change'))`);
await until(async()=>{const t=await text();return t.includes('Previous run selected')&&t.includes('Cancelled')&&t.includes('Filled')&&!(await js('return [...document.querySelectorAll("button")].some(b=>b.getAttribute("aria-label")?.startsWith("Cancel order"))'));},'populated historical view has no actions');
await shot('desk-historical');
check(retained===sql("SELECT * FROM trades WHERE projectionscope='fresh-live' ORDER BY id"),'historical trades unchanged across transition');
console.log('ALL CHECKS PASSED');await done(0);
