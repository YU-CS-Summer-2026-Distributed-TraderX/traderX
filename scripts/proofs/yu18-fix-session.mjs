// yu18-fix-session.mjs — the FIX 4.4 session half of yu18-fix-session.sh. No deps.
// Written from the specs (YU10 FR-FIX04/10/11/12, YU18 FR-ERH-S3), black-box. The wrapper reads the
// effect end (members, SQL); this file speaks the session and prints `RESULT <key>=<value>` lines.
//
//   1. FR-ERH-S3: Logon without Password(554), with a wrong one, and from a CompID outside
//      FIX_SESSION_AUTH are refused (no Logon(A) answer); CLIENT1 with its password logs on.
//   2. FR-FIX10: a resting Sell, then the SAME ClOrdID resent: the answer names no new OrderID.
//   3. FR-FIX04: OrderStatusRequest(H) answers ExecType=I with the order's state (New, then Filled).
//   4. a crossing Buy fills both sides (the wrapper checks members and SQL).
//   5. FR-FIX12: (c) unknown symbol -> ExecutionReport OrdStatus=8; (b) D without Side(54) -> Reject(3).
//   6. FR-FIX11: Logout, reconnect WITHOUT ResetSeqNumFlag: the acceptor's Logon continues its
//      outbound sequence (state survived the reconnect), and a ResendRequest re-delivers the stored
//      ExecutionReports with PossDupFlag(43)=Y.
//
// FIX UTCTimestamp is YYYYMMDD-HH:MM:SS.sss: the time part KEEPS its colons (yu13-fix-cancel.mjs).
import net from 'node:net';

const E = process.env;
const HOST = E.FIX_HOST || '127.0.0.1', PORT = Number(E.FIX_PORT || 18130);
const SENDER = E.SENDER || 'CLIENT1', TARGET = E.TARGET || 'TRADERX';
const PASSWORD = E.FIX_PASSWORD || '';
const TICKER = E.TICKER, PX = Number(E.PX), A = E.ACCT_A, B = E.ACCT_B;
if (!PASSWORD || !TICKER || !PX || !A || !B) { console.log('[FAIL] need FIX_PASSWORD TICKER PX ACCT_A ACCT_B'); process.exit(2); }

const SOH = '\x01';
const ts = () => { const d = new Date().toISOString(); return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`; };
let LIVE = [];  // sessions, for the failure dump
const fail = (m) => {
  console.log(`[FAIL] ${m}`);
  if (E.FIX_DUMP) for (const x of LIVE) for (const r of x.rx) console.log('    rx', JSON.stringify(r));
  process.exit(1);
};
const ok = (m) => console.log(`[ok] ${m}`);
const result = (k, v) => console.log(`RESULT ${k}=${v}`);
// A spec-vs-system disagreement that leaves the rest of the session meaningful: record, carry on, exit 1.
let suspects = 0;
const suspect = (m) => { suspects++; console.log(`[FAIL] ${m}`); };

class Session {
  constructor(sender, seq = 1) { LIVE.push(this); this.sender = sender; this.seq = seq; this.rx = []; this.buf = ''; this.closed = false; }
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
        if (m) { clearInterval(iv); res(m); }
        else if (Date.now() - t0 > ms) { clearInterval(iv); rej(new Error(`timeout waiting for ${label}`)); }
      }, 50);
    });
  }
  async logon(password, reset = true) {
    await this.open();
    this.send('A', ['98=0', '108=30', ...(reset ? ['141=Y'] : []), ...(password === null ? [] : [`554=${password}`])]);
  }
  close() { try { this.sock.destroy(); } catch { /* closed */ } }
}

// A refused logon: no Logon(A) back within the window (a Logout(5) or a dropped socket is the refusal).
async function refused(label, sender, password) {
  const s = new Session(sender);
  try { await s.logon(password); } catch (e) { ok(`${label}: connection refused (${e.message})`); return; }
  try {
    await s.wait((m) => m['35'] === 'A', 'logon', 8000);
    s.close(); fail(`${label}: the acceptor answered Logon(A) — FR-ERH-S3 binds ${sender} to Password(554)`);
  } catch {
    const lo = s.rx.find((m) => m['35'] === '5');
    ok(`${label}: no Logon(A); ${lo ? `Logout(5) "${lo['58'] || ''}"` : (s.closed ? 'socket closed' : 'silence')}`);
    result(`refused_${label}`, 1);
    s.close();
  }
}

const er = (s, clOrdId, pred = () => true, label = clOrdId, ms = 20000) => s.wait((m) => m['35'] === '8' && m['11'] === clOrdId && pred(m), `ExecutionReport ${label}`, ms);
const D = (cl, acct, side, qty, px, sym = TICKER) => ['11=' + cl, '1=' + acct, '55=' + sym, '54=' + side, '38=' + qty, '40=2', '44=' + px.toFixed(2), '60=' + ts()];

try {
  console.log('=== 1. FR-ERH-S3: logon is bound to Password(554) ===');
  await refused('no_password', SENDER, null);
  await refused('wrong_password', SENDER, PASSWORD.split('').reverse().join('') + 'x');
  await refused('unbound_compid', 'NOTBOUND', PASSWORD);
  const s = new Session(SENDER);
  await s.logon(PASSWORD);
  await s.wait((m) => m['35'] === 'A', 'logon').catch(() => fail('CLIENT1 with its password was not logged on (the control: refusals above mean nothing without it)'));
  ok(`${SENDER} with its password: Logon(A)`); result('logon_ok', 1);

  const tag = `pa${Date.now() % 1e9}`;
  const S1 = `${tag}-s1`, B1 = `${tag}-b1`;
  console.log('\n=== 2. FR-FIX10: a resent ClOrdID books once ===');
  s.send('D', D(S1, B, 2, 5, PX));
  const n1 = await er(s, S1, (m) => m['150'] === '0' || m['39'] === '0', 'New for S1');
  ok(`Sell ${S1} rests: OrderID=${n1['37']} OrdStatus=${n1['39']}`); result('sell_order_id', n1['37']);
  const before = s.rx.length;
  s.send('D', D(S1, B, 2, 5, PX));
  const dup = await s.wait((m, i) => s.rx.indexOf(m) >= before && (m['35'] === '8' || m['35'] === '3' || m['35'] === 'j') && (m['11'] === S1 || m['35'] !== '8'), 'answer to the resent ClOrdID');
  const dupKind = dup['35'] === '8' && dup['39'] === '8' ? 'reject' : dup['35'] !== '8' ? `session-${dup['35']}` : (dup['37'] === n1['37'] ? 'original' : 'NEW-ORDER');
  console.log(`    resend answered 35=${dup['35']} 39=${dup['39'] || '-'} 37=${dup['37'] || '-'} 58="${dup['58'] || ''}"`);
  if (dupKind === 'NEW-ORDER') fail(`the resent ClOrdID ${S1} got a NEW OrderID ${dup['37']} (first was ${n1['37']}): FR-FIX10 duplicate admitted`);
  ok(`resent ClOrdID answered without a new order (${dupKind})`); result('dup_answer', dupKind);

  console.log('\n=== 3. FR-FIX04: OrderStatusRequest answers from the read model ===');
  // The read model trails the engine (status is off-consensus): ask until it shows the order, up to 10 s.
  let st1;
  for (let attempt = 0; attempt < 10; attempt++) {
    const mark = s.rx.length;
    s.send('H', [`11=${S1}`, `37=${n1['37']}`, `1=${B}`, `55=${TICKER}`, '54=2']);   // Account: S1's owner, or the default account is searched
    st1 = await s.wait((m) => s.rx.indexOf(m) >= mark && m['35'] === '8' && m['11'] === S1 && m['150'] === 'I', 'status (ExecType=I)');
    if (st1['39'] === '0') break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  if (st1['39'] !== '0' || st1['37'] !== n1['37']) suspect(`status of the resting Sell: OrdStatus=${st1['39']} OrderID=${st1['37']} Text="${st1['58'] || ''}", want 0 / ${n1['37']} (FR-FIX04)`);
  else ok(`H -> ExecType=I OrdStatus=0 (New) OrderID=${st1['37']} LeavesQty=${st1['151'] || '?'}`);

  console.log('\n=== 4. a crossing Buy fills both sides ===');
  s.send('D', D(B1, A, 1, 5, PX + 1));
  // FR-FIX05: fills reach the owning session as ExecutionReports. Whether the cross BOOKED is the
  // wrapper's effect-end question, so a missing report is recorded and the session carries on.
  const f1 = await er(s, B1, (m) => m['39'] === '2', 'Filled for B1', 15000).catch(() => null);
  const fs1 = s.rx.find((m) => m['35'] === '8' && m['11'] === S1 && (m['39'] === '2' || m['150'] === 'F'));
  if (!f1) suspect(`no Filled ExecutionReport for the aggressing Buy ${B1} within 15 s (FR-FIX05); its New was ${s.rx.some((m) => m['11'] === B1 && m['39'] === '0') ? 'received' : 'not received'}`);
  else {
    ok(`Buy ${B1} filled: LastPx=${f1['31']} CumQty=${f1['14']}`); result('fill_px', f1['31']);
    if (Math.abs(Number(f1['31']) - PX) > 1e-9) suspect(`fill at ${f1['31']}, want the resting price ${PX}`);
  }
  if (!fs1) suspect(`no fill ExecutionReport for the resting Sell ${S1} (FR-FIX05: the resting side is a FIX order of this session too)`);
  else ok(`resting Sell ${S1}: fill ExecutionReport OrdStatus=${fs1['39']}`);
  // The read model trails the engine (status is off-consensus): ask until it shows the fill, up to 10 s.
  let st2;
  for (let attempt = 0; attempt < 10; attempt++) {
    const mark = s.rx.length;
    s.send('H', [`11=${S1}`, `37=${n1['37']}`, `1=${B}`, `55=${TICKER}`, '54=2']);   // Account: S1's owner, or the default account is searched
    st2 = await s.wait((m) => s.rx.indexOf(m) >= mark && m['35'] === '8' && m['11'] === S1 && m['150'] === 'I', 'second status of S1');
    if (st2['39'] === '2') break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  if (st2['39'] !== '2') suspect(`status of the filled Sell: OrdStatus=${st2['39']} OrderID=${st2['37']} Text="${st2['58'] || ''}", want 2 (Filled) (FR-FIX04)`);
  else ok('H after the cross -> OrdStatus=2 (Filled)');

  console.log('\n=== 5. FR-FIX12: application vs session rejects ===');
  const U1 = `${tag}-u1`;
  s.send('D', D(U1, A, 1, 5, PX, 'ZZNOSUCH9'));
  const r1 = await s.wait((m) => (m['35'] === '8' && m['11'] === U1) || m['35'] === '3' || m['35'] === 'j', 'answer to the unknown symbol', 20000).catch(() => null);
  if (!r1) suspect(`unknown symbol: no answer of any kind within 20 s (FR-FIX12(c): ExecutionReport OrdStatus=8; an application reject is not an ambiguous outcome)`);
  else if (r1['35'] !== '8' || r1['39'] !== '8') suspect(`unknown symbol answered 35=${r1['35']} 39=${r1['39'] || '-'} (FR-FIX12(c): ExecutionReport OrdStatus=8)`);
  else ok(`(c) unknown symbol -> ExecutionReport OrdStatus=8 "${r1['58'] || ''}"`);
  const M1 = `${tag}-m1`, mark = s.rx.length;
  s.send('D', ['11=' + M1, '1=' + A, '55=' + TICKER, '38=5', '40=2', '44=' + PX.toFixed(2), '60=' + ts()]);
  const r2 = await s.wait((m) => s.rx.indexOf(m) >= mark && (m['35'] === '3' || m['35'] === 'j' || (m['35'] === '8' && m['11'] === M1)), 'answer to D without Side(54)', 15000).catch(() => ({ '35': 'none' }));
  if (r2['35'] !== '3') suspect(`D without Side(54) answered 35=${r2['35']} ${r2['58'] || ''} (FR-FIX12(b): session Reject(3))`);
  else ok(`(b) D without Side(54) -> Reject(3) RefTagID=${r2['371'] || '?'} "${r2['58'] || ''}"`);

  console.log('\n=== 6. FR-FIX11: session state survives a reconnect; ResendRequest re-delivers ===');
  // The wrapper's port-forward pins one pod, so this is the same-replica reconnect; yu18-fix-resume
  // proves the cross-replica one (ADR-086).
  const lastIn = Math.max(...s.rx.map((m) => Number(m['34'])));
  const firstEr = Math.min(...s.rx.filter((m) => m['35'] === '8').map((m) => Number(m['34'])));
  // An abrupt drop, not a Logout: the counterparty case FR-FIX11 exists for (and a clean Logout may
  // legitimately reset under ResetOnLogout). Give the acceptor a moment to see the socket close.
  s.close(); await new Promise((r) => setTimeout(r, 2000));
  const s2 = new Session(SENDER, s.seq);
  await s2.logon(PASSWORD, false);
  const a2 = await s2.wait((m) => m['35'] === 'A', 'logon after reconnect').catch(() => fail('reconnect without ResetSeqNumFlag was not logged on'));
  console.log(`    before: last inbound MsgSeqNum ${lastIn}; reconnect Logon MsgSeqNum ${a2['34']}`);
  if (Number(a2['34']) <= lastIn) { suspect(`after an abrupt reconnect to the same gateway the acceptor restarted its outbound sequence at ${a2['34']} (was ${lastIn}): session state did not persist (FR-FIX11)`); s2.close(); console.log(`\nFIX-SESSION ${suspects} FAILED CHECK(S)`); process.exit(1); }
  ok(`Logon continues the outbound sequence (${a2['34']} > ${lastIn})`);
  s2.send('2', [`7=${firstEr}`, '16=0']);
  const re = await s2.wait((m) => m['35'] === '8' && m['43'] === 'Y', 'a re-delivered ExecutionReport (PossDupFlag=Y)')
    .catch(() => { const gf = s2.rx.find((m) => m['35'] === '4'); fail(`ResendRequest from ${firstEr}: no ExecutionReport re-delivered${gf ? ` (SequenceReset-GapFill to ${gf['36']} instead)` : ''}: PersistMessages (FR-FIX11)`); });
  ok(`ResendRequest from ${firstEr}: ExecutionReport ${re['11']} re-delivered, PossDupFlag=Y, MsgSeqNum ${re['34']}`);
  s2.send('5', []); setTimeout(() => { s2.close(); console.log(suspects ? `\nFIX-SESSION ${suspects} FAILED CHECK(S)` : '\nFIX-SESSION OK'); process.exit(suspects ? 1 : 0); }, 500);
} catch (e) { fail(e.message); }
