import { Controller, Get, NotFoundException, Param, Query } from '@nestjs/common';
import { Stock } from './stock.model';
import { StocksService } from './stocks.service';

@Controller('stocks')
export class StocksController {
  constructor(private readonly stocksService: StocksService) {}

  @Get('control/snapshot')
  async controlSnapshot() {
    return this.stocksService.controlSnapshot();
  }

  @Get('control/deltas')
  async controlDeltas(@Query('after') after = '0') {
    const version = Number.parseInt(after, 10);
    return this.stocksService.controlDeltas(Number.isFinite(version) ? version : 0);
  }

  @Get()
  async findAll(): Promise<Stock[]> {
    return this.stocksService.findAll();
  }

  @Get(':ticker')
  async findByTicker(@Param('ticker') ticker: string): Promise<Stock> {
    const stock = await this.stocksService.findByTicker(ticker);
    if (!stock) {
      throw new NotFoundException(`Stock ticker "${ticker}" not found.`);
    }
    return stock;
  }
}
