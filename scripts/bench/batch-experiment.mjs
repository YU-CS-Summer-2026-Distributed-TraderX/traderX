#!/usr/bin/env node
// batch-experiment.mjs — does HTTP batching extract more end-to-end throughput from the
// 009b LMAX order-matcher? Compares the single-order ingress (POST /orders, one order +
// one ack-block per request) against the batch ingress (POST /orders/batch, K orders +
// one ack-block per request) across a sweep of batch sizes.
//
// Metric (the scrape-safe one — see avg-max-load.mjs for why irate is blind under load):
//   booked/s = fill-counter delta / elapsed, with BOTH reads taken when HTTP is free
//   (just before the load and just after it drains). This is the SUSTAINED rate and the
//   number the whole experiment is about (the prior single-order ceiling was ~700-810/s).
//   We also echo submit/s (accepted-201 orders/elapsed) and the cumulative peak gauge.
//
// Design: ONE warm matcher, configs run back-to-back (no restart) so the JIT is hot for
// all of them — a fair relative comparison. A short warmup precedes the first measured
// config. (Restarting per-config would zero the peak gauge but pay a growing DB-bootstrap
// each time and cold-start every run; booked/s deltas are warm and restart-free.)
//
// Usage:  node batch-experiment.mjs [--secs S] [--cooldown C] [--warmup W] [--out FILE]
//   --secs S      measured load seconds per config   (default 15)
//   --cooldown C  idle seconds between configs       (default 4)
//   --warmup W    JIT warmup seconds before config 1 (default 12; 0 to skip)
//   env: MATCHER_URL, ACCOUNT, TICKERS, QTY, LIMIT (forwarded to the loaders)

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const takeFlag = (name, def) => {
  const i = process.argv.indexOf(name);
  return i !== -1 && process.argv[i + 1] !== undefined ? process.argv[i + 1] : def;
};

const perCfgSecs = Math.max(1, Number(takeFlag('--secs', 15)));
const cooldownSecs = Math.max(0, Number(takeFlag('--cooldown', 4)));
const warmupSecs = Math.max(0, Number(takeFlag('--warmup', 12)));
let outFile = takeFlag('--out', undefined);

const matcherUrl = (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, '');
const metricsUrl = `${matcherUrl}/metrics`;
if (!outFile) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  outFile = path.join(here, 'results', `batch-experiment-${ts}.txt`);
}
outFile = path.resolve(outFile);

// The sweep. Total in-flight orders ≈ conc*batch; kept comfortably under the 65536 ring.
const configs = [
  { label: 'single   conc=128',          script: 'max-load.mjs',   args: ['--conc', '128'] },
  { label: 'batch=1  conc=128 (control)', script: 'batch-load.mjs', args: ['--batch', '1',   '--conc', '128'] },
  { label: 'batch=10 conc=32',           script: 'batch-load.mjs', args: ['--batch', '10',  '--conc', '32'] },
  { label: 'batch=25 conc=16',           script: 'batch-load.mjs', args: ['--batch', '25',  '--conc', '16'] },
  { label: 'batch=50 conc=16',           script: 'batch-load.mjs', args: ['--batch', '50',  '--conc', '16'] },
  { label: 'batch=100 conc=16',          script: 'batch-load.mjs', args: ['--batch', '100', '--conc', '16'] },
  { label: 'batch=200 conc=8',           script: 'batch-load.mjs', args: ['--batch', '200', '--conc', '8'] },
];

// ---- helpers -------------------------------------------------------------------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const i0 = (n) => (Number.isFinite(n) ? String(Math.round(n)) : 'n/a');
const matchNum = (s, re) => { const m = s.match(re); return m ? Number(m[1]) : NaN; };
const FILL_RE = /^traderx_order_events_total\{event="(?:fill|partial_fill|force_fill)"\}\s+([\d.]+)/gm;
const parseGauge = (t) => { const m = t.match(/^traderx_trades_per_second_peak\s+([\d.]+)/m); return m ? Number(m[1]) : NaN; };
function parseFills(t) {
  let total = NaN;
  for (const m of t.matchAll(FILL_RE)) total = (Number.isNaN(total) ? 0 : total) + Number(m[1]);
  return total;
}
async function fetchMetrics() {
  try { const r = await fetch(metricsUrl, { signal: AbortSignal.timeout(4000) }); return r.ok ? await r.text() : ''; }
  catch { return ''; }
}
async function readMetricsStable(timeoutMs = 6000) {
  const t0 = Date.now();
  let txt = await fetchMetrics();
  while (Date.now() - t0 < timeoutMs && !(txt && Number.isFinite(parseFills(txt)))) {
    await sleep(400); txt = await fetchMetrics();
  }
  return txt;
}
async function waitHealth(timeoutMs = 30000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try { if ((await fetch(`${matcherUrl}/health`, { signal: AbortSignal.timeout(2000) })).ok) return true; } catch {}
    await sleep(1000);
  }
  return false;
}
async function waitPriceReady(timeoutMs = 30000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(`${matcherUrl}/health`, { signal: AbortSignal.timeout(2000) });
      if (r.ok) { const h = await r.json(); if ((h?.matcher?.ticks ?? 0) > 0) return true; }
    } catch {}
    await sleep(400);
  }
  return false;
}

let child = null;
function runLoader(script, args, secs, { quiet = false } = {}) {
  return new Promise((resolve) => {
    child = spawn(process.execPath, [path.join(here, script), ...args, '--secs', String(secs)], {
      env: process.env, stdio: ['ignore', 'pipe', 'inherit'],
    });
    let buf = '';
    child.stdout.on('data', (d) => { const s = d.toString(); buf += s; if (!quiet) process.stdout.write(s); });
    child.on('close', () => { child = null; resolve(buf); });
  });
}

async function measure(cfg) {
  const fills0 = parseFills(await readMetricsStable());
  const t0 = Date.now();
  const buf = await runLoader(cfg.script, cfg.args, perCfgSecs);
  const elapsed = (Date.now() - t0) / 1000;
  await sleep(1200);                       // let the ring fully drain into the fill counter
  const txt = await readMetricsStable();
  const fills1 = parseFills(txt);
  const peak = parseGauge(txt);
  const booked = Number.isFinite(fills1) && Number.isFinite(fills0) ? (fills1 - fills0) / elapsed : NaN;
  const submitted = matchNum(buf, /submitted=(\d+)/);
  const failed = matchNum(buf, /failed=(\d+)/);
  const submitRate = Number.isFinite(submitted) ? submitted / elapsed : NaN;
  const row = { label: cfg.label, booked, submitRate, submitted, failed, peak };
  console.log(`[exp]   → ${cfg.label}:  booked ${i0(booked)}/s · submit ${i0(submitRate)}/s · failed ${i0(failed)} · peak(cum) ${i0(peak)}/s`);
  return row;
}

// ---- main ----------------------------------------------------------------------
process.on('SIGINT', () => { console.log('\n[exp] Ctrl-C — stopping…'); if (child) child.kill('SIGINT'); });

console.log(`[exp] batch-ingress experiment on ${matcherUrl}`);
if (!(await waitHealth())) { console.error(`[exp] matcher not reachable at ${matcherUrl}/health — is the stack up?`); process.exit(1); }
const priced = await waitPriceReady();
console.log(`[exp] matcher healthy; price ticks ${priced ? 'flowing' : 'NOT seen yet (orders may rest — check price-publisher)'}`);
console.log(`[exp] ${configs.length} configs × ${perCfgSecs}s  (warmup ${warmupSecs}s, cooldown ${cooldownSecs}s)  → est ${Math.round(warmupSecs + configs.length * (perCfgSecs + cooldownSecs + 3))}s`);

if (warmupSecs > 0) {
  console.log(`[exp] warmup ${warmupSecs}s (single conc=128) to warm the JIT…`);
  await runLoader('max-load.mjs', ['--conc', '128'], warmupSecs, { quiet: true });
  await sleep(cooldownSecs * 1000);
}

const results = [];
for (let i = 0; i < configs.length; i++) {
  console.log(`\n[exp] ── ${i + 1}/${configs.length}  ${configs[i].label} ──────────────`);
  results.push(await measure(configs[i]));
  if (i < configs.length - 1) await sleep(cooldownSecs * 1000);
}

// ---- report --------------------------------------------------------------------
const baseline = results[0]?.booked;
const lines = [];
const L = (s = '') => lines.push(s);
L('TraderX batch-ingress experiment (009b LMAX order-matcher) — option 2: HTTP batching');
L('='.repeat(78));
L(`generated : ${new Date().toISOString()}`);
L(`matcher   : ${matcherUrl}`);
L(`per config: ${perCfgSecs}s warm load  (warmup ${warmupSecs}s, cooldown ${cooldownSecs}s, no restart)`);
const envEcho = ['ACCOUNT', 'TICKERS', 'QTY', 'LIMIT'].filter((k) => process.env[k]).map((k) => `${k}=${process.env[k]}`).join('  ');
if (envEcho) L(`env       : ${envEcho}`);
L('metric    : booked/s = fill-counter delta / elapsed (SUSTAINED, scrape-safe) — the headline');
L('            submit/s = accepted-201 orders / elapsed;  peak(cum) = cumulative gauge high-water');
L('');
L(`${'config'.padEnd(24)}  ${'booked/s'.padStart(9)}  ${'submit/s'.padStart(9)}  ${'failed'.padStart(8)}  ${'vs single'.padStart(10)}`);
L('-'.repeat(78));
for (const r of results) {
  const speedup = Number.isFinite(baseline) && baseline > 0 && Number.isFinite(r.booked)
    ? `${(r.booked / baseline).toFixed(2)}×` : 'n/a';
  L(`${r.label.padEnd(24)}  ${i0(r.booked).padStart(9)}  ${i0(r.submitRate).padStart(9)}  ${i0(r.failed).padStart(8)}  ${speedup.padStart(10)}`);
}
L('-'.repeat(78));
L('');
const best = results.filter((r) => Number.isFinite(r.booked)).sort((a, b) => b.booked - a.booked)[0];
if (best) {
  L(`single-order baseline : ${i0(baseline)}/s booked`);
  L(`best batch config     : ${best.label.trim()} → ${i0(best.booked)}/s booked`);
  if (Number.isFinite(baseline) && baseline > 0) L(`best speedup          : ${(best.booked / baseline).toFixed(2)}× over single-order ingress`);
}

const report = lines.join('\n') + '\n';
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, report);
console.log('\n' + report);
console.log(`[exp] wrote ${outFile}`);
process.exit(0);
