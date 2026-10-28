// yu18-fix-store-faults.mjs — the session half of yu18-fix-store-faults.sh (ADR-086 §2, §7). No deps.
// Two gateway replicas on their own forwards (FIX_PORT_A, FIX_PORT_B). The wrapper's commands, run here at
// the right moments: STOP_A / CONT_A (SIGSTOP / SIGCONT replica A's JVM), NATS_DOWN / NATS_UP.
//
//   1. Lease takeover: CLIENT1 on A rests S1. A is SIGSTOPped; an order is queued on A's dead-still
//      connection. After the lease TTL, B accepts the resumed Logon and answers S2. A is SIGCONTed: it
//      must drop the client without answering and without putting the queued order into the cluster
//      (the wrapper counts the orders), and B's next report follows its own without a gap.
//   2. Store outage: with the session live on B, NATS goes away. The session ends promptly (no hang);
//      a Logon is refused by name; once NATS is back a Logon is accepted and the sequence continues.
import net from 'node:net';
import { execSync } from 'node:child_process';

const E = process.env;
const PORT_A = Number(E.FIX_PORT_A), PORT_B = Number(E.FIX_PORT_B), PASSWORD = E.FIX_PASSWORD;
const TICKER = E.TICKER, PX = Number(E.PX), B = E.ACCT_B, TTL_S = Number(E.LEASE_TTL_S || 10);
for (const k of ['FIX_PORT_A', 'FIX_PORT_B', 'FIX_PASSWORD', 'TICKER', 'PX', 'ACCT_B', 'STOP_A', 'CONT_A', 'NATS_DOWN', 'NATS_UP']) {
  if (!E[k]) { console.log(`[FAIL] need ${k}`); process.exit(2); }
}
const SOH = '\x01';
const ts = () => { const d = new Date().toISOString(); return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ok = (m) => console.log(`[ok] ${m}`);
const result = (k, v) => console.log(`RESULT ${k}=${v}`);
let stopped = false;
const fail = (m) => { console.log(`[FAIL] ${m}`); if (stopped) run('CONT_A'); process.exit(1); };
const run = (k) => { console.log(`    $ ${k}`); execSync(E[k], { stdio: ['ignore', 'inherit', 'inherit'], shell: '/bin/bash' }); };

class Session {
  constructor(port, seq = 1) { this.port = port; this.seq = seq; this.rx = []; this.buf = ''; this.closed = false; this.closedAt = 0; }
  frame(type, fields) {
    const b = [`35=${type}`, `34=${this.seq++}`, '49=CLIENT1', '56=TRADERX', `52=${ts()}`, ...fields].join(SOH) + SOH;
    const s = `8=FIX.4.4${SOH}9=${b.length}${SOH}${b}`;
    let sum = 0; for (let i = 0; i < s.length; i++) sum += s.charCodeAt(i);
    return s + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
  }
  send(type, fields) { this.sock.write(this.frame(type, fields)); }
  gapFill(begin) {
    const b = ['35=4', `34=${begin}`, '43=Y', `49=CLIENT1`, '56=TRADERX', `52=${ts()}`, `122=${ts()}`, '123=Y', `36=${this.seq}`].join(SOH) + SOH;
    const s = `8=FIX.4.4${SOH}9=${b.length}${SOH}${b}`;
    let sum = 0; for (let i = 0; i < s.length; i++) sum += s.charCodeAt(i);
    return s + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
  }
  open() {
    return new Promise((res, rej) => {
      this.sock = net.createConnection({ host: '127.0.0.1', port: this.port }, res);
      this.sock.setEncoding('ascii');
      this.sock.on('error', (e) => { this.closed = true; rej(e); });
      this.sock.on('close', () => { this.closed = true; this.closedAt = Date.now(); });
      this.sock.on('data', (c) => {
        this.buf += c; let i;
        while ((i = this.buf.indexOf(`${SOH}10=`)) >= 0) {
          const end = this.buf.indexOf(SOH, i + 1); if (end < 0) break;
          const raw = this.buf.slice(0, end + 1); this.buf = this.buf.slice(end + 1);
          const m = Object.fromEntries(raw.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
          this.rx.push(m);
          if (m['35'] === '1') this.send('0', [`112=${m['112']}`]);
          // The acceptor asks for numbers it never processed (the order queued on the stopped owner):
          // answer with SequenceReset-GapFill, i.e. this client does NOT resend that order.
          if (m['35'] === '2') this.sock.write(this.gapFill(Number(m['7'])));
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
    return this.wait((m) => m['35'] === 'A' || m['35'] === '5', 'Logon answer', 25000).catch(() => null);
  }
  maxSeq() { return Math.max(0, ...this.rx.map((m) => Number(m['34']))); }
}
const D = (cl, px) => [`11=${cl}`, `1=${B}`, `55=${TICKER}`, '54=2', '38=1', '40=2', `44=${px.toFixed(2)}`, `60=${ts()}`];
const er = (s, cl) => s.wait((m) => m['35'] === '8' && m['11'] === cl, `ExecutionReport ${cl}`);

try {
  const tag = `sf${Date.now() % 1e9}`;
  console.log('=== 1. lease takeover: SIGSTOP the owner, take over on the other replica, SIGCONT ===');
  const a = new Session(PORT_A);
  if ((await a.logon(true))?.['35'] !== 'A') fail('no Logon on A');
  a.send('D', D(`${tag}-s1`, PX + 1));
  await er(a, `${tag}-s1`);
  ok(`A: ${tag}-s1 rests`);
  run('STOP_A'); stopped = true;
  a.send('D', D(`${tag}-stale`, PX + 1));   // queued on A's connection while A cannot run
  ok(`A stopped; ${tag}-stale queued on its connection; waiting out the ${TTL_S} s lease`);
  await sleep((TTL_S + 4) * 1000);
  const b = new Session(PORT_B, a.seq);
  const lb = await b.logon(false);
  if (lb?.['35'] !== 'A') fail(`B refused the session A can no longer renew: ${JSON.stringify(lb)}`);
  const logonSeq = Number(lb['34']);
  ok(`B accepted the resumed Logon at MsgSeqNum ${logonSeq}`);
  b.send('D', D(`${tag}-s2`, PX + 1));
  const s2 = await er(b, `${tag}-s2`);
  run('CONT_A'); stopped = false;
  const t0 = Date.now();
  while (!a.closed && Date.now() - t0 < 20000) await sleep(100);
  if (!a.closed) fail('A, resumed, kept the stale connection open for 20 s');
  if (a.rx.some((m) => m['11'] === `${tag}-stale`)) fail(`A answered ${tag}-stale after losing the session`);
  ok(`A, resumed, dropped its connection ${((a.closedAt - t0) / 1000).toFixed(1)} s after SIGCONT without answering ${tag}-stale`);
  b.send('D', D(`${tag}-s3`, PX + 1));
  const s3 = await er(b, `${tag}-s3`);
  if (Number(s3['34']) !== Number(s2['34']) + 1) fail(`B's reports ${s2['34']} then ${s3['34']}: A wrote into the session`);
  ok(`B's reports continue ${s2['34']} -> ${s3['34']}: nothing from A reached the store`);
  result('takeover', 1);

  console.log('\n=== 2. store outage: NATS goes away mid-session ===');
  const before = b.maxSeq();
  const t1 = Date.now();
  run('NATS_DOWN');
  while (!b.closed && Date.now() - t1 < 60000) await sleep(100);
  if (!b.closed) fail('the live session on B outlived its store by 60 s');
  ok(`B ended the live session ${((b.closedAt - t1) / 1000).toFixed(1)} s after NATS was stopped`);
  result('outage_drop_s', ((b.closedAt - t1) / 1000).toFixed(1));
  const c = new Session(PORT_B, b.seq);
  const lc = await c.logon(false);
  if (lc?.['35'] !== '5' || !/store unavailable/.test(lc['58'] || '')) fail(`Logon during the outage: ${JSON.stringify(lc)} (want Logout "FIX session store unavailable")`);
  ok(`Logon during the outage refused: Logout "${lc['58']}"`);
  run('NATS_UP');
  let ld = null, d = null;
  for (let i = 0; i < 20 && ld?.['35'] !== 'A'; i++) {
    d = new Session(PORT_B, b.seq);   // the refused Logon was not stored: the store still expects b's next
    ld = await d.logon(false);
    if (ld?.['35'] !== 'A') { d.sock.destroy(); await sleep(3000); }
  }
  if (ld?.['35'] !== 'A') fail(`no Logon after NATS came back: ${JSON.stringify(ld)}`);
  if (Number(ld['34']) !== before + 1) fail(`after the outage B's Logon is ${ld['34']}, want ${before + 1}: the stored sequence did not survive`);
  ok(`after NATS returned, Logon accepted at ${ld['34']} (the session continues from ${before})`);
  result('outage_resume', 1);
  d.send('5', []);
  await sleep(300);
  console.log('\nFIX-STORE-FAULTS OK');
  process.exit(0);
} catch (e) { fail(e.message); }
