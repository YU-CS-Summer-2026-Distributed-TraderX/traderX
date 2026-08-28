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

// ---- ADR-073: the sandbox transport ----------------------------------------------------------
// The property under test is not "pause works" — it is that pause, resume and seek are the SAME
// clock moved, not a second one. Every assertion below reads position through positionAt, because
// a transport that maintained its own idea of where the tape is would pass a test that asked it
// where it thinks it is, and diverge from the reference series the engine actually prices against.

test('pause freezes the tape, and time passing does not move it', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const before = mod.positionAt(EPOCH + 60_000);
  mod.pause(EPOCH + 60_000);
  const later = mod.positionAt(EPOCH + 600_000);   // ten minutes of wall clock later
  assert.equal(later.tapeSeconds, before.tapeSeconds);
  assert.equal(later.windowIndex, before.windowIndex);
  assert.equal(mod.status(EPOCH + 600_000).paused, true);
});

test('resume continues from where it paused, not from where the wall clock got to', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const at = mod.positionAt(EPOCH + 60_000);
  mod.pause(EPOCH + 60_000);
  mod.resume(EPOCH + 600_000);                     // paused for nine minutes
  // Immediately after resume the tape is exactly where it was frozen...
  assert.equal(mod.positionAt(EPOCH + 600_000).tapeSeconds, at.tapeSeconds);
  // ...and one further wall second advances it by exactly `compression` tape seconds.
  assert.equal(mod.positionAt(EPOCH + 601_000).tapeSeconds, at.tapeSeconds + C);
  assert.equal(mod.status(EPOCH + 600_000).paused, false);
});

test('pause is idempotent and resume without a pause is a no-op', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  mod.pause(EPOCH + 10_000);
  mod.pause(EPOCH + 90_000);                       // must not re-freeze at the later instant
  assert.equal(mod.positionAt(EPOCH + 900_000).tapeSeconds, 10 * C);
  const origin = mod.state.epochStartMs;
  mod.resume(EPOCH + 900_000);
  mod.resume(EPOCH + 900_000);                     // second resume must not shift the origin again
  assert.equal(mod.state.epochStartMs, origin + 890_000);
});

test('seek by date lands on that day open, and by index agrees with it', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  assert.equal(mod.seekToDay('2025-02-04', EPOCH), true);
  const byDate = mod.positionAt(EPOCH);
  assert.equal(byDate.tapeDate, '2025-02-04');
  assert.equal(byDate.dayIndex, 1);
  assert.equal(byDate.windowIndex, 0);
  const mod2 = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  mod2.seekToDay(1, EPOCH);
  assert.equal(mod2.positionAt(EPOCH).tapeSeconds, byDate.tapeSeconds);
});

test('seeking while paused stays paused at the new point — what a scrub bar has to do', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  mod.pause(EPOCH + 5_000);
  assert.equal(mod.seekToDay('2025-02-04', EPOCH + 5_000), true);
  assert.equal(mod.status(EPOCH + 5_000).paused, true);
  const at = mod.positionAt(EPOCH + 5_000);
  assert.equal(at.tapeDate, '2025-02-04');
  // and still frozen a long while later
  assert.equal(mod.positionAt(EPOCH + 900_000).tapeSeconds, at.tapeSeconds);
});

test('an out-of-tape seek is refused rather than clamped silently', () => {
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const before = mod.positionAt(EPOCH).tapeSeconds;
  assert.equal(mod.seekToDay('1999-01-01', EPOCH), false);
  assert.equal(mod.seekToDay(99, EPOCH), false);
  assert.equal(mod.seekToTapeSeconds(-1, EPOCH), false);
  assert.equal(mod.positionAt(EPOCH).tapeSeconds, before);
});

test('the price the engine sees follows the transport, not just the reported position', () => {
  // The whole point of moving the ORIGIN rather than tracking a position separately: priceAt has
  // to land on the seeked window too, because that is the number the book is priced against.
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  mod.seekToDay('2025-02-04', EPOCH);
  assert.equal(mod.priceAt('AAPL', EPOCH).price, 200 + 1000);  // day 1, window 0
});

test('an unaddressable clock is a null position, not a thrown TypeError', () => {
  // The failure this pins actually happened: a sandbox pod threw
  // "Cannot read properties of undefined (reading 'openMs')" out of status() on its first call,
  // which turns /health -- the surface whose job is to say what state the tape is in -- into a 500
  // at exactly the moment someone is asking why the tape is not running.
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  mod.state.epochStartMs = NaN;                    // NaN fails `>=`, so it slips past the held clamp
  assert.equal(mod.positionAt(EPOCH), null);
  const st = mod.status(EPOCH);
  assert.equal(st.position, null);
  assert.match(st.error, /does not address a day/);
});

test('an unaddressable clock makes priceAt fall through to the walk, not throw', () => {
  // The sibling of the test above, and the reason it exists: the first attempt at that fix landed
  // in priceAt by accident, referencing a `base` that does not exist there. `node --check` cannot
  // see a ReferenceError, and no test covered priceAt on this path, so it would have shipped a
  // worse crash than the one being fixed -- on the hot path rather than the health surface.
  const mod = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  mod.state.epochStartMs = NaN;
  assert.equal(mod.priceAt('AAPL', EPOCH), null);
});

// ---- ADR-073 session journal -------------------------------------------------------------------
// The failure this guards is the one that cannot be seen from outside: after a seek, asking the
// LIVE clock where a past instant landed returns a confident wrong answer, because the origin it
// derives from is no longer the origin that instant was played under. Every test below is really
// the same question -- does a trade booked at wall time T get dated to the day that was actually
// on the tape at T.

const DAY_WALL_MS = (SESSION / C) * 1000;   // wall ms to play one whole tape day

test('journal: a running tape attributes a past instant to the day that was playing then', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const inDay0 = EPOCH + DAY_WALL_MS * 0.25;
  const inDay1 = EPOCH + DAY_WALL_MS * 1.25;
  assert.equal(m.tapeAtWall(inDay0, inDay1 + 1000).tapeDate, '2025-02-03');
  assert.equal(m.tapeAtWall(inDay1, inDay1 + 1000).tapeDate, '2025-02-04');
});

test('journal: after a seek, a PAST instant keeps the day it was played under', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const played = EPOCH + DAY_WALL_MS * 0.25;          // played while day 0 was on the tape
  const seekAt = EPOCH + DAY_WALL_MS * 0.5;
  m.seekToDay(1, seekAt);                              // now day 1 is on the tape
  const now = seekAt + 1000;

  // The live clock says day 1 -- correct for NOW, wrong for `played`.
  assert.equal(m.positionAt(now).tapeDate, '2025-02-04');
  // The journal still dates the past instant to the day that was actually playing.
  assert.equal(m.tapeAtWall(played, now).tapeDate, '2025-02-03');
});

test('journal: an instant while PAUSED belongs to no segment', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const pauseAt = EPOCH + DAY_WALL_MS * 0.25;
  m.pause(pauseAt);
  const whilePaused = pauseAt + 60_000;
  assert.equal(m.tapeAtWall(whilePaused, whilePaused + 1000), null);
  // and resuming does not retroactively claim the paused span
  m.resume(whilePaused + 120_000);
  assert.equal(m.tapeAtWall(whilePaused, whilePaused + 200_000), null);
});

test('journal: a pause does not advance coverage, a resume continues it', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const pauseAt = EPOCH + DAY_WALL_MS * 0.25;
  m.pause(pauseAt);
  const paused = m.coverage(pauseAt + 600_000);
  m.resume(pauseAt + 600_000);
  const after = m.coverage(pauseAt + 600_000 + DAY_WALL_MS * 0.25);

  assert.deepEqual(paused.days.map((d) => d.date), ['2025-02-03']);
  // a quarter day played, then 10 wall minutes of pause that must add nothing
  assert.ok(Math.abs(paused.days[0].fraction - 0.25) < 0.01,
    `paused coverage should hold at 0.25, got ${paused.days[0].fraction}`);
  assert.ok(after.days[0].fraction > 0.49 && after.days[0].fraction < 0.51,
    `resumed coverage should reach ~0.5, got ${after.days[0].fraction}`);
});

test('coverage: reports only the days actually played, not the days in the corpus', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const stop = EPOCH + DAY_WALL_MS * 0.5;   // half of day 0 only
  m.pause(stop);
  const cov = m.coverage(stop);
  assert.deepEqual(cov.days.map((d) => d.date), ['2025-02-03'],
    'day 1 exists in the extract but was never played, so it must not appear');
  assert.ok(cov.days[0].fraction > 0.49 && cov.days[0].fraction < 0.51);
});

test('coverage: a day played in two separate visits accumulates once', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const t1 = EPOCH + DAY_WALL_MS * 0.25;
  m.seekToDay(1, t1);                                  // leave day 0 after a quarter
  const t2 = t1 + DAY_WALL_MS * 0.25;
  m.seekToDay(0, t2);                                  // come back to day 0
  const t3 = t2 + DAY_WALL_MS * 0.25;
  m.pause(t3);
  const cov = m.coverage(t3);
  const day0 = cov.days.find((d) => d.date === '2025-02-03');
  assert.equal(cov.days.length, 2);
  assert.ok(Math.abs(day0.fraction - 0.5) < 0.02,
    `two quarter-day visits should total ~0.5, got ${day0.fraction}`);
});

test('journal: the segment cap drops the oldest and says it did', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  for (let i = 0; i < 600; i += 1) {
    m.seekToTapeSeconds((i % 100) * 10, EPOCH + i * 1000);
  }
  assert.ok(m.state.segments.length <= 512, `segments must stay bounded, got ${m.state.segments.length}`);
  assert.equal(m.coverage(EPOCH + 600_000).segmentsDropped, true);
});

test('coverage: dayRanges date an instant the same way tapeAtWall does', () => {
  const m = freshModule({ extract: validExtract(), epochStartMs: EPOCH });
  const t1 = EPOCH + DAY_WALL_MS * 0.25;
  m.seekToDay(1, t1);
  const now = t1 + DAY_WALL_MS * 0.25;
  const { dayRanges } = m.coverage(now);
  const dateByRange = (ms) => {
    const r = dayRanges.find((x) => ms >= x.wallFromMs && (x.wallToMs === null || ms <= x.wallToMs));
    return r ? r.tapeDate : null;
  };
  for (const probe of [EPOCH + 1000, EPOCH + DAY_WALL_MS * 0.2, t1 + 1000, now - 1000]) {
    assert.equal(dateByRange(probe), m.tapeAtWall(probe, now).tapeDate,
      `the interval lookup and the journal must agree at ${probe - EPOCH}ms after epoch`);
  }
});
