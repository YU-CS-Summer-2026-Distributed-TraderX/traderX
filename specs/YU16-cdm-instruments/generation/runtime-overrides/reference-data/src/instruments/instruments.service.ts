import { Injectable } from '@nestjs/common';
import { ControlSnapshot } from '../stocks/control-snapshot';
import { StocksService } from '../stocks/stocks.service';
import { toInstrument } from './cdm-catalog';
import { Instrument } from './instrument.model';

/**
 * YU16: the CDM view over the SAME universe the stocks module owns (FR-CDM01). Membership is
 * the DB `stocks` table — one store, two views — so a key created at runtime through
 * `POST /stocks` appears here too (default-classified), and the two views can only 404
 * together. The snapshot is the stocks snapshot, verbatim (FR-CDM11): same store, same
 * watermark, same handler contract.
 */
@Injectable()
export class InstrumentsService {
  constructor(private readonly stocksService: StocksService) {}

  async findAll(): Promise<Instrument[]> {
    const stocks = await this.stocksService.findAll();
    return stocks.map((stock) => toInstrument(stock.ticker, stock.companyName));
  }

  async findByInstrumentKey(instrumentKey: string): Promise<Instrument | undefined> {
    const normalized = String(instrumentKey ?? '').trim().toUpperCase();
    if (!normalized) {
      return undefined;
    }
    const stock = await this.stocksService.findByTicker(normalized);
    if (!stock) {
      return undefined;
    }
    return toInstrument(stock.ticker, stock.companyName);
  }

  async snapshot(): Promise<ControlSnapshot> {
    return this.stocksService.snapshot();
  }
}
