// rest-completed-control.mjs — matched-methodology REST single-order control for SC-FIX06.
//
// Structurally identical to fix-load.mjs so the ONLY variable is the transport (HTTP POST /orders
// per order vs one pipelined FIX session): same in-flight WINDOW, same "completed = outcome
// learned" definition, same SIDES=alternate workload, same account. A REST order is "completed"
// when its HTTP response arrives with a definitive order outcome — 201 booked OR 422 risk-rejected
// (the client learned the result), matching the FIX definition of an ExecutionReport received.
// Transport failures (5xx / socket / timeout) are counted separately and are NOT completions.
//
// Usage:  node rest-completed-control.mjs [--secs S] [--window W]
//   env: MATCHER_URL (http://localhost:8080/order-matcher), ACCOUNT (11413),
//        SIDES=alternate, QTY (1), PX (190), TICKERS (JPM)
import http from 'node:http';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : d; };

const cfg = {
  url: (process.env.MATCHER_URL || 'http://localhost:8080/order-matcher').replace(/\/$/, ''),
  secs: Number(flag('--secs', process.env.DURATION_SECS || 30)),
  window: Number(flag('--window', process.env.WINDOW || 256)),
  account: Number(process.env.ACCOUNT || 11413),
  alternate: (process.env.SIDES || '') === 'alternate',
  qty: Number(process.env.QTY || 1),
  px: Number(process.env.PX || 190),
  tickers: (process.env.TICKERS || 'JPM').split(',').map((t) => t.trim().toUpperCase()),
};

const target = new URL(cfg.url + '/orders');
const agent = new http.Agent({ keepAlive: true, maxSockets: cfg.window + 16 });

const RUN = process.env.RUN_ID || String(Date.now() % 1000000);
let submitted = 0, completed = 0, booked = 0, rejected = 0, failed = 0;
let inFlight = 0, seq = 0, sideFlip = 0, stopping = false;

function post() {
  const clId = `REST-${RUN}-${++seq}`;
  const side = cfg.alternate && (sideFlip++ & 1) ? 'Sell' : 'Buy';
  const ticker = cfg.tickers[seq % cfg.tickers.length];
  const body = JSON.stringify({
    clientOrderId: clId, accountId: cfg.account, security: ticker,
    side, quantity: cfg.qty, limitPrice: cfg.px,
  });
  const req = http.request(target, {
    method: 'POST', agent,
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  }, (res) => {
    res.resume();               // drain
    res.on('end', () => {
      inFlight--;
      // Outcome learned = a completed lifecycle. 201 booked, 422 risk-rejected — both learned.
      if (res.statusCode === 201) { completed++; booked++; }
      else if (res.statusCode === 422) { completed++; rejected++; }
      else { failed++; }        // 5xx / unexpected: transport-level, not a completion
      pump();
    });
  });
  req.on('error', () => { inFlight--; failed++; pump(); });
  req.setTimeout(30000, () => req.destroy());
  req.write(body); req.end();
  submitted++; inFlight++;
}

let deadline;
function pump() {
  if (stopping) return;
  while (inFlight < cfg.window && Date.now() < deadline) post();
  if (Date.now() >= deadline && inFlight === 0) finish();
}

const t0 = Date.now();
deadline = t0 + cfg.secs * 1000;
const report = setInterval(() => {
  const el = (Date.now() - t0) / 1000;
  process.stdout.write(`[rest] submit ${submitted}  completed ${completed} (${Math.round(completed / el)}/s)  `
    + `booked ${booked}  risk-rejected ${rejected}  failed ${failed}  in-flight ${inFlight}\n`);
}, 1000);

function finish() {
  stopping = true;
  clearInterval(report);
  const el = (Date.now() - t0) / 1000;
  console.log(`\n[rest] done.  submitted=${submitted}  completed=${completed}  booked=${booked}  `
    + `risk-rejected=${rejected}  failed=${failed}  completed/s=${Math.round(completed / el)}`);
  process.exit(0);
}

pump();
