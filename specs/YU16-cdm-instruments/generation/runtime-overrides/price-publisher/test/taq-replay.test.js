// YU17 (ADR-070): the runnable check for the replay clock.
//
// The load-bearing assertions are the silent failure modes:
//   1. a clock that resumes WRONG after a restart (position must be pure arithmetic on
//      (now - epoch), so two calls with the same inputs agree — the stateless property);
//   2. the end of the tape LOOPING or walking instead of holding (decision: hold at the last
//      close, asOf frozen and honestly ageing);
//   3. a half-valid extract replaying some symbols and walking others (all-or-nothing);
//   4. a failed load that leaves /health unable to say the walk is what you are getting.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');

const W = 195;            // windowSeconds
const SESSION = 23400;    // 09:30-16:00 ET
const WPD = SESSION / W;  // 120 windows/day
const C = 13;             // compression: one trading day per 30 wall-clock minutes
const EPOCH = 1_700_000_000_000;

// Two days whose openMs are NOT 24h apart, standing in for the real extract's EST->EDT shift:
// the per-day openMs IS the timezone handling, so the test days carry an odd gap on purpose.
const DAYS = [
  { date: '2025-02-03', openMs: Date.UTC(2025, 1, 3, 14, 30) },
  { date: '2025-02-04', openMs: Date.UTC(2025, 1, 4, 13, 30) }
];

function series(base) {
  // day d, window w -> base + d*1000 + w, so every position maps to a unique recognisable price
  return DAYS.map((_, d) => Array.from({ length: WPD }, (_, w) => base + d * 1000 + w));
}

function freshModule({ extract, epochStartMs } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'taq-replay-'));
  const file = path.join(dir, 'extract.json.gz');
  if (extract) {
    fs.writeFileSync(file, zlib.gzipSync(JSON.stringify(extract)));
  }
  process.env.TAQ_REPLAY_EXTRACT_PATH = file;
  if (epochStartMs === undefined) {
    delete process.env.REPLAY_EPOCH_START_MS;
  } else {
    process.env.REPLAY_EPOCH_START_MS = String(epochStartMs);
  }
  delete require.cache[require.resolve('../src/taq-replay')];
  const mod = require('../src/taq-replay');
  mod.load();
  return mod;
}

function validExtract() {
  return {
    version: 1,
    source: 'taq-replay-2025-02',
    windowSeconds: W,
    sessionSeconds: SESSION,
    compression: C,
    days: DAYS,
    prices: { AAPL: series(200), SPY: series(500) }
  };
}

test('no extract file: loud error, every ticker walks', () => {
  const mod = freshModule({ epochStartMs: EPOCH });
  assert.equal(mod.state.extract, null);
  assert.match(mod.state.error, /no extract at/);
  assert.equal(mod.priceAt('AAPL', EPOCH), null);
  assert.equal(mod.status(EPOCH).error, mod.state.error);
});

test('no epoch stamp: loud error naming the ConfigMap', () => {
  const mod = freshModule({ extract: validExtract() });
  assert.equal(mod.state.extract, null);
  assert.match(mod.state.error, /replay-epoch ConfigMap/);
});

test('the clock is pure arithmetic on (now - epoch) * compression', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  // At the epoch instant: day 0, window 0, asOf = end of the first window.
  let q = mod.priceAt('AAPL', EPOCH);
  assert.equal(q.price, 200);
  assert.equal(q.held, false);
  assert.equal(q.asOf, new Date(DAYS[0].openMs + W * 1000).toISOString());
  assert.equal(q.source, 'taq-replay-2025-02');
  // One window advances every W/C wall seconds.
  q = mod.priceAt('AAPL', EPOCH + (W / C) * 1000);
  assert.equal(q.price, 200 + 1);
  // Last window of day 0: one wall-clock "day" is SESSION/C seconds.
  q = mod.priceAt('AAPL', EPOCH + (SESSION / C) * 1000 - 1);
  assert.equal(q.price, 200 + WPD - 1);
  assert.equal(q.asOf, new Date(DAYS[0].openMs + SESSION * 1000).toISOString()); // day-0 close
  // First window of day 1 — the overnight gap is one tick wide, and asOf jumps with it.
  q = mod.priceAt('AAPL', EPOCH + (SESSION / C) * 1000);
  assert.equal(q.price, 200 + 1000);
  assert.equal(q.asOf, new Date(DAYS[1].openMs + W * 1000).toISOString());
  // A restart is invisible: same inputs, same answer (there is no cursor to lose).
  const again = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  assert.deepEqual(again.priceAt('AAPL', EPOCH + 12345678), mod.priceAt('AAPL', EPOCH + 12345678));
});

test('end of tape: hold at the last close, never loop, asOf frozen', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const lastClose = new Date(DAYS[1].openMs + SESSION * 1000).toISOString();
  const justPast = EPOCH + (2 * SESSION / C) * 1000;
  for (const nowMs of [justPast, justPast + 3_600_000, justPast + 86_400_000]) {
    const q = mod.priceAt('AAPL', nowMs);
    assert.equal(q.price, 200 + 1000 + WPD - 1, 'held price is the last window of the last day');
    assert.equal(q.asOf, lastClose, 'asOf stops advancing — it is the honest witness of the hold');
    assert.equal(q.held, true);
  }
  assert.equal(mod.status(justPast).position.held, true);
});

test('a clock skewed before the epoch clamps to the first window rather than indexing at -1', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  assert.equal(mod.priceAt('AAPL', EPOCH - 5000).price, 200);
});

test('a ticker the extract does not carry falls through to the walk', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  assert.equal(mod.priceAt('GOOGL', EPOCH), null);
  assert.equal(mod.priceAt('FNMA', EPOCH), null);
});

test('all-or-nothing: one malformed symbol refuses the whole extract', () => {
  const bad = validExtract();
  bad.prices.SPY[1][7] = null; // one hole
  const mod = freshModule({ extract: bad, epochStartMs: EPOCH });
  assert.equal(mod.state.extract, null);
  assert.match(mod.state.error, /SPY has a malformed day/);
  assert.equal(mod.priceAt('AAPL', EPOCH), null, 'the intact symbol must NOT replay either');
});

test('a day-count mismatch refuses the extract and names the symbol', () => {
  const bad = validExtract();
  bad.prices.AAPL = bad.prices.AAPL.slice(0, 1);
  const mod = freshModule({ extract: bad, epochStartMs: EPOCH });
  assert.match(mod.state.error, /AAPL carries 1 day/);
});
