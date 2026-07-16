// fix-load.mjs — minimal raw FIX 4.4 order-entry throughput sender (YU10 spec, NFR-FIX05).
//
// Writing FIX frames is trivial; only the SERVER's resend/session machinery is hard (that's why
// the conformance proof uses a real QuickFIX/J initiator — FixSessionIntegrationTest — and this
// client stays a deliberately minimal firehose for the throughput number). It logs on with a JWT
// in Password(554), sends a stream of NewOrderSingle, and counts COMPLETED lifecycles: a
// NewOrderSingle is "completed" only when its ExecutionReport (matched by ClOrdID) comes back.
// That is the honest FIX throughput unit, not frames written (ADR-037, codeX critique #2.7).
//
// Usage:  node fix-load.mjs [--secs S] [--host H] [--port P]
//   env: FIX_JWT (required), FIX_COMP_ID (BENCH01), FIX_TARGET (TRADERX),
//        SIDES=alternate (self-match to stay clear of risk caps), QTY (1), PX (190)
import net from 'node:net';

const SOH = '\x01';
const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : d; };

const cfg = {
  host: flag('--host', process.env.FIX_HOST || '127.0.0.1'),
  port: Number(flag('--port', process.env.FIX_PORT || 18130)),
  secs: Number(flag('--secs', process.env.DURATION_SECS || 30)),
  sender: process.env.FIX_COMP_ID || 'BENCH01',
  target: process.env.FIX_TARGET || 'TRADERX',
  jwt: process.env.FIX_JWT || '',
  alternate: (process.env.SIDES || '') === 'alternate',
  qty: Number(process.env.QTY || 1),
  px: process.env.PX || '190',
  tickers: (process.env.TICKERS || 'JPM').split(',').map((t) => t.trim().toUpperCase()),
};
if (!cfg.jwt) { console.error('FIX_JWT is required'); process.exit(2); }

const RUN = process.env.RUN_ID || String(Date.now() % 1000000);
let outSeq = 1;
let submitted = 0, completed = 0, rejected = 0;
const pending = new Set();

function frame(type, fields) {
  const head = `35=${type}${SOH}34=${outSeq++}${SOH}49=${cfg.sender}${SOH}52=${stamp()}${SOH}56=${cfg.target}${SOH}`;
  const body = head + fields.map(([t, v]) => `${t}=${v}${SOH}`).join('');
  const withLen = `9=${body.length}${SOH}${body}`;
  const full = `8=FIX.4.4${SOH}${withLen}`;
  let sum = 0;
  for (let i = 0; i < full.length; i++) sum += full.charCodeAt(i);
  return `${full}10=${String(sum % 256).padStart(3, '0')}${SOH}`;
}

function stamp() {
  // FIX UTCTimestamp YYYYMMDD-HH:MM:SS.sss — Date is fine here (a bench client, not the engine)
  return new Date().toISOString().replace(/[-:T]/g, (c) => (c === 'T' ? '-' : c === '-' ? '' : ':'))
    .replace(/\.\d+Z$/, (m) => m.slice(0, 4)).replace('Z', '');
}

const sock = net.connect(cfg.port, cfg.host, () => {
  // Logon(A): EncryptMethod(98)=0, HeartBtInt(108)=30, Password(554)=JWT, ResetSeqNumFlag(141)=Y
  sock.write(frame('A', [[98, 0], [108, 30], [141, 'Y'], [553, cfg.sender], [554, cfg.jwt]]));
});

let buf = '';
let loggedOn = false;
let clSeq = 0;
let sideFlip = 0;

sock.on('data', (chunk) => {
  buf += chunk.toString('latin1');
  let idx;
  while ((idx = buf.indexOf(`${SOH}10=`)) !== -1) {
    const end = buf.indexOf(SOH, idx + 1);
    const msg = buf.slice(0, end + 1);
    buf = buf.slice(end + 1);
    handle(msg);
  }
});

function tag(msg, t) {
  const m = msg.match(new RegExp(`${SOH}${t}=([^${SOH}]*)${SOH}`));
  return m ? m[1] : null;
}

function handle(msg) {
  const type = tag(msg, 35);
  if (type === 'A') { loggedOn = true; startSending(); return; }
  if (type === '5') { process.exit(0); }              // logout
  if (type === '0') { return; }                       // heartbeat
  if (type === '1') { sock.write(frame('0', [[112, tag(msg, 112) || '']])); return; }  // testreq->hb
  if (type === '8') {                                 // ExecutionReport
    const clId = tag(msg, 11);
    if (clId && pending.delete(clId)) {
      const ordStatus = tag(msg, 39);
      if (ordStatus === '8') rejected++; else completed++;
    }
    return;
  }
  if (type === 'j' || type === '9' || type === '3') { // reject of some kind
    const clId = tag(msg, 11) || tag(msg, 379);
    if (clId && pending.delete(clId)) rejected++;
  }
}

function sendOne() {
  const clId = `${cfg.sender}-${RUN}-${++clSeq}`;
  const side = cfg.alternate && (sideFlip++ & 1) ? '2' : '1';
  const ticker = cfg.tickers[clSeq % cfg.tickers.length];
  pending.add(clId);
  submitted++;
  sock.write(frame('D', [
    [11, clId], [55, ticker], [54, side], [38, cfg.qty], [40, 2], [44, cfg.px],
    [60, stamp()],
  ]));
}

function startSending() {
  const deadline = Date.now() + cfg.secs * 1000;
  // keep a bounded window of in-flight orders so a slow ER path applies natural backpressure
  const WINDOW = 256;
  const pump = () => {
    while (pending.size < WINDOW && Date.now() < deadline) sendOne();
    if (Date.now() < deadline) { setImmediate(pump); return; }
    setTimeout(finish, 2000);   // drain trailing ERs
  };
  const t0 = Date.now();
  const report = setInterval(() => {
    const el = (Date.now() - t0) / 1000;
    process.stdout.write(`[fix] submit ${submitted}  completed ${completed} (${Math.round(completed / el)}/s)  `
      + `rejected ${rejected}  in-flight ${pending.size}\n`);
    if (Date.now() >= deadline + 2000) clearInterval(report);
  }, 1000);
  pump();

  function finish() {
    const el = (Date.now() - t0) / 1000;
    console.log(`\n[fix] done.  submitted=${submitted}  completed=${completed}  rejected=${rejected}  `
      + `completed/s=${Math.round(completed / el)}`);
    try { sock.write(frame('5', [])); } catch { /* ignore */ }
    setTimeout(() => process.exit(0), 300);
  }
}

sock.on('error', (e) => { console.error('[fix] socket error:', e.message); process.exit(1); });
