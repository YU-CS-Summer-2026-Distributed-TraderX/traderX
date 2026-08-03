import { Inject, Injectable, OnModuleInit } from '@nestjs/common';
import { Pool, RowDataPacket } from 'mysql2/promise';
import { DATABASE_POOL } from '../database/database.module';

/**
 * The security source epoch (ADR-021/ADR-019): a single row, seeded to 1 on first boot, bumped
 * only for a deliberate unrecoverable resync — never by normal operation.
 */
@Injectable()
export class SourceEpochRepository implements OnModuleInit {
  constructor(@Inject(DATABASE_POOL) private readonly pool: Pool) {}

  async onModuleInit(): Promise<void> {
    const [rows] = await this.pool.query<RowDataPacket[]>('select count(*) as cnt from stocks_source_epoch');
    if (Number(rows[0].cnt) === 0) {
      await this.pool.execute('insert into stocks_source_epoch (epoch) values (1)');
    }
  }

  async currentEpoch(): Promise<number> {
    const [rows] = await this.pool.query<RowDataPacket[]>('select epoch from stocks_source_epoch limit 1');
    return rows.length > 0 ? Number(rows[0].epoch) : 1;
  }
}
