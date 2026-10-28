// yu18-fix-resume.mjs — the FIX half of yu18-fix-resume.sh (ADR-086, YU10 FR-FIX04/11/12(d)). No deps.
// Two gateway replicas, each on its own forward (FIX_PORT_A, FIX_PORT_B). Prints `RESULT k=v` lines.
//
//   1. On A: Logon (141=Y, a clean start), a resting Sell S1 and a crossing Buy B1, both answered.
//   2. On A: two more Sells S2, S3 whose reports the client never reads, then an abrupt drop (RST).
//      While the session is live nowhere, a REST Buy crosses S3: A tracks S3 (ADR-084), takes the
//      session's lease and stores the fill report (ADR-086 §3).
//   3. On B: Logon WITHOUT ResetSeqNumFlag, continuing our numbers. B's Logon continues A's sequence.
//      ResendRequest from the first unread number: exactly S2's and S3's reports and S3's fill, at their
//      original numbers, PossDupFlag=Y, OrigSendingTime set. No report reaches the client twice.
//   4. On B: cancel S2 by OrigClOrdID alone (B rebuilt ClOrdID -> order from the stored reports).
//   5. On B: OrderStatusRequest answers from the read model (S2 Canceled CumQty 0, S3 Filled CumQty 4), not "unavailable".
//   6. On A, while B holds the session: a Logon continuing the numbers is refused (Logout naming the
//      other gateway), and B's session keeps working.
import net from 'node:net';

const E = process.env;
const PORT_A = Number(E.FIX_PORT_A), PORT_B = Number(E.FIX_PORT_B);
const SENDER = E.SENDER || 'CLIENT1', TARGET = 'TRADERX', PASSWORD = E.FIX_PASSWORD || '';
const TICKER = E.TICKER, PX = Number(E.PX), A = E.ACCT_A, B = E.ACCT_B, GW = E.GW, BEARER = E.BEARER;
if (!PORT_A || !PORT_B || !PASSWORD || !TICKER || !PX || !A || !B || !GW || !BEARER) { console.log('[FAIL] need FIX_PORT_A FIX_PORT_B FIX_PASSWORD TICKER PX ACCT_A ACCT_B GW BEARER'); process.exit(2); }

const SOH = '\x01';
const ts = () => { const d = new Date().toISOString(); return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`; };
const fail = (m) => { console.log(`[FAIL] ${m}`); process.exit(1); };
const ok = (m) => console.log(`[ok] ${m}`);
const result = (k, v) => console.log(`RESULT ${k}=${v}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Session {
  constructor(port, seq = 1) { this.port = port; this.seq = seq; this.rx = []; this.buf = ''; this.closed = false; }
  frame(type, fields) {
    const b = [`35=${type}`, `34=${this.seq++}`, `49=${SENDER}`, `56=${TARGET}`, `52=${ts()}`, ...fields].join(SOH) + SOH;
    const s = `8=FIX.4.4${SOH}9=${b.length}${SOH}${b}`;
    let sum = 0; for (let i = 0; i < s.length; i++) sum += s.charCodeAt(i);
    return s + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
  }
  send(type, fields) { this.sock.write(this.frame(type, fields)); }
  open() {
    return new Promise((res, rej) => {
      this.sock = net.createConnection({ host: '127.0.0.1', port: this.port }, res);
      this.sock.setEncoding('ascii');
      this.sock.on('error', (e) => { this.closed = true; rej(e); });
      this.sock.on('close', () => { this.closed = true; });
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
  wait(pred, label, ms = 20000) {
    return new Promise((res, rej) => {
      const t0 = Date.now();
      const iv = setInterval(() => {
        const m = this.rx.find(pred);
        if (m) { clearInterval(iv); res(m); } else if (Date.now() - t0 > ms) { clearInterval(iv); rej(new Error(`timeout waiting for ${label}`)); }
      }, 50);
    });
  }
  async logon(reset) {
    await this.open();
    this.send('A', ['98=0', '108=30', ...(reset ? ['141=Y'] : []), `554=${PASSWORD}`]);
  }
}

const D = (cl, acct, side, qty, px) => ['11=' + cl, '1=' + acct, '55=' + TICKER, '54=' + side, '38=' + qty, '40=2', '44=' + px.toFixed(2), '60=' + ts()];
const isEr = (m, cl) => m['35'] === '8' && m['11'] === cl;

try {
  const tag = `rs${Date.now() % 1e9}`;
  const [S1, B1, S2, S3, X2] = ['s1', 'b1', 's2', 's3', 'x2'].map((x) => `${tag}-${x}`);

  console.log('=== 1. replica A: logon, a resting Sell and a crossing Buy ===');
  const a = new Session(PORT_A);
  await a.logon(true);
  await a.wait((m) => m['35'] === 'A', 'logon on A').catch(() => fail('CLIENT1 was not logged on to replica A'));
  a.send('D', D(S1, B, 2, 5, PX));
  const s1 = await a.wait((m) => isEr(m, S1) && m['39'] === '0', `New for ${S1}`);
  a.send('D', D(B1, A, 1, 5, PX + 1));
  // The engine acks a crossing order ACCEPTED; its fill follows on the order feed (ADR-084).
  const b1 = await a.wait((m) => isEr(m, B1) && m['39'] === '2', `Filled for ${B1}`);
  ok(`A: ${S1} New ${s1['37']} (seq ${s1['34']}), ${B1} Filled at ${b1['31'] || '?'} (seq ${b1['34']})`);
  const s1Fill = await a.wait((m) => isEr(m, S1) && m['150'] === 'F', `fill report for the resting ${S1} (ADR-084)`, 10000)
    .catch(() => fail(`the resting ${S1} was crossed but its session got no fill report (FR-FIX05)`));
  ok(`A: ${S1} fill report (ADR-084) seq ${s1Fill['34']} CumQty ${s1Fill['14']}`);
  const processed = Math.max(...a.rx.map((m) => Number(m['34'])));
  const seenA = a.rx.filter((m) => m['35'] === '8');

  console.log('\n=== 2. replica A: two more orders whose reports are never read, then an abrupt drop ===');
  a.sock.pause();                  // stop reading: the reports pile up unread in our socket buffer
  a.send('D', D(S2, B, 2, 3, PX + 6));   // above the REST Buy's limit: only S3 crosses
  a.send('D', D(S3, B, 2, 4, PX + 2));
  await sleep(3000);
  a.sock.resetAndDestroy();        // RST: no Logout, and whatever was unread is gone
  ok(`dropped with RST after reading through MsgSeqNum ${processed}; our next outbound is ${a.seq}`);
  await sleep(2500);               // A lets the lease go once nothing is in flight: live nowhere
  const rest = await fetch(`${GW}/orders`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${BEARER}` },
    body: JSON.stringify({ accountId: Number(A), ticker: TICKER, side: 'Buy', quantity: 4, limitPrice: PX + 2 }) });
  const restBody = await rest.text();
  if (rest.status !== 200) fail(`REST Buy crossing ${S3}: ${rest.status} ${restBody.slice(0, 200)}`);
  ok(`while the session is live nowhere, a REST Buy 4 @${PX + 2} crosses ${S3}: ${restBody.slice(0, 120)}`);
  await sleep(2500);               // the fill is stored by A, which then lets the lease go again

  console.log('\n=== 3. replica B: resume without reset, ResendRequest what was missed ===');
  const b = new Session(PORT_B, a.seq);
  await b.logon(false);
  const lb = await b.wait((m) => m['35'] === 'A' || m['35'] === '5', 'logon answer on B', 25000);
  if (lb['35'] !== 'A') fail(`B refused the resumed session: Logout "${lb['58'] || ''}" (FR-FIX11)`);
  const logonSeq = Number(lb['34']);
  result('logon_seq_b', logonSeq);
  if (logonSeq !== processed + 4) fail(`B's Logon is MsgSeqNum ${logonSeq}; A had stored through ${processed + 3} (two unread reports and S3's fill), so want ${processed + 4}. A restart at 1 is the TD-AC01 behaviour (suspect 9)`);
  ok(`B continues A's sequence: Logon at ${logonSeq}`);
  b.send('2', [`7=${processed + 1}`, '16=0']);
  for (const [n, cl, type] of [[processed + 1, S2, '0'], [processed + 2, S3, '0'], [processed + 3, S3, 'F']]) {
    const r = await b.wait((m) => m['35'] === '8' && Number(m['34']) === n, `resent report at ${n}`);
    if (r['11'] !== cl || r['150'] !== type) fail(`resent report ${n} is ${r['11']} ExecType=${r['150']}, want ${cl} ExecType=${type}`);
    if (type === 'F' && (r['39'] !== '2' || r['14'] !== '4' || r['32'] !== '4')) fail(`S3's fill: OrdStatus=${r['39']} CumQty=${r['14']} LastQty=${r['32']}, want 2/4/4`);
    if (r['43'] !== 'Y' || !r['122']) fail(`resent report ${n}: PossDupFlag=${r['43']} OrigSendingTime=${r['122']} (both required on a resend)`);
    ok(`resent ${n}: ${cl} ExecType=${r['150']} OrdStatus=${r['39']} PossDupFlag=Y OrigSendingTime=${r['122']}`);
  }
  await sleep(1500);
  const resent = b.rx.filter((m) => m['35'] === '8' && m['43'] === 'Y');
  if (resent.length !== 3) fail(`ResendRequest re-delivered ${resent.length} reports, want exactly 3`);
  const all = [...seenA, ...resent];
  const ids = all.map((m) => m['17']);
  if (new Set(ids).size !== ids.length) fail(`an ExecID reached the client twice: ${ids.join(',')}`);
  const per = Object.fromEntries([S1, B1, S2, S3].map((cl) => [cl, all.filter((m) => m['11'] === cl).length]));
  const want = { [S1]: 2, [B1]: 2, [S2]: 1, [S3]: 2 };   // New, + Fill for the three that filled (ADR-084)
  if (JSON.stringify(per) !== JSON.stringify(want)) fail(`reports per order ${JSON.stringify(per)}, want ${JSON.stringify(want)}`);
  ok(`every report exactly once across both replicas: ${JSON.stringify(per)}`);
  result('resent', resent.length);

  console.log('\n=== 4. replica B: cancel by OrigClOrdID alone ===');
  b.send('F', [`41=${S2}`, `11=${X2}`, `1=${B}`, '54=2', `55=${TICKER}`, `60=${ts()}`]);   // Account: the owner (G3)
  const c2 = await b.wait((m) => (m['35'] === '8' && m['11'] === X2) || (m['35'] === '9' && m['11'] === X2), `answer to cancel ${X2}`);
  if (c2['35'] !== '8' || c2['39'] !== '4') fail(`cancel of ${S2} by OrigClOrdID on B: 35=${c2['35']} 39=${c2['39']} "${c2['58'] || ''}" (B must know A's ClOrdIDs)`);
  if (c2['43'] === 'Y' || Number(c2['34']) <= logonSeq) fail(`the cancel report is MsgSeqNum ${c2['34']} PossDup=${c2['43']}: new messages continue after the Logon`);
  ok(`${S2} canceled on B by OrigClOrdID: OrderID ${c2['37']}, MsgSeqNum ${c2['34']}`);
  result('cancel_seq', c2['34']);

  console.log('\n=== 5. replica B: OrderStatusRequest from the read model (FR-FIX04) ===');
  const s3 = resent.find((m) => m['11'] === S3);
  for (const [cl, id, want, cum] of [[S2, c2['37'], '4', '0'], [S3, s3['37'], '2', '4']]) {   // CumQty: FR-DRS-27
    let st;
    for (let attempt = 0; attempt < 10; attempt++) {   // the read model trails the engine by a moment
      const mark = b.rx.length;
      b.send('H', [`11=${cl}`, `37=${id}`, `1=${B}`, `55=${TICKER}`, '54=2']);
      st = await b.wait((m) => b.rx.indexOf(m) >= mark && isEr(m, cl) && m['150'] === 'I', `status of ${cl}`);
      if (st['39'] === want) break;
      await sleep(1000);
    }
    if (st['39'] !== want || st['37'] !== id || st['14'] !== cum) fail(`status of ${cl}: OrdStatus=${st['39']} OrderID=${st['37']} CumQty=${st['14']} "${st['58'] || ''}", want ${want} / ${id} / ${cum}`);
    ok(`H ${cl} -> ExecType=I OrdStatus=${st['39']} OrderID=${st['37']} LeavesQty=${st['151']} CumQty=${st['14']}`);
  }
  result('status_ok', 1);

  console.log('\n=== 6. replica A while B holds the session: refused ===');
  const a2 = new Session(PORT_A, b.seq + 100);   // past the sequence check, so the refusal is the lease's
  await a2.logon(false);
  const la2 = await a2.wait((m) => m['35'] === 'A' || m['35'] === '5', 'answer to a second logon', 25000).catch(() => null);
  if (!la2) fail('a second Logon on A got no answer at all (want Logout naming the other gateway)');
  if (la2['35'] === 'A') fail('replica A accepted a second live copy of the session held by B');
  if (!/live on another gateway/.test(la2['58'] || '')) fail(`A's Logout says "${la2['58'] || ''}", want "session is live on another gateway"`);
  ok(`A refused: Logout "${la2['58']}"`);
  result('refused', 1);
  const mark = b.rx.length;
  b.send('H', [`11=${S3}`, `37=${s3['37']}`, `1=${B}`, `55=${TICKER}`, '54=2']);
  await b.wait((m) => b.rx.indexOf(m) >= mark && isEr(m, S3), 'B still answering').catch(() => fail('B stopped answering after the refused second logon'));
  ok('B\'s session is unaffected');

  b.send('5', []);
  await sleep(500);
  b.sock.destroy();
  console.log('\nFIX-RESUME OK');
  process.exit(0);
} catch (e) { fail(e.message); }
