// YU15: the option-quote seam. Uses node:test — no test framework dependency added.
// Run with: npm test
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseOcc, blackScholes, quoteOption } = require('../src/option-quotes');

const IV = 0.25;
const RATE = 0.04;
const MODEL = { iv: IV, rate: RATE, minPremium: 0.01, round: (v) => Math.round(v * 1000) / 1000 };
const YEAR_MS = 365.25 * 24 * 60 * 60 * 1000;

test('parses an unpadded OCC symbol into its components', () => {
  const c = parseOcc('AAPL260918C00240000');
  assert.equal(c.root, 'AAPL');
  assert.equal(c.strike, 240);
  assert.equal(c.call, true);
  assert.equal(new Date(c.expiryMillis).toISOString().slice(0, 10), '2026-09-18');

  const put = parseOcc('MSFT261218P00390000');
  assert.equal(put.root, 'MSFT');
  assert.equal(put.strike, 390);
  assert.equal(put.call, false);
});

test('rejects anything that is not an OCC option symbol', () => {
  // An equity ticker must never be mistaken for a contract — it would be quoted off itself.
  for (const bad of ['AAPL', '', null, undefined, 'AAPL260918X00240000', 'AAPL261318C00240000',
                     'AAPL260900C00240000', 'aapl260918c00240000', 'TOOLONGROOT260918C00240000']) {
    assert.equal(parseOcc(bad), null, `expected null for ${bad}`);
  }
});

test('satisfies put-call parity', () => {
  // C - P = S - K*e^(-rT). If this drifts, the surface is internally inconsistent and no pricing
  // engine can reconcile against it.
  const S = 241.8;
  const K = 240;
  const T = 0.5;
  const call = blackScholes(S, K, T, true, IV, RATE);
  const put = blackScholes(S, K, T, false, IV, RATE);
  assert.ok(Math.abs((call - put) - (S - K * Math.exp(-RATE * T))) < 1e-6);
});

test('is never worth less than intrinsic value', () => {
  // A quote below intrinsic implies free arbitrage.
  const S = 241.8;
  for (const K of [180, 220, 240, 260, 300]) {
    assert.ok(blackScholes(S, K, 0.5, true, IV, RATE) >= Math.max(S - K, 0) - 1e-9);
    assert.ok(blackScholes(S, K, 0.5, false, IV, RATE) >= Math.max(K - S, 0) - 1e-9);
  }
});

test('a call is worth less as the strike rises; a put is worth more', () => {
  const S = 241.8;
  const call = (K) => blackScholes(S, K, 0.5, true, IV, RATE);
  const put = (K) => blackScholes(S, K, 0.5, false, IV, RATE);
  assert.ok(call(220) > call(240) && call(240) > call(260));
  assert.ok(put(220) < put(240) && put(240) < put(260));
});

test('an expired contract is worth exactly its intrinsic value', () => {
  const near = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9,
    `${actual} != ${expected}`);
  near(blackScholes(241.8, 240, 0, true, IV, RATE), 1.8);   // in the money
  near(blackScholes(241.8, 260, 0, true, IV, RATE), 0);     // out of the money, worthless
  near(blackScholes(241.8, 260, -1, false, IV, RATE), 18.2); // past expiry is still just intrinsic
});

test('quotes a contract off its underlying tick', () => {
  const now = Date.UTC(2026, 6, 22);
  const contract = parseOcc('AAPL260918C00240000');
  const underlying = { price: 241.8, openPrice: 240, closePrice: 241 };
  const quote = quoteOption('AAPL260918C00240000', contract, underlying, MODEL, now);

  assert.equal(quote.ticker, 'AAPL260918C00240000');
  assert.equal(quote.source, 'black-scholes');
  assert.ok(quote.price > 0);
  // Every leg is priced off the matching leg of the underlying, so a higher spot is a higher call.
  assert.ok(quote.price > quote.openPrice);
});

test('rises with the underlying, so calls and puts cannot contradict each other', () => {
  const now = Date.UTC(2026, 6, 22);
  const call = parseOcc('AAPL260918C00240000');
  const put = parseOcc('AAPL260918P00240000');
  const low = { price: 230, openPrice: 230, closePrice: 230 };
  const high = { price: 250, openPrice: 250, closePrice: 250 };

  assert.ok(quoteOption('c', call, high, MODEL, now).price > quoteOption('c', call, low, MODEL, now).price);
  assert.ok(quoteOption('p', put, high, MODEL, now).price < quoteOption('p', put, low, MODEL, now).price);
});

test('refuses to quote a contract whose underlying is unknown', () => {
  const contract = parseOcc('AAPL260918C00240000');
  assert.equal(quoteOption('AAPL260918C00240000', contract, undefined, MODEL, Date.now()), null);
  assert.equal(quoteOption('AAPL260918C00240000', null, { price: 241.8 }, MODEL, Date.now()), null);
});

test('floors a far out-of-the-money contract at the minimum premium', () => {
  const now = Date.UTC(2026, 6, 22);
  const contract = parseOcc('AAPL260918C00260000');
  const quote = quoteOption('x', contract, { price: 5, openPrice: 5, closePrice: 5 }, MODEL, now);
  assert.equal(quote.price, MODEL.minPremium);
});

test('a longer-dated contract is worth at least as much as a nearer one', () => {
  const now = Date.UTC(2026, 6, 22);
  const near = parseOcc('AAPL260918C00240000');
  const far = parseOcc('AAPL261218C00240000');
  const underlying = { price: 241.8, openPrice: 241.8, closePrice: 241.8 };
  assert.ok(quoteOption('f', far, underlying, MODEL, now).price
    >= quoteOption('n', near, underlying, MODEL, now).price);
  assert.ok((far.expiryMillis - now) / YEAR_MS > (near.expiryMillis - now) / YEAR_MS);
});
