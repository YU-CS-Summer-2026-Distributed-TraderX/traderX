// fix-cancel-probe.mjs — minimal FIX 4.4 client that exercises OrderCancelRequest (F) against
// cluster/FixGatewayAcceptor. No deps.
//
// Trap (cost chat 1 a debugging cycle): FIX UTCTimestamp is YYYYMMDD-HH:MM:SS.sss — the DATE part
// has no separators but the TIME part KEEPS its colons. Strip them and QuickFIX/J rejects every
// logon with "Incorrect data format for value, field=52", silently, with no socket error.
import net from 'node:net';

const HOST = process.env.FIX_HOST || 'localhost';
const PORT = Number(process.env.FIX_PORT || 18130);
const SENDER = process.env.SENDER || 'LOAD0000';
const TARGET = process.env.TARGET || 'TRADERX';
const ACCOUNT = process.env.ACCOUNT || '99001';
const TICKER = process.env.TICKER || 'JPM';
const PRICE = process.env.PRICE || '100.00';
const QTY = process.env.QTY || '7';

const SOH = '\x01';
const ts = () => {
  const d = new Date().toISOString(); // 2026-07-22T17:40:00.123Z
  return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`;
};

let seq = 1;
const body = (type, fields) =>
  [`35=${type}`, `34=${seq++}`, `49=${SENDER}`, `56=${TARGET}`, `52=${ts()}`, ...fields].join(SOH) + SOH;

function frame(type, fields) {
  const b = body(type, fields);
  const head = `8=FIX.4.4${SOH}9=${b.length}${SOH}`;
  const noSum = head + b;
  let sum = 0;
  for (let i = 0; i < noSum.length; i++) sum += noSum.charCodeAt(i);
  return noSum + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
}

const parse = (raw) => Object.fromEntries(
  raw.split(SOH).filter(Boolean).map((kv) => {
    const i = kv.indexOf('=');
    return [kv.slice(0, i), kv.slice(i + 1)];
  }));

const sock = net.createConnection({ host: HOST, port: PORT });
sock.setEncoding('ascii');

const received = [];
let buf = '';
const waitFor = (pred, label, ms = 20000) => new Promise((res, rej) => {
  const hit = received.find(pred);
  if (hit) return res(hit);
  const t = setTimeout(() => rej(new Error(`timeout waiting for ${label}`)), ms);
  const iv = setInterval(() => {
    const m = received.find(pred);
    if (m) { clearInterval(iv); clearTimeout(t); res(m); }
  }, 50);
});

sock.on('data', (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf('10=')) >= 0) {
    const end = buf.indexOf(SOH, i);
    if (end < 0) break;
    const raw = buf.slice(0, end + 1);
    buf = buf.slice(end + 1);
    const m = parse(raw);
    received.push(m);
    if (m['35'] === '0') sock.write(frame('0', []));                  // heartbeat
    if (m['35'] === '1') sock.write(frame('0', [`112=${m['112']}`])); // test request
  }
});

const fail = (msg) => { console.log(`[FAIL] ${msg}`); sock.destroy(); process.exit(1); };
const ok = (msg) => console.log(`[ok] ${msg}`);

sock.on('connect', async () => {
  try {
    sock.write(frame('A', ['98=0', '108=30', '141=Y']));
    await waitFor((m) => m['35'] === 'A', 'logon');
    ok(`logged on as ${SENDER}`);

    // --- 1. NewOrderSingle (D) -> resting order
    const clOrdId = `fixcxl-${Date.now()}`;
    sock.write(frame('D', [`11=${clOrdId}`, `1=${ACCOUNT}`, `55=${TICKER}`, '54=1',
      `38=${QTY}`, '40=2', `44=${PRICE}`, `60=${ts()}`]));
    const er = await waitFor((m) => m['35'] === '8' && m['11'] === clOrdId, 'ExecutionReport for D');
    if (er['39'] !== '0') fail(`order not NEW: OrdStatus=${er['39']} text=${er['58'] || ''}`);
    ok(`D accepted: OrderID=${er['37']} OrdStatus=${er['39']}`);

    // --- 2. OrderCancelRequest (F) by OrigClOrdID ALONE (no tag 37) — the map path
    const cxl1 = `${clOrdId}-c1`;
    sock.write(frame('F', [`11=${cxl1}`, `41=${clOrdId}`, `55=${TICKER}`, '54=1',
      `38=${QTY}`, `60=${ts()}`]));
    const r1 = await waitFor((m) => (m['35'] === '8' || m['35'] === '9') && m['11'] === cxl1,
      'response to F');
    if (r1['35'] !== '8') fail(`expected ExecutionReport, got OrderCancelReject: reason=${r1['102']} text=${r1['58'] || ''}`);
    if (r1['39'] !== '4') fail(`expected OrdStatus=4 (Canceled), got ${r1['39']}`);
    if (r1['150'] !== '4') fail(`expected ExecType=4 (Canceled), got ${r1['150']}`);
    ok(`F by OrigClOrdID alone -> ExecutionReport OrdStatus=4 Canceled, OrderID=${r1['37']}`);

    // --- 3. cancel the SAME order again -> the ref was evicted from the map on success, so this
    //        must come back as an OrderCancelReject with UNKNOWN_ORDER, not a second cancel.
    const cxl2 = `${clOrdId}-c2`;
    sock.write(frame('F', [`11=${cxl2}`, `41=${clOrdId}`, `55=${TICKER}`, '54=1', `60=${ts()}`]));
    const r2 = await waitFor((m) => (m['35'] === '8' || m['35'] === '9') && m['11'] === cxl2,
      'response to repeat F');
    if (r2['35'] !== '9') fail(`expected OrderCancelReject for a forgotten OrigClOrdID, got 35=${r2['35']}`);
    ok(`repeat F -> OrderCancelReject CxlRejReason=${r2['102']} text="${r2['58'] || ''}"`);

    // --- 4. cancel by OrderID (37) — the cross-gateway path, must still resolve
    const cxl3 = `${clOrdId}-c3`;
    sock.write(frame('F', [`11=${cxl3}`, `41=${clOrdId}`, `37=${er['37']}`, `55=${TICKER}`,
      '54=1', `60=${ts()}`]));
    const r3 = await waitFor((m) => (m['35'] === '8' || m['35'] === '9') && m['11'] === cxl3,
      'response to F by OrderID');
    if (r3['35'] !== '8' || r3['39'] !== '4') {
      fail(`cancel by OrderID should be idempotently Canceled, got 35=${r3['35']} 39=${r3['39']} 102=${r3['102']}`);
    }
    ok(`F by OrderID=${er['37']} -> OrdStatus=4 (idempotent, resolved without the local map)`);

    // --- 5. cancel a genuinely unknown order -> OrderCancelReject / UNKNOWN_ORDER
    const cxl4 = `${clOrdId}-c4`;
    sock.write(frame('F', [`11=${cxl4}`, '41=never-existed', '37=ord-999999999',
      `55=${TICKER}`, '54=1', `60=${ts()}`]));
    const r4 = await waitFor((m) => (m['35'] === '8' || m['35'] === '9') && m['11'] === cxl4,
      'response to unknown F');
    if (r4['35'] !== '9') fail(`expected OrderCancelReject for unknown order, got 35=${r4['35']}`);
    if (r4['102'] !== '1') fail(`expected CxlRejReason=1 (unknown order), got ${r4['102']}`);
    ok(`unknown order -> OrderCancelReject CxlRejReason=1 text="${r4['58'] || ''}"`);

    console.log('\n=== PASS — FIX OrderCancelRequest (F) answered correctly on all four paths ===');
    sock.write(frame('5', []));
    setTimeout(() => { sock.destroy(); process.exit(0); }, 300);
  } catch (e) {
    fail(e.message);
  }
});

sock.on('error', (e) => fail(`socket: ${e.message}`));
