// YU16: U.S. Treasury clean-price model. Pure functions, exported for tests.
//
// The walk runs in PERCENT-of-par space (matching the auction quotes and the term profiles);
// everything the publisher emits is a FRACTION of par at six decimals (ADR-057) — the
// conversion is pctToFraction and it is the only place the two spaces meet.

// Longer original term => larger per-step move and wider total band: duration, as a profile.
// The key is a TENOR BUCKET in years, not a day-count term — a 4-week bill buckets at 0.08. A
// term with no bucket throws rather than walking on a borrowed profile (see updateTreasuryClean-
// Price): silently pricing a 30Y off the 2Y band is exactly the kind of plausible-looking wrong
// number this state exists to keep out of the feed.
//
// ponytail: STRIPS reuse the coupon-bond bucket for their tenor, so a 30Y STRIP at ~22% of par
// moves the same ABSOLUTE points as the 30Y bond at ~99 — i.e. ~4.5x the relative move. That is
// directionally right (a zero has the higher duration) and wrong in magnitude. Give zeros their
// own bucket table if anyone ever calibrates this feed against real STRIP vol.
const TREASURY_PROFILE_BY_TERM = Object.freeze({
  0.08: { maxStep: 0.001, maxDistance: 0.02 },
  0.25: { maxStep: 0.002, maxDistance: 0.05 },
  0.5: { maxStep: 0.003, maxDistance: 0.08 },
  1: { maxStep: 0.004, maxDistance: 0.12 },
  2: { maxStep: 0.005, maxDistance: 0.15 },
  3: { maxStep: 0.007, maxDistance: 0.20 },
  5: { maxStep: 0.010, maxDistance: 0.30 },
  7: { maxStep: 0.015, maxDistance: 0.40 },
  10: { maxStep: 0.020, maxDistance: 0.50 },
  20: { maxStep: 0.035, maxDistance: 0.75 },
  30: { maxStep: 0.050, maxDistance: 1.00 }
});

function round3(value) {
  return Math.round((Number(value) + Number.EPSILON) * 1000) / 1000;
}

// Percent-of-par (3dp) -> fraction-of-par (6dp), exact: 99.878 -> 0.998780.
function pctToFraction(pct) {
  return Math.round(Number(pct) * 10000) / 1000000;
}

function clamp(value, low, high) {
  return Math.max(low, Math.min(high, value));
}

// One optional fixed-clock contract across the state (NFR-CDM09). Throws on an invalid instant
// rather than silently walking with a garbage clock.
function now() {
  const fixed = process.env.TRADERX_FIXED_UTC_INSTANT;
  if (fixed === undefined || fixed === '') {
    return Date.now();
  }
  const ts = Date.parse(fixed);
  if (!Number.isFinite(ts)) {
    throw new Error(`TRADERX_FIXED_UTC_INSTANT is not a valid instant: ${fixed}`);
  }
  return ts;
}

// UTC midnight boundary, inclusive: a bond is matured ON its maturity date.
function isMatured(maturityDate, ts) {
  return ts >= Date.parse(`${maturityDate}T00:00:00.000Z`);
}

// change = maxStep x (0.8 shared + 0.2 local) + mean reversion toward the seed; hard clamp to
// seed +/- maxDistance. The shared roll is drawn once per publish batch so the curve moves
// together (80%); the local roll breaks lockstep (20%). All in percent space, 3dp.
function updateTreasuryCleanPrice(quote, sharedRoll, localRoll) {
  const profile = TREASURY_PROFILE_BY_TERM[quote.originalTermYears];
  if (!profile) {
    throw new Error(`unsupported treasury term: ${quote.originalTermYears}`);
  }
  const change = profile.maxStep * (0.8 * sharedRoll + 0.2 * localRoll)
    + 0.02 * (quote.seedCleanPercent - quote.cleanPercent);
  const next = clamp(
    quote.cleanPercent + change,
    quote.seedCleanPercent - profile.maxDistance,
    quote.seedCleanPercent + profile.maxDistance
  );
  return round3(next);
}

// Classic bond approximation, per 100 par, result in percent, null at/after maturity. The
// annual coupon in dollars per 100 face is numerically couponRatePercent. Display analytics
// only — transaction value always uses the clean price (FR-CDM20).
function approximateYtmPercent(couponRatePercent, cleanPercent, maturityDate, quoteTs) {
  const years = (Date.parse(`${maturityDate}T00:00:00.000Z`) - quoteTs) / 86400000 / 365.25;
  if (years <= 0) {
    return null;
  }
  const ytm = ((couponRatePercent + (100 - cleanPercent) / years) / ((100 + cleanPercent) / 2)) * 100;
  return round3(ytm);
}

module.exports = {
  TREASURY_PROFILE_BY_TERM,
  round3,
  pctToFraction,
  now,
  isMatured,
  updateTreasuryCleanPrice,
  approximateYtmPercent
};
