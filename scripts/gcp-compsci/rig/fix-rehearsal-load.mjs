// ADR-096 (FR-PRG-12): the rig rehearsal's FIX load. One session per load CompID (FR-PRG-11), each trading only its own
// test account: limit orders of 1 at one price inside the collar, alternating Buy/Sell, so the accounts cross each
// other and the book stays small. No TimeInForce: the gateway's default rests (a Day order needs an open trading day,
// FR-OT08). Every ClOrdID is tracked until its first ExecutionReport, and at the end every order still resting is
// cancelled, so the run leaves no open test order: at most --max-end-cancels per session (default 2048). Past that
// the run does not flood its sockets (acceptance 450K run 2: 19 of 20 pods ran out of heap cancelling ~2.2M orders
// each that the gateway had cancelled by STP without a report). It reports what it believed rests
// (believedResting) and what the venue said was not open (cancelRejects), for comparison with the members' openOrders.
//
// A gateway that drains logs its sessions out (FR-PRG-07). This client then reconnects through the internal LB with
// its next MsgSeqNum (never a reset after the first Logon), answers the acceptor's ResendRequest with a GapFill, and
// asks for what it missed. It then reconciles before it resends (FR-PRG-44): after --reconcile-settle-ms, one
// OrderStatusRequest (H) per unanswered order. An order the venue reports is counted as found (foundBooked) and taken
// as answered; only one it says it does not know is resent, with PossResend(97). A blind resend double-books once the
// engine's duplicate window (FR-IMRG14, a count shared by every session: ~1.7 s at 150K orders/s) has passed the key:
// acceptance run 3, 1,464 resends after a 47 s outage, +427 NEW in the history store. The status comes from the read
// model, so an order booked within its lag reads as unknown; the settle time is that margin. Each outage records
// when the socket closed, the Logout text or socket error that closed it, and the Logon attempts it took.
//
//   node fix-rehearsal-load.mjs --creds FILE --px P [--ticker MSFT] [--rate N/s per session] [--secs S]
//        [--host 10.90.0.41] [--port 18130] [--out results.json] [--ramp 0.25,0.5,0.75 --step-secs 300]
//        [--max-end-cancels 2048] [--reconcile-settle-ms 10000]
// FILE is provision-fix-test-clients.json ({CompID: {account, password}}, mode 0600). No password is printed.
import fs from 'node:fs';
import net from 'node:net';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  creds: flag('--creds'), px: flag('--px'), ticker: flag('--ticker', 'MSFT'), rate: Number(flag('--rate', 50)),
  secs: Number(flag('--secs', 60)), host: flag('--host', '10.90.0.41'), port: Number(flag('--port', 18130)),
  out: flag('--out', 'results.json'), maxInFlight: Number(flag('--max-in-flight', 5000)),
  drainSecs: Number(flag('--drain-secs', 60)),   // after sending stops: wait this long for answers
  maxEndCancels: Number(flag('--max-end-cancels', 2048)),   // per session
  settleMs: Number(flag('--reconcile-settle-ms', 10000)),   // after a reconnect, before asking for order status
};
if (!cfg.creds || !cfg.px) { console.log('usage: --creds FILE --px PRICE'); process.exit(2); }
const creds = JSON.parse(fs.readFileSync(cfg.creds, 'utf8'));

const SOH = '\x01';
const stamp = () => new Date().toISOString().replace(/-/g, '').replace('T', '-').slice(0, 21);
const parse = (m) => Object.fromEntries(m.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
const RUN = `r${Date.now().toString(36)}`;
const started = new Date();
// --ramp F1,F2,... --step-secs S (FR-PRG-17): the rate is F1 x --rate for the first S seconds, F2 x for the next S, ...,
// then the full rate, so a run records where the knee is instead of only failing at the target.
const RAMP = (flag('--ramp', '') || '').split(',').filter(Boolean).map(Number), STEP_MS = Number(flag('--step-secs', 300)) * 1000;
if (!RAMP.every((f) => f > 0 && f <= 1) || !(STEP_MS > 0)) { console.log('--ramp takes fractions in (0, 1]'); process.exit(2); }
const T0 = Date.now(), rampFactor = () => RAMP[Math.floor((Date.now() - T0) / STEP_MS)] ?? 1;
let sending = true, closing = false;
const bits = () => ({ a: new Uint8Array(1 << 13),
  has(i) { return i >= 0 && i < this.a.length * 8 && ((this.a[i >> 3] >> (i & 7)) & 1) === 1; },
  add(i) { if (i < 0) return; if (i >= this.a.length * 8) { const b = new Uint8Array(Math.max(this.a.length * 2, (i >> 3) + 1)); b.set(this.a); this.a = b; }
           this.a[i >> 3] |= 1 << (i & 7); },
  del(i) { if (this.has(i)) this.a[i >> 3] &= ~(1 << (i & 7)); } });   // sending: new orders; closing: the final Logout (no reconnect after it)

function session(compId, { account, password }, index) {
  const s = { compId, account, sent: 0, answered: 0, resent: 0, restated: 0, rejected: 0, sessionRejects: 0,
              duplicateAnswers: 0, filled: 0, duplicateFills: 0, cancelRejects: 0, believedResting: 0, endCancels: 0,
              statusRequests: 0, foundBooked: 0, foundFilled: 0, reconnects: [], rejectReasons: {} };
  const open = new Map();          // ClOrdID -> fields of the NewOrderSingle, until its first ExecutionReport
  // One bit per sequence of our own ClOrdIDs (RUN-index-seq), so an hour at rate stays a few MB. resting: last status
  // New / PartiallyFilled; done: Filled, Canceled, Rejected or not open, never resting again (a late New after its
  // cancel or fill, as a pipelined answer can be, does not bring it back).
  const answered = bits(), filled = bits(), resting = bits(), done = bits();
  let restingCount = 0;
  const rest = (n) => { if (n >= 0 && !done.has(n) && !resting.has(n)) { resting.add(n); restingCount++; } };
  const end = (n) => { if (n < 0) return; done.add(n); if (resting.has(n)) { resting.del(n); restingCount--; } };
  const mine = `${RUN}-${index}-`, seqOf = (cl) => (cl.startsWith(mine) && /^\d+$/.test(cl.slice(mine.length)) ? Number(cl.slice(mine.length)) : -1);
  let out = 1, inExpected = 1, sock = null, up = false, first = true, buf = '', next = 0, downAt = null, resendTimer = null, credit = 0;
  let outage = null;   // {closedAt, cause, attempts} of the current outage, pushed into reconnects at the next Logon

  const frame = (type, fields, header = []) => {
    const seq = header.find(([k]) => k === '34')?.[1] ?? out++;
    const head = [['35', type], ['49', compId], ['56', 'TRADERX'], ['34', seq], ['52', stamp()], ...header.filter(([k]) => k !== '34')];
    const body = [...head, ...fields].map(([k, v]) => `${k}=${v}`).join(SOH) + SOH;
    const pre = `8=FIX.4.4${SOH}9=${Buffer.byteLength(body)}${SOH}`;
    const sum = [...Buffer.from(pre + body)].reduce((a, c) => (a + c) & 0xff, 0);
    return `${pre}${body}10=${String(sum).padStart(3, '0')}${SOH}`;
  };
  const write = (type, fields, header) => { if (sock && !sock.destroyed) sock.write(frame(type, fields, header)); };
  const order = (cl, side, resend) => write('D', [['11', cl], ['1', account], ['55', cfg.ticker], ['54', side],
    ['38', 1], ['40', 2], ['44', cfg.px], ['60', stamp()]], resend ? [['97', 'Y']] : []);
  // FR-PRG-44: ask, then resend only what the venue does not know (the 150=I answers below)
  const asking = new Set();
  const reconcileOpen = () => { sock.cork(); for (const [cl, side] of open) { if (asking.has(cl)) continue; asking.add(cl); s.statusRequests++;
    write('H', [['11', cl], ['1', account], ['55', cfg.ticker], ['54', side]]); } sock.uncork(); };
  const reconcileLater = () => { clearTimeout(resendTimer); resendTimer = setTimeout(reconcileOpen, cfg.settleMs); };

  const connect = () => {
    buf = ''; sock = net.connect(cfg.port, cfg.host); if (outage) outage.attempts++;
    sock.on('connect', () => write('A', [['98', 0], ['108', 30], ['554', password], ...(first ? [['141', 'Y']] : [])]));
    sock.on('data', (chunk) => {
      buf += chunk.toString('latin1');
      let at = 0;   // one slice per chunk, not per message: per-message slicing is quadratic and reads too slowly at rate
      try { for (let e; (e = buf.indexOf(`${SOH}10=`, at)) >= 0;) {
        const cut = buf.indexOf(SOH, e + 1) + 1; if (cut <= 0) break;
        const f = parse(buf.slice(at, cut)); at = cut;
        const seq = Number(f['34']);
        if (f['35'] === 'A') {
          if (first) { inExpected = seq + 1; first = false; }
          else if (seq > inExpected) write('2', [['7', inExpected], ['16', 0]]);   // ask for the answers we missed
          up = true;
          if (downAt) {   // reconciled only after any ResendRequest is answered, or the GapFill would cover the requests
            s.reconnects.push({ at: new Date().toISOString(), downMs: Date.now() - downAt, ...outage }); downAt = null; outage = null;
            asking.clear(); reconcileLater();   // a request the outage cut off is asked again
          }
          continue;
        }
        if (f['43'] !== 'Y' && seq >= inExpected) inExpected = seq + 1;
        if (f['35'] === '4' && f['123'] === 'Y') { inExpected = Math.max(inExpected, Number(f['36'])); continue; }
        if (f['35'] === '2') {   // GapFill what it asks for, then reconcile the open orders
          write('4', [['123', 'Y'], ['36', out]], [['34', Number(f['7'])], ['43', 'Y'], ['122', stamp()]]);
          reconcileLater(); continue;
        }
        if (f['35'] === '1') { write('0', [['112', f['112']]]); continue; }
        if (f['35'] === '3') { s.sessionRejects++; continue; }
        if (f['35'] === '9') { end(seqOf(f['41'])); s.cancelRejects++; continue; }   // the venue says it is not open
        if (f['35'] === '5') {   // a Logout that ends the session, or one that refuses a Logon during an outage
          if (closing) continue;
          const why = `Logout: ${f['58'] || '(no text)'}`;
          if (outage) outage.lastRefusal = why; else outage = { closedAt: new Date().toISOString(), cause: why, attempts: 0 };
          sock.destroy(); continue;
        }
        if (f['35'] === '8' && f['11']) {
          // The answer to our OrderStatusRequest (ExecType OrderStatus, 150=I): the venue knows the order, or says
          // "unknown order" (OrdStatus Rejected), and only then is it resent. Neither is a restatement nor a reject.
          if (f['150'] === 'I' && (asking.delete(f['11']) || (f['39'] === '8' && /^unknown order/.test(f['58'] || '')))) {
            const cl = f['11'], k = seqOf(cl);
            if (f['39'] === '8' && /^unknown order/.test(f['58'] || '')) { if (open.has(cl)) { order(cl, open.get(cl), true); s.resent++; } continue; }
            s.foundBooked++;
            if (f['39'] === '2' && k >= 0 && !filled.has(k)) { filled.add(k); s.foundFilled++; }   // its fill report was lost
            if (['0', '1'].includes(f['39'])) rest(k); else if (['2', '4', '8', 'C'].includes(f['39'])) end(k);
            if (open.delete(cl)) { answered.add(k); s.answered++; }   // answered: its answer was what the outage lost
            continue;
          }
          const orig = f['41'] || f['11'];   // a cancel's report names the order in OrigClOrdID(41)
          const n = seqOf(orig);
          // every order is a limit of 1: Filled(39=2) is its one fill report; a restatement (150=I) is not a fill
          if (f['39'] === '2' && f['150'] !== 'I' && n >= 0) { if (filled.has(n)) s.duplicateFills++; else { filled.add(n); s.filled++; } }
          // no OrigClOrdID: the venue cancelled an order of ours to stop a self-trade (ADR-057, cancel-oldest)
          if (f['39'] === '4' && !f['41'] && n >= 0 && !done.has(n)) s.selfTradeCancelled = (s.selfTradeCancelled || 0) + 1;
          if (['0', '1'].includes(f['39']) && f['150'] !== 'I') rest(n); else if (['2', '4', '8', 'C'].includes(f['39'])) end(n);
          if (f['41'] && f['39'] === '4') { s.cancelled = (s.cancelled || 0) + 1; continue; }
          if (f['150'] === 'I') s.restated++;
          const m = seqOf(f['11']);
          if (m >= 0 && answered.has(m)) { if (f['39'] === '0' && f['150'] !== 'I') s.duplicateAnswers++; continue; }
          if (!open.has(f['11'])) continue;
          answered.add(m); open.delete(f['11']); s.answered++;
          if (f['39'] === '8') { s.rejected++; const r = (f['58'] || '?').slice(0, 60); s.rejectReasons[r] = (s.rejectReasons[r] || 0) + 1; }
        }
      } } finally { buf = buf.slice(at); }
    });
    sock.on('close', () => {
      up = false;
      if (closing) return;
      downAt ??= Date.now();
      outage ??= { closedAt: new Date().toISOString(), cause: 'socket closed by the peer', attempts: 0 };
      setTimeout(connect, 500);
    });
    sock.on('error', (e) => { outage ??= { closedAt: new Date().toISOString(), cause: `socket error: ${e.code || e.message}`, attempts: 0 }; });
  };
  connect();
  const tick = setInterval(() => {
    if (!sending || !up || open.size >= cfg.maxInFlight) return;
    sock.cork();
    for (credit += cfg.rate * rampFactor() / 100; credit >= 1; credit--) {
      const cl = `${RUN}-${index}-${next++}`; const side = (next + index) % 2 ? 1 : 2;
      open.set(cl, side); order(cl, side, false); s.sent++;
    }
    sock.uncork();
  }, 10);
  const cancelResting = () => {
    s.believedResting = restingCount; sock.cork();
    for (let n = 0; n < next && s.endCancels < cfg.maxEndCancels; n++) {
      if (!resting.has(n)) continue;
      const cl = `${mine}${n}`; s.endCancels++;
      write('F', [['41', cl], ['11', `${cl}-c`], ['1', account], ['55', cfg.ticker], ['54', (n + 1 + index) % 2 ? 1 : 2], ['60', stamp()]]);
    }
    sock.uncork();
  };
  return { s, open, resting: () => restingCount, cancelResting, stop: () => { clearInterval(tick); if (up) write('5', []); setTimeout(() => sock.destroy(), 500); } };
}

const sessions = Object.entries(creds).map(([c, v], i) => session(c, v, i));
const report = (final) => {
  const rows = sessions.map(({ s, open, resting }) => ({ ...s, unanswered: open.size, restingAtEnd: resting() }));
  const sum = (k) => rows.reduce((a, r) => a + r[k], 0);
  const out = { run: RUN, startedAt: started.toISOString(), at: new Date().toISOString(), final, px: cfg.px, ramp: rampFactor(),
                ticker: cfg.ticker, sent: sum('sent'), answered: sum('answered'), unanswered: sum('unanswered'),
                rejected: sum('rejected'), restated: sum('restated'), duplicateAnswers: sum('duplicateAnswers'),
                filled: sum('filled'), duplicateFills: sum('duplicateFills'),
                selfTradeCancelled: rows.reduce((a, r) => a + (r.selfTradeCancelled || 0), 0),
                reconnects: rows.reduce((a, r) => a + r.reconnects.length, 0), restingAtEnd: sum('restingAtEnd'),
                resent: sum('resent'), statusRequests: sum('statusRequests'), foundBooked: sum('foundBooked'), foundFilled: sum('foundFilled'),
                cancelledAtEnd: rows.reduce((a, r) => a + (r.cancelled || 0), 0), believedResting: sum('believedResting'),
                endCancels: sum('endCancels'), cancelRejects: sum('cancelRejects'), sessions: rows };
  fs.writeFileSync(cfg.out, JSON.stringify(out, null, 2));
  console.log(`${out.at} ramp=${out.ramp} sent=${out.sent} answered=${out.answered} unanswered=${out.unanswered} rejected=${out.rejected} `
    + `restated=${out.restated} dupAnswers=${out.duplicateAnswers} filled=${out.filled} dupFills=${out.duplicateFills} reconnects=${out.reconnects}`);
  return out;
};
const progress = setInterval(() => report(false), 10_000);
setTimeout(() => {   // stop sending, then wait for every answer (at most 60 s), then log out
  sending = false;
  const t0 = Date.now(); let cancelled = false;
  const wait = setInterval(() => {
    const left = sessions.reduce((a, x) => a + x.open.size, 0);
    const rest = sessions.reduce((a, x) => a + x.resting(), 0);
    // Sweep once every answer is in, or halfway through the wait: an order that is never answered must not leave
    // the others resting (production 2026-10-29: 3 refused at the session layer left one order on the book).
    if (rest > 0 && !cancelled && (left === 0 || Date.now() - t0 > cfg.drainSecs * 500)) {
      cancelled = true; sessions.forEach((x) => x.cancelResting()); return;
    }
    if ((left === 0 && rest === 0) || Date.now() - t0 > cfg.drainSecs * 1000) {
      clearInterval(wait); clearInterval(progress); closing = true;
      sessions.forEach((x) => x.stop());
      setTimeout(() => { const r = report(true); process.exit(r.unanswered === 0 && r.duplicateAnswers === 0 && r.duplicateFills === 0 && r.restingAtEnd === 0 ? 0 : 1); }, 1000);
    }
  }, 200);
}, cfg.secs * 1000);
