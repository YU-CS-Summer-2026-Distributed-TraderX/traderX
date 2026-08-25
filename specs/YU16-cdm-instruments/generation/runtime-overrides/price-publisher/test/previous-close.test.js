// YU17 (ADR-069): the runnable check for the previous-close rung.
//
// The load-bearing assertions are the ones that would fail SILENTLY in production, because every
// failure mode here produces a completely plausible price:
//
//   1. a DRAFT accepted as an open (rule 2's entire content, and it looks identical to a PUBLISHED
//      one from every price it produces);
//   2. a MISSING mark read as a close of zero, because `Number(null)` is 0 rather than NaN;
//   3. a bond opened at its close while the walk's anchor stays on the static seed, which drags
//      the price back within a few ticks -- continuity that exists for one instant;
//   4. a failed read that leaves /health unable to say it failed.
//
// Everything else here is rule 1's fall-through: no network, no trade-processor, still opens.
const test = require('node:test');
const assert = require('node:assert/strict');
const previousClose = require('../src/previous-close');
const treasury = require('../src/treasury-pricing');

const TOKEN = 'header.payload.signature';

/** Stand in for trade-processor: the dev-token mint plus the previous-session read. */
function stubTradeProcessor({ session = null, sessionStatus = 404, tokenStatus = 200 }) {
  globalThis.fetch = async (url) => {
    if (String(url).includes('/auth/dev-token')) {
      return { ok: tokenStatus === 200, status: tokenStatus, text: async () => TOKEN };
    }
    return {
      ok: sessionStatus >= 200 && sessionStatus < 300,
      status: sessionStatus,
      json: async () => session
    };
  };
}

const published = (instruments) => ({
  sessionDate: '2026-08-24',
  version: 3,
  status: 'PUBLISHED',
  instrumentCount: instruments.length,
  flaggedCount: 0,
  instruments
});

const priced = (security, closingPrice, quality = 'OK') =>
  ({ security, closingPrice, quality, sourceTickMillis: 1, overrideReason: null });

test('a PUBLISHED session becomes the opening closes, and /health names it', async () => {
  stubTradeProcessor({
    sessionStatus: 200,
    session: published([priced('AAPL', 246.636), priced('UST-20280630', 0.999300)])
  });
  const closes = await previousClose.load('2026-08-25');
  assert.equal(closes.get('AAPL'), 246.636);
  assert.equal(closes.get('UST-20280630'), 0.9993);

  const status = previousClose.status();
  assert.equal(status.attempted, true);
  assert.equal(status.error, null);
  assert.deepEqual(status.previousSession,
    { sessionDate: '2026-08-24', version: 3, status: 'PUBLISHED', instrumentCount: 2 });
});

test('a DRAFT is REFUSED even when the server hands one over (rule 2)', async () => {
  // Rule 2 is resolved server-side; this is the client's own refusal. A DRAFT carries known-bad
  // marks, and an open taken from one is indistinguishable from a correct one by price alone --
  // which is exactly why the refusal has to be an assertion rather than an assumption.
  stubTradeProcessor({
    sessionStatus: 200,
    session: { ...published([priced('AAPL', 999.999)]), status: 'DRAFT' }
  });
  assert.equal(await previousClose.load('2026-08-25'), null);
  const status = previousClose.status();
  assert.equal(status.previousSession, null);
  assert.match(status.error, /not PUBLISHED/);
});

test('a MISSING mark is skipped, not opened at zero (Number(null) === 0)', async () => {
  stubTradeProcessor({
    sessionStatus: 200,
    session: published([priced('AAPL', 246.636), priced('QLTY', null, 'MISSING')])
  });
  const closes = await previousClose.load('2026-08-25');
  assert.equal(closes.has('QLTY'), false);
  assert.equal(closes.size, 1);
});

test('a PUBLISHED session with no usable close is refused, loudly', async () => {
  stubTradeProcessor({
    sessionStatus: 200,
    session: published([priced('QLTY', null, 'MISSING')])
  });
  assert.equal(await previousClose.load('2026-08-25'), null);
  assert.match(previousClose.status().error, /no usable close/);
});

test('404 means no close exists yet, and says so rather than going quiet', async () => {
  stubTradeProcessor({ sessionStatus: 404 });
  assert.equal(await previousClose.load('2026-08-25'), null);
  const status = previousClose.status();
  assert.equal(status.attempted, true);
  assert.equal(status.previousSession, null);
  assert.match(status.error, /no published session strictly before 2026-08-25/);
});

test('an unreachable trade-processor opens from the seed and reports why (ADR-068 rule 1)', async () => {
  globalThis.fetch = async () => { throw new Error('connect ECONNREFUSED'); };
  assert.equal(await previousClose.load('2026-08-25'), null);
  const status = previousClose.status();
  assert.equal(status.previousSession, null);
  assert.match(status.error, /ECONNREFUSED/);
});

test('a rejected token is a failure, not an unauthenticated read', async () => {
  stubTradeProcessor({ tokenStatus: 401, sessionStatus: 200, session: published([priced('AAPL', 1)]) });
  assert.equal(await previousClose.load('2026-08-25'), null);
  assert.match(previousClose.status().error, /dev-token -> HTTP 401/);
});

// ---------------------------------------------------------------------------------------------
// The bond open, and the walk anchor that has to move with it.

const BOND = Object.freeze({
  ticker: 'UST-20280630',
  originalTermYears: 2,          // profile: maxStep 0.005, maxDistance 0.15 percent-of-par
  cleanPercent: 99.878,
  seedCleanPercent: 99.878,      // the 2026 auction seed
  officialSeedCleanPrice: 99.878432
});

test('no close leaves the bond on its seed', () => {
  assert.equal(previousClose.openBondFromClose(BOND, undefined), null);
  assert.equal(previousClose.openBondFromClose(BOND, 0), null);
  assert.equal(previousClose.openBondFromClose(BOND, Number.NaN), null);
});

test('a bond opens at its close in BOTH spaces, and the auction static is untouched', () => {
  const opened = previousClose.openBondFromClose(BOND, 0.999300);
  assert.equal(opened.cleanPercent, 99.93);
  assert.equal(opened.price, 0.9993);
  assert.equal(opened.openPrice, 0.9993);
  assert.equal(opened.closePrice, 0.9993);
  // A static fact about the instrument, not a walk parameter.
  assert.equal(opened.officialSeedCleanPrice, 99.878432);
});

test('the walk anchor MOVES to the open, or the close is erased within a few ticks', () => {
  // The close is 0.60 percent-of-par above the static seed -- four times the 2Y profile's
  // maxDistance of 0.15. If seedCleanPercent had stayed on the seed, the very first call would
  // clamp straight back into [99.728, 100.028] and the continuity would be gone with nothing
  // failing. This is the whole reason the anchor moves.
  const opened = previousClose.openBondFromClose(BOND, 1.004780);
  assert.equal(opened.seedCleanPercent, 100.478);

  let quote = opened;
  let low = quote.cleanPercent;
  let high = quote.cleanPercent;
  for (let i = 0; i < 500; i += 1) {
    // Drive it hard in one direction: a pure mean-reverting walk would still wander, and the
    // claim is about the CLAMP, so push every step the same way.
    quote = { ...quote, cleanPercent: treasury.updateTreasuryCleanPrice(quote, 1, 1) };
    low = Math.min(low, quote.cleanPercent);
    high = Math.max(high, quote.cleanPercent);
  }
  assert.ok(high <= 100.478 + 0.15 + 1e-9, `walked above the close's band: ${high}`);
  assert.ok(low >= 100.478 - 0.15 - 1e-9, `walked below the close's band: ${low}`);
  // And it never fell back toward the static seed, which is the failure this test exists for.
  assert.ok(low > 99.878 + 0.15, `walk returned to the static seed's neighbourhood: ${low}`);
});
