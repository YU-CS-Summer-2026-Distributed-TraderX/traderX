import { Body, Controller, Get, NotFoundException, Param, Post } from '@nestjs/common';
import { ControlSnapshot } from './control-snapshot';
import { Stock } from './stock.model';
import { StocksService } from './stocks.service';

interface CreateStockBody {
  ticker: string;
  companyName: string;
}

@Controller('stocks')
export class StocksController {
  constructor(private readonly stocksService: StocksService) {}

  @Get()
  async findAll(): Promise<Stock[]> {
    return this.stocksService.findAll();
  }

  /** New, additive endpoint (ADR-021) — the plain array endpoint above is unchanged. */
  @Get('control-snapshot')
  async getControlSnapshot(): Promise<ControlSnapshot> {
    return this.stocksService.snapshot();
  }

  @Get(':ticker')
  async findByTicker(@Param('ticker') ticker: string): Promise<Stock> {
    const stock = await this.stocksService.findByTicker(ticker);
    if (!stock) {
      throw new NotFoundException(`Stock ticker "${ticker}" not found.`);
    }
    return stock;
  }

  /** New (ADR-021, contract-delta.md #2) — reference-data's first write path. */
  @Post()
  async create(@Body() body: CreateStockBody): Promise<Stock> {
    return this.stocksService.create(body.ticker, body.companyName);
  }
}
