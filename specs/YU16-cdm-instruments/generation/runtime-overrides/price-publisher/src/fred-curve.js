// YU17 (ADR-068): the real U.S. Treasury constant-maturity curve, held in memory and nowhere else.
//
// WHAT THIS REPLACES, AND WHAT IT DOES NOT. treasury-pricing.js already prices a bond properly —
// real coupon schedule, named day count, safeguarded yield solve. What was synthetic was never the
// pricing, it was the CURVE: updateTreasuryCleanPrice random-walks the clean price so the curve
// appears to move. This module supplies the yield that the EXISTING model prices against, and does
// no bond math of its own. cleanPriceFromYield is the exact inverse of the ytmPercent solve the
// publisher already puts on the wire, so a real yield in comes back out as the published yield.
//
// RULE 1 (ADR-068): synthetic stays the default and stays sufficient. No key, no network and no
// account must behave exactly as before — so every failure here returns null and the caller walks.
// Nothing in this file throws into the publish loop and nothing here is awaited by start-up.
//
// RULE 2 (ADR-068): external data must never become durable. It lives in the Map below and on the
// wire. There is deliberately no cache file, no snapshot write and no seed update: a price that
// escaped into a fixture cannot be un-committed by deleting this file later.
//
// THE POLLING CADENCE IS NOT A PROBLEM TO SOLVE. Constant-maturity yields are published ONCE A DAY.
// Polling faster buys nothing and spends someone else's rate limit; the default below is minutes.

// api.stlouisfed.org — the mark does not appear in any hostname of ours, which the terms prohibit.
const HOST = 'https://api.stlouisfed.org';

// H.15 Treasury constant maturities, Board of Governors of the Federal Reserve System. Chosen
// because they are US-government-sourced — but that belief is not what the code relies on: every
// series is put through the copyright gate below before a single observation is read.
const CMT_SERIES = Object.freeze([
  [1 / 12, 'DGS1MO'], [0.25, 'DGS3MO'], [0.5, 'DGS6MO'],
  [1, 'DGS1'], [2, 'DGS2'], [3, 'DGS3'], [5, 'DGS5'], [7, 'DGS7'],
  [10, 'DGS10'], [20, 'DGS20'], [30, 'DGS30']
]);

// The key is a HUMAN step and is never committed: absent means synthetic, which is a clean
// fall-through and not an error.
const API_KEY = process.env.FRED_API_KEY || '';
const POLL_MINUTES = Math.max(1, Number(process.env.FRED_POLL_MINUTES || '15') || 15);
const FETCH_TIMEOUT_MS = 8000;

const state = {
  // tenorYears -> { yieldPercent, date }. Last good reading, held across a failed poll.
  points: new Map(),
  // series id -> 'ok' | 'copyrighted' | 'unchecked'. The record of what was checked, readable at
  // /health rather than living only in someone's memory of having looked.
  licence: new Map(),
  asOf: null,
  lastPollAt: null,
  lastError: null,
  timer: null
};

async function getJson(path) {
  const res = await fetch(`${HOST}${path}`, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  if (!res.ok) {
    throw new Error(`${path.split('?')[0]} -> HTTP ${res.status}`);
  }
  return res.json();
}

/**
 * OBLIGATION 2 OF THE FRED TERMS, enforced rather than remembered: "Data series available through
 * the FRED API may be owned by third parties and subject to copyright restrictions... before using
 * data series owned by third parties for anything other than your own personal use, you must
 * contact the data owner." Copyright-marked series carry the word `Copyright` in their notes.
 *
 * So the notes are read before the data is, and a marked series is refused permanently. It is
 * fail-CLOSED on purpose: an unreadable note is not a clean series, and the cost of being wrong in
 * the permissive direction is a licensing breach rather than a bug.
 *
 * This is the concrete reason corporates are not here. Every free credit-spread series on FRED is
 * third-party (ICE BofA, Moody's) and marked; this gate would refuse them, which is the right
 * answer arrived at by the right route.
 */
async function licenceAllows(seriesId) {
  const cached = state.licence.get(seriesId);
  if (cached) {
    return cached === 'ok';
  }
  const body = await getJson(`/fred/series?series_id=${seriesId}&api_key=${API_KEY}&file_type=json`);
  const notes = String((body.seriess && body.seriess[0] && body.seriess[0].notes) || '');
  const marked = /copyright/i.test(notes);
  state.licence.set(seriesId, marked ? 'copyrighted' : 'ok');
  if (marked) {
    console.warn(`[fred] refusing ${seriesId}: its notes are copyright-marked (ADR-068 obligation 2)`);
  }
  return !marked;
}

/**
 * The latest real observation. FRED writes a missing value as "." — weekends, holidays and the
 * pre-release gap all look like that — so ask for a short tail and take the first number rather
 * than reading a "." as a zero yield, which would price every bond at par and look plausible.
 */
async function latestObservation(seriesId) {
  const body = await getJson(
    `/fred/series/observations?series_id=${seriesId}&api_key=${API_KEY}&file_type=json`
    + '&sort_order=desc&limit=10');
  for (const row of body.observations || []) {
    const value = Number(row.value);
    if (Number.isFinite(value)) {
      return { yieldPercent: value, date: row.date };
    }
  }
  return null;
}

/**
 * One poll. Partial success is kept: a series that failed leaves its previous point in place, so a
 * flaky call degrades the curve's resolution rather than dropping the whole thing back to
 * synthetic. That is the deliberate answer to ADR-068's open question 3 — a book anchored on a real
 * price is not handed a synthetic one on a transient failure, because a reference discontinuity is
 * worse than a stale-by-one-day daily series.
 */
async function refresh() {
  let asOf = state.asOf;
  let failures = 0;
  for (const [years, seriesId] of CMT_SERIES) {
    try {
      if (!(await licenceAllows(seriesId))) {
        continue;
      }
      const observation = await latestObservation(seriesId);
      if (observation) {
        state.points.set(years, observation);
        if (!asOf || observation.date > asOf) {
          asOf = observation.date;
        }
      }
    } catch (err) {
      failures += 1;
      state.lastError = `${seriesId}: ${err.message}`;
    }
  }
  state.asOf = asOf;
  state.lastPollAt = new Date().toISOString();
  if (failures === 0) {
    state.lastError = null;
  }
  console.log(`[fred] curve ${state.points.size}/${CMT_SERIES.length} points, as of ${state.asOf || 'n/a'}`
    + (failures ? `, ${failures} series failed` : ''));
}

/**
 * The curve yield at an arbitrary tenor: linear between the bracketing constant maturities, flat
 * outside them. Linear-in-yield is the reference-curve convention and it is honest about what this
 * is — eleven quoted points, not a bootstrapped zero curve. Anything fancier would be a modelling
 * opinion sitting on top of eleven numbers.
 *
 * Returns null whenever there is no curve at all, which is the whole synthetic fall-through.
 */
function yieldForYears(years) {
  if (state.points.size === 0 || !Number.isFinite(years)) {
    return null;
  }
  const tenors = [...state.points.keys()].sort((a, b) => a - b);
  if (years <= tenors[0]) {
    return state.points.get(tenors[0]).yieldPercent;
  }
  if (years >= tenors[tenors.length - 1]) {
    return state.points.get(tenors[tenors.length - 1]).yieldPercent;
  }
  const upperIndex = tenors.findIndex((t) => t >= years);
  const lo = tenors[upperIndex - 1];
  const hi = tenors[upperIndex];
  const wy = (years - lo) / (hi - lo);
  return state.points.get(lo).yieldPercent * (1 - wy) + state.points.get(hi).yieldPercent * wy;
}

/** Reported on /health so "is this real?" is answerable without reading logs or this file. */
function status() {
  return {
    provider: API_KEY ? 'FRED' : 'none',
    enabled: Boolean(API_KEY),
    pollMinutes: POLL_MINUTES,
    points: state.points.size,
    asOf: state.asOf,
    lastPollAt: state.lastPollAt,
    lastError: state.lastError,
    // What was checked, per series — obligation 2 asks for the check to be recorded, not just done.
    licenceCheck: Object.fromEntries(state.licence),
    attribution: 'This product uses the FRED® API but is not endorsed or certified by the '
      + 'Federal Reserve Bank of St. Louis.',
    termsOfUse: 'https://fred.stlouisfed.org/docs/api/terms_of_use.html'
  };
}

/** Fire-and-forget: no key is silence, and a failing first poll leaves the walk running. */
function start() {
  if (!API_KEY) {
    console.log('[fred] no FRED_API_KEY — Treasury prices stay synthetic (ADR-068 rule 1)');
    return;
  }
  console.log(`[fred] polling the constant-maturity curve every ${POLL_MINUTES}m`);
  const tick = () => refresh().catch((err) => { state.lastError = String(err.message || err); });
  tick();
  state.timer = setInterval(tick, POLL_MINUTES * 60000);
  state.timer.unref?.();
}

module.exports = { CMT_SERIES, start, refresh, yieldForYears, status, state };
