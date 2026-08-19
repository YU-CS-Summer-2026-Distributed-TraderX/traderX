import { Inject, Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { DATABASE_POOL } from '../database/database.module';
import { loadCsvData } from '../data-loader/load-csv-data';
import { buildControlSnapshot, ControlSnapshot } from './control-snapshot';
import { SourceEpochRepository } from './source-epoch.repository';
import { Stock } from './stock.model';
import { StocksControlOutboxRepository } from './stocks-control-outbox.repository';
import { StocksRepository } from './stocks.repository';

@Injectable()
export class StocksService implements OnModuleInit {
  private static readonly logger = new Logger(StocksService.name);

  constructor(
    @Inject(DATABASE_POOL) private readonly pool: Pool,
    private readonly stocksRepository: StocksRepository,
    private readonly outboxRepository: StocksControlOutboxRepository,
    private readonly epochRepository: SourceEpochRepository,
  ) {}

  /**
   * Idempotent startup reconcile against the CSV catalog — replaces the old seed-only-if-empty,
   * which meant any row later deleted from `stocks` (or a catalog addition landing after first
   * boot) never reached a deployed environment. Every missing row is inserted together with its
   * outbox row in one transaction, so repairs flow through the durable feed like any other
   * change (ADR-021). Rows present in `stocks` but absent from the CSV are left alone: the
   * table is the mutable universe, the CSV is only its floor.
   */
  async onModuleInit(): Promise<void> {
    const supportedTickers = this.parseSupportedTickers(process.env.REFERENCE_DATA_SUPPORTED_TICKERS);
    const maxTickers = this.parsePositiveInt(process.env.REFERENCE_DATA_MAX_TICKERS);
    const seedStocks = await loadCsvData({ supportedTickers, maxTickers });
    const existing = new Set((await this.stocksRepository.findAll()).map((stock) => stock.ticker));
    const missing = seedStocks.filter((stock) => !existing.has(stock.ticker));
    if (missing.length === 0) {
      return;
    }

    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();
      for (const stock of missing) {
        await this.stocksRepository.insert(conn, stock.ticker, stock.companyName);
        await this.outboxRepository.recordChange(conn, stock.ticker, stock.companyName);
      }
      await conn.commit();
      StocksService.logger.log(
        `Reconciled ${missing.length} catalog rows from CSV (${existing.size} already present)`,
      );
    } catch (err) {
      await conn.rollback();
      throw err;
    } finally {
      conn.release();
    }
  }

  async findAll(): Promise<Stock[]> {
    return this.stocksRepository.findAll();
  }

  async findByTicker(ticker: string): Promise<Stock | undefined> {
    return this.stocksRepository.findByTicker(ticker);
  }

  /**
   * Adds a ticker to the tradable universe (`reference-data`'s first write path) and records it
   * in the durable control outbox in the SAME transaction (ADR-021) — the two rows can never
   * diverge, since either both commit or neither does.
   */
  async create(ticker: string, companyName: string): Promise<Stock> {
    const normalizedTicker = ticker.trim().toUpperCase();
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();
      await this.stocksRepository.insert(conn, normalizedTicker, companyName);
      await this.outboxRepository.recordChange(conn, normalizedTicker, companyName);
      await conn.commit();
    } catch (err) {
      await conn.rollback();
      throw err;
    } finally {
      conn.release();
    }
    return { ticker: normalizedTicker, companyName };
  }

  async snapshot(): Promise<ControlSnapshot> {
    const [stocks, watermark, epoch] = await Promise.all([
      this.stocksRepository.findAll(),
      this.outboxRepository.publishedWatermark(),
      this.epochRepository.currentEpoch(),
    ]);
    return buildControlSnapshot(epoch, watermark, stocks);
  }

  private parseSupportedTickers(input?: string): Set<string> | undefined {
    const raw = String(input ?? '').trim();
    if (!raw) {
      return undefined;
    }
    const tickers = raw
      .split(',')
      .map((ticker) => ticker.trim().toUpperCase())
      .filter(Boolean);
    if (tickers.length === 0) {
      return undefined;
    }
    return new Set(tickers);
  }

  private parsePositiveInt(input?: string): number | undefined {
    const parsed = Number(input);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      return undefined;
    }
    return parsed;
  }
}
