// YU16: the Treasury model's runnable checks. The load-bearing assertions are the fraction
// emission (ADR-057) and the six-decimal binary tick (FR-CDM15) — the two places a silent
// 100x or a silent 3dp-rounding error would otherwise hide.
const test = require('node:test');
const assert = require('node:assert/strict');
const treasury = require('../src/treasury-pricing');

test('pctToFraction is exact at six decimals (ADR-057)', () => {
  assert.equal(treasury.pctToFraction(99.878), 0.99878);
  assert.equal(treasury.pctToFraction(99.886), 0.99886);
  assert.equal(treasury.pctToFraction(98.481), 0.98481);
  // The wire tick is round(fraction x 1e6): all six decimals survive.
  assert.equal(Math.round(treasury.pctToFraction(99.878) * 1000000), 998780);
  assert.equal(Math.round(treasury.pctToFraction(99.886) * 1000000), 998860);
});

test('a full positive roll steps 2Y to 99.883 and 30Y to 99.343 (FR-CDM18)', () => {
  const q2 = { seedCleanPercent: 99.878, cleanPercent: 99.878, originalTermYears: 2 };
  assert.equal(treasury.updateTreasuryCleanPrice(q2, 1, 1), 99.883);
  const q30 = { seedCleanPercent: 99.293, cleanPercent: 99.293, originalTermYears: 30 };
  assert.equal(treasury.updateTreasuryCleanPrice(q30, 1, 1), 99.343);
});

test('the walk never escapes seed +/- maxDistance (FR-CDM18)', () => {
  const quote = { seedCleanPercent: 99.878, cleanPercent: 99.878, originalTermYears: 2 };
  for (let i = 0; i < 500; i += 1) {
    quote.cleanPercent = treasury.updateTreasuryCleanPrice(quote, 1, 1);
  }
  assert.equal(quote.cleanPercent, 99.878 + 0.15);
  for (let i = 0; i < 1000; i += 1) {
    quote.cleanPercent = treasury.updateTreasuryCleanPrice(quote, -1, -1);
  }
  assert.equal(quote.cleanPercent, 99.878 - 0.15);
});

test('an unsupported term throws rather than walks', () => {
  // 7 became a real bucket when the 7Y note was seeded; 4 is still not one. A term with no
  // bucket must never fall back to a neighbour's band.
  assert.throws(
    () => treasury.updateTreasuryCleanPrice({ seedCleanPercent: 100, cleanPercent: 100, originalTermYears: 4 }, 0, 0),
    /unsupported treasury term/);
  assert.ok(treasury.TREASURY_PROFILE_BY_TERM[7], '7Y must have its own bucket');
});

test('every bill and STRIP tenor has a walk bucket (an added instrument that throws is worse than one that is absent)', () => {
  for (const term of [0.08, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 20, 30]) {
    const profile = treasury.TREASURY_PROFILE_BY_TERM[term];
    assert.ok(profile, `no walk bucket for tenor ${term}`);
    assert.ok(profile.maxStep > 0 && profile.maxDistance > profile.maxStep,
      `tenor ${term}: a band narrower than one step would pin the price`);
  }
  // Monotone in tenor: a longer bond must not move less than a shorter one.
  const terms = [0.08, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 20, 30];
  for (let i = 1; i < terms.length; i += 1) {
    const prev = treasury.TREASURY_PROFILE_BY_TERM[terms[i - 1]];
    const cur = treasury.TREASURY_PROFILE_BY_TERM[terms[i]];
    assert.ok(cur.maxStep > prev.maxStep && cur.maxDistance > prev.maxDistance,
      `tenor ${terms[i]} is not more volatile than ${terms[i - 1]}`);
  }
});

test('maturity is the UTC midnight boundary, inclusive (FR-CDM21)', () => {
  assert.equal(treasury.isMatured('2028-06-30', Date.parse('2028-06-29T23:59:59.999Z')), false);
  assert.equal(treasury.isMatured('2028-06-30', Date.parse('2028-06-30T00:00:00.000Z')), true);
});

// ----------------------------------------------------------------------------------------------
// The bond model (FR-CDM20). What these check, and why each is more than a transcription of the
// implementation: every assertion below is either a CLOSED FORM computed independently of the
// solver, or an INVARIANT the solver cannot satisfy by being wrong in a self-consistent way.
// ----------------------------------------------------------------------------------------------

const SETTLE = new Date(Date.UTC(2026, 7, 13));                   // 2026-08-13
const UST_2Y = { couponRatePercent: 4.125, issueDate: '2026-06-30', maturityDate: '2028-06-30' };
const UST_30Y = { couponRatePercent: 5.0, issueDate: '2026-05-15', maturityDate: '2056-05-15' };
const BILL_52W = { couponRatePercent: 0, issueDate: '2026-08-13', maturityDate: '2027-08-12' };
const STRIP_30Y = { couponRatePercent: 0, issueDate: '2026-08-13', maturityDate: '2056-05-15' };

test('KNOWN ANSWER: a bond priced at par on a coupon date yields exactly its coupon (to the bp and far beyond)', () => {
  // The one bond identity that needs no external source and admits no tolerance argument: on a
  // coupon date, with no accrued interest, price 100 <=> yield == coupon, exactly. If the
  // schedule, the day count, the discounting or the solve is wrong in ANY of them, this breaks.
  for (const bond of [UST_2Y, UST_30Y, { couponRatePercent: 6, issueDate: '2026-01-15', maturityDate: '2046-01-15' }]) {
    const couponDate = new Date(Date.parse(`${bond.issueDate}T00:00:00.000Z`));
    const solved = treasury.yieldFromCleanPrice(bond, couponDate, 100);
    assert.ok(Math.abs(solved - bond.couponRatePercent) < 1e-8,
      `${bond.maturityDate} at par solved ${solved}, coupon is ${bond.couponRatePercent}`);
    // ...and the other direction: the coupon yield reprices it to exactly par.
    assert.ok(Math.abs(treasury.cleanPriceFromYield(bond, couponDate, bond.couponRatePercent) - 100) < 1e-9);
  }
});

test('KNOWN ANSWER: a zero-coupon price is the closed-form discount factor, computed here without the solver', () => {
  // price = 100 / (1 + y/2)^(2t) with t in quasi-coupon periods — evaluated independently below,
  // then handed to the solver, which must return the yield it was built from. A basis point on
  // a 30-year zero is ~0.06 of a price point, so this is far finer than "to the bp".
  for (const [bond, y] of [[BILL_52W, 4.25], [STRIP_30Y, 5.0], [{ couponRatePercent: 0, issueDate: '2026-08-13', maturityDate: '2031-06-30' }, 4.75]]) {
    const period = treasury.couponPeriod(bond, SETTLE);
    const acc = treasury.accrued(bond, SETTLE);
    const periods = acc.remainingPeriodFraction + (period.remaining - 1);
    const closedForm = 100 / Math.pow(1 + y / 200, periods);

    assert.ok(Math.abs(treasury.cleanPriceFromYield(bond, SETTLE, y) - closedForm) < 1e-9,
      `${bond.maturityDate}: model says ${treasury.cleanPriceFromYield(bond, SETTLE, y)}, closed form ${closedForm}`);
    assert.ok(Math.abs(treasury.yieldFromCleanPrice(bond, SETTLE, closedForm) - y) < 1e-7,
      `${bond.maturityDate}: solving the closed-form price did not return ${y}`);
    assert.equal(acc.percentOfPar, 0, 'a zero accrues nothing, ever');
  }
});

test('KNOWN ANSWER: the auction seeds reprice to the curve they were quoted on, and the solve round-trips to 1e-9', () => {
  // The published pair we actually hold: TreasuryDirect's auction clean prices. Solving each one
  // at the 2026-08-13 settle must produce a COHERENT curve — monotone-ish, all inside a
  // plausible band — and repricing at the solved yield must return the input price. The
  // round-trip is the part a wrong day count or a wrong schedule cannot fake: it would reproduce
  // its own error consistently in price space but land on a different yield.
  const seeds = [
    ['UST-20280630', UST_2Y, 99.878432, 4.190749],
    ['UST-20310630', { couponRatePercent: 4.125, issueDate: '2026-06-30', maturityDate: '2031-06-30' }, 99.664909, 4.200770],
    ['UST-20360515', { couponRatePercent: 4.375, issueDate: '2026-05-15', maturityDate: '2036-05-15' }, 99.256552, 4.469089],
    ['UST-20460515', { couponRatePercent: 5.0, issueDate: '2026-06-01', maturityDate: '2046-05-15' }, 98.481099, 5.122745],
    ['UST-20560515', UST_30Y, 99.292811, 5.045654]
  ];
  for (const [key, bond, price, expectedYield] of seeds) {
    const solved = treasury.yieldFromCleanPrice(bond, SETTLE, price);
    // To the tenth of a basis point.
    assert.ok(Math.abs(solved - expectedYield) < 1e-4, `${key}: solved ${solved}, expected ${expectedYield}`);
    assert.ok(Math.abs(treasury.cleanPriceFromYield(bond, SETTLE, solved) - price) < 1e-9,
      `${key}: the solved yield does not reprice to ${price}`);
  }
});

test('the 20Y has a SHORT first coupon and the model prices it as one', () => {
  // Issued 2026-06-01 against a 2046-05-15 maturity: the first period runs 2026-06-01 ->
  // 2026-11-15, shorter than six months. The accrual must start at ISSUE, not at the notional
  // period start (2026-05-15) — starting at the latter would credit the holder 17 days of
  // interest on a bond that did not exist yet.
  const bond = { couponRatePercent: 5.0, issueDate: '2026-06-01', maturityDate: '2046-05-15' };
  const acc = treasury.accrued(bond, SETTLE);
  assert.equal(acc.lastCouponDate, '2026-06-01', 'accrual runs from issue inside a first stub');
  assert.equal(acc.nextCouponDate, '2026-11-15');
  // 73 days elapsed (Jun 1 -> Aug 13) over the 184-day notional period May 15 -> Nov 15.
  assert.ok(Math.abs(acc.percentOfPar - (5.0 / 2) * (73 / 184)) < 1e-12);
  // The negative control for the sentence above: a schedule that ignored the issue date would
  // accrue from 2026-05-15 and produce a strictly larger number. Prove the two differ.
  const ignoringIssue = (5.0 / 2) * (90 / 184);
  assert.ok(acc.percentOfPar < ignoringIssue,
    'accruing from the notional period start instead of issue must give a DIFFERENT (larger) number');
});

test('a LONG first coupon is data, not a guess — stated firstCouponDate, prorated payment', () => {
  // Issued 2026-06-01, first coupon 2027-05-15: nearly a full year, so the first payment covers
  // about two periods and must be worth about two coupons. A model that paid a flat coupon there
  // would undervalue the bond; one that inferred the stub from the dates would be inventing it.
  const bond = {
    couponRatePercent: 5.0, issueDate: '2026-06-01',
    firstCouponDate: '2027-05-15', maturityDate: '2036-05-15'
  };
  const schedule = treasury.couponSchedule(bond);
  assert.equal(schedule[0].toISOString().slice(0, 10), '2027-05-15');
  assert.equal(schedule[1].toISOString().slice(0, 10), '2027-11-15');

  // Same bond, same issue date, but back-generating a SHORT stub instead (first coupon
  // 2026-11-15). The two must pay the same TOTAL cash — the long stub just merges the first two
  // payments — and the long stub pays it LATER, so at a positive yield it is worth strictly less.
  const shortStub = { couponRatePercent: 5.0, issueDate: '2026-06-01', maturityDate: '2036-05-15' };
  assert.equal(treasury.couponSchedule(shortStub)[0].toISOString().slice(0, 10), '2026-11-15');

  // At (effectively) zero yield, price IS the undiscounted sum of cash flows. Equal totals.
  const totalLong = treasury.cleanPriceFromYield(bond, SETTLE, 1e-9);
  const totalShort = treasury.cleanPriceFromYield(shortStub, SETTLE, 1e-9);
  assert.ok(Math.abs(totalLong - totalShort) < 1e-4,
    `merging two coupons into a long stub changed the total cash: ${totalLong} vs ${totalShort}`);

  // ...and money later is worth less, monotonically in the yield.
  for (const y of [1, 5, 10]) {
    assert.ok(treasury.cleanPriceFromYield(bond, SETTLE, y) < treasury.cleanPriceFromYield(shortStub, SETTLE, y),
      `at ${y}% the long stub must be worth less — its cash arrives later`);
  }

  // NEGATIVE CONTROL for the proration itself. Prorating a long stub against ONE notional period
  // (348 days / 181) instead of summing the quasi-periods it spans gives 1.9227 coupons where the
  // truth is 1.9076 — about 1.5% of a coupon invented out of nothing. Show the two differ, so the
  // equal-totals assertion above is checking something.
  const firstPayment = treasury.couponUnits(
    new Date(Date.UTC(2026, 5, 1)), new Date(Date.UTC(2027, 4, 15)),
    new Date(Date.UTC(2027, 4, 15)), treasury.DAY_COUNT.ACT_ACT_ICMA);
  assert.ok(Math.abs(firstPayment - 1.9076) < 1e-3, `first payment is ${firstPayment} coupons`);
  assert.ok(Math.abs(firstPayment - 348 / 181) > 0.01, 'the one-notional-period form must NOT agree');
});

test('the day count is named and the two conventions genuinely differ (ACT/ACT ICMA vs 30/360)', () => {
  assert.equal(treasury.DAY_COUNT.ACT_ACT_ICMA, 'ACT/ACT ICMA');
  assert.equal(treasury.DAY_COUNT.THIRTY_360, '30/360');
  // A corporate on 30/360 and a Treasury on ACT/ACT are NOT the same number for the same bond.
  // If they were, naming the convention would be decoration and this test would be worthless.
  const corp = { couponRatePercent: 5.5, issueDate: '2026-01-31', maturityDate: '2033-07-31' };
  const icma = treasury.accrued(corp, SETTLE, treasury.DAY_COUNT.ACT_ACT_ICMA).percentOfPar;
  const thirty = treasury.accrued(corp, SETTLE, treasury.DAY_COUNT.THIRTY_360).percentOfPar;
  assert.notEqual(icma, thirty, 'the two day counts must not collapse to the same accrual');
  assert.ok(Math.abs(icma - thirty) > 1e-5, `they differ by only ${Math.abs(icma - thirty)}`);
  // 30/360's end-of-month clamps, checked directly: Jan 31 -> Jul 31 is exactly half a year.
  assert.equal(treasury.days30360(new Date(Date.UTC(2026, 0, 31)), new Date(Date.UTC(2026, 6, 31))), 180);
  assert.equal(treasury.days30360(new Date(Date.UTC(2026, 0, 30)), new Date(Date.UTC(2026, 6, 31))), 180);
  assert.equal(treasury.days30360(new Date(Date.UTC(2026, 0, 1)), new Date(Date.UTC(2027, 0, 1))), 360);
});

test('the solve beats the old approximation where it mattered, and the approximation is worst away from par', () => {
  // The reason this replaced a one-liner — and the reason the one-liner survived so long. NEAR
  // PAR the textbook approximation is within a fraction of a basis point, so every instrument
  // anyone would spot-check looks right. Away from par it is tens of basis points out.
  const approximate = (bond, price) => {
    const years = (Date.parse(`${bond.maturityDate}T00:00:00.000Z`) - SETTLE.getTime()) / 86400000 / 365.25;
    return ((bond.couponRatePercent + (100 - price) / years) / ((100 + price) / 2)) * 100;
  };
  const errorBp = (bond, price) =>
    Math.abs(approximate(bond, price) - treasury.yieldFromCleanPrice(bond, SETTLE, price)) * 100;

  assert.ok(errorBp(UST_30Y, 99.292811) < 1, 'near par the approximation looks fine — that is the trap');
  assert.ok(errorBp(UST_30Y, 70) > 20, `deep discount error was only ${errorBp(UST_30Y, 70)}bp`);
  assert.ok(errorBp({ couponRatePercent: 0.125, issueDate: '2026-05-15', maturityDate: '2056-05-15' }, 40) > 10);

  // And only the solved value reprices. This is the assertion the approximation can never pass,
  // at any price, which is what makes "solve" a different thing from "estimate".
  for (const price of [99.292811, 70, 140]) {
    const solved = treasury.yieldFromCleanPrice(UST_30Y, SETTLE, price);
    assert.ok(Math.abs(treasury.cleanPriceFromYield(UST_30Y, SETTLE, solved) - price) < 1e-9);
    assert.ok(Math.abs(treasury.cleanPriceFromYield(UST_30Y, SETTLE, approximate(UST_30Y, price)) - price) > 1e-4,
      `at ${price} the approximation happened to reprice — the comparison is vacuous there`);
  }
});

test('price falls monotonically in yield, and the solve inverts that over a wide range', () => {
  // The property that makes bracketing sound. If it ever failed, the bisection fallback would be
  // converging to the wrong root while looking perfectly convergent.
  for (const bond of [UST_2Y, UST_30Y, BILL_52W, STRIP_30Y]) {
    let previous = Infinity;
    for (let y = 0.25; y <= 20; y += 0.25) {
      const price = treasury.cleanPriceFromYield(bond, SETTLE, y);
      assert.ok(price < previous, `${bond.maturityDate}: price rose from ${previous} to ${price} at y=${y}`);
      previous = price;
      assert.ok(Math.abs(treasury.yieldFromCleanPrice(bond, SETTLE, price) - y) < 1e-6,
        `${bond.maturityDate}: solving ${price} did not return ${y}`);
    }
  }
});

test('the bisection fallback is load-bearing: the solve holds where Newton alone wanders', () => {
  // Newton's trouble is a flat local slope far from the root plus a pole at y = -200%: a single
  // step overshoots past it and the iteration runs to a nonsense root or NaN. The safeguard is
  // that a step leaving the bracket is replaced by a bisection step. Exercise the shapes that
  // provoke it — a 30-year zero (extremely convex), a deep discount, and a deep premium — and
  // require a finite answer that reprices.
  const hard = [
    [STRIP_30Y, 5.0], [STRIP_30Y, 60.0], [STRIP_30Y, 99.5],
    [UST_30Y, 20.0], [UST_30Y, 260.0],
    [{ couponRatePercent: 0.125, issueDate: '2026-05-15', maturityDate: '2056-05-15' }, 8.0]
  ];
  for (const [bond, price] of hard) {
    const solved = treasury.yieldFromCleanPrice(bond, SETTLE, price);
    assert.ok(Number.isFinite(solved), `${bond.maturityDate} at ${price}: solve returned ${solved}`);
    assert.ok(solved > -200, 'the solve escaped past the pole at y = -200%');
    assert.ok(Math.abs(treasury.cleanPriceFromYield(bond, SETTLE, solved) - price) < 1e-8,
      `${bond.maturityDate} at ${price}: solved ${solved} does not reprice`);
  }
  // A price far above par has a root at a deeply NEGATIVE yield, and the bracket must walk toward
  // the pole to find it rather than give up. This is the case that caught the first version of
  // the expansion, which halved the low bound toward zero — i.e. toward a HIGHER yield and a
  // LOWER price, away from the bracket it was trying to establish — and reported a perfectly
  // attainable price as unattainable.
  const extreme = treasury.yieldFromCleanPrice(UST_30Y, SETTLE, 100000);
  assert.ok(Number.isFinite(extreme) && extreme < -20, `expected a deeply negative yield, got ${extreme}`);
  assert.ok(Math.abs(treasury.cleanPriceFromYield(UST_30Y, SETTLE, extreme) / 100000 - 1) < 1e-9);
  // A non-positive price is not a bond price at any yield and refuses rather than solving.
  assert.throws(() => treasury.yieldFromCleanPrice(UST_30Y, SETTLE, 0), /must be positive/);
  assert.throws(() => treasury.yieldFromCleanPrice(UST_30Y, SETTLE, -1), /must be positive/);
});

test('the coupon schedule is anchored on maturity, so end-of-month cannot walk it off its day', () => {
  // Aug 31 -> Feb 28/29 -> Aug 28 under repeated subtraction. Measuring every step from the same
  // anchor is what prevents it, and the extract's Java accrual does the same — they must agree.
  const bond = { couponRatePercent: 4, issueDate: '2026-08-31', maturityDate: '2030-08-31' };
  for (const date of treasury.couponSchedule(bond)) {
    const iso = date.toISOString().slice(0, 10);
    assert.ok(iso.endsWith('-28') || iso.endsWith('-29') || iso.endsWith('-31'),
      `${iso} drifted off the 31st (Feb clamps to 28/29, which is correct)`);
  }
  assert.equal(treasury.addMonths(new Date(Date.UTC(2026, 7, 31)), -6).toISOString().slice(0, 10), '2026-02-28');
  assert.equal(treasury.addMonths(new Date(Date.UTC(2028, 7, 31)), -6).toISOString().slice(0, 10), '2028-02-29');
});

test('ytmPercent nulls at and after maturity, and is finite before it (FR-CDM20)', () => {
  const bond = { ...UST_2Y };
  assert.equal(treasury.ytmPercent(bond, Date.parse('2028-06-30T00:00:00.000Z'), 99.878), null);
  assert.equal(treasury.ytmPercent(bond, Date.parse('2030-01-01T00:00:00.000Z'), 99.878), null);
  const live = treasury.ytmPercent(bond, Date.parse('2026-08-13T00:00:00.000Z'), 99.878432);
  assert.ok(Number.isFinite(live) && live > 4.1 && live < 4.3, `got ${live}`);
  // Six decimals kept: a curve bootstrapper wants better than a rounded basis point.
  assert.equal(live, treasury.round6(live));
});

test('the fixed clock is honored and an invalid instant throws (NFR-CDM09)', () => {
  process.env.TRADERX_FIXED_UTC_INSTANT = '2036-05-15T00:00:00.000Z';
  try {
    assert.equal(treasury.now(), Date.parse('2036-05-15T00:00:00.000Z'));
    process.env.TRADERX_FIXED_UTC_INSTANT = 'not-a-time';
    assert.throws(() => treasury.now(), /not a valid instant/);
  } finally {
    delete process.env.TRADERX_FIXED_UTC_INSTANT;
  }
});

test('main.js: unknown UST- keys get no fallback quote; treasuries emit fractions (FR-CDM21, ADR-057)', () => {
  const main = require('../src/main');
  assert.equal(main.ensureTicker('UST-19990101'), null);
  const equity = main.ensureTicker('ZZTEST');
  assert.ok(equity && equity.price > 0);

  // Simulate a bootstrapped treasury and check payload + binary tick semantics.
  const seeded = {
    ticker: 'UST-20560515', price: 0.99293, openPrice: 0.99293, closePrice: 0.99293,
    cleanPercent: 99.293, seedCleanPercent: 99.293, couponRatePercent: 5.0,
    originalTermYears: 30, issueDate: '2026-05-15', maturityDate: '2056-05-15',
    officialSeedCleanPrice: 99.292811,
    simulated: true, source: 'simulated-us-treasury-auction-seed'
  };
  main.state.prices.set(seeded.ticker, seeded);
  main.state.treasuries.set(seeded.ticker, seeded);

  const payload = main.toPayload(seeded);
  assert.equal(payload.priceSemantics, 'CLEAN_FRACTION_OF_PAR');
  assert.equal(payload.cleanPrice, payload.price);
  assert.ok(payload.price > 0.9 && payload.price < 1.1, 'price is a fraction of par, not a percent');
  assert.equal(payload.asOf, payload.quoteTimestamp);
  assert.ok(Number.isFinite(payload.ytmPercent));
  // The convention travels with the number. A yield without its basis is not a quote.
  assert.equal(payload.yieldConvention, 'SEMIANNUAL_BOND');
  assert.equal(payload.dayCount, 'ACT/ACT ICMA');

  const buf = main.encodeBinaryTick(seeded);
  assert.equal(buf.readBigInt64BE(0), 992930n); // six decimals preserved, not 993000n
});

test('main.js: a corporate quotes on 30/360, carries its rating, and never reaches yfinance', () => {
  const main = require('../src/main');
  const corp = {
    ticker: 'CORP-GS-20360315', price: 0.991230, openPrice: 0.991230, closePrice: 0.991230,
    cleanPercent: 99.123, seedCleanPercent: 99.123, couponRatePercent: 5.75,
    originalTermYears: 10, issueDate: '2026-03-15', maturityDate: '2036-03-15',
    assetClass: 'CORPORATE_BOND', dayCount: '30/360', creditRating: 'BBB+',
    officialSeedCleanPrice: 99.123457, simulated: true,
    source: 'simulated-corporate-credit-spread'
  };
  main.state.prices.set(corp.ticker, corp);
  main.state.treasuries.set(corp.ticker, corp);

  const payload = main.toPayload(corp);
  assert.equal(payload.assetClass, 'CORPORATE_BOND', 'a corporate is not a Treasury on the wire');
  assert.equal(payload.dayCount, '30/360', 'and it is quoted on its OWN convention, not the default');
  assert.equal(payload.creditRating, 'BBB+', 'the second risk factor rides on the tick');
  assert.equal(payload.yieldConvention, 'SEMIANNUAL_BOND', 'one yield basis across the whole book');
  assert.ok(payload.ytmPercent > 5 && payload.ytmPercent < 7, `got ${payload.ytmPercent}`);
  // Six decimals survive for a corporate exactly as for a Treasury (FR-CDM15).
  assert.equal(main.encodeBinaryTick(corp).readBigInt64BE(0), 991230n);

  // The yield really is convention-dependent: solving the same price as ACT/ACT gives a different
  // number, so `dayCount` on the payload is load-bearing rather than decorative.
  const asActAct = treasury.ytmPercent(corp, Date.parse('2026-08-13T00:00:00.000Z'), 99.123,
    treasury.DAY_COUNT.ACT_ACT_ICMA);
  const as30360 = treasury.ytmPercent(corp, Date.parse('2026-08-13T00:00:00.000Z'), 99.123,
    treasury.DAY_COUNT.THIRTY_360);
  assert.notEqual(asActAct, as30360, 'the two conventions must not collapse to one yield');

  // An unknown CORP- key gets a 404, never a fabricated quote — createFallbackQuote would invent
  // a price near 100, which for a fraction of par is a 100x nonsense that still looks numeric.
  assert.equal(main.ensureTicker('CORP-NOPE-20990101'), null);
});

test('main.js: a zero-coupon bill quotes on the same basis, with no coupon anywhere in its payload', () => {
  const main = require('../src/main');
  const bill = {
    ticker: 'UST-BILL-20270812', price: 0.959560, openPrice: 0.959560, closePrice: 0.959560,
    cleanPercent: 95.956, seedCleanPercent: 95.956, couponRatePercent: 0,
    originalTermYears: 1, issueDate: '2026-08-13', maturityDate: '2027-08-12',
    officialSeedCleanPrice: 95.955556,
    simulated: true, source: 'simulated-us-treasury-auction-seed'
  };
  main.state.prices.set(bill.ticker, bill);
  main.state.treasuries.set(bill.ticker, bill);

  const payload = main.toPayload(bill);
  // A bill priced at a discount to par has a POSITIVE yield; the zero path existing at all is the
  // point — the old approximation had a coupon in its numerator and could not price this.
  assert.ok(Number.isFinite(payload.ytmPercent) && payload.ytmPercent > 3 && payload.ytmPercent < 6,
    `bill yield ${payload.ytmPercent} is not plausible for a 95.956 price one year out`);
  assert.equal(payload.yieldConvention, 'SEMIANNUAL_BOND', 'one basis across the curve, or it is not a curve');
  assert.equal(payload.priceSemantics, 'CLEAN_FRACTION_OF_PAR');
  // The price IS the discount factor — literally what a bootstrapper wants.
  assert.equal(main.encodeBinaryTick(bill).readBigInt64BE(0), 959560n);
});
