import { Inject, Injectable } from '@nestjs/common';
import { Pool, PoolConnection, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { DATABASE_POOL } from '../database/database.module';

export interface OutboxRow {
  version: number;
  ticker: string;
  companyName: string;
  createdAt: Date;
}

interface OutboxRowRecord extends RowDataPacket {
  version: number;
  ticker: string;
  company_name: string;
  created_at: Date;
}

/**
 * Transactional-outbox table for security existence/identity changes (ADR-021). {@link
 * recordChange} must always be called with the SAME connection/transaction as the matching
 * `stocks` write — atomicity comes from that shared local transaction (see `StocksService.create`).
 */
@Injectable()
export class StocksControlOutboxRepository {
  constructor(@Inject(DATABASE_POOL) private readonly pool: Pool) {}

  async recordChange(conn: PoolConnection, ticker: string, companyName: string): Promise<number> {
    const [result] = await conn.execute<ResultSetHeader>(
      'insert into stocks_control_outbox (ticker, company_name, published, created_at) values (?, ?, false, ?)',
      [ticker, companyName, new Date()],
    );
    return result.insertId;
  }

  async findUnpublished(limit: number): Promise<OutboxRow[]> {
    const [rows] = await this.pool.query<OutboxRowRecord[]>(
      'select version, ticker, company_name, created_at from stocks_control_outbox '
        + 'where published = false order by version asc limit ?',
      [limit],
    );
    return rows.map((row) => ({
      version: row.version,
      ticker: row.ticker,
      companyName: row.company_name,
      createdAt: row.created_at,
    }));
  }

  async markPublished(version: number): Promise<void> {
    await this.pool.execute('update stocks_control_outbox set published = true where version = ?', [version]);
  }

  /** Highest version already published — the watermark exposed by the snapshot endpoint. */
  async publishedWatermark(): Promise<number> {
    const [rows] = await this.pool.query<RowDataPacket[]>(
      'select coalesce(max(version), 0) as watermark from stocks_control_outbox where published = true',
    );
    return Number(rows[0].watermark);
  }

  async unpublishedCount(): Promise<number> {
    const [rows] = await this.pool.query<RowDataPacket[]>(
      'select count(*) as cnt from stocks_control_outbox where published = false',
    );
    return Number(rows[0].cnt);
  }
}
