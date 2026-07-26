import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { loadCsvData } from './load-csv-data';

// Tests the CSV loader against the bundled ./data/s-and-p-500-companies.csv (deterministic, in-repo).
// The loader carries the real domain rules the reference service depends on: the FB->META rename,
// per-ticker dedup, the supported-tickers allow-list, and the max-tickers cap. A regression in any
// of these silently changes which securities the whole platform will accept orders for.

test('every returned ticker is unique and never the pre-rename FB symbol', async () => {
  const stocks = await loadCsvData();

  const tickers = stocks.map((s) => s.ticker);
  assert.equal(new Set(tickers).size, tickers.length, 'tickers must be de-duplicated');
  assert.ok(!tickers.includes('FB'), 'FB must be remapped to META, never surfaced raw');
});

test('supportedTickers acts as an allow-list', async () => {
  const stocks = await loadCsvData({ supportedTickers: new Set(['AAPL', 'IBM']) });

  assert.ok(stocks.length > 0, 'expected the allow-listed tickers to be present');
  for (const stock of stocks) {
    assert.ok(['AAPL', 'IBM'].includes(stock.ticker), `unexpected ticker leaked: ${stock.ticker}`);
  }
});

test('maxTickers caps the result size', async () => {
  const stocks = await loadCsvData({ maxTickers: 5 });

  assert.ok(stocks.length <= 5, `expected <= 5, got ${stocks.length}`);
});

test('supplemental banks are included even if absent from the CSV (e.g. MS)', async () => {
  const stocks = await loadCsvData({ supportedTickers: new Set(['MS']) });

  assert.deepEqual(
    stocks.find((s) => s.ticker === 'MS'),
    { ticker: 'MS', companyName: 'Morgan Stanley' },
  );
});
