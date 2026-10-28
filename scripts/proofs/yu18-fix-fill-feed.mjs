// yu18-fix-fill-feed.mjs — the session half of yu18-fix-fill-feed.sh (YU18 fix-fill-reports, ADR-084).
// One FIX Sell rests; the counterparties come over REST, so every fill this session sees is unsolicited:
//   1. FR-FFR-04  a stranger's cross on another ticker sends this session nothing
//   2. FR-FFR-07  every /orders consumer on TRADERX_FEED is deleted, then a REST Buy 4 crosses the Sell:
//                 the fill is re-read and reported once (F / OrdStatus 1, LastQty 4, CumQty 4, LeavesQty 6)
//   3. FR-FFR-02/03  a REST Buy 6 fills the rest: F / OrdStatus 2, LastQty 6, CumQty 10, LeavesQty 0, AvgPx PX
//   4. FR-FFR-07  over a further 8 s exactly two fill reports for the order, and /orders consumers exist again
// Env: FIX_HOST FIX_PORT FIX_PASSWORD TICKER TICKER2 PX ACCT_F ACCT_R ACCT_X ACCT_Y GW BEARER_R BEARER_X BEARER_Y
//      NATS (host:port) JS (path to lib-jetstream.py)
import net from 'node:net';
import { execFileSync } from 'node:child_process';

const E = process.env;
const HOST = E.FIX_HOST || '127.0.0.1', PORT = Number(E.FIX_PORT || 18130);
const SENDER = E.SENDER || 'CLIENT1', TARGET = E.TARGET || 'TRADERX';
const TICKER = E.TICKER, PX = Number(E.PX);
const SOH = '\x01';
const ts = () => { const d = new Date().toISOString(); return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ok = (m) => console.log(`[ok] ${m}`);
const result = (k, v) => console.log(`RESULT ${k}=${v}`);
const fail = (m) => { console.log(`[FAIL] ${m}`); process.exit(1); };

class Session {
  constructor(sender, seq = 1) { this.sender = sender; this.seq = seq; this.rx = []; this.buf = ''; this.closed = false; }
  frame(type, fields) {
    const b = [`35=${type}`, `34=${this.seq++}`, `49=${this.sender}`, `56=${TARGET}`, `52=${ts()}`, ...fields].join(SOH) + SOH;
    const s = `8=FIX.4.4${SOH}9=${b.length}${SOH}${b}`;
    let sum = 0; for (let i = 0; i < s.length; i++) sum += s.charCodeAt(i);
    return s + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
  }
  send(type, fields) { this.sock.write(this.frame(type, fields)); }
  open() {
    return new Promise((res, rej) => {
      this.sock = net.createConnection({ host: HOST, port: PORT }, res);
      this.sock.setEncoding('ascii');
      this.sock.on('error', (e) => { this.closed = true; rej(e); });
      this.sock.on('close', () => { this.closed = true; });
      this.sock.on('data', (c) => {
        this.buf += c; let i;
        while ((i = this.buf.indexOf(`${SOH}10=`)) >= 0) {
          const end = this.buf.indexOf(SOH, i + 1); if (end < 0) break;
          const raw = this.buf.slice(0, end + 1); this.buf = this.buf.slice(end + 1);
          const m = Object.fromEntries(raw.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
          m.t = Date.now(); this.rx.push(m);
          if (m['35'] === '1') this.send('0', [`112=${m['112']}`]);
        }
      });
    });
  }
  wait(pred, label, ms = 20000) {
    return new Promise((res, rej) => {
      const t0 = Date.now();
      const iv = setInterval(() => {
        const m = this.rx.find(pred);
        if (m) { clearInterval(iv); res(m); } else if (Date.now() - t0 > ms) { clearInterval(iv); rej(new Error(`timeout waiting for ${label}`)); }
      }, 50);
    });
  }
  async logon(password) { await this.open(); this.send('A', ['98=0', '108=30', '141=Y', `554=${password}`]); }
  close() { try { this.sock.destroy(); } catch { /* closed */ } }
}
const rest = async (acct, bearer, side, qty, px, ticker = TICKER) => {
  const r = await fetch(`${E.GW}/orders`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${bearer}` },
    body: JSON.stringify({ accountId: Number(acct), ticker, side, quantity: qty, limitPrice: px }) });
  const t = await r.text(); if (r.status !== 200 || !/"kind":\s*[14]/.test(t)) fail(`REST ${side} ${qty}@${px} ${ticker} for ${acct}: ${r.status} ${t.slice(0, 160)}`);
  return t;
};
const js = (...a) => execFileSync('python3', [E.JS, a[0], E.NATS, 'TRADERX_FEED', ...a.slice(1)]).toString().trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
// The gateways' fill feed is JetStream ORDERED consumers (ephemeral). trade-processor's durable /orders
// consumer is the read model's, not the gateways': deleting it stops the order projection, so it is spared.
const ordersConsumers = () => js('consumers').filter((c) => !c.durable && c.filters.some((f) => f === '/orders' || f === '/orders.*' || f === '/orders.>'));

const s = new Session(SENDER);
await s.logon(E.FIX_PASSWORD);
await s.wait((m) => m['35'] === 'A', 'Logon', 10000).catch((e) => fail(`${e.message} (FR-ERH-S3 password)`));
result('logon_ok', 1);
const CL = `ffd-${Date.now()}`;
s.send('D', [`11=${CL}`, `1=${E.ACCT_F}`, `55=${TICKER}`, '54=2', '38=10', '40=2', `44=${PX.toFixed(2)}`, `60=${ts()}`]);
const n1 = await s.wait((m) => m['35'] === '8' && m['11'] === CL, `New for ${CL}`).catch((e) => fail(e.message));
if (n1['39'] !== '0') fail(`resting Sell answered OrdStatus=${n1['39']} ${n1['58'] || ''}, want New`);
ok(`FIX Sell 10 @ ${PX} rests as ${n1['37']}`);

// 1. A stranger's cross sends this session nothing.
const before = s.rx.length;
await rest(E.ACCT_X, E.BEARER_X, 'Sell', 3, PX, E.TICKER2);
await rest(E.ACCT_Y, E.BEARER_Y, 'Buy', 3, PX, E.TICKER2);
await sleep(5000);
const stray = s.rx.slice(before).filter((m) => m['35'] === '8');
if (stray.length) fail(`FR-FFR-04: a cross between ${E.ACCT_X} and ${E.ACCT_Y} on ${E.TICKER2} reached this session: ${JSON.stringify(stray.map((m) => [m['11'], m['150'], m['39']]))}`);
ok(`FR-FFR-04: a stranger's cross on ${E.TICKER2} sent this session nothing in 5 s`);

// 2. Delete every /orders consumer, then cross 4.
const gone = ordersConsumers();
if (!gone.length) fail('no /orders consumer on TRADERX_FEED: no gateway reads the fill feed from the stream (FR-FFR-07, FIX_FILL_FEED=jetstream)');
for (const c of gone) js('cdelete', c.name);
result('consumers_deleted', gone.length);
await rest(E.ACCT_R, E.BEARER_R, 'Buy', 4, PX);
const f1 = await s.wait((m) => m['35'] === '8' && m['11'] === CL && m['150'] === 'F', 'first fill report', 30000)
  .catch(() => fail(`FR-FFR-07: after deleting ${gone.length} /orders consumer(s), the fill of 4 never reached the session in 30 s`));
const want1 = { 39: '1', 32: 4, 31: PX, 14: 4, 151: 6, 6: PX, 37: n1['37'], 55: TICKER, 54: '2', 38: 10 };
for (const [k, v] of Object.entries(want1)) if (typeof v === 'number' ? Math.abs(Number(f1[k]) - v) > 1e-9 : f1[k] !== v) fail(`FR-FFR-02/03 partial fill: tag ${k}=${f1[k]}, want ${v} (${JSON.stringify(f1)})`);
ok(`FR-FFR-07/02/03: after ${gone.length} consumer(s) deleted, F / PartiallyFilled LastQty 4 @ ${PX}, CumQty 4, LeavesQty 6`);

// 3. The rest.
await rest(E.ACCT_R, E.BEARER_R, 'Buy', 6, PX);
const f2 = await s.wait((m) => m['35'] === '8' && m['11'] === CL && m['150'] === 'F' && m['39'] === '2', 'final fill report', 30000)
  .catch(() => fail('FR-FFR-02: the fill of the last 6 never reached the session'));
const want2 = { 32: 6, 31: PX, 14: 10, 151: 0, 6: PX };
for (const [k, v] of Object.entries(want2)) if (Math.abs(Number(f2[k]) - v) > 1e-9) fail(`FR-FFR-03 final fill: tag ${k}=${f2[k]}, want ${v}`);
ok(`FR-FFR-02/03: F / Filled LastQty 6, CumQty 10, LeavesQty 0, AvgPx ${f2['6']}`);

// 4. Exactly once, and the feed is back.
await sleep(8000);
const fills = s.rx.filter((m) => m['35'] === '8' && m['11'] === CL && m['150'] === 'F');
if (fills.length !== 2) fail(`FR-FFR-07: ${fills.length} fill reports for ${CL}, want exactly 2 (${JSON.stringify(fills.map((m) => [m['32'], m['14'], m['34']]))})`);
const ids = new Set(fills.map((m) => m['17']));
if (ids.size !== 2) fail(`FR-GFS-07: ExecID repeated across the two fills (${[...ids]})`);
const back = ordersConsumers();
if (!back.length) fail('FR-FFR-07: no /orders consumer exists again 8 s after the deletion');
ok(`exactly 2 fill reports, distinct ExecIDs ${[...ids].join(', ')}; ${back.length} /orders consumer(s) recreated`);
result('fills', fills.length);
s.send('5', []); await sleep(300); s.close();
process.exit(0);
