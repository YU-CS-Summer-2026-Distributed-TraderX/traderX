#!/usr/bin/env node
// fix-latency-probe.mjs — binary-latency-probe.mjs's method on the FIX 4.4 order path (FR-OPL-08).
//
// Constant arrival rate on a wall-clock schedule; latency from the INTENDED send time (from the write when the timer
// fired up to 1 ms early), plus from the write. Three stages per order pair:
//   ack:  NewOrderSingle intended send -> the first ExecutionReport for its ClOrdID (New, or the first answer: the
//         gateway answers an aggressor that fills on arrival with Filled directly, FR-FFR-01);
//   fill: the crossing order's (the sell's) intended send -> its own Filled (the aggressor's answer);
//   rest: the same intended send -> the resting buy's unsolicited fill report (FR-FFR-02, through the fill feed).
//
// Book- and position-neutral on its own ticker, as the binary probe: a LANE sends order 2k as a buy and 2k+1 as a sell
// at one price, so every pair crosses, and the buyer alternates between the lane's two accounts. A lane is
//   --accounts A,B given: one session that sends Account(1) A or B (its CompID must be bound to both, or to *);
//   otherwise:           two sessions from --creds, each trading its own account (the rig's CLIENT2.. test CompIDs).
// Lanes are served round-robin, so a pair's sell goes LANES slots after its buy (16 lanes at 500/s: 32 ms).
// No reconnect: a lost session, a reject (39=8, 35=3, 35=j), an order that does not cross, an unanswered or unended
// order, or a skipped send exits 1.
//
//   node fix-latency-probe.mjs --creds FILE --ticker T --px 50 [--host order-matcher-gw-fix] [--port 18130]
//        [--target TRADERX] [--rate 500] [--sessions N] [--secs 600] [--warmup 20] [--window 60] [--drain 5]
//        [--accounts A,B]
//   node fix-latency-probe.mjs --sender CLIENT1 [--password-env FIX_SESSION_PASSWORD] --accounts A,B ...
// FILE is provision-fix-test-clients.json ({CompID: {account, password}}); no password is printed.
import fs from 'node:fs';
import net from 'node:net';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  creds: flag('--creds'), sender: flag('--sender'), passwordEnv: flag('--password-env', 'FIX_SESSION_PASSWORD'),
  ticker: flag('--ticker'), px: flag('--px'), host: flag('--host', 'order-matcher-gw-fix'), port: Number(flag('--port', 18130)),
  target: flag('--target', 'TRADERX'), rate: Number(flag('--rate', 500)), sessions: flag('--sessions'),
  secs: Number(flag('--secs', 600)), warmup: Number(flag('--warmup', 20)), window: Number(flag('--window', 60)),
  drain: Number(flag('--drain', 5)), accounts: flag('--accounts')?.split(','),
};
const usage = (why) => { console.log(`refused: ${why}\nusage: (--creds FILE | --sender ID [--password-env VAR]) --ticker T `
  + '--px PRICE [--rate N] [--sessions S] [--accounts A,B]'); process.exit(2); };
if (!cfg.ticker || !/^\d+(\.\d{1,6})?$/.test(cfg.px ?? '') || !(cfg.rate > 0)) usage('--ticker and --px are required');
if (cfg.accounts && cfg.accounts.length !== 2) usage('--accounts takes two accounts');
let creds;
if (cfg.creds) creds = Object.entries(JSON.parse(fs.readFileSync(cfg.creds, 'utf8')));
else if (cfg.sender && process.env[cfg.passwordEnv]) creds = [[cfg.sender, {password: process.env[cfg.passwordEnv]}]];
else usage(`--creds, or --sender with ${cfg.passwordEnv} set`);
if (cfg.sessions) creds = creds.slice(0, Number(cfg.sessions));
if (!cfg.accounts && (creds.length < 2 || creds.length % 2 || creds.some(([, c]) => !c.account)))
  usage('without --accounts the sessions pair up: an even number of CompIDs, each with its account');
if (!cfg.accounts) for (let i = 0; i < creds.length; i += 2)
  if (creds[i][1].account === creds[i + 1][1].account) usage(`${creds[i][0]} and ${creds[i + 1][0]} share an account (self-trade)`);

const SOH = '\x01';
const stamp = () => new Date().toISOString().replace(/-/g, '').replace('T', '-').slice(0, 21);
const parse = (m) => Object.fromEntries(m.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
const RUN = `p${Date.now().toString(36)}`;
const now = () => process.hrtime.bigint();
const wallOffset = BigInt(Date.now()) * 1_000_000n - now();          // hrtime -> epoch ns, for window alignment
const winNs = BigInt(cfg.window) * 1_000_000_000n;
let measureFrom = 0n, closing = false;
const stage = () => ({intended: [], actual: []});
const windows = new Map();                                             // window index -> {ack, fill, rejected}
// fill: the crossing order's own Filled (the gateway answers it directly, FR-FFR-01); rest: the resting order's fill
// report, which reaches its session through the fill feed (FR-FFR-02/14). Run 4's baseline (2026-11-11) read ~117 ms
// p50 for the two together, so they are separate stages.
const total = {ack: stage(), fill: stage(), rest: stage(), rejected: 0, uncrossed: 0, lostSessions: 0};
const bucket = (intendedNs) => {
  const k = (intendedNs + wallOffset) / winNs;
  if (!windows.has(k)) windows.set(k, {ack: stage(), fill: stage(), rest: stage(), rejected: 0});
  return windows.get(k);
};
const record = (name, o, t) => {
  if (o.intended < measureFrom) return;
  for (const s of [bucket(o.intended)[name], total[name]]) { s.intended.push(Number(t - o.intended)); s.actual.push(Number(t - o.actual)); }
};
const reject = (o) => { if (o.intended >= measureFrom) bucket(o.intended).rejected++; total.rejected++; };

const orders = new Map();                                              // ClOrdID -> order, until it ends
function session(compId, {password}) {
  // the FIX session handling of fix-rehearsal-load.mjs: frame, Logon with 141=Y, heartbeat, GapFill a ResendRequest
  let out = 1, buf = '', up = false;
  const sock = net.connect(cfg.port, cfg.host);
  sock.setNoDelay(true);
  const frame = (type, fields, header = []) => {
    const seq = header.find(([k]) => k === '34')?.[1] ?? out++;
    const head = [['35', type], ['49', compId], ['56', cfg.target], ['34', seq], ['52', stamp()], ...header.filter(([k]) => k !== '34')];
    const body = [...head, ...fields].map(([k, v]) => `${k}=${v}`).join(SOH) + SOH;
    const pre = `8=FIX.4.4${SOH}9=${Buffer.byteLength(body)}${SOH}`;
    const sum = [...Buffer.from(pre + body)].reduce((a, c) => (a + c) & 0xff, 0);
    return `${pre}${body}10=${String(sum).padStart(3, '0')}${SOH}`;
  };
  const write = (type, fields, header) => { if (!sock.destroyed) sock.write(frame(type, fields, header)); };
  const lost = () => { if (!closing && !sock.lost) { sock.lost = true; total.lostSessions++; console.log(`[probe] LOST ${compId}`); } up = false; };
  sock.on('connect', () => write('A', [['98', 0], ['108', 30], ['141', 'Y'], ['554', password]]));
  sock.on('close', lost);
  sock.on('error', () => {});
  sock.on('data', (chunk) => {
    const t = now();
    buf += chunk.toString('latin1');
    let at = 0;
    for (let e; (e = buf.indexOf(`${SOH}10=`, at)) >= 0;) {
      const cut = buf.indexOf(SOH, e + 1) + 1; if (cut <= 0) break;
      const f = parse(buf.slice(at, cut)); at = cut;
      if (f['35'] === 'A') { up = true; continue; }
      if (f['35'] === '1') { write('0', [['112', f['112']]]); continue; }
      if (f['35'] === '2') { write('4', [['123', 'Y'], ['36', out]], [['34', Number(f['7'])], ['43', 'Y'], ['122', stamp()]]); continue; }
      if (f['35'] === '5') { if (!closing) console.log(`[probe] ${compId} logged out: ${f['58'] || '(no text)'}`); lost(); sock.destroy(); continue; }
      if (f['35'] === '3' || f['35'] === 'j') {                         // a session or business reject of one of ours
        total.rejected++; console.log(`[probe] ${compId} reject 35=${f['35']}: ${f['58'] || ''}`); continue;
      }
      if (f['35'] !== '8') continue;
      const o = orders.get(f['11']);
      if (!o || f['150'] === 'I') continue;                             // not ours, or a status restatement
      if (!o.acked) { o.acked = true; record('ack', o, t); }
      if (f['39'] === '8') { reject(o); console.log(`[probe] ${compId} rejected ${f['11']}: ${f['58'] || ''}`); }
      if (!['2', '4', '8', 'C'].includes(f['39'])) continue;            // still open (New, PartiallyFilled)
      orders.delete(f['11']); o.endedAt = t;
      if (f['39'] !== '2') {
        if (f['39'] !== '8') {
          total.uncrossed++;
          console.log(`[probe] ${compId} ${f['11']} side=${f['54']} ended 39=${f['39']} 150=${f['150']} cum=${f['14']} ${f['58'] || ''}`);
        }
        continue;
      }
      const [buy, sell] = o.pair;
      if (buy.endedAt && sell?.endedAt) { record('fill', sell, sell.endedAt); record('rest', sell, buy.endedAt); }
    }
    buf = buf.slice(at);
  });
  const order = (cl, account, side, o) => {
    const at = now();
    o.intended = o.intended > at ? at : o.intended; o.actual = at;    // sent early (inside the last ms): from the write
    orders.set(cl, o);
    write('D', [['11', cl], ['1', account], ['55', cfg.ticker], ['54', side], ['38', 1], ['40', 2], ['44', cfg.px], ['60', stamp()]]);
  };
  return {compId, order, up: () => up, close: () => { if (up) write('5', []); setTimeout(() => sock.destroy(), 300); }};
}

const sessions = creds.map(([c, v]) => session(c, v));
const lanes = cfg.accounts
  ? sessions.map((s) => [[s, cfg.accounts[0]], [s, cfg.accounts[1]]])
  : sessions.flatMap((s, i) => (i % 2 ? [] : [[[s, creds[i][1].account], [sessions[i + 1], creds[i + 1][1].account]]]));
const laneSeq = lanes.map(() => 0);
const send = (l, intended) => {
  const seq = laneSeq[l]++, pair = seq >> 1, side = seq & 1;          // 0 buy, 1 sell
  const [s, account] = lanes[l][(pair & 1) ^ side];
  const buy = lanes[l].open; lanes[l].open = null;
  if (!s.up() || (side === 1 && !buy)) return false;                  // a sell whose buy was skipped is skipped too
  const o = {intended, acked: false, endedAt: null};
  if (side === 0) { o.pair = [o]; lanes[l].open = o; } else { o.pair = buy.pair; o.pair.push(o); }
  s.order(`${RUN}-${l}-${seq}`, account, side ? 2 : 1, o);
  return true;
};

const pct = (s, p) => s.length ? s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)] : 0;
const us = (ns) => (ns / 1000).toFixed(0);
const dist = (v) => { const s = v.slice().sort((a, b) => a - b);
  return `p50=${us(pct(s, 50))}us p90=${us(pct(s, 90))}us p99=${us(pct(s, 99))}us p99.9=${us(pct(s, 99.9))}us max=${us(s[s.length - 1] || 0)}us`; };
// the part before the first '|' has binary-latency-probe's keys (the ack stage), so l0-report.py reads either probe
const line = (tag, at, w) => `${tag} at=${at} count=${w.ack.intended.length} rejected=${w.rejected} ${dist(w.ack.intended)} `
  + `| from-write ${dist(w.ack.actual)} | fill count=${w.fill.intended.length} ${dist(w.fill.intended)} | fill-from-write ${dist(w.fill.actual)}`
  + ` | rest count=${w.rest.intended.length} ${dist(w.rest.intended)}`;
const flushWindows = (all) => {
  const current = (now() + wallOffset) / winNs;
  for (const [k, w] of [...windows].sort((x, y) => (x[0] < y[0] ? -1 : 1))) {
    if (!all && k >= current - 1n) break;                              // a window is closed one window after it ends
    console.log(line('PROBE-WINDOW', new Date(Number(k * winNs / 1_000_000n)).toISOString(), w));
    windows.delete(k);
  }
};

for (let t = Date.now(); sessions.some((s) => !s.up()) && Date.now() - t < 10_000;) await new Promise((res) => setTimeout(res, 50));
const down = sessions.filter((s) => !s.up()).map((s) => s.compId);
if (down.length) { console.log(`refused: no Logon from ${down.join(',')} within 10 s`); process.exit(2); }
const periodNs = BigInt(Math.round(1e9 / cfg.rate));
const t0 = now();
measureFrom = t0 + BigInt(cfg.warmup) * 1_000_000_000n;
const end = measureFrom + BigInt(cfg.secs) * 1_000_000_000n;
console.log(`[probe] ${cfg.host}:${cfg.port} ${cfg.ticker}@${cfg.px} rate=${cfg.rate}/s sessions=${sessions.length} lanes=${lanes.length} `
  + `warmup=${cfg.warmup}s measure=${cfg.secs}s window=${cfg.window}s run=${RUN}`);
const report = setInterval(() => flushWindows(false), 5000);
let n = 0, skipped = 0;
// ponytail: whole pairs only, so the schedule ends position-neutral; the last pair may run past `end` by one period
for (let intended = t0; intended < end || laneSeq.some((q) => q & 1); intended += periodNs) {
  const waitMs = Number(intended - now()) / 1e6;
  if (waitMs > 1) await new Promise((res) => setTimeout(res, waitMs));
  const l = n++ % lanes.length;
  if (intended >= end && !(laneSeq[l] & 1)) continue;                 // past the end: only close open pairs
  if (!send(l, intended)) skipped++;
}
for (let t = Date.now(); orders.size && Date.now() - t < cfg.drain * 1000;) await new Promise((res) => setTimeout(res, 50));
clearInterval(report);
flushWindows(true);
const unanswered = [...orders.values()].filter((o) => !o.acked).length, unended = orders.size;
console.log(line('PROBE-TOTAL', new Date().toISOString(), total) + ` uncrossed=${total.uncrossed} unanswered=${unanswered} `
  + `unended=${unended} skipped=${skipped} lostSessions=${total.lostSessions}`);
closing = true;
sessions.forEach((s) => s.close());
setTimeout(() => process.exit(unended || skipped || total.lostSessions || total.rejected || total.uncrossed ? 1 : 0), 400);
