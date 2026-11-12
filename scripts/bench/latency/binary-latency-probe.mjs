#!/usr/bin/env node
// binary-latency-probe.mjs — rest-latency-probe.mjs's method on the binary order path (latency L0).
//
// Constant arrival rate on a wall-clock schedule, latency from the INTENDED send time (coordinated-omission
// safe; from the write instead when the timer fired up to 1 ms early), plus the same latency from the actual write, so the probe's own scheduling lateness is visible
// instead of mixed in. Orders go round-robin over SESSIONS connections: the binary acceptor answers one order
// per session at a time, so RATE/SESSIONS must leave each session idle between its orders (default 16
// sessions at 500/s = one order per session every 32 ms) or the probe measures its own queue.
//
// Book- and position-neutral on its own ticker: in each session order 2k is a buy and 2k+1 a sell at one
// price, so every pair crosses; the buyer is account A for even pairs and B for odd ones, so neither account
// is ever more than one lot long or short, and the two never trade with themselves (no STP cancels).
//
// Report: one PROBE-WINDOW line per wall-clock window (WINDOW s, aligned to the epoch, so 60 s windows line up
// with binary-load's 300 s phases and the 4 s pause falls in the last window of each phase), then PROBE-TOTAL.
//
//   node binary-latency-probe.mjs --resolve http://GW:18110 --ticker T --px 50 [--host order-matcher-gw-binary]
//        [--port 18140] [--rate 500] [--sessions 16] [--secs 600] [--warmup 20] [--window 60]
//        [--accounts 9900010,9900011]
import net from 'node:net';
import crypto from 'node:crypto';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  resolve: flag('--resolve'), ticker: flag('--ticker'), px: flag('--px'), host: flag('--host', 'order-matcher-gw-binary'),
  port: Number(flag('--port', 18140)), rate: Number(flag('--rate', 500)), sessions: Number(flag('--sessions', 16)),
  secs: Number(flag('--secs', 600)), warmup: Number(flag('--warmup', 20)), window: Number(flag('--window', 60)),
  accounts: flag('--accounts', '9900010,9900011').split(',').map(Number),
};
if (!cfg.resolve || !cfg.ticker || !/^\d+(\.\d{1,6})?$/.test(cfg.px ?? '') || cfg.accounts.length !== 2
    || !(cfg.sessions >= 1 && cfg.sessions <= 255) || !(cfg.rate > 0)) {
  console.log('usage: --resolve URL --ticker T --px PRICE [--rate N] [--sessions S] [--accounts A,B]'); process.exit(2);
}
const [whole, frac = ''] = cfg.px.split('.');
const PX = BigInt(whole) * 1000000n + BigInt(frac.padEnd(6, '0'));
// After a member roll the gateways can still be reconnecting: retry the resolve (no order sent) for up to a minute
// instead of dying, which left a whole arm without client latency (2026-11-11, S').
let r;
for (let attempt = 1; ; attempt++) {
  try {
    r = await fetch(`${cfg.resolve}/resolve`, {method: 'POST', headers: {'content-type': 'application/json'},
      body: JSON.stringify({ticker: cfg.ticker}), signal: AbortSignal.timeout(5000)});
    if (r.status === 200 || attempt >= 12) break;
  } catch (e) {
    if (attempt >= 12) throw e;
  }
  console.log(`resolve attempt ${attempt} failed; retrying`);
  await new Promise((ok) => setTimeout(ok, 5000));
}
const SECURITY = r.status === 200 ? (await r.json()).securityId : undefined;
if (!Number.isInteger(SECURITY)) { console.log(`refused: ${cfg.ticker} does not resolve (${r.status})`); process.exit(2); }
const NONCE = crypto.randomBytes(4).readUInt32LE(0) || 1;

const now = () => process.hrtime.bigint();
const wallOffset = BigInt(Date.now()) * 1_000_000n - now();          // hrtime -> epoch ns, for window alignment
const winNs = BigInt(cfg.window) * 1_000_000_000n;
let measureFrom = 0n;
const windows = new Map();                                             // window index -> {intended[], actual[], rejected}
const total = {intended: [], actual: [], rejected: 0, ambiguous: 0, lostSessions: 0};
const bucket = (intendedNs) => {
  const k = (intendedNs + wallOffset) / winNs;
  if (!windows.has(k)) windows.set(k, {intended: [], actual: [], rejected: 0});
  return windows.get(k);
};

function session(index) {
  const open = new Map();                                              // seq -> [intendedNs, actualNs]
  let seq = 0, buf = Buffer.alloc(0), up = false;
  const sock = net.connect(cfg.port, cfg.host);
  sock.setNoDelay(true);
  sock.on('connect', () => { up = true; });
  sock.on('close', () => { if (up) total.lostSessions++; up = false; });
  sock.on('error', () => {});
  sock.on('data', (chunk) => {
    const t = now();
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    for (; buf.length >= 22; buf = buf.subarray(22)) {   // ADR-105: ack 0x82, 20 bytes, u64 ref at 6
      if (buf.readUInt16LE(0) !== 20 || buf[2] !== 0x82) { sock.destroy(); return; }
      const status = buf[3], lo = buf.readUInt32LE(14), hi = buf.readUInt32LE(18);
      if (hi !== NONCE || lo >>> 24 !== index) continue;
      const sent = open.get(lo & 0xffffff);
      if (!sent) continue;                                             // a second answer: the first one counted
      open.delete(lo & 0xffffff);
      if (sent[0] < measureFrom) continue;
      const w = bucket(sent[0]);
      if (status === 1) {
        w.intended.push(Number(t - sent[0])); w.actual.push(Number(t - sent[1]));
        total.intended.push(Number(t - sent[0])); total.actual.push(Number(t - sent[1]));
      } else if (status === 0) { w.rejected++; total.rejected++; } else total.ambiguous++;
    }
  });
  const send = (intendedNs) => {
    if (!up) return false;
    if (++seq > 0xffffff) throw Error('session sequence exhausted');
    const pair = (seq - 1) >> 1, side = (seq - 1) & 1;                   // 0 buy, 1 sell
    const account = cfg.accounts[(pair & 1) ^ side];
    const b = Buffer.alloc(34);
    b.writeUInt16LE(32, 0); b[2] = 0x11; b[3] = side;
    b.writeUInt32LE(account, 6); b.writeUInt32LE(SECURITY, 10); b.writeInt32LE(1, 14);
    b.writeBigInt64LE(PX, 18); b.writeUInt32LE((index << 24 | seq) >>> 0, 26); b.writeUInt32LE(NONCE, 30);
    const at = now();
    open.set(seq, [intendedNs > at ? at : intendedNs, at]);           // sent early (inside the last ms): from the write
    sock.write(b);
    return true;
  };
  return {send, open, close: () => sock.end()};
}

const pct = (s, p) => s.length ? s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)] : 0;
const us = (ns) => (ns / 1000).toFixed(0);
const line = (tag, at, w) => {
  const i = w.intended.slice().sort((a, b) => a - b), a = w.actual.slice().sort((x, y) => x - y);
  return `${tag} at=${at} count=${i.length} rejected=${w.rejected} p50=${us(pct(i, 50))}us p90=${us(pct(i, 90))}us `
    + `p99=${us(pct(i, 99))}us p99.9=${us(pct(i, 99.9))}us max=${us(i[i.length - 1] || 0)}us | from-write p50=${us(pct(a, 50))}us `
    + `p99=${us(pct(a, 99))}us p99.9=${us(pct(a, 99.9))}us`;
};
const flushWindows = (all) => {
  const current = (now() + wallOffset) / winNs;
  for (const [k, w] of [...windows].sort((x, y) => (x[0] < y[0] ? -1 : 1))) {
    if (!all && k >= current - 1n) break;                              // a window is closed one window after it ends
    console.log(line('PROBE-WINDOW', new Date(Number(k * winNs / 1_000_000n)).toISOString(), w));
    windows.delete(k);
  }
};

const sessions = Array.from({length: cfg.sessions}, (_, i) => session(i));
await new Promise((res) => setTimeout(res, 2000));                     // connect
const periodNs = BigInt(Math.round(1e9 / cfg.rate));
const t0 = now();
measureFrom = t0 + BigInt(cfg.warmup) * 1_000_000_000n;
const end = measureFrom + BigInt(cfg.secs) * 1_000_000_000n;
console.log(`[probe] ${cfg.host}:${cfg.port} ${cfg.ticker}@${cfg.px} rate=${cfg.rate}/s sessions=${cfg.sessions} `
  + `warmup=${cfg.warmup}s measure=${cfg.secs}s window=${cfg.window}s nonce=${NONCE}`);
const report = setInterval(() => flushWindows(false), 5000);
let n = 0, skipped = 0;
for (let intended = t0; intended < end; intended += periodNs) {
  const waitMs = Number(intended - now()) / 1e6;
  if (waitMs > 1) await new Promise((res) => setTimeout(res, waitMs));
  if (!sessions[n++ % cfg.sessions].send(intended)) skipped++;
}
await new Promise((res) => setTimeout(res, 5000));                     // the in-flight tail
clearInterval(report);
flushWindows(true);
const unanswered = sessions.reduce((a, s) => a + s.open.size, 0);
console.log(line('PROBE-TOTAL', new Date().toISOString(), total)
  + ` ambiguous=${total.ambiguous} unanswered=${unanswered} skipped=${skipped} lostSessions=${total.lostSessions}`);
sessions.forEach((s) => s.close());
process.exit(unanswered || skipped || total.lostSessions ? 1 : 0);
