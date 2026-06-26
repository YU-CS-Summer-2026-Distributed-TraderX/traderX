#!/usr/bin/env node
/**
 * Output fan-out latency probe for account trade and position subjects.
 *
 * Run this against one already-started runtime at a time to record a JSON result, then use
 * `--compare` to print an A/B report from two result files.
 *
 * Typical pairing:
 *   - baseline `lmax-sequencer-no-gc` 009b old fan-out vs current `output-disruptor`
 */

import { connect, StringCodec } from 'nats';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const sc = StringCodec();

const cfg = {
  state: process.env.FANOUT_STATE || '009b',
  label: process.env.FANOUT_LABEL || '',
  matcherUrl: (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, ''),
  natsUrl: process.env.NATS_URL || 'nats://localhost:4222',
  account: int(process.env.FANOUT_ACCOUNT, 42422),
  security: (process.env.FANOUT_SECURITY || 'JPM').toUpperCase(),
  iterations: int(process.env.FANOUT_ITERATIONS, 50),
  quantity: int(process.env.FANOUT_QTY, 25),
  quantityStep: int(process.env.FANOUT_QTY_STEP, 1),
  limitPrice: number(process.env.FANOUT_LIMIT_PRICE, 150),
  fillPrice: number(process.env.FANOUT_FILL_PRICE, 100),
  fillPriceStep: number(process.env.FANOUT_FILL_PRICE_STEP, 0.001),
  timeoutMs: int(process.env.FANOUT_TIMEOUT_MS, 10_000),
  out: process.env.FANOUT_OUT || `bench-results/output-fanout-${Date.now()}.json`,
};

function int(value, fallback) {
  const parsed = Number.parseInt(value ?? '', 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function number(value, fallback) {
  const parsed = Number.parseFloat(value ?? '');
  return Number.isFinite(parsed) ? parsed : fallback;
}

function px3(value) {
  return value.toFixed(3);
}

function iterationFillPrice(iteration) {
  return cfg.fillPrice + (iteration * cfg.fillPriceStep);
}

function iterationQuantity(iteration) {
  return cfg.quantity + (iteration * cfg.quantityStep);
}

function nowNs() {
  return process.hrtime.bigint();
}

function microsSince(startNs) {
  return Number((nowNs() - startNs) / 1000n);
}

function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

function normalizeMessage(bytes) {
  const parsed = JSON.parse(sc.decode(bytes));
  return parsed && typeof parsed === 'object' && parsed.payload ? parsed.payload : parsed;
}

async function requestJson(path, options, expectedStatus) {
  const res = await fetch(`${cfg.matcherUrl}${path}`, options);
  const text = await res.text();
  if (res.status !== expectedStatus) {
    throw new Error(`${options.method || 'GET'} ${path} -> ${res.status}: ${text}`);
  }
  return text ? JSON.parse(text) : {};
}

function waitForMessages(pending, timeoutMs) {
  return Promise.race([
    new Promise((resolve) => {
      pending.resolve = resolve;
    }),
    new Promise((_, reject) => setTimeout(() => reject(new Error('timed out waiting for fan-out subjects')), timeoutMs)),
  ]);
}

function maybeResolvePending(pending, startedNs) {
  if (pending.trade && pending.position) {
    pending.resolve({
      accountTradeMicros: pending.trade.micros,
      accountPositionMicros: pending.position.micros,
      legacyTradesMicros: pending.legacyTrade ? pending.legacyTrade.micros : null,
      tradePayload: pending.trade.payload,
      positionPayload: pending.position.payload,
    });
  }
}

async function run() {
  const accountTradeSubject = `/accounts/${cfg.account}/trades`;
  const positionSubject = `/accounts/${cfg.account}/positions`;
  const legacyTradeSubject = '/trades';
  const nc = await connect({ servers: cfg.natsUrl });

  const results = [];
  let activePending = null;
  let lastPositionQuantity = null;

  const subscriptions = [
    [accountTradeSubject, 'trade'],
    [positionSubject, 'position'],
    [legacyTradeSubject, 'legacyTrade'],
  ].map(([subject, kind]) => {
    const sub = nc.subscribe(subject);
    (async () => {
      for await (const msg of sub) {
        let payload;
        try {
          payload = normalizeMessage(msg.data);
        } catch {
          continue;
        }
        const pending = activePending;
        if (!pending) {
          continue;
        }

        if (!matchesPending(kind, payload, pending)) {
          continue;
        }
        pending[kind] = { micros: microsSince(pending.startedNs), payload };
        maybeResolvePending(pending);
      }
    })().catch((err) => {
      console.error(`[fanout-bench] subscription ${subject} failed: ${err.message}`);
      process.exitCode = 1;
    });
    return sub;
  });

  await nc.flush();

  try {
    for (let i = 0; i < cfg.iterations; i++) {
      const quantity = iterationQuantity(i);
      const created = await requestJson('/orders', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          accountId: cfg.account,
          security: cfg.security,
          side: 'Buy',
          quantity,
          limitPrice: px3(cfg.limitPrice),
        }),
      }, 201);
      if (!created.orderId) {
        throw new Error('order create response missing orderId');
      }

      const pending = {
        startedNs: nowNs(),
        resolve: null,
        accountId: cfg.account,
        security: cfg.security,
        quantity,
        side: 'Buy',
        orderId: created.orderId,
        expectedPositionQuantity: lastPositionQuantity == null ? null : lastPositionQuantity + quantity,
      };
      activePending = pending;
      const wait = waitForMessages(pending, cfg.timeoutMs);
      const fillPrice = iterationFillPrice(i);
      nc.publish(`pricing.${cfg.security}`, sc.encode(`{"price":${px3(fillPrice)}}`));
      await nc.flush();
      const result = await wait;
      activePending = null;
      if (Number.isFinite(Number(result.positionPayload?.quantity))) {
        lastPositionQuantity = Number(result.positionPayload.quantity);
      }
      results.push({
        iteration: i,
        orderId: created.orderId,
        quantity,
        fillPrice: px3(fillPrice),
        ...result,
      });
      console.log(`[fanout-bench] ${i + 1}/${cfg.iterations} ${created.orderId} qty=${quantity} px=${px3(fillPrice)} trade=${result.accountTradeMicros}us position=${result.accountPositionMicros}us`);
    }
  } finally {
    for (const sub of subscriptions) {
      sub.unsubscribe();
    }
    await nc.drain();
  }

  const tradeLatencies = results.map((r) => r.accountTradeMicros).sort((a, b) => a - b);
  const positionLatencies = results.map((r) => r.accountPositionMicros).sort((a, b) => a - b);
  const legacyLatencies = results
    .map((r) => r.legacyTradesMicros)
    .filter((v) => v != null)
    .sort((a, b) => a - b);
  const output = {
    state: cfg.state,
    label: cfg.label || cfg.state,
    matcherUrl: cfg.matcherUrl,
    natsUrl: cfg.natsUrl,
    account: cfg.account,
    security: cfg.security,
    iterations: cfg.iterations,
    quantity: cfg.quantity,
    quantityStep: cfg.quantityStep,
    limitPrice: cfg.limitPrice,
    fillPrice: cfg.fillPrice,
    fillPriceStep: cfg.fillPriceStep,
    subjects: {
      accountTrades: accountTradeSubject,
      positions: positionSubject,
      legacyTrades: legacyTradeSubject,
    },
    summaryMicros: {
      accountTrades: summarize(tradeLatencies),
      positions: summarize(positionLatencies),
      legacyTrades: summarize(legacyLatencies),
    },
    results,
  };

  mkdirSync(dirname(cfg.out), { recursive: true });
  writeFileSync(cfg.out, JSON.stringify(output, null, 2));
  console.log(`[fanout-bench] wrote ${cfg.out}`);
}

function summarize(sorted) {
  if (sorted.length === 0) {
    return { count: 0 };
  }
  return {
    count: sorted.length,
    min: sorted[0],
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
  };
}

function matchesPending(kind, payload, pending) {
  if (!payload || typeof payload !== 'object') {
    return false;
  }

  if (kind === 'position') {
    if (Number(payload.accountId) !== pending.accountId || payload.security !== pending.security) {
      return false;
    }
    if (pending.expectedPositionQuantity == null) {
      return true;
    }
    return Number(payload.quantity) === pending.expectedPositionQuantity;
  }

  if (kind === 'trade') {
    return (
      Number(payload.accountId) === pending.accountId &&
      payload.security === pending.security &&
      Number(payload.quantity) === pending.quantity &&
      String(payload.side || '').toLowerCase() === String(pending.side).toLowerCase()
    );
  }

  if (kind === 'legacyTrade') {
    return (
      Number(payload.accountId) === pending.accountId &&
      payload.security === pending.security &&
      Number(payload.quantity) === pending.quantity &&
      String(payload.side || '').toLowerCase() === String(pending.side).toLowerCase()
    );
  }

  return false;
}

const fmtMicros = (value) => {
  if (value == null) {
    return 'n/a';
  }
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)}ms`;
  }
  return `${value}us`;
};

function summaryValue(run, family, field) {
  return run.summaryMicros?.[family]?.[field] ?? null;
}

async function compare(aPath, bPath) {
  const a = JSON.parse(await import('node:fs/promises').then(({ readFile }) => readFile(aPath, 'utf8')));
  const b = JSON.parse(await import('node:fs/promises').then(({ readFile }) => readFile(bPath, 'utf8')));
  const aName = a.label || a.state;
  const bName = b.label || b.state;
  const rows = [
    ['iterations', String(a.iterations), String(b.iterations)],
    ['security / qty', `${a.security} / ${a.quantity ?? '?'} +${a.quantityStep ?? 0}`, `${b.security} / ${b.quantity ?? '?'} +${b.quantityStep ?? 0}`],
    ['acct trade p50', fmtMicros(summaryValue(a, 'accountTrades', 'p50')), fmtMicros(summaryValue(b, 'accountTrades', 'p50'))],
    ['acct trade p95', fmtMicros(summaryValue(a, 'accountTrades', 'p95')), fmtMicros(summaryValue(b, 'accountTrades', 'p95'))],
    ['acct trade p99', fmtMicros(summaryValue(a, 'accountTrades', 'p99')), fmtMicros(summaryValue(b, 'accountTrades', 'p99'))],
    ['position p50', fmtMicros(summaryValue(a, 'positions', 'p50')), fmtMicros(summaryValue(b, 'positions', 'p50'))],
    ['position p95', fmtMicros(summaryValue(a, 'positions', 'p95')), fmtMicros(summaryValue(b, 'positions', 'p95'))],
    ['position p99', fmtMicros(summaryValue(a, 'positions', 'p99')), fmtMicros(summaryValue(b, 'positions', 'p99'))],
    ['legacy /trades p50', fmtMicros(summaryValue(a, 'legacyTrades', 'p50')), fmtMicros(summaryValue(b, 'legacyTrades', 'p50'))],
    ['legacy /trades p95', fmtMicros(summaryValue(a, 'legacyTrades', 'p95')), fmtMicros(summaryValue(b, 'legacyTrades', 'p95'))],
  ];

  const w0 = Math.max(...rows.map((row) => row[0].length));
  const w1 = Math.max(aName.length, ...rows.map((row) => row[1].length));
  console.log(`\n=== output fan-out benchmark: ${aName} vs ${bName} ===\n`);
  console.log(`${''.padEnd(w0)}  ${aName.padEnd(w1)}  ${bName}`);
  for (const [label, va, vb] of rows) {
    console.log(`${label.padEnd(w0)}  ${va.padEnd(w1)}  ${vb}`);
  }

  const tradeDelta = (summaryValue(a, 'accountTrades', 'p50') ?? 0) - (summaryValue(b, 'accountTrades', 'p50') ?? 0);
  const posDelta = (summaryValue(a, 'positions', 'p50') ?? 0) - (summaryValue(b, 'positions', 'p50') ?? 0);
  console.log(`\nAccount-trade p50 delta: ${fmtMicros(Math.abs(tradeDelta))} (${tradeDelta >= 0 ? aName + ' slower' : aName + ' faster'})`);
  console.log(`Position p50 delta: ${fmtMicros(Math.abs(posDelta))} (${posDelta >= 0 ? aName + ' slower' : aName + ' faster'})`);
}

const args = process.argv.slice(2);
if (args[0] === '--compare') {
  if (args.length !== 3) {
    console.error('usage: output-fanout-bench.mjs --compare <a.json> <b.json>');
    process.exit(1);
  }
  compare(args[1], args[2]).catch((err) => {
    console.error(`[fanout-bench] ${err.stack || err.message}`);
    process.exit(1);
  });
} else {
  run().catch((err) => {
    console.error(`[fanout-bench] ${err.stack || err.message}`);
    process.exit(1);
  });
}
