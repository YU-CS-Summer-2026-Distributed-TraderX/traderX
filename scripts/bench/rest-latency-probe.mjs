#!/usr/bin/env node
// rest-latency-probe.mjs — WIRE-TO-WIRE latency of the REST order path, measured honestly.
//
// What this measures: client-observed round trip for ONE order — HTTP request in, gateway
// forwards to the cluster leader over Aeron ingress, Raft commits it, the clustered service
// applies it, the committed egress ack comes back, gateway answers HTTP. That is the whole
// system's decision latency, NOT the in-memory match op (which is the engine's own number,
// hundreds of ns) and NOT wire-to-wire in the NIC sense.
//
// Two methodology rules, both load-bearing:
//
//  1. CONSTANT ARRIVAL RATE, not closed-loop. A closed-loop generator (send, wait for reply,
//     send again) cannot enqueue work while the system is stalled, so it never observes the
//     stall — the classic coordinated-omission error (Gil Tene). We schedule sends on a fixed
//     wall-clock cadence regardless of whether earlier requests have come back.
//  2. Latency is measured from the INTENDED send time, not the actual one. If the process is
//     busy and a send goes out late, that lateness is part of the latency the caller would
//     have experienced. Measuring from the actual write would launder exactly the stalls we
//     care about.
//
// Flow is genuinely two-sided so orders actually cross on a real book: alternating Buy/Sell at
// the same limit on one ticker, so each pair produces a match (a one-sided stream would just
// pile up resting orders and measure the insert path only).
//
// Usage: RATE=500 SECS=60 MATCHER_URL=http://order-matcher-gw:18110 node rest-latency-probe.mjs
//   RATE   target orders/second (constant arrival)          default 500
//   SECS   measured seconds (after WARMUP)                   default 60
//   WARMUP seconds of unmeasured traffic to settle JIT       default 10
//   TICKER single symbol so both sides land on one book      default AAPL
//   PX     limit price both sides use, so every pair crosses default 150.000

import http from 'node:http';

const cfg = {
  url: (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, ''),
  rate: Number(process.env.RATE || 500),
  secs: Number(process.env.SECS || 60),
  warmup: Number(process.env.WARMUP || 10),
  ticker: process.env.TICKER || 'AAPL',
  px: Number(process.env.PX || 150.0),
  // BOTH sides use the SAME account on purpose (self-cross). Splitting buys and sells across two
  // accounts makes each account's net position grow monotonically, and a sustained run then hits
  // POSITION_LIMIT and starts measuring REJECTION latency instead of booking latency (observed:
  // 83% rejects at 250/s, 100% at 500/s). Self-crossing keeps positions ~flat so the run stays a
  // latency measurement of the booking path. The engine has no self-trade prevention by design.
  acct: Number(process.env.ACCOUNT || 42422),
};

const u = new URL(cfg.url + '/orders');
// Sockets must not be the bottleneck or we would measure queueing on our own connection pool
// rather than the system under test.
const agent = new http.Agent({ keepAlive: true, maxSockets: 4096, maxFreeSockets: 512 });

const latencies = [];       // nanoseconds, measured phase only
let measuring = false;
let sent = 0, ok = 0, failed = 0;

function submit(seq, intendedNs) {
  const sell = (seq & 1) === 1;
  const body = Buffer.from(JSON.stringify({
    accountId: cfg.acct,
    ticker: cfg.ticker,
    side: sell ? 'Sell' : 'Buy',
    quantity: 100,
    limitPrice: cfg.px,
  }));
  const req = http.request({
    hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST', agent,
    headers: { 'Content-Type': 'application/json', 'Content-Length': body.length },
  }, (res) => {
    res.resume();
    res.on('end', () => {
      // From INTENDED send time — includes any lateness in our own scheduling.
      const elapsed = Number(process.hrtime.bigint() - intendedNs);
      if (measuring) { latencies.push(elapsed); if (res.statusCode === 200) ok++; else failed++; }
    });
  });
  req.on('error', () => { if (measuring) failed++; });
  req.write(body);
  req.end();
}

function pct(sorted, p) {
  if (!sorted.length) return 0;
  const i = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[i];
}
const us = (ns) => (ns / 1000).toFixed(0);

async function main() {
  const periodNs = BigInt(Math.round(1e9 / cfg.rate));
  const t0 = process.hrtime.bigint();
  const warmupEndNs = t0 + BigInt(cfg.warmup) * 1_000_000_000n;
  const endNs = warmupEndNs + BigInt(cfg.secs) * 1_000_000_000n;

  console.log(`[lat] ${cfg.url}/orders  rate=${cfg.rate}/s  warmup=${cfg.warmup}s  measure=${cfg.secs}s  ticker=${cfg.ticker} @${cfg.px} (alternating Buy/Sell -> every pair crosses)`);

  let seq = 0;
  // Schedule against absolute intended times so drift cannot accumulate and so a slow tick
  // does not silently reduce the offered rate.
  for (let intended = t0; intended < endNs; intended += periodNs) {
    const now = process.hrtime.bigint();
    if (!measuring && now >= warmupEndNs) { measuring = true; }
    const waitMs = Number(intended - now) / 1e6;
    if (waitMs > 1) await new Promise((r) => setTimeout(r, waitMs));
    submit(seq++, intended);
    sent++;
  }
  // Let the tail of in-flight requests land.
  await new Promise((r) => setTimeout(r, 5000));
  measuring = false;

  const s = latencies.slice().sort((a, b) => a - b);
  console.log(`[lat] offered=${sent} measured=${s.length} ok=${ok} failed=${failed}`);
  console.log(`REST-LATENCY rate=${cfg.rate}/s count=${s.length} p50=${us(pct(s,50))}us p90=${us(pct(s,90))}us p99=${us(pct(s,99))}us p99.9=${us(pct(s,99.9))}us p99.99=${us(pct(s,99.99))}us max=${us(s[s.length-1] || 0)}us`);
}

main();
