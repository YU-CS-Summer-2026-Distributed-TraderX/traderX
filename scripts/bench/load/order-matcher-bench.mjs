#!/usr/bin/env node
/**
 * Same-workload benchmark driver for the TraderX order-matcher, state 009 vs 009b.
 *
 * Workload (identical for both states):
 *   1. submit:  ORDERS resting Buy orders via REST POST /orders (account ACCOUNT, real
 *               tickers so trade-service booking validation passes), limit prices spread
 *               across [limitLow, limitHigh) so the tick ramp crosses them progressively.
 *   2. publish: TICKS NATS price ticks on pricing.<TICKER>, per-ticker price ramping
 *               linearly from PX_START (above every limit: nothing in the money) down to
 *               PX_FLOOR (below every limit: everything crosses), rate-limited to
 *               TICK_RATE msg/s aggregate. price-publisher stays RUNNING (trade-service
 *               needs it for booking quotes); see the tickers note below for why its own
 *               ticks can never fill a bench order.
 *   3. drain:   poll GET /orders?status=open&accountId=ACCOUNT until no bench order is
 *               open. If the count stalls (a state consumed none of the late low ticks —
 *               e.g. client-side slow-consumer shedding), publish small "closer" batches
 *               at the floor price and count them in the report.
 *
 * Measures wall time per phase, REST ack latency percentiles, and effective tick
 * consumption evidence per state; writes a JSON result plus raw /health and /metrics
 * snapshots. `--compare a.json b.json` renders the A/B report.
 *
 * Usage:
 *   node order-matcher-bench.mjs --state <id> --out <result.json> [env knobs below]
 *   node order-matcher-bench.mjs --compare <a.json> <b.json>
 *
 * Env knobs: BENCH_ORDERS (5000), BENCH_TICKS (3000000), BENCH_TICK_RATE (25000 msg/s),
 *   BENCH_ACCOUNT (42422), BENCH_TICKERS (AAPL,MSFT,JPM,GS), MATCHER_URL
 *   (http://localhost:18110), NATS_URL (nats://localhost:4222),
 *   BENCH_DRAIN_TIMEOUT_MS (2700000), BENCH_SUBMIT_CONCURRENCY (16).
 */

import { connect, StringCodec } from 'nats';
import { writeFileSync, mkdirSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { dirname } from 'node:path';

const sc = StringCodec();

const cfg = {
  orders: int(process.env.BENCH_ORDERS, 5000),
  ticks: int(process.env.BENCH_TICKS, 3_000_000),
  tickRate: int(process.env.BENCH_TICK_RATE, 25_000),
  account: int(process.env.BENCH_ACCOUNT, 42422),
  // Ticker constraints (both must hold; see bench-results/20260611-214440 forensics and
  // LMAX-BENCHMARK-009-VS-009B.md):
  //  1. In reference-data's SERVED set — it filters the S&P CSV down to ~20 tickers
  //    (supplemental financial names + the compose SUPPORTED_TICKERS list). Anything
  //    else 404s at trade-service booking validation, which wedges 009 (pre-fill
  //    rejection + per-tick retry) into a booking-failure storm.
  //  2. Snapshot price comfortably ABOVE the limit ladder. The compose stack runs
  //    price-publisher with PRICE_TICKERS = the same ~20 tickers, so ambient random-walk
  //    ticks for bench tickers are unavoidable; they stay confined to a volatility band
  //    around the snapshot price (max ±4%). JPM ~196, GS ~404, COF ~150, DFS ~128 keep
  //    every ambient tick far above limitHigh (90), so ONLY our descending ramp can
  //    cross a limit. (Do not use DB ~17 or MS ~91: their ambient ticks fill orders.)
  tickers: (process.env.BENCH_TICKERS || 'JPM,GS,COF,DFS').split(',').map((t) => t.trim().toUpperCase()),
  matcherUrl: (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, ''),
  natsUrl: process.env.NATS_URL || 'nats://localhost:4222',
  drainTimeoutMs: int(process.env.BENCH_DRAIN_TIMEOUT_MS, 45 * 60_000),
  submitConcurrency: int(process.env.BENCH_SUBMIT_CONCURRENCY, 16),
  // Alternating quantities. Below the matcher's fill-full threshold (1000) an order fills
  // in one round; >= threshold needs two (half, then remainder). 009 books trades inside
  // its match loop and retries failed bookings on every tick/poll, so two-round cohorts
  // can amplify a transient booking failure into a retry storm (see bench-results
  // 20260611-211628). Set BENCH_QTY_B=500 for a single-round-only workload.
  qtyA: int(process.env.BENCH_QTY_A, 500),
  qtyB: int(process.env.BENCH_QTY_B, 1800),
  pxStart: 210.0,
  pxFloor: 60.0,
  limitLow: 70.0,
  limitHigh: 90.0,
  drainPollMs: 500,
  stallMs: int(process.env.BENCH_STALL_MS, 20_000),
  closerBatch: 200,
  maxCloserBatches: 60,
};

function int(value, fallback) {
  const parsed = Number.parseInt(value ?? '', 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function px3(value) {
  return value.toFixed(3);
}

function percentile(sortedMicros, p) {
  if (sortedMicros.length === 0) return 0;
  const idx = Math.min(sortedMicros.length - 1, Math.ceil((p / 100) * sortedMicros.length) - 1);
  return sortedMicros[Math.max(0, idx)];
}

const nowMs = () => Number(process.hrtime.bigint() / 1_000_000n);

async function getJson(path) {
  const res = await fetch(`${cfg.matcherUrl}${path}`);
  if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`);
  return res.json();
}

async function getText(path) {
  const res = await fetch(`${cfg.matcherUrl}${path}`);
  if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`);
  return res.text();
}

async function openCount() {
  const open = await getJson(`/orders?status=open&accountId=${cfg.account}`);
  return open.length;
}

// ----- phase 1: submit resting orders -------------------------------------------------

async function submitOrders() {
  const latenciesMicros = [];
  let next = 0;
  let failures = 0;

  async function worker() {
    for (;;) {
      const i = next++;
      if (i >= cfg.orders) return;
      const body = JSON.stringify({
        accountId: cfg.account,
        security: cfg.tickers[i % cfg.tickers.length],
        side: 'Buy',
        quantity: i % 2 === 0 ? cfg.qtyA : cfg.qtyB,
        limitPrice: px3(cfg.limitLow + ((cfg.limitHigh - cfg.limitLow) * i) / cfg.orders),
      });
      const t0 = process.hrtime.bigint();
      const res = await fetch(`${cfg.matcherUrl}/orders`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
      });
      latenciesMicros.push(Number((process.hrtime.bigint() - t0) / 1000n));
      if (res.status !== 201) {
        failures++;
        if (failures <= 3) console.error(`[bench] create failed: ${res.status} ${await res.text()}`);
      } else {
        await res.arrayBuffer(); // drain body so the connection can be reused
      }
    }
  }

  const t0 = nowMs();
  await Promise.all(Array.from({ length: cfg.submitConcurrency }, worker));
  const elapsed = nowMs() - t0;
  if (failures > 0) throw new Error(`${failures} order creates failed`);
  latenciesMicros.sort((a, b) => a - b);
  return { elapsedMs: elapsed, latenciesMicros };
}

// ----- phase 2: publish the tick ramp --------------------------------------------------

async function publishTicks(nc, total, startPx, floorPx) {
  const perTicker = Math.floor(total / cfg.tickers.length);
  const published = cfg.tickers.length * perTicker;
  const batch = 1000;
  const msPerBatch = (batch / cfg.tickRate) * 1000;

  const t0 = nowMs();
  let sent = 0;
  while (sent < published) {
    const batchStart = nowMs();
    for (let b = 0; b < batch && sent < published; b++, sent++) {
      const tickerIdx = sent % cfg.tickers.length;
      const j = Math.floor(sent / cfg.tickers.length);
      const frac = perTicker <= 1 ? 1 : j / (perTicker - 1);
      const price = px3(startPx - (startPx - floorPx) * frac);
      nc.publish(`pricing.${cfg.tickers[tickerIdx]}`, sc.encode(`{"price":${price}}`));
    }
    await nc.flush();
    const elapsed = nowMs() - batchStart;
    if (elapsed < msPerBatch) await sleep(msPerBatch - elapsed);
  }
  return { elapsedMs: nowMs() - t0, published };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ----- phase 3: drain to terminal -------------------------------------------------------

async function drain(nc) {
  const t0 = nowMs();
  let lastCount = Number.POSITIVE_INFINITY;
  let lastProgressAt = t0;
  let closersPublished = 0;
  let closerBatches = 0;

  for (;;) {
    const open = await openCount();
    const elapsed = nowMs() - t0;
    if (open === 0) {
      return { elapsedMs: elapsed, closersPublished, closerBatches, completed: true };
    }
    if (open < lastCount) {
      lastCount = open;
      lastProgressAt = nowMs();
    }
    if (elapsed > cfg.drainTimeoutMs) {
      return { elapsedMs: elapsed, closersPublished, closerBatches, completed: false, openAtTimeout: open };
    }
    // Stalled with orders still open: the state never processed late low ticks (e.g.
    // client-side slow-consumer shedding). Nudge with floor-price closers, and count them.
    if (nowMs() - lastProgressAt > cfg.stallMs && closerBatches < cfg.maxCloserBatches) {
      for (let i = 0; i < cfg.closerBatch; i++) {
        nc.publish(`pricing.${cfg.tickers[i % cfg.tickers.length]}`, sc.encode(`{"price":${px3(cfg.pxFloor)}}`));
        closersPublished++;
      }
      await nc.flush();
      closerBatches++;
      lastProgressAt = nowMs();
    }
    await sleep(cfg.drainPollMs);
  }
}

// ----- evidence extraction ---------------------------------------------------------------

function metricValue(metricsText, name, labels = '') {
  const line = metricsText.split('\n').find((l) => l.startsWith(`${name}${labels}`));
  if (!line) return null;
  const value = Number(line.trim().split(/\s+/).pop());
  return Number.isFinite(value) ? value : null;
}

// ----- main run ---------------------------------------------------------------------------

async function run(stateId, outPath) {
  console.log(`[bench] state=${stateId} orders=${cfg.orders} ticks=${cfg.ticks} rate=${cfg.tickRate}/s account=${cfg.account} tickers=${cfg.tickers.join(',')}`);

  const health0 = await getJson('/health');
  const preOpen = await openCount();
  if (preOpen !== 0) throw new Error(`account ${cfg.account} already has ${preOpen} open orders; not a clean stack`);

  const nc = await connect({ servers: cfg.natsUrl });
  try {
    const totalT0 = nowMs();

    console.log('[bench] phase 1/3: submitting orders…');
    const submit = await submitOrders();
    console.log(`[bench]   ${cfg.orders} orders in ${(submit.elapsedMs / 1000).toFixed(1)}s`);

    console.log('[bench] phase 2/3: publishing tick ramp…');
    const publish = await publishTicks(nc, cfg.ticks, cfg.pxStart, cfg.pxFloor);
    console.log(`[bench]   ${publish.published} ticks in ${(publish.elapsedMs / 1000).toFixed(1)}s (${Math.round(publish.published / (publish.elapsedMs / 1000))}/s)`);

    console.log('[bench] phase 3/3: draining to terminal…');
    const drained = await drain(nc);
    const totalMs = nowMs() - totalT0;
    console.log(`[bench]   drained in ${(drained.elapsedMs / 1000).toFixed(1)}s (completed=${drained.completed}, closers=${drained.closersPublished})`);

    // Order-terminal is the ack-path milestone (009 parity: a 009 fill implies a booked
    // trade; 009b books asynchronously off the output ring). Give the async booking
    // bridge a settle window so final booking counters are honest in the report.
    await sleep(10_000);

    const filled = await getJson(`/orders?status=filled&accountId=${cfg.account}`);
    const health = await getJson('/health');
    const metricsText = await getText('/metrics');

    // Per-ticker final market price as seen by the matcher (evidence of how far through
    // the ramp the state actually consumed; OrderResponse carries marketPrice).
    const finalMarketPx = {};
    for (const order of filled.slice(0, 200)) {
      if (order.marketPrice != null && finalMarketPx[order.security] == null) {
        finalMarketPx[order.security] = order.marketPrice;
      }
    }

    const result = {
      state: stateId,
      config: { ...cfg },
      startedAt: new Date(Date.now() - totalMs).toISOString(),
      timings: {
        submitMs: submit.elapsedMs,
        publishMs: publish.elapsedMs,
        drainAfterPublishMs: drained.elapsedMs,
        totalMs,
      },
      ackLatencyMicros: {
        p50: percentile(submit.latenciesMicros, 50),
        p90: percentile(submit.latenciesMicros, 90),
        p99: percentile(submit.latenciesMicros, 99),
        max: submit.latenciesMicros[submit.latenciesMicros.length - 1] ?? 0,
      },
      workload: {
        ordersSubmitted: cfg.orders,
        ticksPublished: publish.published,
        closersPublished: drained.closersPublished,
        closerBatches: drained.closerBatches,
      },
      outcome: {
        completed: drained.completed,
        openAtEnd: drained.completed ? 0 : drained.openAtTimeout,
        filledCount: filled.length,
        finalMarketPx,
      },
      evidence: {
        // 009b only (null on 009): exact sequenced price ticks the BLP consumed.
        blpPriceTicksConsumed: metricValue(metricsText, 'traderx_input_events_total', '{type="price_tick"}'),
        blpAllocBytes: metricValue(metricsText, 'traderx_hotpath_alloc_bytes_total', '{node="blp"}'),
        autoFillSuccess: health?.matcher?.autoFillSuccess ?? null,
        tradeSubmitFailures: health?.matcher?.tradeSubmitFailures ?? null,
        matcherEngine: health?.matcher?.engine ?? health0?.matcher?.engine ?? '009-poll',
      },
    };

    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, JSON.stringify(result, null, 2));
    writeFileSync(outPath.replace(/\.json$/, '.metrics.txt'), metricsText);
    writeFileSync(outPath.replace(/\.json$/, '.health.json'), JSON.stringify(health, null, 2));
    console.log(`[bench] wrote ${outPath}`);

    if (!drained.completed) {
      console.error(`[bench] WARNING: drain timed out with ${drained.openAtTimeout} orders still open`);
      process.exitCode = 2;
    }
    if (filled.length !== cfg.orders) {
      console.error(`[bench] WARNING: filled=${filled.length} != submitted=${cfg.orders} (trade submit failures: ${result.evidence.tradeSubmitFailures})`);
    }
  } finally {
    await nc.close();
  }
}

// ----- comparison report --------------------------------------------------------------------

const fmtS = (ms) => `${(ms / 1000).toFixed(1)}s`;
const fmtMicros = (us) => (us >= 1000 ? `${(us / 1000).toFixed(1)}ms` : `${us}µs`);

async function compare(aPath, bPath) {
  const a = JSON.parse(await readFile(aPath, 'utf8'));
  const b = JSON.parse(await readFile(bPath, 'utf8'));
  const rows = [
    ['workload', `${a.workload.ordersSubmitted} orders + ${a.workload.ticksPublished.toLocaleString()} ticks`, `${b.workload.ordersSubmitted} orders + ${b.workload.ticksPublished.toLocaleString()} ticks`],
    ['order submit (REST)', fmtS(a.timings.submitMs), fmtS(b.timings.submitMs)],
    ['ack latency p50 / p99', `${fmtMicros(a.ackLatencyMicros.p50)} / ${fmtMicros(a.ackLatencyMicros.p99)}`, `${fmtMicros(b.ackLatencyMicros.p50)} / ${fmtMicros(b.ackLatencyMicros.p99)}`],
    ['tick publish window', fmtS(a.timings.publishMs), fmtS(b.timings.publishMs)],
    ['drain after last tick', fmtS(a.timings.drainAfterPublishMs), fmtS(b.timings.drainAfterPublishMs)],
    ['TOTAL wall time', fmtS(a.timings.totalMs), fmtS(b.timings.totalMs)],
    ['completed / filled', `${a.outcome.completed} / ${a.outcome.filledCount}`, `${b.outcome.completed} / ${b.outcome.filledCount}`],
    ['closer ticks needed', String(a.workload.closersPublished), String(b.workload.closersPublished)],
    ['ticks consumed (exact)', a.evidence.blpPriceTicksConsumed == null ? 'n/a (009 has no counter)' : a.evidence.blpPriceTicksConsumed.toLocaleString(), b.evidence.blpPriceTicksConsumed == null ? 'n/a' : b.evidence.blpPriceTicksConsumed.toLocaleString()],
    ['trade submit failures', String(a.evidence.tradeSubmitFailures), String(b.evidence.tradeSubmitFailures)],
  ];

  const w0 = Math.max(...rows.map((r) => r[0].length));
  const w1 = Math.max(a.state.length, ...rows.map((r) => r[1].length));
  console.log(`\n=== order-matcher benchmark: ${a.state} vs ${b.state} ===\n`);
  console.log(`${''.padEnd(w0)}  ${a.state.padEnd(w1)}  ${b.state}`);
  for (const [label, va, vb] of rows) {
    console.log(`${label.padEnd(w0)}  ${va.padEnd(w1)}  ${vb}`);
  }
  const delta = a.timings.totalMs - b.timings.totalMs;
  const ratio = b.timings.totalMs > 0 ? a.timings.totalMs / b.timings.totalMs : 0;
  console.log(`\nTOTAL difference: ${a.state} took ${fmtS(Math.abs(delta))} ${delta >= 0 ? 'LONGER' : 'less'} than ${b.state} (${ratio.toFixed(2)}x)`);
  const drainDelta = a.timings.drainAfterPublishMs - b.timings.drainAfterPublishMs;
  console.log(`Drain-after-last-tick difference: ${fmtS(Math.abs(drainDelta))} (${a.state} ${drainDelta >= 0 ? 'slower' : 'faster'})`);
  if (!a.outcome.completed || !b.outcome.completed) {
    console.log('NOTE: at least one run hit the drain timeout; totals understate the difference.');
  }
}

// ----- entrypoint -----------------------------------------------------------------------------

const args = process.argv.slice(2);
if (args[0] === '--compare') {
  await compare(args[1], args[2]);
} else {
  const stateIdx = args.indexOf('--state');
  const outIdx = args.indexOf('--out');
  if (stateIdx === -1 || outIdx === -1) {
    console.error('usage: order-matcher-bench.mjs --state <id> --out <result.json> | --compare <a.json> <b.json>');
    process.exit(1);
  }
  await run(args[stateIdx + 1], args[outIdx + 1]);
}
