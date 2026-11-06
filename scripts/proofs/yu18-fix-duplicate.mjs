// yu18-fix-duplicate.mjs — the FIX session half of yu18-fix-duplicate.sh. No deps.
// YU18 FR-XOC-03 (ADR-091 §3), black-box. The wrapper reads the effect end; this prints `RESULT k=v`.
//
//   1. a resting Sell S1 and a crossing Buy B1 on TICKER: both Filled, so S1 is terminal.
//   2. FILLER IOC buys on FILLER_TICKER, which has no sell: the engine cancels each on arrival (terminal).
//      The engine retains its last 1,024 terminal orders (MatchingEngine terminal retention = POOL_SIZE),
//      so past that S1 has left retention while its idempotency key (262,144) has not: the engine now
//      answers a resend of S1 REJECTED/DUPLICATE naming its ref (FR-XOC-01).
//   3. S1's NewOrderSingle resent, same ClOrdID. FR-XOC-03: ExecutionReport 150=I, 37=S1's OrderID,
//      11=S1, 58 "DUPLICATE: ClOrdID already executed as <OrderID>", 39 from the read model (2, Filled);
//      never 39=8, never OrderID NONE.
//   4. OrderCancelRequest by OrigClOrdID=S1 resolves to S1's OrderID (a Cancel Reject naming it, too late).
//
// NEGATIVE=1 resends under a FRESH ClOrdID with S1's economics: a new key is decided as new, so step 3's
// checks must fail (and the wrapper finds a third order in SQL).
import net from 'node:net';

const E = process.env;
const HOST = E.FIX_HOST || '127.0.0.1', PORT = Number(E.FIX_PORT || 18130);
const SENDER = E.SENDER || 'CLIENT1', TARGET = E.TARGET || 'TRADERX', PASSWORD = E.FIX_PASSWORD || '';
const TICKER = E.TICKER, FT = E.FILLER_TICKER, PX = Number(E.PX), A = E.ACCT_A, B = E.ACCT_B;
const FILLER = Number(E.FILLER || 1100), NEG = E.NEGATIVE === '1';
if (!PASSWORD || !TICKER || !FT || !PX || !A || !B) { console.log('[FAIL] need FIX_PASSWORD TICKER FILLER_TICKER PX ACCT_A ACCT_B'); process.exit(2); }

const SOH = '\x01';
const ts = () => { const d = new Date().toISOString(); return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`; };
let LIVE = null;  // the session, for the failure dump
const fail = (m) => {
  console.log(`[FAIL] ${m}`);
  for (const r of (LIVE ? LIVE.rx.slice(-4) : [])) console.log('    rx', JSON.stringify(r));
  process.exit(1);
};
const ok = (m) => console.log(`[ok] ${m}`);
const result = (k, v) => console.log(`RESULT ${k}=${v}`);

class Session {
  constructor(sender) { this.sender = sender; this.seq = 1; this.rx = []; this.buf = ''; }
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
      this.sock.on('error', rej);
      this.sock.on('data', (c) => {
        this.buf += c; let i;
        while ((i = this.buf.indexOf(`${SOH}10=`)) >= 0) {
          const end = this.buf.indexOf(SOH, i + 1); if (end < 0) break;
          const raw = this.buf.slice(0, end + 1); this.buf = this.buf.slice(end + 1);
          const m = Object.fromEntries(raw.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
          this.rx.push(m);
          if (m['35'] === '1') this.send('0', [`112=${m['112']}`]);
        }
      });
    });
  }
  wait(pred, label, ms = 20000, from = 0) {
    return new Promise((res, rej) => {
      const t0 = Date.now();
      const iv = setInterval(() => {
        const m = this.rx.slice(from).find(pred);
        if (m) { clearInterval(iv); res(m); } else if (Date.now() - t0 > ms) { clearInterval(iv); rej(new Error(`timeout waiting for ${label}`)); }
      }, 20);
    });
  }
}
const D = (cl, acct, side, qty, px, sym, tif = '0') => ['11=' + cl, '1=' + acct, '55=' + sym, '54=' + side, '38=' + qty, '40=2', '44=' + px.toFixed(2), '59=' + tif, '60=' + ts()];
const er = (s, cl, pred, label, from = 0) => s.wait((m) => m['35'] === '8' && m['11'] === cl && pred(m), label, 20000, from).catch((e) => fail(e.message));

const s = new Session(SENDER); LIVE = s;
await s.open();
s.send('A', ['98=0', '108=30', '141=Y', `554=${PASSWORD}`]);
await s.wait((m) => m['35'] === 'A', 'logon').catch(() => fail(`${SENDER} was not logged on`));
ok(`${SENDER}: Logon(A)`); result('logon_ok', 1);

const tag = `xd${Date.now() % 1e9}`, S1 = `${tag}-s1`, B1 = `${tag}-b1`;
console.log('=== 1. S1 rests, B1 crosses it: both Filled ===');
s.send('D', D(S1, B, 2, 5, PX, TICKER));
const n1 = await er(s, S1, (m) => m['39'] === '0', 'New for S1');
s.send('D', D(B1, A, 1, 5, PX, TICKER));
await er(s, S1, (m) => m['39'] === '2', 'S1 Filled');
await er(s, B1, (m) => m['39'] === '2', 'B1 Filled');
ok(`S1 ${n1['37']} and B1 filled`); result('s1_order_id', n1['37']);

console.log(`=== 2. ${FILLER} IOC buys on ${FT} (no sell): each Canceled, terminal ===`);
let done = 0;
for (let i = 0; i < FILLER; i += 50) {
  const ids = [];
  for (let j = i; j < Math.min(FILLER, i + 50); j++) { const cl = `${tag}-f${j}`; ids.push(cl); s.send('D', D(cl, A, 1, 1, PX, FT, '3')); }
  // Only the ack is awaited: the acceptor answers an IOC the engine cancels on arrival New and never reports the
  // cancel (suspect, GAP-LIST.md). The wrapper counts the filler CANCELED in the read model instead.
  for (const cl of ids) {
    const m = await er(s, cl, () => true, `ack for IOC ${cl}`);
    if (m['39'] === '8') fail(`IOC ${cl} rejected "${m['58'] || ''}": the filler must be orders the engine accepts`);
    done++;
  }
}
ok(`${done} IOC buys acknowledged`); result('filler_acked', done);

console.log(`=== 3. S1 resent${NEG ? ' (NEGATIVE: under a FRESH ClOrdID)' : ''} ===`);
const R = NEG ? `${tag}-s1x` : S1;
const mark = s.rx.length;
s.send('D', D(R, B, 2, 5, PX, TICKER));
const d = await er(s, R, () => true, 'answer to the resend', mark);
console.log(`    answer 150=${d['150']} 39=${d['39']} 37=${d['37']} 14=${d['14'] || '-'} 58="${d['58'] || ''}"`);
const want = `DUPLICATE: ClOrdID already executed as ${n1['37']}`;
const bad = [];
if (d['150'] !== 'I') bad.push(`ExecType ${d['150']}, want I (Order Status)`);
if (d['37'] !== n1['37']) bad.push(`OrderID ${d['37']}, want S1's ${n1['37']}`);
if (d['39'] === '8' || d['37'] === 'NONE') bad.push('a reject (39=8 / OrderID NONE) of an order that executed');
if ((d['58'] || '') !== want) bad.push(`Text "${d['58'] || ''}", want "${want}"`);
if (d['39'] !== '2') bad.push(`OrdStatus ${d['39']}, want 2 (Filled, from the read model)`);
if (bad.length) fail(`FR-XOC-03: ${bad.join('; ')}`);
ok('Order Status report for the original: 150=I, its OrderID, DUPLICATE text, Filled'); result('dup_answer', 'status');

console.log('=== 4. OrderCancelRequest by OrigClOrdID=S1 resolves to its OrderID ===');
const C = `${tag}-c1`, cm = s.rx.length;
s.send('F', ['41=' + S1, '11=' + C, '1=' + B, '55=' + TICKER, '54=2', '38=5', '60=' + ts()]);
const c = await s.wait((m) => (m['35'] === '9' || m['35'] === '8') && m['11'] === C, 'cancel answer', 20000, cm).catch((e) => fail(e.message));
console.log(`    cancel answered 35=${c['35']} 37=${c['37']} 39=${c['39'] || '-'} 58="${c['58'] || ''}"`);
if (c['37'] !== n1['37']) fail(`the cancel by OrigClOrdID did not resolve to ${n1['37']} (37=${c['37']}): FR-XOC-03 keeps the ClOrdID mapping`);
ok(`resolved to ${n1['37']}`);
s.sock.destroy();
console.log('FIX-DUPLICATE SESSION PASS');
