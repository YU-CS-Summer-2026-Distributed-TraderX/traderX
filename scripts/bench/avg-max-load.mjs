#!/usr/bin/env node
// avg-max-load.mjs — drive the order-matcher to saturation N times and report the
// AVERAGE peak throughput (plus median / min / max / stddev) to the console and to a
// text file.
//
// Measurement (learned the hard way — see LMAX-OUTPUT-DISRUPTOR.md / the bench notes):
//   The matcher's REST listener is BOTH the load target and the /metrics source, so
//   while the load saturates it, every Prometheus/HTTP scrape of the fill counter is
//   starved — `irate(...)` mostly samples the quiet gaps and reads ~0. The only
//   trustworthy peak is the IN-PROCESS gauge `traderx_trades_per_second_peak`
//   (engine.peakTradesPerSecondOut(), a 100ms event-time high-water mark). That gauge
//   "resets on matcher restart" ONLY — so to get independent per-run peaks this driver
//   restarts the matcher before each run, then reads the gauge after the run.
//
//   Per run it reports three numbers, each measured where it is actually reliable:
//     · peak/s     — internal hot-path peak  = the gauge, read after the load (HTTP free)
//     · booked/s   — sustained booked fills  = fill-counter delta / elapsed
//                    (both counter reads happen when HTTP is NOT saturated: just after
//                     restart, and just after the load stops)
//     · submit/s   — client REST submit rate = max-load's accepted-201 count / elapsed
//
//   NOTE: each restart is COLD; the LMAX engine warms up (JIT) during the run, so longer
//   runs reach a higher peak. The warm steady-state ceiling under sustained no-restart
//   load is higher than any single cold run — run with --no-reset for that (one number).
//
// Usage:  node avg-max-load.mjs [--runs N] [--secs S] [--cooldown C] [--out FILE] [max-load flags…]
//   --runs N        how many measured runs                              (default 10)
//   --secs S        load duration of EACH run, must be > 0 (forwarded)  (default 25)
//   --cooldown C    idle seconds between runs                           (default 4)
//   --out FILE      write the report here  (default scripts/bench/results/…)
//   --no-reset      do NOT restart the matcher between runs. The gauge is then a
//                   cumulative high-water mark, so only run 1 is an independent peak —
//                   use this with --runs 1 for a single WARM ceiling number.
//   --no-preflight  skip the matcher reachability check
//
//   Reset command (gauge zeroing) — override any of:
//     RESET_CMD                full shell command to restart the matcher
//     MATCHER_COMPOSE_FILE     (default generated 009/009b compose file)
//     MATCHER_COMPOSE_PROJECT  (default traderx-state-009)
//     MATCHER_SERVICE          (default order-matcher)
//   Load/env (passed through to max-load.mjs): MATCHER_URL, ACCOUNT, TICKERS, QTY,
//   CONC, and flags like --conc / --rate / --ramp. See max-load.mjs for the full list.
//   env equivalents for this driver: RUNS, DURATION_SECS, COOLDOWN_SECS, OUT,
//   SKIP_PREFLIGHT=1, NO_RESET=1
//
// Examples:
//   node avg-max-load.mjs --runs 10 --secs 25            # averaged cold-start peak
//   node avg-max-load.mjs --no-reset --runs 1 --secs 60  # single warm ceiling

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../..');
const MAX_LOAD = path.join(here, 'max-load.mjs');

// ---- arg parsing: consume our own flags, forward the rest to max-load ----------
const takeFlag = (args, name) => {
  const i = args.indexOf(name);
  if (i !== -1 && args[i + 1] !== undefined) return args.splice(i, 2)[1];
  return undefined;
};
const takeBool = (args, name) => {
  const i = args.indexOf(name);
  if (i !== -1) { args.splice(i, 1); return true; }
  return false;
};

const passthrough = process.argv.slice(2);
const runs = Math.max(1, Number(takeFlag(passthrough, '--runs') ?? process.env.RUNS ?? 10));
const cooldownSecs = Math.max(0, Number(takeFlag(passthrough, '--cooldown') ?? process.env.COOLDOWN_SECS ?? 4));
const skipPreflight = takeBool(passthrough, '--no-preflight') || process.env.SKIP_PREFLIGHT === '1';
const noReset = takeBool(passthrough, '--no-reset') || process.env.NO_RESET === '1';
let outFile = takeFlag(passthrough, '--out') ?? process.env.OUT;

// Each run must terminate, so --secs has to be present and > 0 (default 25 for warmup).
let perRunSecs;
const secsIdx = passthrough.indexOf('--secs');
if (secsIdx !== -1 && passthrough[secsIdx + 1] !== undefined) {
  perRunSecs = Number(passthrough[secsIdx + 1]);
} else if (process.env.DURATION_SECS !== undefined) {
  perRunSecs = Number(process.env.DURATION_SECS);
} else {
  perRunSecs = 25;
  passthrough.push('--secs', '25');
}
if (!Number.isFinite(perRunSecs) || perRunSecs <= 0) {
  console.error('[avg] --secs must be > 0 — each run has to finish before we can average. Aborting.');
  process.exit(2);
}

const matcherUrl = (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, '');
const metricsUrl = `${matcherUrl}/metrics`;
const composeFile = process.env.MATCHER_COMPOSE_FILE
  || path.join(repoRoot, 'generated/code/target-generated/order-management-matcher/docker-compose.yml');
const composeProj = process.env.MATCHER_COMPOSE_PROJECT || 'traderx-state-009';
const matcherService = process.env.MATCHER_SERVICE || 'order-matcher';
const resetCmd = process.env.RESET_CMD
  || `docker compose -f "${composeFile}" -p "${composeProj}" restart ${matcherService}`;

if (!outFile) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  outFile = path.join(here, 'results', `max-load-avg-${ts}.txt`);
}
outFile = path.resolve(outFile);

// ---- helpers -------------------------------------------------------------------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const matchNum = (s, re) => { const m = s.match(re); return m ? Number(m[1]) : NaN; };
const sum = (a) => a.reduce((x, y) => x + y, 0);
const mean = (a) => (a.length ? sum(a) / a.length : NaN);
const median = (a) => {
  if (!a.length) return NaN;
  const s = [...a].sort((x, y) => x - y);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
const stddev = (a) => {
  if (a.length < 2) return 0;
  const m = mean(a);
  return Math.sqrt(sum(a.map((x) => (x - m) ** 2)) / a.length);
};
const i0 = (n) => (Number.isFinite(n) ? String(Math.round(n)) : 'n/a');

async function fetchMetrics() {
  try {
    const r = await fetch(metricsUrl, { signal: AbortSignal.timeout(4000) });
    return r.ok ? await r.text() : '';
  } catch { return ''; }
}
const FILL_RE = /^traderx_order_events_total\{event="(?:fill|partial_fill|force_fill)"\}\s+([\d.]+)/gm;
function parseGauge(txt) { const m = txt.match(/^traderx_trades_per_second_peak\s+([\d.]+)/m); return m ? Number(m[1]) : NaN; }
function parseFills(txt) {
  let total = NaN;
  for (const m of txt.matchAll(FILL_RE)) total = (Number.isNaN(total) ? 0 : total) + Number(m[1]);
  return total;
}

function sh(cmd) {
  return new Promise((resolve) => {
    const p = spawn(cmd, { shell: true, stdio: 'ignore' });
    p.on('close', (code) => resolve(code));
    p.on('error', () => resolve(-1));
  });
}
async function waitHealth(timeoutMs = 90000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try { if ((await fetch(`${matcherUrl}/health`, { signal: AbortSignal.timeout(2000) })).ok) return true; } catch {}
    await sleep(1000);
  }
  return false;
}

// After a restart the matcher has no price yet; autoFill rests every order until the
// first price tick arrives over NATS. Wait for ticks>0 so the load actually fills
// (otherwise run 1 books nothing and the gauge stays ~0).
async function waitPriceReady(timeoutMs = 25000) {
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

// Right after a heavy load the matcher can briefly 500 its /metrics while draining;
// retry until the gauge line parses (HTTP is free again once the load has stopped).
async function readMetricsStable(timeoutMs = 6000) {
  const t0 = Date.now();
  let txt = await fetchMetrics();
  while (Date.now() - t0 < timeoutMs && !(txt && Number.isFinite(parseGauge(txt)))) {
    await sleep(400);
    txt = await fetchMetrics();
  }
  return txt;
}

// ---- preflight -----------------------------------------------------------------
async function preflight() {
  if (skipPreflight) return;
  let ok = false;
  try { ok = (await fetch(`${matcherUrl}/health`, { signal: AbortSignal.timeout(5000) })).ok; } catch {}
  if (!ok) {
    console.error(`[avg] preflight failed — order-matcher not reachable at ${matcherUrl}/health (is the stack up?).`);
    console.error('      Start it (scripts/start-state-009b-lmax-sequencer-architecture-generated.sh) or pass --no-preflight.');
    process.exit(1);
  }
  if (!noReset && (await sh('docker compose version')) !== 0) {
    console.error('[avg] preflight failed — `docker compose` is required to reset the gauge between runs.');
    console.error('      Fix docker, or pass --no-reset (then use --runs 1 for a single warm number), or set RESET_CMD.');
    process.exit(1);
  }
}

// ---- run one measured run ------------------------------------------------------
let child = null;
let aborted = false;

async function runOnce(idx) {
  console.log(`\n[avg] ── run ${idx}/${runs} ─────────────────────────────── (${perRunSecs}s load)`);
  if (!noReset) {
    process.stdout.write(`[avg] restarting ${matcherService} to reset the peak gauge… `);
    await sh(resetCmd);
    const healthy = await waitHealth();
    const priced = healthy && (await waitPriceReady());
    console.log(healthy ? (priced ? 'ready' : 'ready (no price tick yet — orders may rest)') : 'TIMEOUT (continuing anyway)');
  }
  const fills0 = parseFills(await readMetricsStable()); // HTTP is free pre-load; retry guards a transient miss
  const t0 = Date.now();

  const buf = await new Promise((resolve) => {
    child = spawn(process.execPath, [MAX_LOAD, ...passthrough], {
      env: process.env,
      stdio: ['ignore', 'pipe', 'inherit'],
    });
    let b = '';
    child.stdout.on('data', (d) => { const s = d.toString(); b += s; process.stdout.write(s); });
    child.on('close', () => { child = null; resolve(b); });
  });

  const elapsed = (Date.now() - t0) / 1000;
  const txt = await readMetricsStable(); // HTTP is free again after the load; retry through any drain-500s
  const peak = parseGauge(txt);
  const fills1 = parseFills(txt);
  const booked = Number.isFinite(fills1) && Number.isFinite(fills0) ? (fills1 - fills0) / elapsed : NaN;
  const submitted = matchNum(buf, /submitted=(\d+)/);
  const failed = matchNum(buf, /failed=(\d+)/);
  const submitRate = Number.isFinite(submitted) ? submitted / elapsed : NaN;
  console.log(`[avg]   → peak ${i0(peak)}/s · booked ${i0(booked)}/s · submit ${i0(submitRate)}/s · failed ${i0(failed)}`);
  return { run: idx, peak, booked, submitRate, submitted, failed };
}

process.on('SIGINT', () => {
  aborted = true;
  console.log('\n[avg] Ctrl-C — stopping current run and writing a partial report…');
  if (child) child.kill('SIGINT');
});

// ---- main ----------------------------------------------------------------------
await preflight();

const cmdLine = `node max-load.mjs ${passthrough.join(' ')}`.trim();
const envEcho = ['ACCOUNT', 'TICKERS', 'QTY', 'LIMIT', 'CONC', 'RATE']
  .filter((k) => process.env[k] !== undefined).map((k) => `${k}=${process.env[k]}`).join('  ');

console.log(`[avg] averaging PEAK trades/sec over ${runs} run(s) × ${perRunSecs}s  (cooldown ${cooldownSecs}s)`);
console.log(`[avg] matcher=${matcherUrl}  reset=${noReset ? 'OFF (cumulative gauge — run 1 only is independent)' : `restart ${matcherService}`}`);
console.log(`[avg] each run: ${cmdLine}${envEcho ? `   [${envEcho}]` : ''}`);
console.log(`[avg] report  : ${outFile}`);
const perRunOverhead = noReset ? cooldownSecs : 18 + cooldownSecs; // ~restart+health
console.log(`[avg] est. wall time ≈ ${Math.round(runs * (perRunSecs + perRunOverhead))}s\n`);

const results = [];
for (let i = 1; i <= runs; i++) {
  results.push(await runOnce(i));
  if (aborted) break;
  if (i < runs && cooldownSecs > 0) { await sleep(cooldownSecs * 1000); }
  if (aborted) break;
}

// ---- aggregate + report --------------------------------------------------------
const peaks = results.map((r) => r.peak).filter((n) => Number.isFinite(n) && n > 0);
const bookeds = results.map((r) => r.booked).filter((n) => Number.isFinite(n) && n > 0);
const submits = results.map((r) => r.submitRate).filter((n) => Number.isFinite(n) && n > 0);
const zeroRuns = results.filter((r) => !(Number.isFinite(r.peak) && r.peak > 0)).map((r) => r.run);

const lines = [];
const L = (s = '') => lines.push(s);
L('TraderX max-load — average peak throughput (009b LMAX order-matcher)');
L('='.repeat(64));
L(`generated   : ${new Date().toISOString()}`);
L(`matcher     : ${matcherUrl}`);
L(`runs        : ${results.length} of ${runs}${aborted ? ' (ABORTED early)' : ''}   per run: ${perRunSecs}s load, cooldown ${cooldownSecs}s`);
L(`reset       : ${noReset ? 'OFF — gauge is cumulative; only run 1 is an independent peak' : `restart ${matcherService} before each run (cold start each time)`}`);
L(`command     : ${cmdLine}`);
if (envEcho) L(`env         : ${envEcho}`);
L('metric      : peak/s   = traderx_trades_per_second_peak (in-process 100ms high-water mark)');
L('              booked/s = fill-counter delta / elapsed (sustained, scrape-safe)');
L('              submit/s = accepted-201 orders / elapsed (client REST rate)');
L('');
L(`${'run'.padStart(4)}  ${'peak/s'.padStart(9)}  ${'booked/s'.padStart(9)}  ${'submit/s'.padStart(9)}  ${'failed'.padStart(7)}`);
L('-'.repeat(64));
for (const r of results) {
  L(`${String(r.run).padStart(4)}  ${i0(r.peak).padStart(9)}  ${i0(r.booked).padStart(9)}  ${i0(r.submitRate).padStart(9)}  ${i0(r.failed).padStart(7)}`);
}
L('-'.repeat(64));
L('');
L(`valid runs (peak > 0)        : ${peaks.length} / ${results.length}`);
if (zeroRuns.length) L(`runs with no peak (excluded) : ${zeroRuns.join(', ')}`);
L('');
L(`AVERAGE peak trades/sec      : ${i0(mean(peaks))}`);
L(`  median                     : ${i0(median(peaks))}`);
L(`  min / max                  : ${i0(peaks.length ? Math.min(...peaks) : NaN)} / ${i0(peaks.length ? Math.max(...peaks) : NaN)}`);
L(`  stddev (population)        : ${i0(stddev(peaks))}`);
L('');
L(`AVERAGE booked trades/sec    : ${i0(mean(bookeds))}   (sustained)`);
L(`AVERAGE client submit/sec    : ${i0(mean(submits))}`);
if (!noReset) L('NOTE: each run is a COLD restart; the warm steady-state ceiling is higher — see --no-reset.');

const report = lines.join('\n') + '\n';
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, report);

console.log('\n' + report);
console.log(`[avg] wrote ${outFile}`);
if (!peaks.length) {
  console.log('[avg] WARNING: no run produced a peak — check the stack is up and the tickers are price-published.');
  process.exit(1);
}
process.exit(0);
