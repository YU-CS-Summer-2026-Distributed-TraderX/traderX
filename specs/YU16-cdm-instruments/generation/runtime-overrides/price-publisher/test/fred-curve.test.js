// YU17 (ADR-068): the runnable check for the external curve. The load-bearing assertions are the
// two that would fail SILENTLY in production — a copyright-marked series slipping through the
// licence gate, and a FRED "." missing value being read as a zero yield (which prices every bond
// at par and looks entirely plausible). The rest is the synthetic fall-through, which is rule 1.
const test = require('node:test');
const assert = require('node:assert/strict');
const fred = require('../src/fred-curve');
const treasury = require('../src/treasury-pricing');

/** Stand in for the two FRED endpoints. `notes` and `observations` are per series id. */
function stubFred({ notes = {}, observations = {} }) {
  globalThis.fetch = async (url) => {
    const id = new URL(url).searchParams.get('series_id');
    const body = url.includes('/fred/series?')
      ? { seriess: [{ id, notes: notes[id] ?? '' }] }
      : { observations: observations[id] ?? [] };
    return { ok: true, status: 200, json: async () => body };
  };
}

function reset() {
  fred.state.points.clear();
  fred.state.licence.clear();
  fred.state.asOf = null;
  fred.state.lastError = null;
}

const obs = (date, value) => ({ date, value: String(value) });

test('no curve at all means null, which is the synthetic fall-through (ADR-068 rule 1)', () => {
  reset();
  assert.equal(fred.yieldForYears(10), null);
  assert.equal(fred.status().provider, 'none');
  assert.equal(fred.status().enabled, false);
});

test('a copyright-marked series is refused and contributes no point (obligation 2)', async () => {
  reset();
  stubFred({
    notes: {
      DGS10: 'Series from H.15. Board of Governors of the Federal Reserve System.',
      DGS30: 'Copyright, 2016, Moody\'s Investors Service. Reproduction is prohibited.'
    },
    observations: { DGS10: [obs('2026-08-21', 4.2)], DGS30: [obs('2026-08-21', 4.9)] }
  });
  await fred.refresh();
  assert.equal(fred.state.licence.get('DGS10'), 'ok');
  assert.equal(fred.state.licence.get('DGS30'), 'copyrighted');
  assert.equal(fred.state.points.has(10), true);
  // The refusal is the point: a marked series must not reach the curve even though its
  // observations fetched perfectly well.
  assert.equal(fred.state.points.has(30), false);
});

test('a FRED "." missing value is skipped, never read as a zero yield', async () => {
  reset();
  stubFred({
    observations: {
      // Weekend/holiday shape: the newest rows are missing, the real number is further back.
      DGS10: [obs('2026-08-23', '.'), obs('2026-08-22', '.'), obs('2026-08-21', 4.2)],
      DGS2: [obs('2026-08-21', '.')]
    }
  });
  await fred.refresh();
  assert.equal(fred.state.points.get(10).yieldPercent, 4.2);
  assert.equal(fred.state.points.get(10).date, '2026-08-21');
  // Every value missing means NO point, not a 0.0 point.
  assert.equal(fred.state.points.has(2), false);
});

test('a failed poll keeps the last good curve rather than dropping to synthetic (open question 3)', async () => {
  reset();
  stubFred({ observations: { DGS10: [obs('2026-08-21', 4.2)] } });
  await fred.refresh();
  globalThis.fetch = async () => { throw new Error('network down'); };
  await fred.refresh();
  assert.equal(fred.state.points.get(10).yieldPercent, 4.2);
  assert.match(fred.state.lastError, /network down/);
});

test('interpolation is linear between tenors and flat outside them', () => {
  reset();
  fred.state.points.set(2, { yieldPercent: 3.0, date: '2026-08-21' });
  fred.state.points.set(10, { yieldPercent: 4.0, date: '2026-08-21' });
  assert.equal(fred.yieldForYears(2), 3.0);
  assert.equal(fred.yieldForYears(6), 3.5);
  assert.equal(fred.yieldForYears(10), 4.0);
  assert.equal(fred.yieldForYears(0.5), 3.0);   // flat below the short end
  assert.equal(fred.yieldForYears(30), 4.0);    // flat beyond the long end
});

test('a curve yield prices through the EXISTING model and round-trips back out of it', () => {
  reset();
  fred.state.points.set(10, { yieldPercent: 4.375, date: '2026-08-21' });
  const bond = {
    couponRatePercent: 4.375,
    issueDate: '2026-05-15',
    maturityDate: '2036-05-15',
    dayCount: treasury.DAY_COUNT.ACT_ACT_ICMA
  };
  const settle = new Date('2026-08-23T00:00:00.000Z');
  const clean = treasury.cleanPriceFromYield(bond, settle, fred.yieldForYears(10),
    bond.dayCount);
  // Yield == coupon on a semiannual bond basis is par, which is the sanity anchor for the whole
  // composition: if the curve, the day count or the solve were on different bases this misses.
  assert.ok(Math.abs(clean - 100) < 0.01, `clean ${clean} should be ~par`);
  // And the publisher's own solve returns the yield we put in — the wire YTM IS the curve yield.
  const back = treasury.ytmPercent(bond, settle.getTime(), clean, bond.dayCount);
  assert.ok(Math.abs(back - 4.375) < 1e-4, `solved ${back} should be the curve's 4.375`);
});

test('a long zero prices far below par on the same curve, a short bill near it', () => {
  reset();
  fred.state.points.set(0.25, { yieldPercent: 4.0, date: '2026-08-21' });
  fred.state.points.set(30, { yieldPercent: 5.0, date: '2026-08-21' });
  const settle = new Date('2026-08-23T00:00:00.000Z');
  const bill = { couponRatePercent: 0, issueDate: '2026-08-13', maturityDate: '2026-11-12',
    dayCount: treasury.DAY_COUNT.ACT_ACT_ICMA };
  const strip = { couponRatePercent: 0, issueDate: '2026-08-13', maturityDate: '2056-05-15',
    dayCount: treasury.DAY_COUNT.ACT_ACT_ICMA };
  const billClean = treasury.cleanPriceFromYield(bill, settle, fred.yieldForYears(0.22), bill.dayCount);
  const stripClean = treasury.cleanPriceFromYield(strip, settle, fred.yieldForYears(29.7), strip.dayCount);
  assert.ok(billClean > 98 && billClean < 100, `bill ${billClean} should sit just under par`);
  assert.ok(stripClean > 15 && stripClean < 30, `30Y strip ${stripClean} should be a deep discount`);
});
