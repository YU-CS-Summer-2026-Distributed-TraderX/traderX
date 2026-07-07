import { Inject, Injectable } from '@nestjs/common';
import { Pool, PoolConnection, RowDataPacket } from 'mysql2/promise';
import { DATABASE_POOL } from '../database/database.module';
import { Stock } from './stock.model';

interface StockRow extends RowDataPacket {
  ticker: string;
  company_name: string;
}

function toStock(row: StockRow): Stock {
  return { ticker: row.ticker, companyName: row.company_name };
}

@Injectable()
export class StocksRepository {
  constructor(@Inject(DATABASE_POOL) private readonly pool: Pool) {}

  async findAll(): Promise<Stock[]> {
    const [rows] = await this.pool.query<StockRow[]>('select ticker, company_name from stocks order by ticker');
    return rows.map(toStock);
  }

  async findByTicker(ticker: string): Promise<Stock | undefined> {
    const [rows] = await this.pool.query<StockRow[]>(
      'select ticker, company_name from stocks where ticker = ?',
      [ticker],
    );
    return rows.length > 0 ? toStock(rows[0]) : undefined;
  }

  async exists(ticker: string): Promise<boolean> {
    const [rows] = await this.pool.query<RowDataPacket[]>('select count(*) as cnt from stocks where ticker = ?', [
      ticker,
    ]);
    return Number(rows[0].cnt) > 0;
  }

  async count(): Promise<number> {
    const [rows] = await this.pool.query<RowDataPacket[]>('select count(*) as cnt from stocks');
    return Number(rows[0].cnt);
  }

  /** Must run within the SAME transaction/connection as the matching outbox insert (ADR-021). */
  async insert(conn: PoolConnection, ticker: string, companyName: string): Promise<void> {
    await conn.execute('insert into stocks (ticker, company_name) values (?, ?)', [ticker, companyName]);
  }
}
