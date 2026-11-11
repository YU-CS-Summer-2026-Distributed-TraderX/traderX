// ADR-096 (FR-PRG-16): the production throughput run's binary load, one process per loadgen pod. Production-safe by
// construction:
// - test accounts only, its own (9900010–9900017): disjoint from the FIX load's (9900002–9900008, prepare.FIX_TEST_ACCOUNTS),
//   so a combined run's two loads never trade against each other's accounts;
// - the security id is resolved per run through the gateway's lookup-only /resolve (an unknown ticker is refused, never
//   registered);
// - every order is a limit of 1 at one operator-given price inside the collar;
// - book-neutral: at any moment buys and sells come from disjoint accounts (no self-trade cancels), so at one price the
//   book holds exactly accepted buys minus accepted sells of ours. Before each phase boundary every session stops,
//   waits for its answers and sends the difference on the other side until its counts are equal; at the boundary an
//   unbalanced session stops the pod (exit 1). Every pod balanced = the book is back.
// - position-bounded: phases are wall-clock aligned (--phase-secs, the same on every pod), and the account sets swap
//   roles each phase, so no account accumulates a position (each phase's peak: buys per account in one phase).
//
// Accounting: every clOrdId (runNonce:u32 | session:u8 | seq:u24) is tracked until its first ack. There is no resend:
// past the engine's idempotency window (256Ki keys, 0.65 s at 400K/s) a resend can book twice. A lost connection
// leaves its orders unanswered: reported, exit 1.
//
// --sequenced (ADR-090, FR-XOD-11; for a render with exactly-once on, never with BINARY_SEQUENCED=refuse): every NEW
// sets flags bit 0 and its clOrdId reads streamId:u32 (high) | seq:u32 (low). The engine keys a stream by
// (account, streamId), and a session trades 8 accounts, so each (session, account) has its own stream:
//   streamId = runNonce XOR (session << 3 | account index), never 0
// A fresh nonce gives every run fresh streams, from seq 1 (a reused stream would answer seq 1 as a duplicate). The
// ack's echoed id names the stream, and seq counts each stream's NEWs from 1.
// - DUPLICATE (riskReason 17) with orderRef 0 is a receipt: committed once before, its answer lost past the window
//   (FR-XOD-03). It is counted as duplicateReceipts, not as a reject or a failure.
// - OUT_OF_SEQUENCE (34) is a lost frame, and STREAM_LIMIT (35) a full stream table. Both are counted, and both fail
//   the run.
//
//   node binary-load.mjs --resolve http://GW:18110 --ticker MSFT --px 412.50 [--host order-matcher-gw-binary]
//        [--port 18140] [--sessions 16] [--rate N/s per session] [--secs S] [--max-in-flight 2000] [--out results.json]
//        [--phase-secs 60] [--pause-ms 3000] [--ramp 0.25,0.5,0.75 --step-secs 300] [--sequenced]
import fs from 'node:fs';
import net from 'node:net';
import crypto from 'node:crypto';

const SETS = [[9900010, 9900012, 9900014, 9900016], [9900011, 9900013, 9900015, 9900017]];   // set[role] buys, set[1-role] sells
const ACCOUNTS = SETS.flat();   // --sequenced: an account's index in its session's streams
const DUPLICATE = 17, OUT_OF_SEQUENCE = 34, STREAM_LIMIT = 35;   // RiskReason ordinals in the ack (FR-XOD-03/04/07)
const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  resolve: flag('--resolve'), ticker: flag('--ticker'), px: flag('--px'), host: flag('--host', 'order-matcher-gw-binary'),
  port: Number(flag('--port', 18140)), sessions: Number(flag('--sessions', 16)), rate: Number(flag('--rate', 1000)),
  secs: Number(flag('--secs', 60)), maxInFlight: Number(flag('--max-in-flight', 2000)), out: flag('--out', 'results.json'),
  phaseMs: Number(flag('--phase-secs', 60)) * 1000, pauseMs: Number(flag('--pause-ms', 3000)),
  sequenced: argv.includes('--sequenced'),
};
if (!cfg.resolve || !cfg.ticker || !/^\d+(\.\d{1,6})?$/.test(cfg.px ?? '')) {
  console.log('usage: --resolve URL --ticker T --px PRICE'); process.exit(2);
}
if (!(cfg.sessions >= 1 && cfg.sessions <= 256)) { console.log('--sessions is 1..256'); process.exit(2); }
// --ramp F1,F2,... --step-secs S (FR-PRG-17): the rate is F1 x --rate for the first S seconds, F2 x for the next S, ...,
// then the full rate, so a run records where the knee is instead of only failing at the target.
const RAMP = (flag('--ramp', '') || '').split(',').filter(Boolean).map(Number), STEP_MS = Number(flag('--step-secs', 300)) * 1000;
if (!RAMP.every((f) => f > 0 && f <= 1) || !(STEP_MS > 0)) { console.log('--ramp takes fractions in (0, 1]'); process.exit(2); }
const T0 = Date.now(), rampFactor = () => RAMP[Math.floor((Date.now() - T0) / STEP_MS)] ?? 1;
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
let sending = false, role = 0;
const GUARD_MS = 250;   // after a boundary, before sending: the other pods' clocks
if (!(cfg.pauseMs >= 200 && cfg.phaseMs >= 2 * cfg.pauseMs + GUARD_MS)) { console.log('--phase-secs must exceed 2 x --pause-ms'); process.exit(2); }

function session(index) {
  const s = {index, sent: 0, accepted: 0, rejected: 0, ambiguous: 0, protocolErrors: 0, duplicates: 0, unexpected: 0,
             duplicateReceipts: 0, outOfSequence: 0, streamLimit: 0,
             acceptedBuys: 0, acceptedSells: 0, balanceOrders: 0, rejectReasons: {}, closed: false};
  const open = new Map();            // key -> side, until the first ack: seq, or (sequenced) account index * 2^32 + its seq
  const streams = ACCOUNTS.map((_, a) => ((NONCE ^ (index << 3 | a)) >>> 0) || 1);   // --sequenced
  const streamOf = new Map(streams.map((id, a) => [id, a])), seqs = ACCOUNTS.map(() => 0);
  let seq = 0, buf = Buffer.alloc(0), credit = 0, up = false;
  const picks = [0, 0];   // per side: sides alternate with seq, so seq % n would give one side only half the set's accounts
  const sock = net.connect(cfg.port, cfg.host);
  sock.setNoDelay(true);
  sock.on('connect', () => { up = true; });
  sock.on('close', () => { up = false; s.closed = true; });
  sock.on('error', () => {});
  const frame = (side) => {
    if (++seq > 0xffffff && !cfg.sequenced) throw Error('session sequence exhausted');   // u24 in an unsequenced clOrdId
    const accounts = SETS[side === 0 ? role : 1 - role], b = Buffer.alloc(34), account = accounts[picks[side]++ % accounts.length];
    b.writeUInt16LE(32, 0); b[2] = 1; b[3] = side;
    b.writeUInt32LE(account, 6); b.writeUInt32LE(SECURITY, 10); b.writeInt32LE(1, 14); b.writeBigInt64LE(PX, 18);
    if (cfg.sequenced) {   // FR-XOD-11: flags bit 0; clOrdId = streamId (high) | seq (low), this account's stream
      const a = ACCOUNTS.indexOf(account), q = ++seqs[a];
      b[4] = 1; b.writeUInt32LE(q, 26); b.writeUInt32LE(streams[a], 30); open.set(a * 2 ** 32 + q, side);
    } else {               // flags 0
      b.writeUInt32LE((index << 24 | seq) >>> 0, 26); b.writeUInt32LE(NONCE, 30); open.set(seq, side);
    }
    s.sent++;
    return b;
  };
  sock.on('data', (chunk) => {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    for (; buf.length >= 18; buf = buf.subarray(18)) {
      if (buf.readUInt16LE(0) !== 16 || buf[2] !== 0x81) { s.protocolErrors++; sock.destroy(); return; }
      const status = buf[3], kind = buf[4], risk = buf[5], ref = buf.readUInt32LE(6), lo = buf.readUInt32LE(10), hi = buf.readUInt32LE(14);
      let q, sentUpTo;
      if (cfg.sequenced) {
        const a = streamOf.get(hi);
        if (a === undefined) { s.unexpected++; continue; }
        q = a * 2 ** 32 + lo; sentUpTo = lo >= 1 && lo <= seqs[a];
      } else {
        if (hi !== NONCE || lo >>> 24 !== index) { s.unexpected++; continue; }
        q = lo & 0xffffff; sentUpTo = q >= 1 && q <= seq;
      }
      const side = open.get(q);
      // Not open: a second answer if this session sent it (seq is per session, from 1), else not ours. No set of every
      // answered seq: at 2,000/s per session that grew past node's heap in 18 minutes (production 2026-10-29 11:04 pm).
      if (side === undefined) { sentUpTo ? s.duplicates++ : s.unexpected++; continue; }
      open.delete(q);
      if (status === 1) { s.accepted++; side === 0 ? s.acceptedBuys++ : s.acceptedSells++; }
      else if (status === 0 && risk === DUPLICATE && ref === 0) s.duplicateReceipts++;   // FR-XOD-03: a receipt
      else if (status === 0 && risk === OUT_OF_SEQUENCE) s.outOfSequence++;
      else if (status === 0 && risk === STREAM_LIMIT) s.streamLimit++;
      else if (status === 0) { s.rejected++; s.rejectReasons[`kind${kind}:risk${risk}`] = (s.rejectReasons[`kind${kind}:risk${risk}`] ?? 0) + 1; }
      else if (status === 2) s.ambiguous++;
      else s.protocolErrors++;
    }
  });
  const tick = setInterval(() => {
    if (!sending || !up) return;
    credit += cfg.rate * rampFactor() / 100;
    const frames = [];
    for (; credit >= 1 && open.size < cfg.maxInFlight; credit--) frames.push(frame(s.sent % 2));
    credit = Math.min(credit, 1);
    if (frames.length) sock.write(Buffer.concat(frames));
  }, 10);
  // Book-neutral: send the difference on the other side; a rejected balance order is retried on the next round.
  const balance = () => {
    const d = s.acceptedBuys - s.acceptedSells;
    if (d === 0 || open.size || !up || sending) return d;
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
               securityId: SECURITY, px: cfg.px, ramp: rampFactor(), sequenced: cfg.sequenced, ...Object.fromEntries(['sent', 'accepted', 'rejected', 'ambiguous',
               'protocolErrors', 'duplicates', 'unexpected', 'unanswered', 'acceptedBuys', 'acceptedSells', 'balanceOrders',
               'duplicateReceipts', 'outOfSequence', 'streamLimit'].map((k) => [k, sum(k)])), imbalance: sum('imbalance'),
               closedSessions: rows.filter((x) => x.closed && !final).length, sessions: rows};
  fs.writeFileSync(cfg.out, JSON.stringify(out, null, 2));
  console.log(`${out.at} sent=${out.sent} accepted=${out.accepted} rejected=${out.rejected} unanswered=${out.unanswered} `
    + `ambiguous=${out.ambiguous} dup=${out.duplicates} imbalance=${out.imbalance} balanceOrders=${out.balanceOrders}`);
  if (final) console.log('RESULT ' + JSON.stringify({...out, sessions: undefined}));   // the pod log carries it
  return out;
};
const progress = setInterval(() => report(false), 10_000);
const deadline = Date.now() + cfg.secs * 1000;
let phase = Math.floor(Date.now() / cfg.phaseMs);
const finish = (why) => {
  clearInterval(clock); clearInterval(progress); sessions.forEach((x) => { x.stopSending(); x.close(); });
  const o = report(true);
  if (why) console.log('STOPPED ' + why);
  process.exit(!why && o.unanswered === 0 && o.ambiguous === 0 && o.duplicates === 0 && o.unexpected === 0
    && o.protocolErrors === 0 && o.imbalance === 0 && o.outOfSequence === 0 && o.streamLimit === 0 ? 0 : 1);
};
const clock = setInterval(() => {   // send inside the phase; drain and balance in its pause; check at the boundary
  const now = Date.now(), k = Math.floor(now / cfg.phaseMs), off = now % cfg.phaseMs;
  if (k !== phase) {
    const bad = sessions.filter((x) => x.open.size || x.s.acceptedBuys !== x.s.acceptedSells).length;
    if (bad) return finish(`${bad} sessions not balanced at the phase boundary (pause too short for the answers)`);
    if (now >= deadline) return finish(null);
    phase = k;
  }
  role = k % 2;
  sending = off >= GUARD_MS && off < cfg.phaseMs - cfg.pauseMs && now < deadline;
  if (!sending) sessions.forEach((x) => x.balance());
}, 50);
