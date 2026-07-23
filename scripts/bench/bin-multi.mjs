// bin-multi.mjs — N-connection BINARY order-entry load generator against cluster/BinaryGatewayAcceptor
// (brief 03, lever 4). The third ingress contract: binary per-order, distinct from FIX-text per-order
// (~20k/s) and JSON batch (438k/s) — NEVER blend the three. This drives the binary fast path the same
// way fix-multi.mjs drives FIX, so the two numbers are a clean single-variable A/B (wire format only).
//
// Committed on creation: a prior FIX rig went missing living only in a session scratchpad. This is the
// only generator that speaks the binary protocol; the others speak FIX text or JSON.
//
// EXACT PER-GATEWAY PLACEMENT. GATEWAYS is a comma list of gateway pod IPs; connection i is pinned to
// GATEWAYS[i % G]:BIN_PORT directly, so the per-gateway split is exact by construction. HTTP (:HTTP_PORT)
// on the SAME pod IPs is used ONLY for the one-time /seed + /resolve at startup and the authoritative
// server-side counter reads — never on the order path.
//
// TWO-SIDED so connections actually cross (post-STP a single self-crossing account books zero fills):
// even connections BUY on ACCT_BUY, odd SELL on ACCT_SELL, both at PRICE -> they cross in the one engine.
//
// Wire (all little-endian, incl. the 2-byte length prefix), matching BinaryGatewayAcceptor:
//   NEW  frame = [u16 len=32][u8 type=1][u8 side(0=BUY,1=SELL)][2 pad][u32 acct][u32 sec][i32 qty]
//                [i64 limitPx x1e6][u64 clOrdId]
//   ACK  frame = [u16 len=16][u8 0x81][u8 status(1=acc,0=rej,2=ambig,3=proto)][u8 kind][u8 riskReason]
//                [u32 orderRef][u64 clOrdId echo]
import net from 'node:net';
import http from 'node:http';
import crypto from 'node:crypto';

const GATEWAYS = (process.env.GATEWAYS || 'localhost:18140').split(',').map((s) => s.trim());
const BIN_PORT = Number(process.env.BIN_PORT || 18140);   // per-gateway binary port (overrides :port in GATEWAYS if absent)
const HTTP_PORT = Number(process.env.HTTP_PORT || 18110); // gateway HTTP: /seed, /resolve, /metrics
const SESSIONS = Number(process.env.SESSIONS || 10);      // = TCP connections
const SECS = Number(process.env.SECS || 20);
// Per-connection order rate. TOTAL set -> per-conn = TOTAL/SESSIONS (constant offered load as conn
// count varies — the clean flatness test); else RATE directly.
const TOTAL = process.env.TOTAL ? Number(process.env.TOTAL) : null;
const RATE = TOTAL ? TOTAL / SESSIONS : Number(process.env.RATE || 50);
const ACCT_BUY = Number(process.env.ACCT_BUY || 42422);
const ACCT_SELL = Number(process.env.ACCT_SELL || 22214);
const TICKER = process.env.TICKER || 'JPM';
const PRICE = Number(process.env.PRICE || 100.0);
const QTY = Number(process.env.QTY || 10);
const PX_TICKS = Math.round(PRICE * 1_000_000);
const WARMUP_MS = Number(process.env.WARMUP_MS || 2000);
const CONNECT_SPREAD_MS = Number(process.env.CONNECT_SPREAD_MS || 3000);
// Every process must own a distinct key range. The old fixed ranges restarted from zero on each
// run, silently changing later measurements into cheaper idempotent-replay workloads.
const RUN_ID = BigInt(process.env.RUN_ID || crypto.randomInt(1, 65536));
const POD_INDEX = BigInt(process.env.POD_INDEX || 0);
const START_AT_MS = Number(process.env.START_AT_MS || 0);
const DO_SEED = process.env.SEED !== '0'; // self-contained by default: enable accounts + register ticker
const MEMBER = process.env.MEMBER || '';  // member IP:8080 for the authoritative nextOrderRef delta
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const hr = process.hrtime.bigint();
const nowMs = () => Number(process.hrtime.bigint() - hr) / 1e6;

// ---- HTTP helpers (startup + measurement only; NEVER on the order path) ----
function httpJson(host, port, method, path, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null;
    const req = http.request({ host, port, method, path, timeout: 5000,
      headers: data ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) } : {} },
      (res) => { let d = ''; res.on('data', (c) => { d += c; }); res.on('end', () => resolve({ code: res.statusCode, body: d })); });
    req.on('error', () => resolve({ code: 0, body: '' }));
    req.on('timeout', () => { req.destroy(); resolve({ code: 0, body: '' }); });
    if (data) req.write(data);
    req.end();
  });
}
function scrape(host, port, path, re) {
  return new Promise((resolve) => {
    const req = http.get({ host, port, path, timeout: 4000 }, (res) => {
      let d = ''; res.on('data', (c) => { d += c; });
      res.on('end', () => { const m = d.match(re); resolve(m ? Number(m[1]) : NaN); });
    });
    req.on('error', () => resolve(NaN));
    req.on('timeout', () => { req.destroy(); resolve(NaN); });
  });
}
const gwHost = (g) => GATEWAYS[g].split(':')[0];
const gwBinPort = (g) => Number(GATEWAYS[g].split(':')[1] || BIN_PORT);
const gwAccepted = (g) => scrape(gwHost(g), HTTP_PORT, '/metrics', /event="accepted"\}\s+(\d+)/);
const gwStage = (g, family, stage) => scrape(gwHost(g), HTTP_PORT, '/metrics',
  new RegExp(`${family}\\{stage="${stage}"\\}\\s+(\\d+)`));
const memNextRef = () => (MEMBER
  ? scrape(MEMBER.split(':')[0], Number(MEMBER.split(':')[1] || 8080), '/metrics', /traderx_cluster_next_order_ref\S*\s+(\d+)/)
  : Promise.resolve(NaN));

// ---- binary framing ----
function frameNew(side, account, security, qty, pxTicks, clOrdId) {
  const b = Buffer.allocUnsafe(2 + 32);
  b.writeUInt16LE(32, 0);                    // length prefix
  b.writeUInt8(1, 2);                        // payload[0] msgType = NEW
  b.writeUInt8(side, 3);                     // payload[1] side (0=BUY,1=SELL)
  b.writeUInt16LE(0, 4);                     // payload[2..3] pad
  b.writeUInt32LE(account, 6);               // payload[4]
  b.writeUInt32LE(security, 10);             // payload[8]
  b.writeInt32LE(qty, 14);                   // payload[12]
  b.writeBigInt64LE(BigInt(pxTicks), 18);    // payload[16]
  b.writeBigUInt64LE(BigInt(clOrdId), 26);   // payload[24]
  return b;
}

const stats = { latencies: [], perGwCompleted: GATEWAYS.map(() => 0) };

// ---- one connection ----
class Conn {
  constructor(i, security) {
    this.i = i;
    this.gw = i % GATEWAYS.length;
    this.host = gwHost(this.gw);
    this.port = gwBinPort(this.gw);
    this.buy = (i % 2) === 0;
    this.account = this.buy ? ACCT_BUY : ACCT_SELL;
    this.side = this.buy ? 0 : 1;
    this.security = security;
    if (RUN_ID < 0n || RUN_ID > 65535n || POD_INDEX < 0n || POD_INDEX > 255n || i >= 65536) {
      throw new Error('RUN_ID, POD_INDEX, or connection index exceeds its wire key partition');
    }
    // 16b run | 8b pod | 16b connection | 24b sequence = one uint64 client-order key.
    this.base = (RUN_ID << 48n) | (POD_INDEX << 40n) | (BigInt(i) << 24n);
    this.k = 0n;
    this.offered = 0;
    this.writeBackpressure = 0;
    this.completed = 0;
    this.up = false;
    this.inflight = new Map();     // clOrdId -> intendedMs
    this.acc = Buffer.alloc(0);    // ack accumulator
  }

  connect() {
    return new Promise((resolve) => {
      const sock = net.createConnection({ host: this.host, port: this.port });
      this.sock = sock;
      sock.setNoDelay(true);
      sock.on('connect', () => { this.up = true; resolve(true); });
      sock.on('data', (chunk) => this.onData(chunk));
      sock.on('error', () => { if (!this.up) resolve(false); });
      setTimeout(() => { if (!this.up) resolve(false); }, 8000);
    });
  }

  onData(chunk) {
    this.acc = this.acc.length ? Buffer.concat([this.acc, chunk]) : chunk;
    while (this.acc.length >= 2) {
      const len = this.acc.readUInt16LE(0);
      if (this.acc.length < 2 + len) break;
      const p = 2; // payload start
      const clOrdId = this.acc.readBigUInt64LE(p + 8);
      const intended = this.inflight.get(clOrdId);
      if (intended !== undefined) {
        this.inflight.delete(clOrdId);
        this.completed++;
        stats.latencies.push(nowMs() - intended);
        stats.perGwCompleted[this.gw]++;
      }
      this.acc = this.acc.subarray(2 + len);
    }
  }

  fire(intendedMs) {
    const clOrdId = this.base + this.k;
    this.inflight.set(clOrdId, intendedMs);
    this.offered++;
    if (!this.sock.write(frameNew(this.side, this.account, this.security, QTY, PX_TICKS, clOrdId))) {
      this.writeBackpressure++;
    }
    this.k++;
  }

  close() { try { this.sock.destroy(); } catch { /* noop */ } }
}

function pct(arr, p) {
  if (!arr.length) return NaN;
  const a = [...arr].sort((x, y) => x - y);
  return a[Math.min(a.length - 1, Math.floor((p / 100) * a.length))];
}

async function main() {
  const g0h = gwHost(0);
  // 1) Seed accounts + ticker + price through the sequenced ingress (self-contained rig).
  if (DO_SEED) {
    for (const acct of [ACCT_BUY, ACCT_SELL]) {
      const r = await httpJson(g0h, HTTP_PORT, 'POST', '/seed',
        { accountId: acct, tickers: TICKER, price: PRICE });
      if (r.code !== 200) { console.log(`[FAIL] seed acct ${acct}: ${r.code} ${r.body}`); process.exit(1); }
    }
  }
  // 2) Resolve the numeric securityId the binary protocol carries (cold path, once).
  const rr = await httpJson(g0h, HTTP_PORT, 'POST', '/resolve', { ticker: TICKER });
  const secMatch = rr.body.match(/"securityId":(\d+)/);
  if (rr.code !== 200 || !secMatch) { console.log(`[FAIL] resolve ${TICKER}: ${rr.code} ${rr.body}`); process.exit(1); }
  const security = Number(secMatch[1]);

  console.log(`bin-multi: ${SESSIONS} connections across ${GATEWAYS.length} gateways ` +
    `(${GATEWAYS.map((g, i) => `${gwHost(i)}:${gwBinPort(i)}`).join(', ')}), ` +
    `run ${RUN_ID} pod ${POD_INDEX}, rate ${RATE.toFixed(2)}/s/conn ` +
    `(${(RATE * SESSIONS).toFixed(0)}/s scheduled), ${SECS}s, ` +
    `${TICKER}=#${security} qty ${QTY} @ ${PRICE}, buy=${ACCT_BUY} sell=${ACCT_SELL}`);

  const conns = Array.from({ length: SESSIONS }, (_, i) => new Conn(i, security));
  const t0 = nowMs();
  const gap = SESSIONS > 1 ? Math.min(50, CONNECT_SPREAD_MS / SESSIONS) : 0;
  await Promise.all(conns.map(async (c, i) => { await sleep(i * gap); return c.connect(); }));
  const up = conns.filter((c) => c.up);
  const perGwUp = GATEWAYS.map((_, g) => up.filter((c) => c.gw === g).length);
  console.log(`connected: ${up.length}/${SESSIONS} in ${(nowMs() - t0).toFixed(0)}ms; per-gateway: ${perGwUp.join(' / ')}`);
  if (!up.length) { console.log('[FAIL] no connections'); process.exit(1); }

  // Warm, then snapshot the authoritative counters over EXACTLY the load window.
  await sleep(WARMUP_MS);
  if (START_AT_MS > 0) {
    const barrierWait = START_AT_MS - Date.now();
    if (barrierWait < -1000) {
      console.log(`[FAIL] synchronized start missed by ${-barrierWait}ms`);
      process.exit(1);
    }
    if (barrierWait > 0) {
      console.log(`barrier: waiting ${barrierWait}ms for epoch ${START_AT_MS}`);
      await sleep(barrierWait);
    }
  }
  const srvT0 = nowMs();
  const srv0 = await Promise.all(GATEWAYS.map((_, g) => gwAccepted(g)));
  const decoded0 = await Promise.all(GATEWAYS.map((_, g) =>
    gwStage(g, 'traderx_binary_frames_total', 'decoded')));
  const offer0 = await Promise.all(GATEWAYS.map((_, g) =>
    gwStage(g, 'traderx_gateway_pipeline_total', 'offer_success')));
  const ack0 = await Promise.all(GATEWAYS.map((_, g) =>
    gwStage(g, 'traderx_gateway_pipeline_total', 'ack_completed')));
  const ref0 = await memNextRef();

  const startMs = nowMs();
  const endMs = startMs + SECS * 1000;
  const period = 1000 / RATE;
  const reportIv = setInterval(() => {
    const off = up.reduce((a, c) => a + c.offered, 0);
    const cmp = up.reduce((a, c) => a + c.completed, 0);
    const t = (nowMs() - startMs) / 1000;
    console.log(`  t=${t.toFixed(0)}s offered=${off} completed=${cmp} inflight=${off - cmp} rate=${(cmp / t).toFixed(0)}/s`);
  }, 2000);

  // One shared timer drives every connection's fixed schedule (coordinated-omission safe: each order
  // is timed from its intended send time, so a stalled gateway shows as latency, never a dropped sample).
  await new Promise((resolve) => {
    const tick = () => {
      const now = nowMs();
      for (const c of up) {
        let fired = 0;
        while (Number(c.k) * period <= (now - startMs) && fired < 64) {
          c.fire(startMs + Number(c.k) * period); fired++;
        }
      }
      if (now >= endMs) return resolve();
      setTimeout(tick, 2);
    };
    tick();
  });

  const srv1 = await Promise.all(GATEWAYS.map((_, g) => gwAccepted(g)));
  const decoded1 = await Promise.all(GATEWAYS.map((_, g) =>
    gwStage(g, 'traderx_binary_frames_total', 'decoded')));
  const offer1 = await Promise.all(GATEWAYS.map((_, g) =>
    gwStage(g, 'traderx_gateway_pipeline_total', 'offer_success')));
  const ack1 = await Promise.all(GATEWAYS.map((_, g) =>
    gwStage(g, 'traderx_gateway_pipeline_total', 'ack_completed')));
  const ref1 = await memNextRef();
  const srvWin = (nowMs() - srvT0) / 1000;
  clearInterval(reportIv);
  await sleep(3000); // drain in-flight acks

  const off = up.reduce((a, c) => a + c.offered, 0);
  const cmp = up.reduce((a, c) => a + c.completed, 0);
  console.log('\n=== RESULT (binary per-order ingress) ===');
  console.log(`connections up:     ${up.length}/${SESSIONS}  (per-gateway ${perGwUp.join(' / ')})`);
  console.log(`offered:            ${off}  (${(off / SECS).toFixed(0)}/s over ${SECS}s)`);
  const writeBp = up.reduce((a, c) => a + c.writeBackpressure, 0);
  const writableBytes = up.reduce((a, c) => a + c.sock.writableLength, 0);
  console.log(`socket backpressure:${writeBp} write(false); ${writableBytes} bytes queued client-side`);
  console.log(`completed (acks):   ${cmp}  (${(cmp / SECS).toFixed(0)}/s)`);
  console.log(`in-flight at end:   ${off - cmp}`);
  console.log(`per-gateway completed (client-side): ${stats.perGwCompleted.join(' / ')}`);
  const srvD = srv0.map((v, g) => (Number.isNaN(v) || Number.isNaN(srv1[g])) ? NaN : srv1[g] - v);
  const srvTot = srvD.reduce((a, v) => a + (Number.isNaN(v) ? 0 : v), 0);
  console.log(`SERVER-SIDE accepted over ${srvWin.toFixed(1)}s (booked-only, collapses when positions max):`);
  srvD.forEach((v, g) => console.log(`  gateway ${g} (${gwHost(g)}): +${v} = ${(v / srvWin).toFixed(0)}/s`));
  console.log(`  AGGREGATE accepted: +${srvTot} = ${(srvTot / srvWin).toFixed(0)}/s`);
  const printStage = (label, before, after) => {
    const deltas = before.map((v, g) =>
      (Number.isNaN(v) || Number.isNaN(after[g])) ? NaN : after[g] - v);
    if (deltas.every(Number.isNaN)) {
      console.log(`${label}: ${deltas.join(' / ')}; aggregate NaN`);
      return;
    }
    const total = deltas.reduce((a, v) => a + (Number.isNaN(v) ? 0 : v), 0);
    console.log(`${label}: ${deltas.join(' / ')}; aggregate ${total} = ${(total / srvWin).toFixed(0)}/s`);
  };
  console.log('PER-HOP FUNNEL (server counters; NaN means the deployed gateway predates diagnostics):');
  printStage('  decoded', decoded0, decoded1);
  printStage('  offered-to-log', offer0, offer1);
  printStage('  committed-acks', ack0, ack1);
  const refD = (Number.isNaN(ref0) || Number.isNaN(ref1)) ? NaN : ref1 - ref0;
  console.log(`MEMBER nextOrderRef delta (AUTHORITATIVE committed/s): +${refD} = ${(refD / srvWin).toFixed(0)}/s`);
  console.log(`latency (from intended send) p50 ${pct(stats.latencies, 50)?.toFixed(0)}ms  ` +
    `p99 ${pct(stats.latencies, 99)?.toFixed(0)}ms  max ${stats.latencies.reduce((m,x)=>x>m?x:m,0).toFixed(0)}ms`);

  for (const c of up) c.close();
  setTimeout(() => process.exit(0), 300);
}

main().catch((e) => { console.log(`[FAIL] ${e.message}`); process.exit(1); });
