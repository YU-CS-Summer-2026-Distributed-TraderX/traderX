import { Global, Module, OnModuleDestroy } from '@nestjs/common';
import * as mysql from 'mysql2/promise';

export const DATABASE_POOL = 'DATABASE_POOL';

/**
 * MariaDB connection pool for reference-data's new persistence layer (ADR-021) — reuses the same
 * shared instance/env-var names account-service already connects with (`DATABASE_PG_HOST` keeps
 * its historical "_PG_" naming from the pre-MariaDB era; kept as-is for k8s manifest consistency
 * across services, not renamed here).
 */
@Global()
@Module({
  providers: [
    {
      provide: DATABASE_POOL,
      useFactory: (): mysql.Pool =>
        mysql.createPool({
          host: process.env.DATABASE_PG_HOST ?? 'localhost',
          port: Number(process.env.DATABASE_PG_PORT ?? 3306),
          database: process.env.DATABASE_NAME ?? 'traderx',
          user: process.env.DATABASE_DBUSER ?? 'traderx',
          password: process.env.DATABASE_DBPASS ?? 'traderx',
          waitForConnections: true,
          connectionLimit: 10,
        }),
    },
  ],
  exports: [DATABASE_POOL],
})
export class DatabaseModule implements OnModuleDestroy {
  constructor() {}

  async onModuleDestroy(): Promise<void> {
    // Pool teardown is handled per-test where a fake pool is used; the real pool is process-lifetime.
  }
}
