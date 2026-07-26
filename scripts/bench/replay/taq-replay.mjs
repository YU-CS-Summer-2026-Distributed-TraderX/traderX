#!/usr/bin/env node
// taq-replay.mjs — replay REAL NYSE TAQ trade prints (curated from the YU07 tick store)
// against the YU13 crossing book. Each historical print becomes a passive limit order at the
// print price + an aggressor limit at the same price on the other side, so every fill the
// engine books corresponds 1:1 to a trade that actually happened in the market — same symbol,
// price, size, and sequence. The pair is order-insensitive (whichever lands first rests, the
// other crosses at the resting price), so a sequenced replay reproduces every print exactly.
//
// Input CSV (no header): ts_us,symbol,px,qty,aggr   (aggr B/S = tick-rule aggressor side)
//
// Modes:
//   --mode paced (default)  sim-clock pacing at --speed x real time, ONE in-flight batch.
//                           Target ONE gateway pod for exact global sequencing — the
//                           order-matcher-gw Service round-robins across gateways, which
//                           interleaves batches and lets fills slip off the historical price.
//   --mode max              full-throttle: --conc workers race sequential chunks through the
//                           Service. Real flow at max ingest rate; cross-pair interleaving can
//                           fill at a better resting price than the print (report, don't hide).
//
// Usage: node taq-replay.mjs --file taq.csv [--mode paced|max] [--speed 10] [--conc 8]
//          [--batch 200] [--prints N] [--secs S]
//   env: MATCHER_URL (default http://order-matcher-gw:18110), SEED_URL (default MATCHER_URL)
//
// Risk-freshness: only TYPE_PRICE_TICK refreshes lastPriceTime (30 s max age), and this flow
// submits only orders — so a refresher POSTs /seed per symbol every 8 s at the latest replayed
// price. Accounts rotate over the 7 real SQL accounts (passive and aggressor always distinct),
// keeping per-account executed notional ~1/7 of 2x the slice notional and net position a
// near-flat random walk. 422s that do appear are the 15c3-5 risk gate working, not capacity.

import http from 'node:http';
import readline from 'node:readline';
import fs from 'node:fs';

const argv = process.argv.slice(2);
const flag = (name, def) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : def;
};

const cfg = {
  file: flag('--file', 'taq-replay.csv'),
  mode: flag('--mode', 'paced'),
  speed: Number(flag('--speed', 1)),
  conc: Number(flag('--conc', 8)),
  // prints per POST (x2 orders). 100 is the measured sweet spot: at 200 the gateway's
  // pipelined-batch ack window overflows egress under crossing load (2 booked events per
  // print) and every lossy batch eats its full ~7s ack budget — 100 runs stall-free.
  batch: Math.min(500, Number(flag('--batch', 100))),
  prints: Number(flag('--prints', 0)),                // 0 = whole file
  secs: Number(flag('--secs', 0)),                    // wall-clock cap; 0 = none
  matcherUrl: (process.env.MATCHER_URL || 'http://order-matcher-gw:18110').replace(/\/$/, ''),
  seedUrl: (process.env.SEED_URL || process.env.MATCHER_URL || 'http://order-matcher-gw:18110').replace(/\/$/, ''),
};

const ACCOUNTS = [10031, 11413, 22214, 42422, 44044, 52355, 62654];
const u = new URL(cfg.matcherUrl + '/orders/batch');
const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(cfg.conc, 16) });

// ---- load the slice into flat arrays (1.1M rows: typed where it counts) -------------------
const symbols = [];
const symId = new Map();
const rows = { ts: [], sym: [], px: [], qty: [], aggr: [] };
{
  const rl = readline.createInterface({ input: fs.createReadStream(cfg.file), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line) continue;
    const [ts, sym, px, qty, aggr] = line.split(',');
    let id = symId.get(sym);
    if (id === undefined) { id = symbols.length; symbols.push(sym); symId.set(sym, id); }
    rows.ts.push(Number(ts));
    rows.sym.push(id);
    rows.px.push(Number(px));
    rows.qty.push(Number(qty) | 0);
    rows.aggr.push(aggr === 'B' ? 1 : 0);
    if (cfg.prints && rows.ts.length >= cfg.prints) break;
  }
}
const total = rows.ts.length;
console.log(`[taq] ${total} prints, ${symbols.length} symbols (${symbols.join(',')}), mode=${cfg.mode}` +
  (cfg.mode === 'paced' ? ` speed=${cfg.speed}x` : ` conc=${cfg.conc}`) + `, batch=${cfg.batch} prints`);

// ---- shared state --------------------------------------------------------------------------
let printsSent = 0, ordersAccepted = 0, ordersOffered = 0, httpFailed = 0;
let running = true;
const latestPx = new Array(symbols.length).fill(0); // per-symbol last replayed price (refresher)
let acctRR = 0;

function pairsBody(from, to) { // [from, to) print indexes -> one batch array
  const orders = new Array((to - from) * 2);
  for (let i = from, o = 0; i < to; i++) {
    const sym = symbols[rows.sym[i]], px = rows.px[i], qty = rows.qty[i];
    const buyAggr = rows.aggr[i] === 1;
    const passiveAcct = ACCOUNTS[acctRR % 7], aggrAcct = ACCOUNTS[(acctRR + 3) % 7];
    acctRR++;
    latestPx[rows.sym[i]] = px;
    orders[o++] = { accountId: passiveAcct, security: sym, side: buyAggr ? 'Sell' : 'Buy', quantity: qty, limitPrice: px };
    orders[o++] = { accountId: aggrAcct, security: sym, side: buyAggr ? 'Buy' : 'Sell', quantity: qty, limitPrice: px };
  }
  return Buffer.from(JSON.stringify(orders));
}

function post(url, data, timeoutMs = 60000) {
  return new Promise((resolve) => {
    const req = http.request(
      { hostname: url.hostname, port: url.port, path: url.pathname, method: 'POST', agent,
        headers: { 'content-type': 'application/json', 'content-length': data.length } },
      (res) => {
        let body = '';
        res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({ status: res.statusCode, body }));
        res.on('error', () => resolve({ status: 0, body: '' }));
      });
    req.setTimeout(timeoutMs, () => req.destroy(new Error('timeout')));
    req.on('error', () => resolve({ status: 0, body: '' }));
    req.end(data);
  });
}

async function sendChunk(from, to) {
  const res = await post(u, pairsBody(from, to));
  printsSent += to - from;
  ordersOffered += (to - from) * 2;
  if (res.status === 201) {
    try { ordersAccepted += JSON.parse(res.body).accepted; } catch { /* count as 0 */ }
  } else {
    httpFailed += (to - from) * 2;
  }
}

// ---- price-freshness refresher (risk.price.max-age-ms = 30s; orders don't refresh it) ------
const seedU = new URL(cfg.seedUrl + '/seed');
// Upfront seed at each symbol's first print price: the cluster's last price tick may be far
// older than 30s, which would PRICE_STALE-reject every order before the refresher first fires.
for (let s = 0; s < symbols.length; s++) {
  const first = rows.sym.indexOf(s);
  if (first === -1) continue;
  const res = await post(seedU, Buffer.from(JSON.stringify(
    { accountId: 42422, tickers: symbols[s], price: rows.px[first] })), 20000);
  if (res.status !== 200) console.log(`[taq] WARN seed ${symbols[s]} -> HTTP ${res.status} ${res.body}`);
}
console.log('[taq] symbols seeded at first-print prices');
const refresher = setInterval(async () => {
  for (let s = 0; s < symbols.length; s++) {
    if (latestPx[s] > 0) {
      await post(seedU, Buffer.from(JSON.stringify(
        { accountId: 42422, tickers: symbols[s], price: latestPx[s] })), 15000);
    }
  }
}, 8000);
refresher.unref();

// ---- stats ---------------------------------------------------------------------------------
let lastPrints = 0, lastT = Date.now();
const stats = setInterval(() => {
  const now = Date.now(), dt = (now - lastT) / 1000 || 1;
  const rate = Math.round((printsSent - lastPrints) / dt);
  lastPrints = printsSent; lastT = now;
  console.log(`[taq] prints ${printsSent}/${total} (${rate}/s)  orders accepted ${ordersAccepted}/${ordersOffered}  httpFailed ${httpFailed}`);
}, 2000);

const deadline = cfg.secs > 0 ? Date.now() + cfg.secs * 1000 : Infinity;

// ---- paced: sim clock, one in-flight batch, exact sequencing -------------------------------
async function runPaced() {
  const t0Sim = rows.ts[0];
  const t0Wall = Date.now();
  let i = 0;
  while (i < total && running && Date.now() < deadline) {
    const simNow = t0Sim + (Date.now() - t0Wall) * 1000 * cfg.speed; // µs of sim time elapsed
    let j = i;
    while (j < total && rows.ts[j] <= simNow && j - i < cfg.batch) j++;
    if (j === i) { await new Promise((r) => setTimeout(r, 5)); continue; }
    await sendChunk(i, j); // one in flight: array order + sequential POSTs = exact global order
    i = j;
  }
}

// ---- max: conc workers race sequential chunks through the Service --------------------------
async function runMax() {
  let next = 0;
  async function worker() {
    while (running && Date.now() < deadline) {
      const from = next; if (from >= total) return;
      next = Math.min(total, from + cfg.batch);
      await sendChunk(from, next);
    }
  }
  await Promise.all(Array.from({ length: cfg.conc }, worker));
}

process.on('SIGINT', () => { running = false; });

const started = Date.now();
await (cfg.mode === 'max' ? runMax() : runPaced());
const elapsed = (Date.now() - started) / 1000;

clearInterval(stats); clearInterval(refresher);
console.log(`\n[taq] done in ${elapsed.toFixed(1)}s  prints=${printsSent}  ` +
  `orders accepted=${ordersAccepted}/${ordersOffered}  httpFailed=${httpFailed}  ` +
  `(${Math.round(printsSent / elapsed)} prints/s, ${Math.round(ordersAccepted / elapsed)} accepted orders/s)`);
process.exit(0);
