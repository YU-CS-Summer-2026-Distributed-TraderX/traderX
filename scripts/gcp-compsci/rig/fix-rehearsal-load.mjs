// ADR-096 (FR-PRG-12): the rig rehearsal's FIX load. One session per load CompID (FR-PRG-11), each trading only its own
// test account: limit orders of 1 at one price inside the collar, alternating Buy/Sell, so the accounts cross each
// other and the book stays small. No TimeInForce: the gateway's default rests (a Day order needs an open trading day,
// FR-OT08). Every ClOrdID is tracked until its first ExecutionReport, and at the end every order still resting is
// cancelled, so the run leaves no open test order.
//
// A gateway that drains logs its sessions out (FR-PRG-07). This client then reconnects through the internal LB with
// its next MsgSeqNum (never a reset after the first Logon), answers the acceptor's ResendRequest with a GapFill, asks
// for what it missed, and resends every unanswered order with PossResend(97): the engine dedupes a key it already
// holds and the gateway restates it (150=I, ADR-091). So a drain loses nothing and books nothing twice.
//
//   node fix-rehearsal-load.mjs --creds FILE --px P [--ticker MSFT] [--rate N/s per session] [--secs S]
//        [--host 10.90.0.41] [--port 18130] [--out results.json]
// FILE is provision-fix-test-clients.json ({CompID: {account, password}}, mode 0600). No password is printed.
import fs from 'node:fs';
import net from 'node:net';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const cfg = {
  creds: flag('--creds'), px: flag('--px'), ticker: flag('--ticker', 'MSFT'), rate: Number(flag('--rate', 50)),
  secs: Number(flag('--secs', 60)), host: flag('--host', '10.90.0.41'), port: Number(flag('--port', 18130)),
  out: flag('--out', 'results.json'), maxInFlight: Number(flag('--max-in-flight', 5000)),
};
if (!cfg.creds || !cfg.px) { console.log('usage: --creds FILE --px PRICE'); process.exit(2); }
const creds = JSON.parse(fs.readFileSync(cfg.creds, 'utf8'));

const SOH = '\x01';
const stamp = () => new Date().toISOString().replace(/-/g, '').replace('T', '-').slice(0, 21);
const parse = (m) => Object.fromEntries(m.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
const RUN = `r${Date.now().toString(36)}`;
const started = new Date();
let sending = true, closing = false;   // sending: new orders; closing: the final Logout (no reconnect after it)

function session(compId, { account, password }, index) {
  const s = { compId, account, sent: 0, answered: 0, resent: 0, restated: 0, rejected: 0, sessionRejects: 0,
              duplicateAnswers: 0, reconnects: [], rejectReasons: {} };
  const open = new Map();          // ClOrdID -> fields of the NewOrderSingle, until its first ExecutionReport
  const answered = new Set(), resting = new Set();   // resting: last status New / PartiallyFilled
  let out = 1, inExpected = 1, sock = null, up = false, first = true, buf = '', next = 0, downAt = null, resendTimer = null, credit = 0;

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
  const resendOpen = () => { for (const [cl, side] of open) { order(cl, side, true); s.resent++; } };

  const connect = () => {
    buf = ''; sock = net.connect(cfg.port, cfg.host);
    sock.on('connect', () => write('A', [['98', 0], ['108', 30], ['554', password], ...(first ? [['141', 'Y']] : [])]));
    sock.on('data', (chunk) => {
      buf += chunk.toString('latin1');
      for (let e; (e = buf.indexOf(`${SOH}10=`)) >= 0;) {
        const cut = buf.indexOf(SOH, e + 1) + 1; if (cut <= 0) break;
        const f = parse(buf.slice(0, cut)); buf = buf.slice(cut);
        const seq = Number(f['34']);
        if (f['35'] === 'A') {
          if (first) { inExpected = seq + 1; first = false; }
          else if (seq > inExpected) write('2', [['7', inExpected], ['16', 0]]);   // ask for the answers we missed
          up = true;
          if (downAt) {   // resent only after any ResendRequest is answered, or the GapFill would cover the resends
            s.reconnects.push({ at: new Date().toISOString(), downMs: Date.now() - downAt }); downAt = null;
            resendTimer = setTimeout(resendOpen, 300);
          }
          continue;
        }
        if (f['43'] !== 'Y' && seq >= inExpected) inExpected = seq + 1;
        if (f['35'] === '4' && f['123'] === 'Y') { inExpected = Math.max(inExpected, Number(f['36'])); continue; }
        if (f['35'] === '2') {   // GapFill what it asks for, then resend the open orders as new messages
          clearTimeout(resendTimer);
          write('4', [['123', 'Y'], ['36', out]], [['34', Number(f['7'])], ['43', 'Y'], ['122', stamp()]]);
          resendOpen(); continue;
        }
        if (f['35'] === '1') { write('0', [['112', f['112']]]); continue; }
        if (f['35'] === '3') { s.sessionRejects++; continue; }
        if (f['35'] === '9') { resting.delete(f['41']); s.cancelRejects = (s.cancelRejects || 0) + 1; continue; }   // not open any more
        if (f['35'] === '5') { if (!closing) sock.destroy(); continue; }
        if (f['35'] === '8' && f['11']) {
          const orig = f['41'] || f['11'];   // a cancel's report names the order in OrigClOrdID(41)
          if (['0', '1'].includes(f['39']) && f['150'] !== 'I') resting.add(orig); else if (['2', '4', '8', 'C'].includes(f['39'])) resting.delete(orig);
          if (f['41'] && f['39'] === '4') { s.cancelled = (s.cancelled || 0) + 1; continue; }
          if (f['150'] === 'I') s.restated++;
          if (answered.has(f['11'])) { if (f['39'] === '0' && f['150'] !== 'I') s.duplicateAnswers++; continue; }
          if (!open.has(f['11'])) continue;
          answered.add(f['11']); open.delete(f['11']); s.answered++;
          if (f['39'] === '8') { s.rejected++; const r = (f['58'] || '?').slice(0, 60); s.rejectReasons[r] = (s.rejectReasons[r] || 0) + 1; }
        }
      }
    });
    sock.on('close', () => {
      up = false;
      if (closing) return;
      downAt ??= Date.now();
      setTimeout(connect, 500);
    });
    sock.on('error', () => {});
  };
  connect();
  const tick = setInterval(() => {
    if (!sending || !up || open.size >= cfg.maxInFlight) return;
    for (credit += cfg.rate / 100; credit >= 1; credit--) {
      const cl = `${RUN}-${index}-${next++}`; const side = (next + index) % 2 ? 1 : 2;
      open.set(cl, side); order(cl, side, false); s.sent++;
    }
  }, 10);
  const cancelResting = () => { for (const cl of resting) write('F', [['41', cl], ['11', `${cl}-c`], ['1', account], ['55', cfg.ticker],
    ['54', (Number(cl.split('-').pop()) + index) % 2 ? 1 : 2], ['60', stamp()]]); };
  return { s, open, resting, cancelResting, stop: () => { clearInterval(tick); if (up) write('5', []); setTimeout(() => sock.destroy(), 500); } };
}

const sessions = Object.entries(creds).map(([c, v], i) => session(c, v, i));
const report = (final) => {
  const rows = sessions.map(({ s, open, resting }) => ({ ...s, unanswered: open.size, restingAtEnd: resting.size }));
  const sum = (k) => rows.reduce((a, r) => a + r[k], 0);
  const out = { run: RUN, startedAt: started.toISOString(), at: new Date().toISOString(), final, px: cfg.px,
                ticker: cfg.ticker, sent: sum('sent'), answered: sum('answered'), unanswered: sum('unanswered'),
                rejected: sum('rejected'), restated: sum('restated'), duplicateAnswers: sum('duplicateAnswers'),
                reconnects: rows.reduce((a, r) => a + r.reconnects.length, 0), restingAtEnd: sum('restingAtEnd'),
                cancelledAtEnd: rows.reduce((a, r) => a + (r.cancelled || 0), 0), sessions: rows };
  fs.writeFileSync(cfg.out, JSON.stringify(out, null, 2));
  console.log(`${out.at} sent=${out.sent} answered=${out.answered} unanswered=${out.unanswered} rejected=${out.rejected} `
    + `restated=${out.restated} dupAnswers=${out.duplicateAnswers} reconnects=${out.reconnects}`);
  return out;
};
const progress = setInterval(() => report(false), 10_000);
setTimeout(() => {   // stop sending, then wait for every answer (at most 60 s), then log out
  sending = false;
  const t0 = Date.now(); let cancelled = false;
  const wait = setInterval(() => {
    const left = sessions.reduce((a, x) => a + x.open.size, 0);
    const rest = sessions.reduce((a, x) => a + x.resting.size, 0);
    if (left === 0 && rest > 0 && !cancelled) { cancelled = true; sessions.forEach((x) => x.cancelResting()); return; }
    if ((left === 0 && rest === 0) || Date.now() - t0 > 60_000) {
      clearInterval(wait); clearInterval(progress); closing = true;
      sessions.forEach((x) => x.stop());
      setTimeout(() => { const r = report(true); process.exit(r.unanswered === 0 && r.duplicateAnswers === 0 && r.restingAtEnd === 0 ? 0 : 1); }, 1000);
    }
  }, 200);
}, cfg.secs * 1000);
