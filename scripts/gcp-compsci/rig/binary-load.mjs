// ADR-096 (FR-PRG-16): the production throughput run's binary load, one process per loadgen pod. Production-safe by
// construction:
// - test accounts only: buys from 9900002/4/6/8, sells from 9900003/5/7 (prepare.FIX_TEST_ACCOUNTS); any other is refused;
// - the security id is resolved per run through the gateway's lookup-only /resolve (an unknown ticker is refused, never
//   registered);
// - every order is a limit of 1 at one operator-given price inside the collar;
// - book-neutral: buy and sell accounts are disjoint (no self-trade cancels), so at one price the book holds exactly
//   accepted buys minus accepted sells of ours. At the end the client sends the difference on the other side until
//   its own counts are equal, so a pod that exits 0 leaves nothing resting. Every pod balanced = the book is back.
//
// Accounting: every clOrdId (runNonce:u32 | session:u8 | seq:u24) is tracked until its first ack. There is no resend:
// past the engine's idempotency window (256Ki keys, 0.65 s at 400K/s) a resend can book twice, and the members of
// rollout 5a cannot read a sequenced frame (the gateway refuses it, BINARY_SEQUENCED=refuse). A lost connection
// leaves its orders unanswered: reported, exit 1.
//
//   node binary-load.mjs --resolve http://GW:18110 --ticker MSFT --px 412.50 [--host order-matcher-gw-binary]
//        [--port 18140] [--sessions 16] [--rate N/s per session] [--secs S] [--max-in-flight 2000] [--out results.json]
import fs from 'node:fs';
import net from 'node:net';
import crypto from 'node:crypto';

export const BUY_ACCOUNTS = [9900002, 9900004, 9900006, 9900008], SELL_ACCOUNTS = [9900003, 9900005, 9900007];
const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  resolve: flag('--resolve'), ticker: flag('--ticker'), px: flag('--px'), host: flag('--host', 'order-matcher-gw-binary'),
  port: Number(flag('--port', 18140)), sessions: Number(flag('--sessions', 16)), rate: Number(flag('--rate', 1000)),
  secs: Number(flag('--secs', 60)), maxInFlight: Number(flag('--max-in-flight', 2000)), out: flag('--out', 'results.json'),
  drainSecs: Number(flag('--drain-secs', 30)),
};
if (!cfg.resolve || !cfg.ticker || !/^\d+(\.\d{1,6})?$/.test(cfg.px ?? '')) {
  console.log('usage: --resolve URL --ticker T --px PRICE'); process.exit(2);
}
if (!(cfg.sessions >= 1 && cfg.sessions <= 256)) { console.log('--sessions is 1..256'); process.exit(2); }
const [whole, frac = ''] = cfg.px.split('.');
const PX = BigInt(whole) * 1000000n + BigInt(frac.padEnd(6, '0'));   // limitPx x1e6

const r = await fetch(`${cfg.resolve}/resolve`, {method: 'POST', headers: {'content-type': 'application/json'},
  body: JSON.stringify({ticker: cfg.ticker}), signal: AbortSignal.timeout(5000)});
const resolved = r.status === 200 ? await r.json() : null;
if (!Number.isInteger(resolved?.securityId) || resolved.securityId < 0) {
  console.log(`refused: ${cfg.ticker} does not resolve (${r.status})`); process.exit(2);
}
const SECURITY = resolved.securityId;
const NONCE = crypto.randomBytes(4).readUInt32LE(0) || 1;
const started = new Date();
let sending = true;

function session(index) {
  const s = {index, sent: 0, accepted: 0, rejected: 0, ambiguous: 0, protocolErrors: 0, duplicates: 0, unexpected: 0,
             acceptedBuys: 0, acceptedSells: 0, balanceOrders: 0, rejectReasons: {}, closed: false};
  const open = new Map();            // seq -> side, until the first ack
  const answered = new Set();
  let seq = 0, buf = Buffer.alloc(0), credit = 0, up = false;
  const sock = net.connect(cfg.port, cfg.host);
  sock.setNoDelay(true);
  sock.on('connect', () => { up = true; });
  sock.on('close', () => { up = false; s.closed = true; });
  sock.on('error', () => {});
  const frame = (side) => {
    if (++seq > 0xffffff) throw Error('session sequence exhausted');
    const accounts = side === 0 ? BUY_ACCOUNTS : SELL_ACCOUNTS, b = Buffer.alloc(34);
    b.writeUInt16LE(32, 0); b[2] = 1; b[3] = side;   // flags 0: never sequenced
    b.writeUInt32LE(accounts[seq % accounts.length], 6); b.writeUInt32LE(SECURITY, 10); b.writeInt32LE(1, 14);
    b.writeBigInt64LE(PX, 18); b.writeUInt32LE((index << 24 | seq) >>> 0, 26); b.writeUInt32LE(NONCE, 30);
    open.set(seq, side); s.sent++;
    return b;
  };
  sock.on('data', (chunk) => {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    for (; buf.length >= 18; buf = buf.subarray(18)) {
      if (buf.readUInt16LE(0) !== 16 || buf[2] !== 0x81) { s.protocolErrors++; sock.destroy(); return; }
      const status = buf[3], kind = buf[4], risk = buf[5], lo = buf.readUInt32LE(10), hi = buf.readUInt32LE(14);
      const q = lo & 0xffffff;
      if (hi !== NONCE || lo >>> 24 !== index) { s.unexpected++; continue; }
      if (answered.has(q)) { s.duplicates++; continue; }
      const side = open.get(q);
      if (side === undefined) { s.unexpected++; continue; }
      open.delete(q); answered.add(q);
      if (status === 1) { s.accepted++; side === 0 ? s.acceptedBuys++ : s.acceptedSells++; }
      else if (status === 0) { s.rejected++; s.rejectReasons[`kind${kind}:risk${risk}`] = (s.rejectReasons[`kind${kind}:risk${risk}`] ?? 0) + 1; }
      else if (status === 2) s.ambiguous++;
      else s.protocolErrors++;
    }
  });
  const tick = setInterval(() => {
    if (!sending || !up) return;
    credit += cfg.rate / 100;
    const frames = [];
    for (; credit >= 1 && open.size < cfg.maxInFlight; credit--) frames.push(frame(s.sent % 2));
    credit = Math.min(credit, 1);
    if (frames.length) sock.write(Buffer.concat(frames));
  }, 10);
  // Book-neutral: send the difference on the other side; a rejected balance order is retried on the next round.
  const balance = () => {
    const d = s.acceptedBuys - s.acceptedSells;
    if (d === 0 || open.size || !up) return d;
    const frames = Array.from({length: Math.min(Math.abs(d), cfg.maxInFlight)}, () => frame(d > 0 ? 1 : 0));
    s.balanceOrders += frames.length; sock.write(Buffer.concat(frames));
    return d;
  };
  return {s, open, stopSending: () => clearInterval(tick), balance, close: () => sock.end()};
}

const sessions = Array.from({length: cfg.sessions}, (_, i) => session(i));
const report = (final) => {
  const rows = sessions.map(({s, open}) => ({...s, unanswered: open.size, imbalance: s.acceptedBuys - s.acceptedSells}));
  const sum = (k) => rows.reduce((a, x) => a + x[k], 0);
  const out = {nonce: NONCE, startedAt: started.toISOString(), at: new Date().toISOString(), final, ticker: cfg.ticker,
               securityId: SECURITY, px: cfg.px, ...Object.fromEntries(['sent', 'accepted', 'rejected', 'ambiguous',
               'protocolErrors', 'duplicates', 'unexpected', 'unanswered', 'acceptedBuys', 'acceptedSells', 'balanceOrders'].map((k) => [k, sum(k)])), imbalance: sum('imbalance'),
               closedSessions: rows.filter((x) => x.closed && !final).length, sessions: rows};
  fs.writeFileSync(cfg.out, JSON.stringify(out, null, 2));
  console.log(`${out.at} sent=${out.sent} accepted=${out.accepted} rejected=${out.rejected} unanswered=${out.unanswered} `
    + `ambiguous=${out.ambiguous} dup=${out.duplicates} imbalance=${out.imbalance} balanceOrders=${out.balanceOrders}`);
  if (final) console.log('RESULT ' + JSON.stringify({...out, sessions: undefined}));   // the pod log carries it
  return out;
};
const progress = setInterval(() => report(false), 10_000);
setTimeout(() => {   // stop, wait for every answer, balance each session to zero, then close
  sending = false; sessions.forEach((x) => x.stopSending());
  const t0 = Date.now();
  const wait = setInterval(() => {
    const pending = sessions.reduce((a, x) => a + x.open.size, 0);
    const left = sessions.reduce((a, x) => a + Math.abs(x.balance()), 0);
    if ((pending === 0 && left === 0) || Date.now() - t0 > cfg.drainSecs * 1000) {
      clearInterval(wait); clearInterval(progress); sessions.forEach((x) => x.close());
      const o = report(true);
      const ok = o.unanswered === 0 && o.ambiguous === 0 && o.duplicates === 0 && o.unexpected === 0
        && o.protocolErrors === 0 && o.imbalance === 0;
      process.exit(ok ? 0 : 1);
    }
  }, 200);
}, cfg.secs * 1000);
