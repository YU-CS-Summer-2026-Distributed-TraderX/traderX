import { Injectable } from '@nestjs/common';
import { loadCsvData } from '../data-loader/load-csv-data';
import { Stock } from './stock.model';
import { SecurityControlStore } from './security-control.store';

@Injectable()
export class StocksService {
  private readonly stocks: Promise<Stock[]>;

  constructor(private readonly controls: SecurityControlStore) {
    const supportedTickers = this.parseSupportedTickers(
      process.env.REFERENCE_DATA_SUPPORTED_TICKERS
    );
    const maxTickers = this.parsePositiveInt(process.env.REFERENCE_DATA_MAX_TICKERS);
    this.stocks = loadCsvData({ supportedTickers, maxTickers });
  }

  private async initializeControls() {
    await this.controls.initialize((await this.stocks).map((stock) => stock.ticker));
  }

  async findAll(): Promise<Stock[]> {
    return this.stocks;
  }

  async findByTicker(ticker: string): Promise<Stock | undefined> {
    return (await this.stocks).find((stock) => stock.ticker === ticker);
  }

  async controlSnapshot() {
    await this.initializeControls();
    return this.controls.snapshot();
  }

  async controlDeltas(after: number) {
    await this.initializeControls();
    return this.controls.deltas(after);
  }

  async updateSecurity(ticker: string, enabled: boolean, halted: boolean,
                       expectedVersion: number, operator: string) {
    await this.initializeControls();
    return this.controls.mutate(ticker, enabled, halted, expectedVersion, operator);
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
