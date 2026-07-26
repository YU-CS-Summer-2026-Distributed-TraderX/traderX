import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { NotFoundException } from '@nestjs/common';
import { StocksController } from './stocks.controller';
import { StocksService } from './stocks.service';
import { Stock } from './stock.model';

// Unit tests for the reference-data lookup controller — no Nest DI, no CSV load. The service is a
// hand stub. The load-bearing path is the miss: an unknown ticker must raise NotFoundException
// (which Nest maps to HTTP 404), not return undefined that serializes to an empty 200 body — a
// silent "this ticker exists but is empty" is exactly the failure this reference lookup guards.

function serviceStub(byTicker: Record<string, Stock>): StocksService {
  return {
    findAll: async () => Object.values(byTicker),
    findByTicker: async (ticker: string) => byTicker[ticker],
  } as StocksService;
}

test('findByTicker returns the stock when known', async () => {
  const aapl: Stock = { ticker: 'AAPL', companyName: 'Apple Inc.' };
  const controller = new StocksController(serviceStub({ AAPL: aapl }));

  assert.deepEqual(await controller.findByTicker('AAPL'), aapl);
});

test('findByTicker throws NotFoundException naming the ticker when unknown', async () => {
  const controller = new StocksController(serviceStub({}));

  await assert.rejects(
    () => controller.findByTicker('ZZZZ'),
    (err: unknown) => {
      assert.ok(err instanceof NotFoundException);
      assert.match((err as Error).message, /ZZZZ/);
      return true;
    },
  );
});

test('findAll passes the service list through', async () => {
  const list: Stock[] = [
    { ticker: 'AAPL', companyName: 'Apple Inc.' },
    { ticker: 'IBM', companyName: 'International Business Machines' },
  ];
  const controller = new StocksController(serviceStub(Object.fromEntries(list.map((s) => [s.ticker, s]))));

  assert.deepEqual(await controller.findAll(), list);
});
