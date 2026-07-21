#!/usr/bin/env node
// batch-load.mjs — like max-load.mjs, but exercises the BATCH ingress endpoint
// (POST /orders/batch) added for the throughput experiment (option 2). Each HTTP
// request carries `--batch` orders, so one matcher (Tomcat) thread sequences the whole
// batch onto the input ring and blocks ONCE for all the acks, instead of one
// round-trip + one ack-block per order. We keep `--conc` such batch requests in flight.
//
// Why this isolates the ingress architecture: the orders are the same deep-in-the-money
// Buy orders max-load uses (auto-fill on submit, nothing rests), so trades/sec tracks
// accepted-orders/sec. The ONLY thing that changes vs max-load is how many orders ride
// each HTTP round-trip / each ack-block — i.e. the gateway batching factor.
//
// Usage:  node batch-load.mjs [--conc N] [--batch K] [--secs S]
//   --conc N    concurrent in-flight batch POSTs                 (default 16)
//   --batch K   orders per batch request                         (default 50)
//   --secs S    run for S seconds; 0 = until Ctrl-C              (default 0)
//   env: MATCHER_URL (http://localhost:18110), ACCOUNT (42422),
//        TICKERS (JPM,GS,COF,DFS — must be price-published), QTY (500), LIMIT (1000000)
//
// Output line mirrors max-load.mjs so the orchestrator parses it identically:
//   "submitted=<orders accepted> failed=<orders rejected>"  (counts ORDERS, not requests)

import http from 'node:http';

const argv = process.argv.slice(2);
const flag = (name, env, def) => {
  const i = argv.indexOf(name);
  if (i !== -1 && argv[i + 1] !== undefined) return argv[i + 1];
  return process.env[env] ?? def;
};

const cfg = {
  matcherUrl: (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, ''),
  account: Number(flag('--account', 'ACCOUNT', 42422)),
  conc: Number(flag('--conc', 'CONC', 16)),
  batch: Math.max(1, Number(flag('--batch', 'BATCH', 50))),
  durationSecs: Number(flag('--secs', 'DURATION_SECS', 0)),
  // Per-request backstop. A saturated matcher blocks server-side in claimInputSlots()
  // (Disruptor next(n) has NO timeout) BEFORE the 5s gateway ack-timeout even starts, so a
  // batch response can be delayed indefinitely. Without this cap a worker would park forever
  // on one starved request and the run would never end. Tune with --req-timeout / REQ_TIMEOUT_MS.
  reqTimeoutMs: Math.max(1000, Number(flag('--req-timeout', 'REQ_TIMEOUT_MS', 30000))),
  qty: Number(process.env.QTY || 500),
  limit: Number(process.env.LIMIT || 1_000_000),
  tickers: (process.env.TICKERS || 'JPM,GS,COF,DFS').split(',').map((t) => t.trim().toUpperCase()),
};

const u = new URL(cfg.matcherUrl + '/orders/batch');
const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(cfg.conc, 64), maxFreeSockets: 64 });

let submitted = 0; // accepted ORDERS (batch * accepted requests)
let failed = 0;    // orders in rejected/errored requests
let running = true;
let stopping = false;
let rr = 0;
const inflight = new Set(); // live requests, so shutdown can abort whatever the matcher is starving

// Pre-render one batch body per ticker rotation so the hot loop only pays the socket cost,
// not JSON.stringify of K objects every request. Each body is `--batch` orders; we rotate
// the leading ticker so load spreads across the price-published symbols.
const bodies = cfg.tickers.map((_, offset) => {
  // SIDES=alternate flips every other order to Sell: orders self-match, positions stay ~flat,
  // and the book stays bounded — use it to build a big JOURNAL without tripping the risk
  // position cap (an all-Buy run starts drawing POSITION_LIMIT 422s after cap/qty orders).
  const alternate = (process.env.SIDES || '') === 'alternate';
  // Keep every request self-crossing even when --batch is not divisible by the full
  // ticker/side cycle (2 * ticker count). The old batch=200 + 3-ticker body left two
  // unmatched resting orders at shutdown, so authoritative booked != applied by exactly 2
  // despite zero rejects. Preserve the established round-robin sequence for the largest
  // complete cycle prefix, then close the even-sized tail as explicit same-ticker pairs.
  const cycle = 2 * cfg.tickers.length;
  const balancedPrefix = alternate ? cfg.batch - (cfg.batch % cycle) : cfg.batch;
  const orders = Array.from({ length: cfg.batch }, (_, j) => ({
    accountId: cfg.account,
    security: j < balancedPrefix
      ? cfg.tickers[(offset + j) % cfg.tickers.length]
      : cfg.tickers[(offset + Math.floor((j - balancedPrefix) / 2)) % cfg.tickers.length],
    side: alternate && (j < balancedPrefix ? j : j - balancedPrefix) % 2 === 1 ? 'Sell' : 'Buy',
    quantity: cfg.qty,
    limitPrice: cfg.limit,
  }));
  return Buffer.from(JSON.stringify(orders));
});

function postBatch() {
  if (stopping) return Promise.resolve(); // don't start new work once we're tearing down
  const data = bodies[rr++ % bodies.length];
  return new Promise((resolve) => {
    let settled = false;
    let req;
    const settle = (outcome) => { // 'ok' | 'fail' | 'abort'
      if (settled) return;
      settled = true;
      inflight.delete(req);
      if (outcome === 'ok') submitted += cfg.batch;
      else if (outcome === 'fail') failed += cfg.batch;
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
    // Cap the wait so a worker can never park forever on a starved batch (see cfg.reqTimeoutMs).
    req.setTimeout(cfg.reqTimeoutMs, () => req.destroy(new Error('request timeout')));
    req.on('error', () => settle(stopping ? 'abort' : 'fail'));
    inflight.add(req);
    req.end(data);
  });
}

async function worker() {
  while (running) await postBatch();
}

// Stop accepting work and unblock every worker: a batch the matcher is starving (no response)
// would otherwise keep `await postBatch()` parked past --secs, so we destroy the in-flight
// requests (their 'error' settles each promise as an abort) and the keep-alive sockets.
function stop(reason) {
  if (stopping) return;
  stopping = true;
  running = false;
  if (reason) console.log(`\n[batch] ${reason}`);
  for (const req of inflight) req.destroy(new Error('shutdown'));
  agent.destroy();
  setTimeout(() => process.exit(0), 3000).unref(); // failsafe: never linger past shutdown
}

let lastSubmitted = 0;
let lastT = Date.now();
function printStats() {
  const now = Date.now();
  const dt = (now - lastT) / 1000 || 1;
  const rate = Math.round((submitted - lastSubmitted) / dt);
  lastSubmitted = submitted;
  lastT = now;
  console.log(`[batch] conc=${cfg.conc} batch=${cfg.batch}  ·  submit ${String(rate).padStart(6)}/s (orders)  ·  accepted ${submitted}  ·  failed ${failed}`);
}

console.log(`[batch] matcher=${cfg.matcherUrl}/orders/batch  batch=${cfg.batch} conc=${cfg.conc} qty=${cfg.qty}  tickers=${cfg.tickers.join(',')}  ${cfg.durationSecs ? `for ${cfg.durationSecs}s` : '(Ctrl-C to stop)'}`);

const statsTimer = setInterval(printStats, 2000);
process.on('SIGINT', () => stop('stopping…'));
if (cfg.durationSecs > 0) setTimeout(() => stop(null), cfg.durationSecs * 1000);

await Promise.all(Array.from({ length: cfg.conc }, worker));

clearInterval(statsTimer);
printStats();
console.log(`\n[batch] done.  submitted=${submitted}  failed=${failed}`);
process.exit(0);
