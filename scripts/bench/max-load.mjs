#!/usr/bin/env node
// max-load.mjs — saturate the running order-matcher so the Grafana "Trades/sec"
// dashboard hits this laptop's ceiling, and hold it there (a sustained plateau, not a
// blip). Run until Ctrl-C (or --secs N).
//
// Why this maxes out trades/sec: in 009b a NEW order auto-fills immediately against the
// security's last price (MatchingEngine.onNewOrder -> autoFill). A *deep in-the-money*
// Buy order (huge limit price) is always crossable, so it books a trade on submit — no
// price ticks, and nothing rests/accumulates (qty < fill-full-threshold => one full
// fill). trades/sec therefore tracks submit/sec, and REST submission is the bottleneck.
// We push it with HTTP keep-alive + many concurrent in-flight requests.
//
// Usage:  node max-load.mjs [--conc N] [--secs S]
//   --conc N   concurrent in-flight POSTs (raise to push harder)  (default 128)
//   --secs S   run for S seconds; 0 = until Ctrl-C                 (default 0)
//   env: MATCHER_URL (http://localhost:18110), PROM_URL (http://localhost:9090),
//        ACCOUNT (42422), TICKERS (JPM,GS,COF,DFS — must be price-published), QTY (500)
//
// Watch live:  http://localhost:8080/grafana/d/traderx-trades-per-second  (refresh = 1s)
// Tip: one Node process may itself become the limit; run several copies in parallel
// terminals to push the server harder.

import http from 'node:http';

const argv = process.argv.slice(2);
const flag = (name, env, def) => {
  const i = argv.indexOf(name);
  if (i !== -1 && argv[i + 1] !== undefined) return argv[i + 1];
  return process.env[env] ?? def;
};

const cfg = {
  matcherUrl: (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, ''),
  promUrl: (process.env.PROM_URL || 'http://localhost:9090').replace(/\/$/, ''),
  account: Number(flag('--account', 'ACCOUNT', 42422)),
  conc: Number(flag('--conc', 'CONC', 128)),
  rate: Number(flag('--rate', 'RATE', 0)),
  ramp: argv.includes('--ramp') || process.env.RAMP === '1',
  rampFrom: Number(flag('--ramp-from', 'RAMP_FROM', 500)),
  rampStep: Number(flag('--ramp-step', 'RAMP_STEP', 250)),
  rampSecs: Number(flag('--ramp-secs', 'RAMP_SECS', 4)),
  rampTo: Number(flag('--ramp-to', 'RAMP_TO', 8000)),
  durationSecs: Number(flag('--secs', 'DURATION_SECS', 0)),
  // Per-request backstop so a worker can never park forever on a starved response (the matcher
  // can block server-side with no timeout under saturation). Tune with --req-timeout / REQ_TIMEOUT_MS.
  reqTimeoutMs: Math.max(1000, Number(flag('--req-timeout', 'REQ_TIMEOUT_MS', 30000))),
  qty: Number(process.env.QTY || 500),
  limit: Number(process.env.LIMIT || 1_000_000),
  tickers: (process.env.TICKERS || 'JPM,GS,COF,DFS').split(',').map((t) => t.trim().toUpperCase()),
};

const u = new URL(cfg.matcherUrl + '/orders');
const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(cfg.conc, 512), maxFreeSockets: 256 });

let submitted = 0;
let failed = 0;
let running = true;
let stopping = false;
let rr = 0;
const inflight = new Set(); // live requests, so shutdown can abort whatever the matcher is starving

function postOrder() {
  if (stopping) return Promise.resolve(); // don't start new work once we're tearing down
  const ticker = cfg.tickers[rr++ % cfg.tickers.length];
  const data = Buffer.from(JSON.stringify({
    accountId: cfg.account, security: ticker, side: 'Buy', quantity: cfg.qty, limitPrice: cfg.limit,
  }));
  return new Promise((resolve) => {
    let settled = false;
    let req;
    const settle = (outcome) => { // 'ok' | 'fail' | 'abort'
      if (settled) return;
      settled = true;
      inflight.delete(req);
      if (outcome === 'ok') submitted++;
      else if (outcome === 'fail') failed++;
      // 'abort' = shutdown teardown: don't pollute the failed count with our own cancellations
      resolve();
    };
    req = http.request(
      { hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST', agent,
        headers: { 'content-type': 'application/json', 'content-length': data.length } },
      (res) => {
        const ok = res.statusCode === 201;
        res.resume();
        res.on('end', () => settle(ok ? 'ok' : 'fail'));
        res.on('error', () => settle(ok ? 'ok' : 'fail'));
      },
    );
    // Cap the wait so a worker can never park forever on a starved order (see cfg.reqTimeoutMs).
    req.setTimeout(cfg.reqTimeoutMs, () => req.destroy(new Error('request timeout')));
    req.on('error', () => settle(stopping ? 'abort' : 'fail'));
    inflight.add(req);
    req.end(data);
  });
}

async function worker() {
  while (running) await postOrder();
}

// Stop accepting work and unblock every worker: an order the matcher is starving (no response)
// would otherwise keep `await postOrder()` parked past --secs, so we destroy the in-flight
// requests (their 'error' settles each promise as an abort) and the keep-alive sockets.
function stop(reason) {
  if (stopping) return;
  stopping = true;
  running = false;
  if (reason) console.log(`\n[max] ${reason}`);
  for (const req of inflight) req.destroy(new Error('shutdown'));
  agent.destroy();
  setTimeout(() => process.exit(0), 3000).unref(); // failsafe: never linger past shutdown
}

async function promScalar(query) {
  try {
    const r = await fetch(`${cfg.promUrl}/api/v1/query?query=${encodeURIComponent(query)}`);
    const res = (await r.json()).data.result;
    return res.length ? Number(res[0].value[1]) : NaN;
  } catch { return NaN; }
}

let lastSubmitted = 0;
let lastT = Date.now();
let peakTrades = 0;
let currentTarget = 0;
let lastFailed = 0;
let cleanCeiling = 0;
const TRADES_RATE = 'sum(irate(traderx_order_events_total{event=~"fill|partial_fill|force_fill"}[15s]))';

async function printStats() {
  const now = Date.now();
  const dt = (now - lastT) / 1000 || 1;
  const clientRate = Math.round((submitted - lastSubmitted) / dt);
  lastSubmitted = submitted;
  lastT = now;
  const tps = await promScalar(TRADES_RATE);
  if (Number.isFinite(tps)) peakTrades = Math.max(peakTrades, tps);
  const open = await promScalar('max(traderx_orders_open_total)');
  const failDelta = failed - lastFailed;
  lastFailed = failed;
  if ((cfg.ramp || cfg.rate > 0) && failDelta === 0 && Number.isFinite(tps)) {
    cleanCeiling = Math.max(cleanCeiling, tps);
  }
  const targetStr = currentTarget > 0 ? `target ${String(currentTarget).padStart(5)}/s  ·  ` : '';
  console.log(
    `[max] ${targetStr}submit ${String(clientRate).padStart(6)}/s  ·  ` +
    `trades ${Number.isFinite(tps) ? tps.toFixed(0).padStart(6) : '     …'}/s  ·  ` +
    `peak ${peakTrades.toFixed(0).padStart(6)}/s  ·  ` +
    `open ${Number.isFinite(open) ? open : '…'}  ·  failed ${failed}${failDelta > 0 ? ` (+${failDelta} ← over the ceiling)` : ''}`,
  );
}

const mode = cfg.ramp
  ? `ramp ${cfg.rampFrom}→${cfg.rampTo}/s (+${cfg.rampStep} every ${cfg.rampSecs}s) — climbs until the trade-booking pipeline breaks (watch for "over the ceiling")`
  : cfg.rate > 0
    ? `paced ${cfg.rate}/s (steady plateau)`
    : `max-burst conc=${cfg.conc} (sawtooth + connection resets as the trade-booking pipeline backpressures — use --ramp or --rate for a clean number)`;
console.log(`[max] matcher=${cfg.matcherUrl}  qty=${cfg.qty}  tickers=${cfg.tickers.join(',')}  ${cfg.durationSecs ? `for ${cfg.durationSecs}s` : '(Ctrl-C to stop)'}`);
console.log(`[max] mode: ${mode}`);
console.log('[max] dashboard: http://localhost:8080/grafana/d/traderx-trades-per-second  (set refresh to 1s)');

const statsTimer = setInterval(printStats, 2000);
process.on('SIGINT', () => stop('stopping…'));
if (cfg.durationSecs > 0) setTimeout(() => stop(null), cfg.durationSecs * 1000);

if (cfg.rate > 0 || cfg.ramp) {
  // Paced/ramp mode: offer a controlled orders/sec so the matcher's output ring never
  // saturates -> a clean line instead of the ring-fill sawtooth. --rate holds a steady
  // plateau; --ramp climbs the target until the booking pipeline breaks (failures), which
  // is the true sustainable ceiling.
  let inFlight = 0;
  const maxRate = cfg.ramp ? cfg.rampTo : cfg.rate;
  const cap = Math.max(512, Math.round(maxRate));
  const startMs = Date.now();
  let credit = 0;
  const targetAt = () => {
    if (!cfg.ramp) return cfg.rate;
    const steps = Math.floor((Date.now() - startMs) / 1000 / cfg.rampSecs);
    return Math.min(cfg.rampTo, cfg.rampFrom + steps * cfg.rampStep);
  };
  await new Promise((resolve) => {
    const t = setInterval(() => {
      if (!running) { clearInterval(t); resolve(); return; }
      currentTarget = targetAt();
      credit += currentTarget / 50; // 50 launch ticks/sec
      while (credit >= 1 && inFlight < cap) {
        credit -= 1;
        inFlight++;
        postOrder().finally(() => { inFlight--; });
      }
    }, 20);
  });
  const t0 = Date.now();
  while (inFlight > 0 && Date.now() - t0 < 3000) await new Promise((r) => setTimeout(r, 50));
} else {
  // Max-burst mode: keep cfg.conc requests in flight at all times -> reveals the peak.
  await Promise.all(Array.from({ length: cfg.conc }, worker));
}

clearInterval(statsTimer);
await printStats();
console.log(`\n[max] done.  submitted=${submitted}  failed=${failed}  PEAK trades/sec=${peakTrades.toFixed(0)}`);
if (cfg.ramp || cfg.rate > 0) {
  console.log(`[max] highest SUSTAINED trades/sec (no new failures) ≈ ${cleanCeiling.toFixed(0)}/s`);
}
process.exit(0);
