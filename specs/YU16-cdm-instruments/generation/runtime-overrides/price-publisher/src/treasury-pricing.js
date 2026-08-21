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

// ---------------------------------------------------------------------------------------------
// The bond model. Everything below is per 100 par, in percent-of-par space, and pure.
//
// What it replaced, and why. The old yield was the one-line textbook approximation
//   ytm = (coupon + (100 - price)/years) / ((100 + price)/2)
// which has no coupon schedule, no day count and no solve. Measured against this model at the
// 2026-08-13 settle: near par it is only ~0.1-0.4bp out, which is exactly what makes it
// dangerous — it looks fine on the instruments anyone spot-checks. Away from par it falls apart,
// 47bp on the 30Y at 70 and 25bp on a 0.125% long bond at 40. And it cannot express a zero at
// all: a bill has no `coupon` to put in that numerator. Its error is smooth and plausible
// everywhere, so a curve bootstrapped off it is wrong everywhere and obviously wrong nowhere.
//
// The convention is NAMED, never assumed: ACT/ACT (ICMA) for Treasuries, 30/360 for corporates.
// A price and a yield are only a pair with respect to a day count, and "which one" is exactly
// the question a tie-out discrepancy starts from.
// ---------------------------------------------------------------------------------------------

/** Coupon frequency. Every instrument here is semiannual; it is a constant, not a knob. */
const PERIODS_PER_YEAR = 2;
const MONTHS_PER_PERIOD = 12 / PERIODS_PER_YEAR;

const DAY_COUNT = Object.freeze({
  /** US Treasuries: actual days over the actual days in the (quasi-)coupon period. */
  ACT_ACT_ICMA: 'ACT/ACT ICMA',
  /** US corporate bond basis: 30-day months, 360-day year, with the end-of-month clamps. */
  THIRTY_360: '30/360'
});

const DAY_MS = 86400000;

function parseDate(iso) {
  const ts = Date.parse(`${iso}T00:00:00.000Z`);
  if (!Number.isFinite(ts)) {
    throw new Error(`not a date: ${iso}`);
  }
  return new Date(ts);
}

function toIso(date) {
  return date.toISOString().slice(0, 10);
}

// java.time.LocalDate.minusMonths semantics: shift the month, then clamp the day to the new
// month's length. Each step is measured from the SAME anchor rather than from the step before
// it, so end-of-month clamping cannot walk a schedule off its day (Aug 31 -> Feb 29 -> Aug 29
// under repeated subtraction). The extract's Java accrual generates schedules the same way and
// the two must not disagree.
function addMonths(anchor, months) {
  const y = anchor.getUTCFullYear();
  const m = anchor.getUTCMonth() + months;
  const d = anchor.getUTCDate();
  const lastDayOfTarget = new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  return new Date(Date.UTC(y, m, Math.min(d, lastDayOfTarget)));
}

function daysBetween(from, to) {
  return Math.round((to.getTime() - from.getTime()) / DAY_MS);
}

// US 30/360 (bond basis). The two clamps are the whole convention and both matter: without the
// second one, a period ending on the 31st is a day longer than one ending on the 30th, which is
// precisely what 30/360 exists to deny.
function days30360(from, to) {
  let d1 = from.getUTCDate();
  let d2 = to.getUTCDate();
  if (d1 === 31) {
    d1 = 30;
  }
  if (d2 === 31 && d1 === 30) {
    d2 = 30;
  }
  return 360 * (to.getUTCFullYear() - from.getUTCFullYear())
    + 30 * (to.getUTCMonth() - from.getUTCMonth())
    + (d2 - d1);
}

function dayCountBetween(from, to, dayCount) {
  return dayCount === DAY_COUNT.THIRTY_360 ? days30360(from, to) : daysBetween(from, to);
}

/**
 * The (quasi-)coupon schedule, generated BACKWARDS from maturity in whole periods and stopped at
 * the issue date — so it is anchored on the dates the issuer actually pays, and the irregular
 * period, if there is one, lands at the FRONT where the market puts it.
 *
 * Returns every payment date from the first coupon to maturity, plus the period boundaries a day
 * count needs. A zero-coupon instrument gets the same schedule with `pays: false` — the dates
 * carry no cash flow and exist only to measure time, which is what makes a bill and a bond
 * comparable on one yield basis. That comparability is the entire point of a curve.
 *
 * First-coupon handling:
 *   * SHORT first coupon — the back-generated schedule's first date is less than one whole period
 *     after issue. The stub is the natural result and needs no special case.
 *   * LONG first coupon — the caller states `firstCouponDate` explicitly, more than one period
 *     after issue. It is data, not a heuristic: whether an issuer paid a long first coupon is a
 *     fact about that bond, and guessing it from the dates is how a model invents a cash flow.
 *
 * The payment AMOUNT of an irregular first coupon is prorated over its notional (quasi) period
 * per ICMA rule 251, so a stub pays less than a full coupon rather than a full one paid early.
 */
function couponSchedule(bond) {
  const issue = parseDate(bond.issueDate);
  const maturity = parseDate(bond.maturityDate);
  if (maturity <= issue) {
    throw new Error(`${bond.maturityDate} does not follow issue ${bond.issueDate}`);
  }
  const stopAt = bond.firstCouponDate ? parseDate(bond.firstCouponDate) : issue;

  const dates = [];
  for (let k = 0; ; k += 1) {
    const date = addMonths(maturity, -MONTHS_PER_PERIOD * k);
    if (date <= stopAt) {
      break;
    }
    dates.push(date);
    if (k > 2000) {
      throw new Error(`coupon schedule did not terminate for ${bond.maturityDate}`);
    }
  }
  if (bond.firstCouponDate) {
    dates.push(parseDate(bond.firstCouponDate));
  }
  dates.reverse();
  return dates;
}

/**
 * Where the settle date sits inside its (quasi-)coupon period, and what a full period around it
 * looks like. `periodStart`/`periodEnd` are the REGULAR period boundaries — for a stub, the
 * notional period ending at the next payment — which is what ACT/ACT (ICMA) measures against.
 * `accrualStart` is where interest actually began: the issue date inside a first stub, the
 * previous coupon everywhere else.
 */
function couponPeriod(bond, settle) {
  const issue = parseDate(bond.issueDate);
  const dates = couponSchedule(bond);
  const next = dates.find((date) => date > settle);
  if (!next) {
    return null; // at or past maturity
  }
  const index = dates.indexOf(next);
  const periodStart = addMonths(next, -MONTHS_PER_PERIOD);
  const previous = index > 0 ? dates[index - 1] : issue;
  return {
    accrualStart: previous > periodStart ? previous : (index === 0 ? issue : periodStart),
    periodStart,
    periodEnd: next,
    remaining: dates.length - index,
    isFirstPeriod: index === 0
  };
}

/**
 * Time between two dates measured in COUPON PERIODS, against the quasi-coupon grid anchored at
 * `anchor` (a payment date). This one function is the whole day-count layer: it is the accrual
 * factor, it is the proration of an irregular coupon, and it is the discount exponent.
 *
 * It SUMS over every quasi-period the interval touches (ICMA rule 251) rather than dividing by
 * one notional period. For a regular period that is exactly 1 and the distinction never shows.
 * It shows on a LONG first coupon: dividing 348 elapsed days by a single 181-day notional period
 * gives 1.9227 coupons, where summing the quasi-periods the interval actually spans gives 1.9076
 * — the same total the equivalent pair of regular coupons pays. The one-period form invents about
 * 1.5% of a coupon out of nothing, and it invents it consistently, so a price built on it and a
 * yield solved back out of that price agree with each other perfectly while both being wrong.
 */
function couponUnits(from, to, anchor, dayCount) {
  if (to <= from) {
    return 0;
  }
  let units = 0;
  for (let k = 0; k < 2000; k += 1) {
    const quasiEnd = addMonths(anchor, -MONTHS_PER_PERIOD * k);
    const quasiStart = addMonths(anchor, -MONTHS_PER_PERIOD * (k + 1));
    if (quasiStart < to) {
      const lower = quasiStart > from ? quasiStart : from;
      const upper = quasiEnd < to ? quasiEnd : to;
      if (upper > lower) {
        const denominator = dayCount === DAY_COUNT.THIRTY_360
          ? 360 / PERIODS_PER_YEAR
          : dayCountBetween(quasiStart, quasiEnd, dayCount);
        units += dayCountBetween(lower, upper, dayCount) / denominator;
      }
    }
    if (quasiStart <= from) {
      return units;
    }
  }
  throw new Error(`coupon-unit walk did not terminate between ${toIso(from)} and ${toIso(to)}`);
}

/**
 * Accrued interest as a percent of par, plus the two quantities the pricer needs: how much of the
 * current period has elapsed, and how far the next cash flow still is. Returns null at or past
 * maturity, where the final coupon has paid and nothing has accrued since.
 */
function accrued(bond, settle, dayCount = DAY_COUNT.ACT_ACT_ICMA) {
  const period = couponPeriod(bond, settle);
  if (!period) {
    return null;
  }
  const elapsed = couponUnits(period.accrualStart, settle, period.periodEnd, dayCount);
  const couponAmount = Number(bond.couponRatePercent) / PERIODS_PER_YEAR;
  return {
    lastCouponDate: toIso(period.accrualStart),
    nextCouponDate: toIso(period.periodEnd),
    // The exponent the first cash flow is discounted by, in coupon periods. Greater than 1 inside
    // a long first period, which is the point: that payment really is more than a period away.
    remainingPeriodFraction: couponUnits(settle, period.periodEnd, period.periodEnd, dayCount),
    elapsedFraction: elapsed,
    // A zero accrues nothing, ever. Not "0% of a period" — no coupon exists to accrue.
    percentOfPar: couponAmount === 0 ? 0 : couponAmount * elapsed
  };
}

/**
 * Dirty price per 100 par at a given yield. Cash flows are discounted in COUPON PERIODS, the
 * street convention: the first lands `remainingPeriodFraction` periods out and each subsequent
 * one a whole period after that.
 *
 * An irregular first coupon pays its prorated amount (ICMA 251), not a full one.
 */
function dirtyPriceFromYield(bond, settle, yieldPercent, dayCount = DAY_COUNT.ACT_ACT_ICMA) {
  const period = couponPeriod(bond, settle);
  if (!period) {
    return null;
  }
  const acc = accrued(bond, settle, dayCount);
  const v = 1 / (1 + yieldPercent / 100 / PERIODS_PER_YEAR);
  const couponAmount = Number(bond.couponRatePercent) / PERIODS_PER_YEAR;
  const dates = couponSchedule(bond);
  const first = dates.length - period.remaining;

  let pv = 0;
  for (let k = 0; k < period.remaining; k += 1) {
    const exponent = acc.remainingPeriodFraction + k;
    if (couponAmount !== 0) {
      const date = dates[first + k];
      // Every coupon is prorated by the same quasi-period sum, so a regular one comes out at
      // exactly 1 and an irregular one — short OR long — comes out at what it actually pays.
      // No special case, and therefore no special case to get wrong.
      const start = first + k === 0 ? parseDate(bond.issueDate) : dates[first + k - 1];
      pv += couponAmount * couponUnits(start, date, date, dayCount) * Math.pow(v, exponent);
    }
    if (k === period.remaining - 1) {
      pv += 100 * Math.pow(v, exponent); // redemption, alongside the final coupon
    }
  }
  return pv;
}

function cleanPriceFromYield(bond, settle, yieldPercent, dayCount = DAY_COUNT.ACT_ACT_ICMA) {
  const dirty = dirtyPriceFromYield(bond, settle, yieldPercent, dayCount);
  return dirty === null ? null : dirty - accrued(bond, settle, dayCount).percentOfPar;
}

const SOLVE_TOLERANCE = 1e-11;
const SOLVE_MAX_ITERATIONS = 100;

/**
 * Price -> yield. SAFEGUARDED Newton: a bracket is established first, Newton proposes each step,
 * and any step that leaves the bracket or fails to shrink the interval is replaced by a bisection
 * step. So it converges quadratically where Newton behaves and cannot diverge where it does not.
 *
 * The safeguard is not decoration. Bare Newton wanders on long maturities — a 30Y bond's price
 * function is stiff enough near par that a step overshoots past the pole at y = -200% and the
 * iteration runs off to a nonsense root or to NaN. Bisection alone is safe but slow. Bracketing
 * Newton is both, and it is barely more code than either.
 *
 * Returns null at or past maturity. Throws if the price lies outside any attainable yield, which
 * is a malformed input, not a hard root — silently returning a boundary would be a lie.
 */
function yieldFromCleanPrice(bond, settle, cleanPercent, dayCount = DAY_COUNT.ACT_ACT_ICMA) {
  const period = couponPeriod(bond, settle);
  if (!period) {
    return null;
  }
  const target = Number(cleanPercent);
  if (!(target > 0)) {
    throw new Error(`a clean price must be positive, got ${cleanPercent}`);
  }
  const f = (y) => cleanPriceFromYield(bond, settle, y, dayCount) - target;

  // Price falls monotonically in yield, so the bracket runs [low yield -> price too high,
  // high yield -> price too low]. Expand rather than assume: a deep-discount STRIP needs a far
  // wider high end than a par note, and a price above par-plus-all-coupons needs a negative one.
  //
  // The low end walks toward the POLE at y = -100 x PERIODS_PER_YEAR, where the discount factor
  // diverges and the price goes to infinity — halfway each time, never reaching it. Halving `lo`
  // toward zero instead would move the yield UP and the price DOWN, i.e. away from the bracket it
  // is trying to establish; the loop would then exhaust and report a perfectly attainable price
  // as unattainable.
  const POLE = -100 * PERIODS_PER_YEAR;
  let lo = -50;
  let hi = 100;
  let flo = f(lo);
  let fhi = f(hi);
  for (let i = 0; !(flo > 0) && i < 200; i += 1) {
    lo = (lo + POLE) / 2;
    flo = f(lo);
  }
  for (let i = 0; !(fhi < 0) && i < 200; i += 1) {
    hi *= 2;
    fhi = f(hi);
  }
  if (!(flo > 0) || !(fhi < 0)) {
    throw new Error(`clean price ${cleanPercent} is not attainable for ${bond.maturityDate}`);
  }

  // Seed at the current yield — close enough that Newton usually lands in two or three steps.
  let y = Math.min(Math.max(Number(bond.couponRatePercent) || 1, lo), hi);
  for (let i = 0; i < SOLVE_MAX_ITERATIONS; i += 1) {
    const fy = f(y);
    if (Math.abs(fy) < SOLVE_TOLERANCE || hi - lo < SOLVE_TOLERANCE) {
      return y;
    }
    if (fy > 0) {
      lo = y;
    } else {
      hi = y;
    }
    // Central difference: the analytic derivative would be a second cash-flow loop to maintain
    // in lockstep with the first, and a wrong one degrades silently into slow convergence.
    const h = 1e-6;
    const slope = (f(y + h) - f(y - h)) / (2 * h);
    const step = Number.isFinite(slope) && slope !== 0 ? y - fy / slope : NaN;
    // THE SAFEGUARD: take Newton's step only if it stayed inside the bracket. Otherwise bisect.
    y = Number.isFinite(step) && step > lo && step < hi ? step : (lo + hi) / 2;
  }
  throw new Error(`yield solve did not converge for ${bond.maturityDate} at ${cleanPercent}`);
}

/**
 * The publisher's yield for a tick. Semiannual bond basis for EVERY instrument, coupon-bearing or
 * zero, so the points are directly comparable and a consumer can bootstrap across them — which is
 * the one thing a curve needs and the old per-instrument approximation could never give.
 * Null at or past maturity (FR-CDM20).
 */
function ytmPercent(bond, quoteTs, cleanPercent, dayCount = DAY_COUNT.ACT_ACT_ICMA) {
  const settle = new Date(quoteTs);
  if (isMatured(bond.maturityDate, quoteTs)) {
    return null;
  }
  const solved = yieldFromCleanPrice(bond, settle, cleanPercent, dayCount);
  return solved === null ? null : round6(solved);
}

function round6(value) {
  return Math.round((Number(value) + Number.EPSILON) * 1000000) / 1000000;
}

module.exports = {
  TREASURY_PROFILE_BY_TERM,
  DAY_COUNT,
  PERIODS_PER_YEAR,
  round3,
  round6,
  pctToFraction,
  now,
  isMatured,
  updateTreasuryCleanPrice,
  addMonths,
  days30360,
  couponUnits,
  couponSchedule,
  couponPeriod,
  accrued,
  dirtyPriceFromYield,
  cleanPriceFromYield,
  yieldFromCleanPrice,
  ytmPercent
};
