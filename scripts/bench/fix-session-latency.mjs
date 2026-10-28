// fix-session-latency.mjs — what a FIX session store costs (ADR-086). No deps.
// One FIX session on one gateway (FIX_PORT). Measures NewOrderSingle -> its first ExecutionReport:
//   1. SEQ: N orders one at a time (send, wait for the answer, next): the per-order round trip.
//   2. BURST: M orders written back to back, then wait for every answer: what one session sustains when a
//      client fires faster than the gateway answers (QuickFIX/J works a session's messages in order).
// Orders alternate a resting Sell 1 and a crossing Buy 1 at PX on TICKER, so the book stays empty.
// Prints `RESULT <phase> n= p50_us= p90_us= p99_us= max_us= [rate_per_s=]`.
// Run it INSIDE the cluster (a pod beside the gateways): a port-forward from a laptop measures the WAN.
// Usage: [FIX_HOST=<pod IP>] FIX_PORT= FIX_PASSWORD= TICKER= PX= ACCT_A= ACCT_B= [N=1000 M=1000] node fix-session-latency.mjs
import net from 'node:net';

const E = process.env;
const HOST = E.FIX_HOST || '127.0.0.1', PORT = Number(E.FIX_PORT), PASSWORD = E.FIX_PASSWORD, TICKER = E.TICKER, PX = Number(E.PX);
const A = E.ACCT_A, B = E.ACCT_B, N = Number(E.N || 1000), M = Number(E.M || 1000);
if (!PORT || !PASSWORD || !TICKER || !PX || !A || !B) { console.log('need FIX_PORT FIX_PASSWORD TICKER PX ACCT_A ACCT_B'); process.exit(2); }
const SOH = '\x01';
const ts = () => { const d = new Date().toISOString(); return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`; };

let seq = 1, buf = '';
const waiting = new Map();   // ClOrdID -> resolve(answerTimeNs)
const sock = net.createConnection({ host: HOST, port: PORT });
sock.setNoDelay(true);
sock.setEncoding('ascii');
let logon;
const logged = new Promise((r) => { logon = r; });
sock.on('data', (c) => {
  const now = process.hrtime.bigint();
  buf += c; let i;
  while ((i = buf.indexOf(`${SOH}10=`)) >= 0) {
    const end = buf.indexOf(SOH, i + 1); if (end < 0) break;
    const raw = buf.slice(0, end + 1); buf = buf.slice(end + 1);
    const m = Object.fromEntries(raw.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
    if (m['35'] === 'A') logon();
    if (m['35'] === '1') send('0', [`112=${m['112']}`]);
    if (m['35'] === '5') { console.log(`Logout: ${m['58'] || ''}`); process.exit(1); }
    if (m['35'] === '8' && waiting.has(m['11'])) { waiting.get(m['11'])(now); waiting.delete(m['11']); }
  }
});
sock.on('close', () => { if (waiting.size) { console.log(`connection closed with ${waiting.size} unanswered`); process.exit(1); } });
function frame(type, fields) {
  const b = [`35=${type}`, `34=${seq++}`, '49=CLIENT1', '56=TRADERX', `52=${ts()}`, ...fields].join(SOH) + SOH;
  const s = `8=FIX.4.4${SOH}9=${b.length}${SOH}${b}`;
  let sum = 0; for (let i = 0; i < s.length; i++) sum += s.charCodeAt(i);
  return s + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
}
function send(type, fields) { sock.write(frame(type, fields)); }
const tag = `lat${Date.now() % 1e9}`;
function order(k) {
  const cl = `${tag}-${k}`, sell = k % 2 === 0;
  const answered = new Promise((r) => waiting.set(cl, r));
  const t0 = process.hrtime.bigint();
  send('D', [`11=${cl}`, `1=${sell ? B : A}`, `55=${TICKER}`, `54=${sell ? 2 : 1}`, '38=1', '40=2', `44=${PX.toFixed(2)}`, `60=${ts()}`]);
  return answered.then((t1) => Number(t1 - t0) / 1000);
}
function stats(name, us, extra = '') {
  us.sort((x, y) => x - y);
  const q = (p) => us[Math.min(us.length - 1, Math.floor(p * us.length))].toFixed(0);
  console.log(`RESULT ${name} n=${us.length} p50_us=${q(0.5)} p90_us=${q(0.9)} p99_us=${q(0.99)} max_us=${us[us.length - 1].toFixed(0)}${extra}`);
}

await new Promise((r) => sock.on('connect', r));
send('A', ['98=0', '108=30', '141=Y', `554=${PASSWORD}`]);
await Promise.race([logged, new Promise((_, j) => setTimeout(() => j(new Error('no Logon')), 15000))]);
for (let k = 0; k < 20; k++) await order(500000 + k);   // warm-up, discarded
const seqUs = [];
for (let k = 0; k < N; k++) seqUs.push(await order(1000 + k));
stats('seq', seqUs);
const t0 = process.hrtime.bigint();
const burst = [];
for (let k = 0; k < M; k++) burst.push(order(100000 + k));
const burstUs = await Promise.all(burst);
const wallS = Number(process.hrtime.bigint() - t0) / 1e9;
stats('burst', burstUs, ` rate_per_s=${(M / wallS).toFixed(0)}`);
send('5', []);
setTimeout(() => process.exit(0), 300);
