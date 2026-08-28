// YU17 (ADR-070): the tape is the reference, replayed on an epoch clock.
//
// The publisher replays a RESAMPLED extract of the licensed TAQ corpus (median trade price per
// window, computed offline — scripts/yu17/build-taq-replay-extract.py) for the equity/ETF names
// the extract carries. Everything else about this module is the ADR's two decisions made code:
//
//   STATELESS CLOCK (decision 2). Position is DERIVED, never stored:
//       tape_trading_seconds = (now - epoch_start) / 1000 * compression
//   A publisher restart resumes in the right place with no coordination, and two publishers
//   reading the same epoch stamp agree without talking. There is no cursor anywhere. Protect
//   this property before any other if you change this file.
//
//   HOLD AT END OF TAPE (open question 2, ruled 2026-08-26). Past the last window the price
//   FREEZES at the last day's close and `asOf` stops advancing — a real price with an honestly
//   ageing timestamp. Never loop (the Mar 31 -> Feb 3 seam is a fabricated overnight gap, the
//   exact thing this ADR exists to stop fabricating) and never fall back to synthetic (a silent
//   provenance-category change mid-run, the exact thing decision 4 exists to prevent).
//
// The clock maps TRADING seconds only: 40 sessions of 09:30-16:00 ET, concatenated. The overnight
// and weekend gaps appear as a real discontinuity between two consecutive ticks — compression
// shortens a gap's duration to zero but preserves the discontinuity, which is the half ADR-069's
// argument needs. `asOf` carries the true tape timestamp (per-day openMs is stamped in UTC by the
// extract builder, so the Feb EST -> Mar EDT shift lives in the data, not in timezone code here).
//
// FAILURE CONTRACT — same as previous-close.js, for the same reason: a publisher that quietly
// fell back to the walk looks exactly like one that never had a tape. Every path that ends with
// the replay off records a sentence in `error`, reported on /health. ADR-068 rule 1 holds: no
// extract, no epoch stamp, no valid file — the walk continues and the pod starts exactly as
// before.
const fs = require('fs');
const zlib = require('zlib');

// The Secret mount (eod-chain.yaml), fetched from the bucket at bring-up (ADR-070: the extract
// lives in gs://traderx-501015-tick-store, never in the repo — ADR-068's durability rule intact).
const EXTRACT_PATH = process.env.TAQ_REPLAY_EXTRACT_PATH || '/etc/taq-replay/extract.json.gz';

const state = {
  attempted: false,
  extractPath: EXTRACT_PATH,
  // The fresh-epoch mint instant, stamped into the replay-epoch ConfigMap by the bring-up /
  // rebuild_fresh_epoch (derived from the member-0 PVC's creationTimestamp, which IS the mint).
  epochStartMs: NaN,
  extract: null,
  // Wall instant the clock was frozen at, or null while running. ADR-073's sandbox needs
  // pause/resume/seek, and this is the ONLY way to offer them without a second clock: freeze and
  // seek both move THIS origin, so positionAt keeps deriving from one field and there is still
  // exactly one derivation of where the tape is. Never used on the live publisher, which never
  // pauses -- it stays null there and every expression below reduces to what it was.
  frozenAtMs: null,
  // A sentence, never a boolean. Null ONLY while a loaded extract is actually replaying.
  error: null
};

function fail(sentence) {
  state.extract = null;
  state.error = sentence;
  console.warn(`[taq-replay] ${sentence}; equities stay on the synthetic walk (ADR-068 rule 1)`);
  return null;
}

/** Load and validate the extract, or record why not. Sync and called once at startup: the file is
 *  a local Secret mount of a few hundred KB, not a network read. */
function load() {
  state.attempted = true;
  state.epochStartMs = Number(process.env.REPLAY_EPOCH_START_MS || NaN);
  if (!fs.existsSync(EXTRACT_PATH)) {
    return fail(`no extract at ${EXTRACT_PATH}`);
  }
  if (!Number.isFinite(state.epochStartMs) || state.epochStartMs <= 0) {
    return fail('REPLAY_EPOCH_START_MS is unset or unreadable — the replay-epoch ConfigMap was '
      + 'never stamped for this epoch');
  }
  let extract;
  try {
    extract = JSON.parse(zlib.gunzipSync(fs.readFileSync(EXTRACT_PATH)).toString('utf8'));
  } catch (err) {
    return fail(`${EXTRACT_PATH} did not gunzip+parse: ${String((err && err.message) || err)}`);
  }
  // All-or-nothing: a half-valid extract replayed for some symbols and walked for others would be
  // a provenance mess nobody could reason about after the fact. Validation refusing here leaves
  // EVERY equity on the walk, with the reason on /health.
  const windowsPerDay = Number(extract.sessionSeconds) / Number(extract.windowSeconds);
  if (extract.version !== 1 || !extract.source
      || !Number.isInteger(windowsPerDay) || windowsPerDay <= 0
      || !Number.isFinite(extract.compression) || extract.compression <= 0
      || !Array.isArray(extract.days) || extract.days.length === 0
      || !extract.prices || typeof extract.prices !== 'object') {
    return fail(`${EXTRACT_PATH} is not a v1 extract (version/source/window/compression/days/prices)`);
  }
  for (const day of extract.days) {
    if (!day.date || !Number.isFinite(day.openMs) || day.openMs <= 0) {
      return fail(`extract day entry unreadable: ${JSON.stringify(day)}`);
    }
  }
  for (const [ticker, series] of Object.entries(extract.prices)) {
    if (!Array.isArray(series) || series.length !== extract.days.length) {
      return fail(`${ticker} carries ${series && series.length} day(s), extract has ${extract.days.length}`);
    }
    for (const day of series) {
      if (!Array.isArray(day) || day.length !== windowsPerDay
          || day.some((px) => !Number.isFinite(px) || px <= 0)) {
        return fail(`${ticker} has a malformed day (want ${windowsPerDay} finite positive prices per day; `
          + 'the builder forward-fills, so a hole means a truncated or hand-edited extract)');
      }
    }
  }
  state.extract = extract;
  state.error = null;
  console.log(`[taq-replay] replaying ${Object.keys(extract.prices).length} symbols, `
    + `${extract.days.length} days, window ${extract.windowSeconds}s, compression ${extract.compression}x, `
    + `epoch ${new Date(state.epochStartMs).toISOString()}`);
  return extract;
}

/** The clock. Derived every call, stored nowhere. */
function positionAt(nowMs) {
  const ex = state.extract;
  if (!ex) {
    return null;
  }
  const windowsPerDay = ex.sessionSeconds / ex.windowSeconds;
  // While frozen the clock reads the instant it was frozen at. Still one derivation.
  const atMs = state.frozenAtMs === null ? nowMs : state.frozenAtMs;
  const tapeSeconds = Math.max(0, (atMs - state.epochStartMs) / 1000) * ex.compression;
  let dayIndex = Math.floor(tapeSeconds / ex.sessionSeconds);
  let windowIndex = Math.floor((tapeSeconds % ex.sessionSeconds) / ex.windowSeconds);
  const held = dayIndex >= ex.days.length;
  if (held) {
    dayIndex = ex.days.length - 1;
    windowIndex = windowsPerDay - 1;
  }
  // The clamps above cover a clock past the end of the tape and one before its start. They do NOT
  // cover a dayIndex that is not a number at all: NaN fails `>=` silently, so it walks past the
  // `held` branch and indexes days[NaN], and the next line throws TypeError on `.openMs`. Observed
  // once on a sandbox pod's first status call after start (2026-08-28) -- a 500 from /health, which
  // is the surface whose whole job is to say what state the tape is in. Refuse instead: a caller
  // that gets null reports "position unknown", which is true, where a throw reports nothing.
  if (!Number.isInteger(dayIndex) || dayIndex < 0 || dayIndex >= ex.days.length) {
    return null;
  }
  // A window's median is the price AS OF the window's end; the last window's end is the close.
  const asOfMs = ex.days[dayIndex].openMs + (windowIndex + 1) * ex.windowSeconds * 1000;
  // `tapeSeconds` is the RAW, unclamped position — continuous, sub-window, and monotone in wall
  // clock even past the end of the tape. print-replay.js (ADR-072) schedules replayed orders off
  // it, and it must be THIS number: a second derivation of the clock is a second clock, and the
  // whole ADR-070 property is that there is only one. Everything above stays clamped.
  return { dayIndex, windowIndex, held, tapeDate: ex.days[dayIndex].date, asOfMs, tapeSeconds };
}

/** The replayed reference for one ticker, or null (not loaded / not a tape symbol — the caller
 *  falls through to the walk, which is exactly right for GOOGL and FNMA: see the ADR's account of
 *  the suffix-merged roots and the OTC names TAQ does not carry). */
function priceAt(ticker, nowMs) {
  const series = state.extract && state.extract.prices[ticker];
  if (!series) {
    return null;
  }
  const pos = positionAt(nowMs);
  // Same contract as an unknown ticker: no addressable position means no replayed reference, and
  // the caller falls through to the walk.
  if (!pos) {
    return null;
  }
  return {
    price: series[pos.dayIndex][pos.windowIndex],
    source: state.extract.source,
    asOf: new Date(pos.asOfMs).toISOString(),
    held: pos.held
  };
}

/** The /health block. Position is computed at request time — reporting a stored one would be a
 *  second clock that could disagree with the one priceAt uses. */
function status(nowMs) {
  const base = {
    attempted: state.attempted,
    extractPath: state.extractPath,
    error: state.error
  };
  if (!state.extract) {
    return base;
  }
  const pos = positionAt(nowMs);
  if (!pos) {
    // Loaded, but the clock does not address a day in it. Say so rather than 500 -- /health is the
    // surface whose job is to report what state the tape is in.
    return { ...base, source: state.extract.source, position: null,
      error: state.error || 'the tape is loaded but the clock does not address a day in it' };
  }
  return {
    ...base,
    source: state.extract.source,
    symbols: Object.keys(state.extract.prices).length,
    days: state.extract.days.length,
    windowSeconds: state.extract.windowSeconds,
    compression: state.extract.compression,
    epochStartMs: state.epochStartMs,
    paused: state.frozenAtMs !== null,
    position: {
      tapeDate: pos.tapeDate,
      dayIndex: pos.dayIndex,
      windowIndex: pos.windowIndex,
      asOf: new Date(pos.asOfMs).toISOString(),
      held: pos.held
    }
  };
}

// ---- ADR-073 sandbox controls -----------------------------------------------------------------
// Each of these MOVES THE ORIGIN. None of them computes a position; positionAt stays the only
// place that does, which is what keeps "one clock" true while the tape gains a transport.

/** Freeze the tape where it is. Idempotent. */
function pause(nowMs) {
  if (state.frozenAtMs === null) { state.frozenAtMs = nowMs; }
  return state.frozenAtMs;
}

/** Resume from where it was frozen, by shifting the origin forward over the paused span. */
function resume(nowMs) {
  if (state.frozenAtMs !== null) {
    state.epochStartMs += (nowMs - state.frozenAtMs);
    state.frozenAtMs = null;
  }
  return state.epochStartMs;
}

/**
 * Put the tape at `seconds` of tape time. Keeps the paused/running state it was in: seeking while
 * paused lands paused at the new point, which is what a scrub bar has to do.
 */
function seekToTapeSeconds(seconds, nowMs) {
  const ex = state.extract;
  if (!ex || !Number.isFinite(seconds) || seconds < 0) { return false; }
  const ref = state.frozenAtMs === null ? nowMs : state.frozenAtMs;
  state.epochStartMs = ref - (seconds / ex.compression) * 1000;
  return true;
}

/** Seek to the open of a tape day, by index or by its `YYYY-MM-DD` date. */
function seekToDay(dayRef, nowMs) {
  const ex = state.extract;
  if (!ex) { return false; }
  const idx = typeof dayRef === 'number'
    ? dayRef
    : ex.days.findIndex((d) => d.date === String(dayRef));
  if (!(idx >= 0 && idx < ex.days.length)) { return false; }
  return seekToTapeSeconds(idx * ex.sessionSeconds, nowMs);
}

module.exports = { load, priceAt, positionAt, status, state,
  pause, resume, seekToTapeSeconds, seekToDay };
