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
  assert.throws(
    () => treasury.updateTreasuryCleanPrice({ seedCleanPercent: 100, cleanPercent: 100, originalTermYears: 7 }, 0, 0),
    /unsupported treasury term/);
});

test('maturity is the UTC midnight boundary, inclusive (FR-CDM21)', () => {
  assert.equal(treasury.isMatured('2028-06-30', Date.parse('2028-06-29T23:59:59.999Z')), false);
  assert.equal(treasury.isMatured('2028-06-30', Date.parse('2028-06-30T00:00:00.000Z')), true);
});

test('approximate YTM matches the bond approximation and nulls at maturity (FR-CDM20)', () => {
  const ts = Date.parse('2026-08-10T00:00:00.000Z');
  const years = (Date.parse('2028-06-30T00:00:00.000Z') - ts) / 86400000 / 365.25;
  const expected = Math.round((((4.125 + (100 - 99.878) / years) / ((100 + 99.878) / 2)) * 100 + Number.EPSILON) * 1000) / 1000;
  assert.equal(treasury.approximateYtmPercent(4.125, 99.878, '2028-06-30', ts), expected);
  assert.ok(expected > 4.1 && expected < 4.3);
  assert.equal(treasury.approximateYtmPercent(4.125, 99.878, '2028-06-30', Date.parse('2028-06-30T00:00:00.000Z')), null);
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
    originalTermYears: 30, maturityDate: '2056-05-15', officialSeedCleanPrice: 99.292811,
    simulated: true, source: 'simulated-us-treasury-auction-seed'
  };
  main.state.prices.set(seeded.ticker, seeded);
  main.state.treasuries.set(seeded.ticker, seeded);

  const payload = main.toPayload(seeded);
  assert.equal(payload.priceSemantics, 'CLEAN_FRACTION_OF_PAR');
  assert.equal(payload.cleanPrice, payload.price);
  assert.ok(payload.price > 0.9 && payload.price < 1.1, 'price is a fraction of par, not a percent');
  assert.equal(payload.asOf, payload.quoteTimestamp);
  assert.ok(Number.isFinite(payload.approximateYtmPercent));

  const buf = main.encodeBinaryTick(seeded);
  assert.equal(buf.readBigInt64BE(0), 992930n); // six decimals preserved, not 993000n
});
