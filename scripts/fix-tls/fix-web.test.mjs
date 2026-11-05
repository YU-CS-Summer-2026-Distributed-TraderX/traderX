import test from 'node:test';
import assert from 'node:assert/strict';
import { orderArgs, markFor } from './fix-web.mjs';

test('FR-FTL-09: only a well-formed order becomes fix-order.sh arguments', () => {
  assert.deepEqual(orderArgs({ account: '65002', side: 'Buy', qty: '10', ticker: 'MSFT', px: '' }), ['65002', 'Buy', '10', 'MSFT']);
  assert.deepEqual(orderArgs({ account: 22214, side: 'Sell', qty: 5, ticker: 'AAPL', px: '245.24' }), ['22214', 'Sell', '5', 'AAPL', '245.24']);
  for (const bad of [{ account: '1; rm -rf ~', side: 'Buy', qty: 1, ticker: 'MSFT' }, { account: 1, side: 'Hold', qty: 1, ticker: 'MSFT' },
    { account: 1, side: 'Buy', qty: 0, ticker: 'MSFT' }, { account: 1, side: 'Buy', qty: 1, ticker: 'ms ft' },
    { account: 1, side: 'Buy', qty: 1, ticker: 'MSFT', px: '1e9' }, null])
    assert.equal(orderArgs(bad), null, JSON.stringify(bad));
});

test('FR-FTL-09: "market" prices at the engine mark, and refuses a ticker with none', () => {
  const bbo = { books: [{ ticker: 'MSFT', mark: 392.13 }, { ticker: 'IBM', mark: 187.2150001 }, { ticker: 'ZERO', mark: 0 }] };
  assert.equal(markFor(bbo, 'MSFT'), '392.13');
  assert.equal(markFor(bbo, 'IBM'), '187.215');
  assert.equal(markFor(bbo, 'ZERO'), null);
  assert.equal(markFor(bbo, 'NOPE'), null);
  assert.equal(markFor(null, 'MSFT'), null);
});
