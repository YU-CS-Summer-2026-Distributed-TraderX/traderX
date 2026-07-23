// fix-multi.mjs — N-session FIX 4.4 load generator against cluster/FixGatewayAcceptor.
// Coordinated-omission safe: every order is timed from its INTENDED send time on a fixed per-session
// schedule, so a stalled owner thread shows as latency, never as a silently dropped sample.
//
// Rebuilt 2026-07-23 for the GKE venue-shaped FIX number (brief 02 task 1). The chat-1 original
// lived only in a session scratchpad that is gone; the FIX framing + the UTCTimestamp trap come from
// the committed yu13-fix-cancel.mjs, the two-account crossing from yu13-two-account-bench.sh.
//
// EXACT PER-GATEWAY PLACEMENT. GATEWAYS is a comma list of gateway *pod IPs* :18130. Session i is
// pinned to GATEWAYS[i % G] directly, bypassing the ClientIP-affinity FIX Service — so the
// per-gateway session split is exact by construction, not a hash we have to measure and hope is even.
// (Verify server-side anyway via each gateway's traderx_order_events_total{event="accepted"}.)
//
// TWO-SIDED so sessions actually cross (post-STP a single self-crossing account books zero fills):
// even sessions BUY on ACCT_BUY, odd sessions SELL on ACCT_SELL, both at PRICE -> they cross globally
// in the one cluster matching engine.
//
// UTCTimestamp trap (cost chat 1 a cycle): YYYYMMDD-HH:MM:SS.sss — the DATE has no separators but the
// TIME KEEPS its colons. Strip them and QuickFIX/J silently rejects every logon, field=52, no socket error.
import net from 'node:net';
import http from 'node:http';

const GATEWAYS = (process.env.GATEWAYS || 'localhost:18130').split(',').map((s) => s.trim());
const SESSIONS = Number(process.env.SESSIONS || 10);
const SECS = Number(process.env.SECS || 20);
// Per-session order rate. If TOTAL is set, per-session rate = TOTAL/SESSIONS (constant total offered
// load as session count varies — the clean flatness test). Else use RATE directly.
const TOTAL = process.env.TOTAL ? Number(process.env.TOTAL) : null;
const RATE = TOTAL ? TOTAL / SESSIONS : Number(process.env.RATE || 5);
const TARGET = process.env.TARGET || 'TRADERX';
const ACCT_BUY = process.env.ACCT_BUY || '42422';
const ACCT_SELL = process.env.ACCT_SELL || '22214';
const TICKER = process.env.TICKER || 'JPM';
const PRICE = process.env.PRICE || '100.00';
const QTY = process.env.QTY || '10';
const PREFIX = process.env.COMPID_PREFIX || 'LOAD';
const START = Number(process.env.COMPID_START || 0);
const LOGON_TIMEOUT_MS = Number(process.env.LOGON_TIMEOUT_MS || 12000);
// Spread TCP connects/logons over ~CONNECT_SPREAD_MS so we don't fan 500 simultaneous logons at the
// acceptors at once (60-at-once gave 46/60 up and a skewed split — the acceptor onLogon serializes).
const CONNECT_SPREAD_MS = Number(process.env.CONNECT_SPREAD_MS || 5000);
const WARMUP_MS = Number(process.env.WARMUP_MS || 2000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Authoritative server-side throughput: each gateway's own accepted counter (client-completed
// under-counts when egress ERs drop under flood). Read in-cluster over the EXACT load window.
function scrape(host, port, path, re) {
  return new Promise((resolve) => {
    const req = http.get({ host, port, path, timeout: 4000 }, (res) => {
      let d = '';
      res.on('data', (c) => { d += c; });
      res.on('end', () => { const m = d.match(re); resolve(m ? Number(m[1]) : NaN); });
    });
    req.on('error', () => resolve(NaN));
    req.on('timeout', () => { req.destroy(); resolve(NaN); });
  });
}
const gwAccepted = (ipPort) => scrape(ipPort.split(':')[0], 18110, '/metrics', /event="accepted"\}\s+(\d+)/);
// Authoritative, outcome-independent throughput: member's sequenced-order gauge. Counts every order
// that reached consensus whether it booked, rested, crossed or was risk-rejected — unlike the
// gateway 'accepted' counter, which only tallies booked orders.
const MEMBER = process.env.MEMBER || '10.8.5.3';
const memNextRef = () => scrape(MEMBER, 8080, '/metrics', /traderx_cluster_next_order_ref\S*\s+(\d+)/);

const SOH = '\x01';
const ts = () => {
  const d = new Date().toISOString(); // 2026-07-23T17:40:00.123Z
  return `${d.slice(0, 4)}${d.slice(5, 7)}${d.slice(8, 10)}-${d.slice(11, 23)}`;
};
const compId = (i) => `${PREFIX}${String(START + i).padStart(4, '0')}`;

// ---- one session ----
class Session {
  constructor(i) {
    this.i = i;
    this.sender = compId(i);
    const [host, port] = GATEWAYS[i % GATEWAYS.length].split(':');
    this.host = host;
    this.port = Number(port);
    this.gw = i % GATEWAYS.length;          // which gateway index this session hits
    this.buy = (i % 2) === 0;               // even buys, odd sells -> crossing
    this.account = this.buy ? ACCT_BUY : ACCT_SELL;
    this.side = this.buy ? '1' : '2';
    this.seq = 1;
    this.buf = '';
    this.inflight = new Map();               // clOrdId -> intendedMs
    this.loggedOn = false;
    this.offered = 0;
    this.completed = 0;
    this.k = 0;                              // orders scheduled so far
  }

  body(type, fields) {
    return [`35=${type}`, `34=${this.seq++}`, `49=${this.sender}`, `56=${TARGET}`,
      `52=${ts()}`, ...fields].join(SOH) + SOH;
  }

  frame(type, fields) {
    const b = this.body(type, fields);
    const head = `8=FIX.4.4${SOH}9=${b.length}${SOH}`;
    const noSum = head + b;
    let sum = 0;
    for (let j = 0; j < noSum.length; j++) sum += noSum.charCodeAt(j);
    return noSum + `10=${String(sum % 256).padStart(3, '0')}${SOH}`;
  }

  connect() {
    return new Promise((resolve) => {
      this.resolveLogon = resolve;
      const sock = net.createConnection({ host: this.host, port: this.port });
      this.sock = sock;
      sock.setEncoding('ascii');
      sock.setNoDelay(true);
      sock.on('connect', () => sock.write(this.frame('A', ['98=0', '108=30', '141=Y'])));
      sock.on('data', (chunk) => this.onData(chunk));
      sock.on('error', () => { if (!this.loggedOn) resolve(false); });
      this.logonTimer = setTimeout(() => { if (!this.loggedOn) resolve(false); }, LOGON_TIMEOUT_MS);
    });
  }

  onData(chunk) {
    this.buf += chunk;
    let i;
    while ((i = this.buf.indexOf('10=')) >= 0) {
      const end = this.buf.indexOf(SOH, i);
      if (end < 0) break;
      const raw = this.buf.slice(0, end + 1);
      this.buf = this.buf.slice(end + 1);
      this.onMsg(raw);
    }
  }

  onMsg(raw) {
    const m = {};
    for (const kv of raw.split(SOH)) {
      if (!kv) continue;
      const j = kv.indexOf('=');
      m[kv.slice(0, j)] = kv.slice(j + 1);
    }
    const type = m['35'];
    if (type === '1') { this.sock.write(this.frame('0', [`112=${m['112']}`])); return; } // TestRequest
    if (type === '0') return;                                                             // Heartbeat
    if (type === 'A') {
      if (!this.loggedOn) { this.loggedOn = true; clearTimeout(this.logonTimer); this.resolveLogon(true); }
      return;
    }
    if (type === '5' || type === '3') return; // Logout / Reject (seq issues etc.)
    if (type === '8') {                        // ExecutionReport — the committed ack for a D
      const cl = m['11'];
      const intended = this.inflight.get(cl);
      if (intended !== undefined) {
        this.inflight.delete(cl);
        this.completed++;
        stats.latencies.push(nowMs() - intended);
        stats.perGwCompleted[this.gw]++;
      }
    }
  }

  // Send one scheduled order whose intended time is `intendedMs`.
  fire(intendedMs) {
    const cl = `${this.sender}-${this.k}`;
    this.inflight.set(cl, intendedMs);
    this.offered++;
    this.sock.write(this.frame('D', [
      `11=${cl}`, `1=${this.account}`, `55=${TICKER}`, `54=${this.side}`,
      `38=${QTY}`, '40=2', `44=${PRICE}`, `60=${ts()}`,
    ]));
  }

  close() { try { this.sock.write(this.frame('5', [])); this.sock.destroy(); } catch { /* noop */ } }
}

const hr = process.hrtime.bigint();
const nowMs = () => Number(process.hrtime.bigint() - hr) / 1e6;

const stats = { latencies: [], perGwCompleted: GATEWAYS.map(() => 0) };

function pct(arr, p) {
  if (!arr.length) return NaN;
  const a = [...arr].sort((x, y) => x - y);
  return a[Math.min(a.length - 1, Math.floor((p / 100) * a.length))];
}

async function main() {
  console.log(`fix-multi: ${SESSIONS} sessions across ${GATEWAYS.length} gateways ` +
    `(${GATEWAYS.join(', ')}), rate ${RATE.toFixed(3)}/s/session ` +
    `(${(RATE * SESSIONS).toFixed(0)}/s offered total), ${SECS}s, ` +
    `${TICKER} qty ${QTY} @ ${PRICE}, buy=${ACCT_BUY} sell=${ACCT_SELL}`);

  const sessions = Array.from({ length: SESSIONS }, (_, i) => new Session(i));
  const t0logon = nowMs();
  const gap = SESSIONS > 1 ? Math.min(50, CONNECT_SPREAD_MS / SESSIONS) : 0;
  const pending = sessions.map(async (s, i) => { await sleep(i * gap); return s.connect(); });
  await Promise.all(pending);
  const up = sessions.filter((s) => s.loggedOn);
  const perGwUp = GATEWAYS.map((_, g) => up.filter((s) => s.gw === g).length);
  console.log(`logon: ${up.length}/${SESSIONS} up in ${(nowMs() - t0logon).toFixed(0)}ms; ` +
    `per-gateway sessions: ${perGwUp.join(' / ')}`);
  if (!up.length) { console.log('[FAIL] no sessions logged on'); process.exit(1); }

  // Warm up (let sessions reach steady state), then snapshot each gateway's accepted counter so the
  // authoritative server-side rate is measured over EXACTLY the load window.
  await sleep(WARMUP_MS);
  const gwList = GATEWAYS;
  const srvT0 = nowMs();
  const srv0 = await Promise.all(gwList.map(gwAccepted));
  const ref0 = await memNextRef();

  // Fixed schedule, started simultaneously for all sessions.
  const startMs = nowMs();
  const endMs = startMs + SECS * 1000;
  const period = 1000 / RATE;

  const reportIv = setInterval(() => {
    const off = up.reduce((a, s) => a + s.offered, 0);
    const cmp = up.reduce((a, s) => a + s.completed, 0);
    const t = (nowMs() - startMs) / 1000;
    console.log(`  t=${t.toFixed(0)}s offered=${off} completed=${cmp} ` +
      `inflight=${off - cmp} rate=${(cmp / t).toFixed(0)}/s`);
  }, 2000);

  // Drive each session's schedule off one shared timer to avoid 500 independent intervals.
  await new Promise((resolve) => {
    const tick = () => {
      const now = nowMs();
      for (const s of up) {
        // catch the schedule up to `now` (bounded so a slow start can't burst thousands at once)
        let fired = 0;
        while (s.k * period <= (now - startMs) && fired < 64) {
          s.fire(startMs + s.k * period);
          s.k++;
          fired++;
        }
      }
      if (now >= endMs) return resolve();
      setTimeout(tick, 2);
    };
    tick();
  });

  // Snapshot gateway counters at load end (before drain) → authoritative per-gateway server rate.
  const srv1 = await Promise.all(gwList.map(gwAccepted));
  const ref1 = await memNextRef();
  const srvWin = (nowMs() - srvT0) / 1000;

  clearInterval(reportIv);
  // brief drain for in-flight ERs
  await new Promise((r) => setTimeout(r, 3000));

  const off = up.reduce((a, s) => a + s.offered, 0);
  const cmp = up.reduce((a, s) => a + s.completed, 0);
  const wall = (nowMs() - startMs) / 1000;
  console.log('\n=== RESULT ===');
  console.log(`sessions up:        ${up.length}/${SESSIONS}  (per-gateway ${perGwUp.join(' / ')})`);
  console.log(`offered:            ${off}  (${(off / SECS).toFixed(0)}/s over the ${SECS}s window)`);
  console.log(`completed:          ${cmp}  (${(cmp / SECS).toFixed(0)}/s)`);
  console.log(`in-flight at end:   ${off - cmp}`);
  console.log(`per-gateway completed (client-side): ${stats.perGwCompleted.join(' / ')}`);
  // Authoritative: gateway accepted-counter deltas over the exact load window.
  const srvD = srv0.map((v, g) => (Number.isNaN(v) || Number.isNaN(srv1[g])) ? NaN : srv1[g] - v);
  const srvTot = srvD.reduce((a, v) => a + (Number.isNaN(v) ? 0 : v), 0);
  console.log(`SERVER-SIDE accepted over ${srvWin.toFixed(1)}s window (authoritative):`);
  srvD.forEach((v, g) => console.log(`  gateway ${g} (${gwList[g]}): +${v} = ${(v / srvWin).toFixed(0)}/s`));
  console.log(`  AGGREGATE: +${srvTot} = ${(srvTot / srvWin).toFixed(0)}/s   ` +
    `per-gw share ${srvD.map((v) => srvTot ? Math.round((v / srvTot) * 100) + '%' : '0%').join(' / ')}`);
  // Ground truth: member nextOrderRef delta = every order sequenced through consensus in the window.
  const refD = (Number.isNaN(ref0) || Number.isNaN(ref1)) ? NaN : ref1 - ref0;
  console.log(`MEMBER nextOrderRef delta (authoritative committed/s): +${refD} = ${(refD / srvWin).toFixed(0)}/s`);
  console.log(`latency (from intended send) p50 ${pct(stats.latencies, 50)?.toFixed(0)}ms  ` +
    `p99 ${pct(stats.latencies, 99)?.toFixed(0)}ms  max ${stats.latencies.reduce((m,x)=>x>m?x:m,0).toFixed(0)}ms`);
  console.log(`(wall incl. drain ${wall.toFixed(1)}s)`);

  for (const s of up) s.close();
  setTimeout(() => process.exit(0), 300);
}

main().catch((e) => { console.log(`[FAIL] ${e.message}`); process.exit(1); });
