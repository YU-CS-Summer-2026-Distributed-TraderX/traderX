import { Pool, PoolConnection } from 'mysql2/promise';
import { SourceEpochRepository } from './source-epoch.repository';
import { StocksControlOutboxRepository } from './stocks-control-outbox.repository';
import { StocksRepository } from './stocks.repository';
import { StocksService } from './stocks.service';

/**
 * Proves `StocksService.create` orchestrates one transaction across the `stocks` write and the
 * outbox insert (ADR-021) using a fake connection that records which transaction primitives were
 * called — this tests OUR orchestration code (begin/commit/rollback in the right places), not
 * MariaDB's own ACID guarantee, which is a given. No real database is needed; a real MariaDB run
 * is exercised live in the isolated staging environment (see tasks.md T-56).
 */
describe('StocksService.create', () => {
  interface FakeConnection extends Pick<PoolConnection, 'execute' | 'beginTransaction' | 'commit' | 'rollback'> {
    release: jest.Mock;
  }

  function fakeConnection(onExecute?: (sql: string) => void): FakeConnection {
    let insertId = 0;
    return {
      execute: jest.fn(async (sql: string) => {
        onExecute?.(sql);
        if (sql.includes('stocks_control_outbox')) {
          insertId += 1;
          return [{ insertId }, []];
        }
        return [{}, []];
      }) as unknown as PoolConnection['execute'],
      beginTransaction: jest.fn().mockResolvedValue(undefined) as unknown as PoolConnection['beginTransaction'],
      commit: jest.fn().mockResolvedValue(undefined) as unknown as PoolConnection['commit'],
      rollback: jest.fn().mockResolvedValue(undefined) as unknown as PoolConnection['rollback'],
      release: jest.fn(),
    };
  }

  function buildService(connection: FakeConnection): StocksService {
    const pool = { getConnection: jest.fn().mockResolvedValue(connection) } as unknown as Pool;
    const stocksRepository = new StocksRepository(pool);
    const outboxRepository = new StocksControlOutboxRepository(pool);
    const epochRepository = new SourceEpochRepository(pool);
    return new StocksService(pool, stocksRepository, outboxRepository, epochRepository);
  }

  it('commits both the stocks write and the outbox insert in one transaction', async () => {
    const executedSql: string[] = [];
    const connection = fakeConnection((sql) => executedSql.push(sql));
    const service = buildService(connection);

    const result = await service.create('ibm', 'IBM Corp');

    expect(result).toEqual({ ticker: 'IBM', companyName: 'IBM Corp' });
    expect(connection.beginTransaction).toHaveBeenCalledTimes(1);
    expect(executedSql.some((sql) => sql.includes('insert into stocks '))).toBe(true);
    expect(executedSql.some((sql) => sql.includes('insert into stocks_control_outbox'))).toBe(true);
    expect(connection.commit).toHaveBeenCalledTimes(1);
    expect(connection.rollback).not.toHaveBeenCalled();
    expect(connection.release).toHaveBeenCalledTimes(1);
  });

  it('rolls back the stocks write too if the outbox insert fails', async () => {
    const connection = fakeConnection((sql) => {
      if (sql.includes('stocks_control_outbox')) {
        throw new Error('simulated outbox failure');
      }
    });
    const service = buildService(connection);

    await expect(service.create('ibm', 'IBM Corp')).rejects.toThrow('simulated outbox failure');

    expect(connection.commit).not.toHaveBeenCalled();
    expect(connection.rollback).toHaveBeenCalledTimes(1);
    expect(connection.release).toHaveBeenCalledTimes(1);
  });
});
