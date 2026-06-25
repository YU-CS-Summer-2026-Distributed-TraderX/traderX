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
  qty: Number(process.env.QTY || 500),
  limit: Number(process.env.LIMIT || 1_000_000),
  tickers: (process.env.TICKERS || 'JPM,GS,COF,DFS').split(',').map((t) => t.trim().toUpperCase()),
};

const u = new URL(cfg.matcherUrl + '/orders/batch');
const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(cfg.conc, 64), maxFreeSockets: 64 });

let submitted = 0; // accepted ORDERS (batch * accepted requests)
let failed = 0;    // orders in rejected/errored requests
let running = true;
let rr = 0;

// Pre-render one batch body per ticker rotation so the hot loop only pays the socket cost,
// not JSON.stringify of K objects every request. Each body is `--batch` orders; we rotate
// the leading ticker so load spreads across the price-published symbols.
const bodies = cfg.tickers.map((_, offset) => {
  const orders = Array.from({ length: cfg.batch }, (_, j) => ({
    accountId: cfg.account,
    security: cfg.tickers[(offset + j) % cfg.tickers.length],
    side: 'Buy',
    quantity: cfg.qty,
    limitPrice: cfg.limit,
  }));
  return Buffer.from(JSON.stringify(orders));
});

function postBatch() {
  const data = bodies[rr++ % bodies.length];
  return new Promise((resolve) => {
    const req = http.request(
      { hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST', agent,
        headers: { 'content-type': 'application/json', 'content-length': data.length } },
      (res) => {
        if (res.statusCode === 201) submitted += cfg.batch; else failed += cfg.batch;
        res.resume();
        res.on('end', resolve);
      },
    );
    req.on('error', () => { failed += cfg.batch; resolve(); });
    req.end(data);
  });
}

async function worker() {
  while (running) await postBatch();
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
process.on('SIGINT', () => { console.log('\n[batch] stopping…'); running = false; });
if (cfg.durationSecs > 0) setTimeout(() => { running = false; }, cfg.durationSecs * 1000);

await Promise.all(Array.from({ length: cfg.conc }, worker));

clearInterval(statsTimer);
printStats();
console.log(`\n[batch] done.  submitted=${submitted}  failed=${failed}`);
process.exit(0);
