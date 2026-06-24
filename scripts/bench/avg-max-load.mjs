#!/usr/bin/env node
// avg-max-load.mjs — run max-load.mjs N times back-to-back, scrape each run's
// "PEAK trades/sec", and report the AVERAGE peak throughput (plus median / min /
// max / stddev) to the console and to a text file.
//
// Why a wrapper instead of a re-implementation: max-load.mjs already encodes the
// tuned saturation workload (deep-in-the-money auto-fills, HTTP keep-alive, the
// Prometheus irate peak query). This driver just spawns it `--runs` times, each for
// a fixed `--secs`, reads the headline number it prints, and averages across runs so
// one unlucky (or lucky) run does not define "max throughput".
//
// Usage:  node avg-max-load.mjs [--runs N] [--secs S] [--cooldown C] [--out FILE] [max-load flags…]
//   --runs N        how many times to run max-load.mjs                 (default 10)
//   --secs S        duration of EACH run, must be > 0 (forwarded)      (default 20)
//   --cooldown C    idle seconds between runs to let the irate window
//                   decay (set ≥16 to fully clear the 15s window)      (default 8)
//   --out FILE      write the report here  (default scripts/bench/results/…)
//   --no-preflight  skip the matcher/Prometheus reachability check
//
//   Any other flag (--conc, --rate, --ramp, …) and all env vars (MATCHER_URL,
//   PROM_URL, ACCOUNT, TICKERS, QTY, …) pass straight through to max-load.mjs — see
//   that file's header for the full list.
//   env equivalents for this driver: RUNS, DURATION_SECS, COOLDOWN_SECS, OUT, SKIP_PREFLIGHT=1
//
// Examples:
//   node avg-max-load.mjs --runs 10 --secs 20 --conc 256
//   CONC=256 node avg-max-load.mjs --runs 5 --out /tmp/peak.txt

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
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
const cooldownSecs = Math.max(0, Number(takeFlag(passthrough, '--cooldown') ?? process.env.COOLDOWN_SECS ?? 8));
const skipPreflight = takeBool(passthrough, '--no-preflight') || process.env.SKIP_PREFLIGHT === '1';
let outFile = takeFlag(passthrough, '--out') ?? process.env.OUT;

// Each run must terminate so we can average, so --secs has to be present and > 0.
let perRunSecs;
const secsIdx = passthrough.indexOf('--secs');
if (secsIdx !== -1 && passthrough[secsIdx + 1] !== undefined) {
  perRunSecs = Number(passthrough[secsIdx + 1]);
} else if (process.env.DURATION_SECS !== undefined) {
  perRunSecs = Number(process.env.DURATION_SECS); // max-load reads this env itself
} else {
  perRunSecs = 20;
  passthrough.push('--secs', '20');
}
if (!Number.isFinite(perRunSecs) || perRunSecs <= 0) {
  console.error('[avg] --secs must be > 0 — each run has to finish before we can average. Aborting.');
  process.exit(2);
}

const matcherUrl = (process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, '');
const promUrl = (process.env.PROM_URL || 'http://localhost:9090').replace(/\/$/, '');

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

// ---- preflight: fail fast instead of doing N empty runs ------------------------
async function reachable(url) {
  try { return (await fetch(url, { signal: AbortSignal.timeout(5000) })).ok; }
  catch { return false; }
}
async function preflight() {
  if (skipPreflight) return;
  const [okMatcher, okProm] = await Promise.all([
    reachable(`${matcherUrl}/health`),
    reachable(`${promUrl}/api/v1/query?query=up`),
  ]);
  if (okMatcher && okProm) return;
  console.error('[avg] preflight failed — the workload would produce empty results:');
  if (!okMatcher) console.error(`      · order-matcher not reachable at ${matcherUrl}/health (is the stack up?)`);
  if (!okProm)    console.error(`      · Prometheus not reachable at ${promUrl} (peak trades/sec is read from it)`);
  console.error('      Start the stack (scripts/start-state-009b-lmax-sequencer-architecture-generated.sh)');
  console.error('      or re-run with --no-preflight to override.');
  process.exit(1);
}

// ---- run one child -------------------------------------------------------------
let child = null;
let aborted = false;

function runOnce(idx) {
  return new Promise((resolve) => {
    console.log(`\n[avg] ── run ${idx}/${runs} ─────────────────────────────── (${perRunSecs}s)`);
    child = spawn(process.execPath, [MAX_LOAD, ...passthrough], {
      env: process.env,
      stdio: ['ignore', 'pipe', 'inherit'], // capture+tee stdout, pass stderr through
    });
    let buf = '';
    child.stdout.on('data', (d) => { const s = d.toString(); buf += s; process.stdout.write(s); });
    child.on('close', (code) => {
      child = null;
      resolve({
        run: idx,
        code,
        peak: matchNum(buf, /PEAK trades\/sec=([\d.]+)/),
        sustained: matchNum(buf, /SUSTAINED trades\/sec[^\d]*([\d.]+)/),
        submitted: matchNum(buf, /submitted=(\d+)/),
        failed: matchNum(buf, /failed=(\d+)/),
      });
    });
  });
}

process.on('SIGINT', () => {
  aborted = true;
  console.log('\n[avg] Ctrl-C — stopping current run and writing a partial report…');
  if (child) child.kill('SIGINT'); // max-load handles SIGINT: graceful stop + prints its peak
});

// ---- main ----------------------------------------------------------------------
await preflight();

const cmdLine = `node max-load.mjs ${passthrough.join(' ')}`.trim();
const envEcho = ['ACCOUNT', 'TICKERS', 'QTY', 'LIMIT', 'CONC', 'RATE']
  .filter((k) => process.env[k] !== undefined)
  .map((k) => `${k}=${process.env[k]}`)
  .join('  ');

console.log(`[avg] averaging PEAK trades/sec over ${runs} run(s) × ${perRunSecs}s  (cooldown ${cooldownSecs}s)`);
console.log(`[avg] matcher=${matcherUrl}  prom=${promUrl}`);
console.log(`[avg] each run: ${cmdLine}${envEcho ? `   [${envEcho}]` : ''}`);
console.log(`[avg] report  : ${outFile}`);
const etaS = runs * perRunSecs + (runs - 1) * cooldownSecs;
console.log(`[avg] est. wall time ≈ ${Math.round(etaS)}s (${(etaS / 60).toFixed(1)} min)`);

const results = [];
for (let i = 1; i <= runs; i++) {
  results.push(await runOnce(i));
  if (aborted) break;
  if (i < runs && cooldownSecs > 0) {
    console.log(`[avg] cooldown ${cooldownSecs}s…`);
    await sleep(cooldownSecs * 1000);
    if (aborted) break;
  }
}

// ---- aggregate + report --------------------------------------------------------
const peaks = results.map((r) => r.peak).filter((n) => Number.isFinite(n) && n > 0);
const sustaineds = results.map((r) => r.sustained).filter((n) => Number.isFinite(n) && n > 0);
const zeroRuns = results.filter((r) => !(Number.isFinite(r.peak) && r.peak > 0)).map((r) => r.run);

const lines = [];
const L = (s = '') => lines.push(s);
L('TraderX max-load — average peak throughput');
L('='.repeat(58));
L(`generated   : ${new Date().toISOString()}`);
L(`matcher     : ${matcherUrl}`);
L(`prometheus  : ${promUrl}`);
L(`runs        : ${results.length} of ${runs}${aborted ? ' (ABORTED early)' : ''}`);
L(`per run     : ${perRunSecs}s   cooldown ${cooldownSecs}s`);
L(`command     : ${cmdLine}`);
if (envEcho) L(`env         : ${envEcho}`);
L('metric      : PEAK trades/sec = max over the run of');
L('              sum(irate(traderx_order_events_total{event=~"fill|partial_fill|force_fill"}[15s]))');
L('');
L(`${'run'.padStart(4)}  ${'peak/s'.padStart(10)}  ${'sustained/s'.padStart(12)}  ${'submitted'.padStart(11)}  ${'failed'.padStart(8)}`);
L('-'.repeat(58));
for (const r of results) {
  L(`${String(r.run).padStart(4)}  ${i0(r.peak).padStart(10)}  ${i0(r.sustained).padStart(12)}  ${i0(r.submitted).padStart(11)}  ${i0(r.failed).padStart(8)}`);
}
L('-'.repeat(58));
L('');
L(`valid runs (peak > 0)        : ${peaks.length} / ${results.length}`);
if (zeroRuns.length) L(`runs with no trades (excluded): ${zeroRuns.join(', ')}`);
L('');
L(`AVERAGE peak trades/sec      : ${i0(mean(peaks))}`);
L(`  median                     : ${i0(median(peaks))}`);
L(`  min / max                  : ${i0(Math.min(...peaks))} / ${i0(Math.max(...peaks))}`);
L(`  stddev (population)        : ${i0(stddev(peaks))}`);
if (sustaineds.length) {
  L('');
  L(`AVERAGE sustained trades/sec : ${i0(mean(sustaineds))}  (ramp/--rate mode: highest with no new failures)`);
  L(`  min / max                  : ${i0(Math.min(...sustaineds))} / ${i0(Math.max(...sustaineds))}`);
}

const report = lines.join('\n') + '\n';
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, report);

console.log('\n' + report);
console.log(`[avg] wrote ${outFile}`);
if (!peaks.length) {
  console.log('[avg] WARNING: no run produced trades — check the stack is up and the tickers are price-published.');
  process.exit(1);
}
process.exit(0);
