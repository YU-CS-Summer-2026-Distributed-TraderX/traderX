// YU17 (ADR-069 rules 1-4): the session opens where the last one closed.
//
// WHAT THIS ADDS, AND WHAT IT DOES NOT. It adds ONE RUNG to the ADR-068 hierarchy at bootstrap:
//
//     external source (FRED, for rates)  >  prior published close  >  static seed
//
// The static seed stays the floor, so ADR-068 rule 1 survives intact: a rig with no database and
// no network still opens. Nothing here is awaited by anything that can fail the process, nothing
// here throws into the caller, and every failure resolves to null, which means "walk from the
// seed exactly as before".
//
// For Treasuries the ordering is deliberate rather than incidental: they seed from the prior
// close and are then SUPERSEDED by the FRED poll within one interval (fred-curve.js, and the
// curve branch in main.js's updateTick). A close is the best answer available until a live curve
// arrives; a live curve is better than any close.
//
// RULE 3: OVER HTTP, NOT OUT OF THE DATABASE. price-publisher owns no persistence and does not
// acquire a schema dependency to gain a bootstrap. Rule 2 -- which version of which session is
// "the previous close" -- is resolved SERVER-SIDE by trade-processor, where the version and
// DRAFT/PUBLISHED semantics already live. This file must not re-implement them, and the one
// assertion it does make about them (the PUBLISHED check below) is a refusal, not a second
// implementation.
//
// THE TRAP THIS FILE IS BUILT AGAINST, quoting ADR-069 because it is the design instruction:
//
//     "A failed close-read is indistinguishable from a successful one. If the HTTP call fails,
//      times out, or resolves to an empty session, the publisher falls back to the seed and every
//      price is still completely plausible. There is no wrong number to notice, no error a human
//      sees, and the feature is silently absent -- for weeks, if nobody thinks to check."
//
// So the corollary is the contract of `status()`: THE ABSENCE OF A CONTINUITY SIGNAL MUST BE
// LOUD. Every path that ends at the seed sets `error` to a sentence naming which path it was, and
// `attempted` distinguishes "the read ran and found nothing" from "the read never ran at all".
// There is no way to reach the seed quietly.

const treasury = require('./treasury-pricing');

// In-cluster by default. The publisher and trade-processor are both in the traderx namespace.
const TRADE_PROCESSOR_URL = (process.env.TRADE_PROCESSOR_URL || 'http://trade-processor:18091')
  .replace(/\/+$/, '');

// ponytail: the dev default, matching AuthController's own and the eod-session-close CronJob's.
// The rig manifest wires this to the auth-secrets secret; for anything real, rotate it there.
const MASTER_SECRET = process.env.AUTH_DEV_TOKEN_MASTER_SECRET || 'dev-token-master-secret';

// Short. This runs on the start-up path, and rule 1 says a bootstrap must not become a liveness
// risk -- a trade-processor that is down has to cost seconds, not a start-up.
const TIMEOUT_MS = Math.max(500, Number(process.env.PRICE_OPEN_READ_TIMEOUT_MS || '5000') || 5000);

// The off switch is an EXPLICIT '0', not a truthiness test: an unset variable means on, which is
// what makes the continuity the default rather than an opt-in nobody remembers to set.
const ENABLED = String(process.env.PRICE_OPEN_FROM_PREVIOUS_CLOSE ?? '1') !== '0';

const state = {
  // Did the read run at all? A `false` here with prices on the wire means a code path skipped it,
  // which is a different defect from a read that ran and came back empty.
  attempted: false,
  enabled: ENABLED,
  endpoint: TRADE_PROCESSOR_URL,
  // { sessionDate, version, status, instrumentCount } of the session that won, or null.
  session: null,
  // security -> closing price, as published on the wire. Fraction of par for bonds (ADR-057),
  // currency for everything else -- this module does no unit conversion, because the unit is a
  // property of the instrument class and main.js is what knows the class.
  closes: new Map(),
  // A sentence, never a boolean. Null ONLY when a session was actually resolved.
  error: null
};

async function mintAdminToken() {
  const res = await fetch(`${TRADE_PROCESSOR_URL}/auth/dev-token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Auth-Master-Secret': MASTER_SECRET },
    body: JSON.stringify({ subject: 'price-publisher', accounts: [], admin: true, ttlSeconds: 120 }),
    signal: AbortSignal.timeout(TIMEOUT_MS)
  });
  if (!res.ok) {
    throw new Error(`/auth/dev-token -> HTTP ${res.status}`);
  }
  // The endpoint returns the bare token as text/plain, not JSON.
  const token = (await res.text()).trim();
  if (!token) {
    throw new Error('/auth/dev-token returned an empty token');
  }
  return token;
}

/**
 * Read the session the open should come from. `openingDate` is an ISO date; the server resolves
 * rule 2 strictly before it.
 *
 * Returns the closes Map on success and null on every other outcome, having recorded WHY in
 * `state.error`. It never throws: the caller is bootstrapPrices, and a bootstrap that can fail on
 * a network read is the liveness risk rule 1 exists to prevent.
 */
async function load(openingDate) {
  state.attempted = true;
  state.session = null;
  state.closes.clear();
  if (!ENABLED) {
    state.error = 'disabled: PRICE_OPEN_FROM_PREVIOUS_CLOSE=0';
    return null;
  }
  try {
    const token = await mintAdminToken();
    const res = await fetch(
      `${TRADE_PROCESSOR_URL}/eod/session/previous?before=${encodeURIComponent(openingDate)}`,
      { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (res.status === 404) {
      // Not an error condition -- it is the first session this database has ever seen. Still
      // recorded as a sentence, because "no close exists" and "the read failed" produce identical
      // prices and must not produce identical /health.
      state.error = `no published session strictly before ${openingDate}`;
      return null;
    }
    if (!res.ok) {
      throw new Error(`/eod/session/previous -> HTTP ${res.status}`);
    }
    const body = await res.json();
    // DEFENCE IN DEPTH, not a second implementation of rule 2. The server decides which session;
    // this refuses to open from anything that is not PUBLISHED even if it is handed one, because
    // a DRAFT's marks are known-bad and a hierarchy that quietly accepted one would look
    // identical, price for price, to one that did not.
    if (body.status !== 'PUBLISHED') {
      state.error = `refusing ${body.sessionDate} v${body.version}: status is ${body.status}, not PUBLISHED`;
      return null;
    }
    for (const row of body.instruments || []) {
      const price = Number(row.closingPrice);
      // A MISSING instrument has a NULL closing price, and `Number(null)` is 0, not NaN -- so a
      // finiteness test alone would open that instrument at zero and call it a close. `> 0` is
      // the guard, and it is the whole reason this is not written as Number.isFinite. Skipping
      // leaves the instrument on its seed, which is the right answer for one with no close, and
      // /health counts it as a seed open rather than absorbing it silently.
      if (row.security && Number.isFinite(price) && price > 0) {
        state.closes.set(String(row.security).toUpperCase(), price);
      }
    }
    if (state.closes.size === 0) {
      state.error = `${body.sessionDate} v${body.version} is PUBLISHED but carries no usable close`;
      return null;
    }
    state.session = {
      sessionDate: body.sessionDate,
      version: body.version,
      status: body.status,
      instrumentCount: body.instrumentCount
    };
    state.error = null;
    console.log(`[open] session opens from ${body.sessionDate} v${body.version} (PUBLISHED): `
      + `${state.closes.size} closes`);
    return state.closes;
  } catch (err) {
    // Node's fetch rejects with a bare "fetch failed" and hides the real reason on `cause` --
    // measured off-rig 2026-08-25, where an unreachable trade-processor and a DNS failure and a
    // timeout all produced that same two-word sentence. This field is the ONLY place a seed open
    // gets attributed, so it names the endpoint and the underlying code or the attribution is
    // worthless.
    const code = err && err.cause && err.cause.code ? ` (${err.cause.code})` : '';
    state.error = `${TRADE_PROCESSOR_URL}: ${String((err && err.message) || err)}${code}`;
    console.warn(`[open] no previous close (${state.error}); opening from the static seed `
      + '(ADR-068 rule 1)');
    return null;
  }
}

/**
 * YU17 (ADR-069 rule 1) for a BOND. The close arrives as the FRACTION of par the wire carries
 * (ADR-057); the walk lives in percent-of-par, so it is the walk's state that has to move.
 *
 * `seedCleanPercent` MOVES WITH IT, and that is the load-bearing line. treasury-pricing's
 * updateTreasuryCleanPrice mean-reverts toward seedCleanPercent and HARD CLAMPS at
 * seedCleanPercent +/- maxDistance. Open at the close while leaving the anchor on the
 * 2026-vintage auction seed and the walk drags the price straight back to that seed -- continuity
 * that is real for one instant and then quietly gone, which is this ADR's trap wearing a
 * different hat. `officialSeedCleanPrice` is a STATIC FACT about the instrument (its auction
 * price) and is deliberately NOT moved.
 *
 * Lives here rather than in main.js so it has a runnable check: main.js pulls in express, nats and
 * yahoo-finance2 at require time, and the regression this guards against is silent.
 *
 * Returns null when there is no usable close, which the caller reads as "keep the seed".
 */
function openBondFromClose(quote, close) {
  if (!Number.isFinite(close) || close <= 0) {
    return null;
  }
  const cleanPercent = treasury.round3(close * 100);
  const fraction = treasury.pctToFraction(cleanPercent);
  return {
    ...quote,
    cleanPercent,
    seedCleanPercent: cleanPercent,
    openPrice: fraction,
    closePrice: fraction,
    price: fraction
  };
}

/** The read half of /health's `openingSource`. main.js adds the per-class tally (rule 4). */
function status() {
  return {
    enabled: state.enabled,
    attempted: state.attempted,
    endpoint: state.endpoint,
    previousSession: state.session,
    error: state.error
  };
}

module.exports = { load, openBondFromClose, status, state };
