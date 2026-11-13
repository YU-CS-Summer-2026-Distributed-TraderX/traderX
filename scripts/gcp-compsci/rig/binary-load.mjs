// ADR-096 (FR-PRG-16): the production throughput run's binary load, one process per loadgen pod. Production-safe by
// construction:
// - test accounts only, its own (9900010–9900017): disjoint from the FIX load's (9900002–9900008, prepare.FIX_TEST_ACCOUNTS),
//   so a combined run's two loads never trade against each other's accounts;
// - the security id is resolved per run through the gateway's lookup-only /resolve (an unknown ticker is refused, never
//   registered);
// - every order is a limit of 1 at one operator-given price inside the collar;
// - book-neutral: at any moment buys and sells come from disjoint accounts (no self-trade cancels), so at one price the
//   book holds exactly accepted buys minus accepted sells of ours. Before each phase boundary every session stops,
//   waits for its answers and sends the difference on the other side until its counts are equal. A session still
//   unbalanced at the boundary (an answer later than the pause: a slow NATS server, a leader change) sits out the next
//   phase: it sends no new flow, only its answers and its balance, and is counted (satOut). A session still unbalanced
//   at the boundary after that stops the pod (exit 1). Every pod balanced = the book is back. 2026-11-12: one answer
//   later than the 2 s pause during a NATS write throttle stopped a pod, and its Job then stopped the other seven.
// - position-bounded: phases are wall-clock aligned (--phase-secs, the same on every pod), and the account sets swap
//   roles each phase, so no account accumulates a position (each phase's peak: buys per account in one phase).
//
// Accounting: every clOrdId (runNonce:u32 | session:u8 | seq:u24) is tracked until its first ack. There is no resend:
// past the engine's idempotency window (256Ki keys, 0.65 s at 400K/s) a resend can book twice. A lost connection
// leaves its orders unanswered: reported, exit 1.
//
// Sequenced by default (ADR-090, FR-XOD-11, yaakov 2026-11-10: "Turn on exactly-once"): every NEW sets flags bit 0
// and its clOrdId reads streamId:u32 (high) | seq:u32 (low). --unsequenced sends the old unflagged frame, for members
// older than ADR-090 (BINARY_SEQUENCED=refuse). The engine keys a stream by (account, streamId), and a session trades
// 8 accounts, so each (pod, session, account) has its own stream, the same in every run:
//   streamId = 0x40000000 | pod << 11 | session << 3 | account index      (pod: the Job's JOB_COMPLETION_INDEX)
// Stable, not fresh. The engine holds 8,192 streams (EXACTLY_ONCE_MAX_STREAMS) and evicts one only after an idle hour
// (FR-XOD-07). A fresh stream per run filled it within an hour on 2026-11-11: three runs of 8 x 64, and the last pod
// got STREAM_LIMIT, then 19.7M OUT_OF_SEQUENCE. With stable streams the rig holds pods x sessions x 8 for good.
// So a stream may already have a past: before its first real NEW each stream is probed with seq 2^31. The engine
// answers a gap OUT_OF_SEQUENCE(e) and changes nothing (FR-XOD-04), and the stream continues from e: 1 if new, or
// where an earlier run left it. A session sends nothing until its 8 probes are answered. One binary load at a time
// on an epoch: two would interleave on the same streams.
// Result: probes (each answered OUT_OF_SEQUENCE as expected, never a failure), streamsNew, streamsResumed, and streams
// (this pod's, against streamCap: the engine's EXACTLY_ONCE_MAX_STREAMS, --stream-cap). Any other answer to a probe,
// STREAM_LIMIT included, fails the run.
// - DUPLICATE (riskReason 17) cannot happen without a resend, and this load never resends: a frame reached the engine
//   twice (the gateway or the transport). It is counted as sequencedDuplicates and fails the run.
// - OUT_OF_SEQUENCE (34) is a lost frame, and STREAM_LIMIT (35) a full stream table. Both are counted, and both fail
//   the run.
//
//   node binary-load.mjs --resolve http://GW:18110 --ticker MSFT --px 412.50 [--host order-matcher-gw-binary]
//        [--port 18140] [--sessions 16] [--rate N/s per session] [--secs S] [--max-in-flight 2000] [--out results.json]
//        [--phase-secs 60] [--pause-ms 3000] [--ramp 0.25,0.5,0.75 --step-secs 300] [--unsequenced]
import fs from 'node:fs';
import net from 'node:net';
import crypto from 'node:crypto';

const SETS = [[9900010, 9900012, 9900014, 9900016], [9900011, 9900013, 9900015, 9900017]];   // set[role] buys, set[1-role] sells
const ACCOUNTS = SETS.flat();   // sequenced: an account's index in its session's streams
const DUPLICATE = 17, OUT_OF_SEQUENCE = 34, STREAM_LIMIT = 35;   // RiskReason ordinals in the ack (FR-XOD-03/04/07)
const PROBE = 2 ** 31;   // far above any real seq: answered OUT_OF_SEQUENCE(next), no state changed
const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  resolve: flag('--resolve'), ticker: flag('--ticker'), px: flag('--px'), host: flag('--host', 'order-matcher-gw-binary'),
  port: Number(flag('--port', 18140)), sessions: Number(flag('--sessions', 16)), rate: Number(flag('--rate', 1000)),
  secs: Number(flag('--secs', 60)), maxInFlight: Number(flag('--max-in-flight', 2000)), out: flag('--out', 'results.json'),
  phaseMs: Number(flag('--phase-secs', 60)) * 1000, pauseMs: Number(flag('--pause-ms', 3000)),
  sequenced: !argv.includes('--unsequenced'),
  pod: Number(process.env.JOB_COMPLETION_INDEX ?? flag('--pod-index', 0)),
  streamCap: Number(flag('--stream-cap', 8192)),   // the engine's EXACTLY_ONCE_MAX_STREAMS, for the report
};
if (!cfg.resolve || !cfg.ticker || !/^\d+(\.\d{1,6})?$/.test(cfg.px ?? '')) {
  console.log('usage: --resolve URL --ticker T --px PRICE'); process.exit(2);
}
if (!(cfg.sessions >= 1 && cfg.sessions <= 256)) { console.log('--sessions is 1..256'); process.exit(2); }
if (!(Number.isInteger(cfg.pod) && cfg.pod >= 0 && cfg.pod < 2 ** 19)) { console.log('the pod index is 0..2^19-1'); process.exit(2); }
// --ramp F1,F2,... --step-secs S (FR-PRG-17): the rate is F1 x --rate for the first S seconds, F2 x for the next S, ...,
// then the full rate, so a run records where the knee is instead of only failing at the target.
const RAMP = (flag('--ramp', '') || '').split(',').filter(Boolean).map(Number), STEP_MS = Number(flag('--step-secs', 300)) * 1000;
if (!RAMP.every((f) => f > 0 && f <= 1) || !(STEP_MS > 0)) { console.log('--ramp takes fractions in (0, 1]'); process.exit(2); }
const T0 = Date.now(), rampFactor = () => RAMP[Math.floor((Date.now() - T0) / STEP_MS)] ?? 1;
const [whole, frac = ''] = cfg.px.split('.');
const PX = BigInt(whole) * 1000000n + BigInt(frac.padEnd(6, '0'));   // limitPx x1e6

// A pod on a node the autoscaler has just added can time out its first request before the node's network is up
// (2026-11-10): retry the resolve, which sends no order, for up to a minute instead of dying on it.
let r;
for (let attempt = 1; ; attempt++) {
  try {
    r = await fetch(`${cfg.resolve}/resolve`, {method: 'POST', headers: {'content-type': 'application/json'},
      body: JSON.stringify({ticker: cfg.ticker}), signal: AbortSignal.timeout(5000)});
    break;
  } catch (e) {
    if (attempt >= 12) throw e;
    console.log(`resolve attempt ${attempt} failed (${e.name}); retrying`);
    await new Promise((ok) => setTimeout(ok, 5000));
  }
}
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
             sequencedDuplicates: 0, outOfSequence: 0, streamLimit: 0, probes: 0, streamsNew: 0, streamsResumed: 0,
             acceptedBuys: 0, acceptedSells: 0, balanceOrders: 0, satOut: 0, rejectReasons: {}, closed: false};
  const open = new Map();            // key -> side, until the first ack: seq, or (sequenced) account index * 2^32 + its seq
  const streams = ACCOUNTS.map((_, a) => (0x40000000 | cfg.pod << 11 | index << 3 | a) >>> 0);   // sequenced
  const streamOf = new Map(streams.map((id, a) => [id, a])), seqs = ACCOUNTS.map(() => 0);
  const probing = new Set();
  let ready = !cfg.sequenced;   // sequenced: once every stream's probe is answered
  let seq = 0, buf = Buffer.alloc(0), credit = 0, up = false, benched = false;   // benched: sitting this phase out
  const picks = [0, 0];   // per side: sides alternate with seq, so seq % n would give one side only half the set's accounts
  const sock = net.connect(cfg.port, cfg.host);
  sock.setNoDelay(true);
  sock.on('connect', () => {
    up = true;
    if (!cfg.sequenced) return;
    sock.write(Buffer.concat(ACCOUNTS.map((account, a) => {   // each stream's probe: where does it continue?
      const b = Buffer.alloc(34); probing.add(a);
      b.writeUInt16LE(32, 0); b[2] = 0x11; b[3] = 0; b[4] = 1;   // ADR-105: NEW is type 0x11
      b.writeUInt32LE(account, 6); b.writeUInt32LE(SECURITY, 10); b.writeInt32LE(1, 14); b.writeBigInt64LE(PX, 18);
      b.writeUInt32LE(PROBE, 26); b.writeUInt32LE(streams[a], 30);
      return b;
    })));
  });
  sock.on('close', () => { up = false; s.closed = true; });
  sock.on('error', () => {});
  const frame = (side) => {
    if (++seq > 0xffffff && !cfg.sequenced) throw Error('session sequence exhausted');   // u24 in an unsequenced clOrdId
    const accounts = SETS[side === 0 ? role : 1 - role], b = Buffer.alloc(34), account = accounts[picks[side]++ % accounts.length];
    b.writeUInt16LE(32, 0); b[2] = 0x11; b[3] = side;
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
    // ADR-105: the ack is type 0x82, 20 bytes, its ref a u64 (refs start at 2^32 + 1; exact as a Number below 2^53)
    for (; buf.length >= 22; buf = buf.subarray(22)) {
      if (buf.readUInt16LE(0) !== 20 || buf[2] !== 0x82) { s.protocolErrors++; sock.destroy(); return; }
      const status = buf[3], kind = buf[4], risk = buf[5], ref = Number(buf.readBigUInt64LE(6)), lo = buf.readUInt32LE(14), hi = buf.readUInt32LE(18);
      let q, sentUpTo;
      if (cfg.sequenced) {
        const a = streamOf.get(hi);
        if (a === undefined) { s.unexpected++; continue; }
        if (lo === PROBE && probing.delete(a)) {
          if (status === 0 && risk === OUT_OF_SEQUENCE && ref >= 1) { s.probes++; seqs[a] = ref - 1; ref === 1 ? s.streamsNew++ : s.streamsResumed++; }
          else if (status === 0 && risk === STREAM_LIMIT) s.streamLimit++;   // the table is full: this stream never opens
          else s.protocolErrors++;
          if (probing.size === 0 && !s.streamLimit && !s.protocolErrors) ready = true;
          continue;
        }
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
      else if (status === 0 && risk === DUPLICATE && ref === 0 && cfg.sequenced) s.sequencedDuplicates++;   // never resent: a frame twice
      else if (status === 0 && risk === OUT_OF_SEQUENCE) s.outOfSequence++;
      else if (status === 0 && risk === STREAM_LIMIT) s.streamLimit++;
      else if (status === 0) { s.rejected++; s.rejectReasons[`kind${kind}:risk${risk}`] = (s.rejectReasons[`kind${kind}:risk${risk}`] ?? 0) + 1; }
      else if (status === 2) s.ambiguous++;
      else s.protocolErrors++;
    }
  });
  const tick = setInterval(() => {
    if (!sending || benched || !up || !ready) return;
    credit += cfg.rate * rampFactor() / 100;
    const frames = [];
    for (; credit >= 1 && open.size < cfg.maxInFlight; credit--) frames.push(frame(s.sent % 2));
    credit = Math.min(credit, 1);
    if (frames.length) sock.write(Buffer.concat(frames));
  }, 10);
  // Book-neutral: send the difference on the other side; a rejected balance order is retried on the next round.
  const balance = () => {
    const d = s.acceptedBuys - s.acceptedSells;
    if (d === 0 || open.size || !up || !ready || (sending && !benched)) return d;
    const frames = Array.from({length: Math.min(Math.abs(d), cfg.maxInFlight)}, () => frame(d > 0 ? 1 : 0));
    s.balanceOrders += frames.length; sock.write(Buffer.concat(frames));
    return d;
  };
  return {s, open, stopSending: () => clearInterval(tick), balance, close: () => sock.end(),
          benched: () => benched, bench: (v) => { benched = v; }};
}

const sessions = Array.from({length: cfg.sessions}, (_, i) => session(i));
const report = (final) => {
  const rows = sessions.map(({s, open}) => ({...s, unanswered: open.size, imbalance: s.acceptedBuys - s.acceptedSells}));
  const sum = (k) => rows.reduce((a, x) => a + x[k], 0);
  const out = {nonce: NONCE, startedAt: started.toISOString(), at: new Date().toISOString(), final, ticker: cfg.ticker,
               securityId: SECURITY, px: cfg.px, ramp: rampFactor(), sequenced: cfg.sequenced, ...Object.fromEntries(['sent', 'accepted', 'rejected', 'ambiguous',
               'protocolErrors', 'duplicates', 'unexpected', 'unanswered', 'acceptedBuys', 'acceptedSells', 'balanceOrders',
               'sequencedDuplicates', 'outOfSequence', 'streamLimit', 'satOut'].map((k) => [k, sum(k)])), imbalance: sum('imbalance'),
               probes: sum('probes'), streamsNew: sum('streamsNew'), streamsResumed: sum('streamsResumed'),
               streams: sum('streamsNew') + sum('streamsResumed'), streamCap: cfg.streamCap, pod: cfg.pod,
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
    && o.protocolErrors === 0 && o.imbalance === 0 && o.sequencedDuplicates === 0 && o.outOfSequence === 0 && o.streamLimit === 0 ? 0 : 1);
};
const clock = setInterval(() => {   // send inside the phase; drain and balance in its pause; check at the boundary
  const now = Date.now(), k = Math.floor(now / cfg.phaseMs), off = now % cfg.phaseMs;
  if (k !== phase) {
    const late = sessions.filter((x) => x.open.size || x.s.acceptedBuys !== x.s.acceptedSells);
    const again = late.filter((x) => x.benched()).length;
    if (again) return finish(`${again} sessions not balanced at two phase boundaries running (answers later than a phase)`);
    sessions.forEach((x) => x.bench(false)); late.forEach((x) => { x.bench(true); x.s.satOut++; });
    if (now >= deadline && !late.length) return finish(null);   // a late session gets one more phase to balance
    phase = k;
  }
  role = k % 2;
  sending = off >= GUARD_MS && off < cfg.phaseMs - cfg.pauseMs && now < deadline;
  sessions.forEach((x) => { if (!sending || x.benched()) x.balance(); });
}, 50);
