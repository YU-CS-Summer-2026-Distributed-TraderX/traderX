import { Body, ConflictException, Controller, Get, Headers, NotFoundException,
  Param, Post, Query, UnauthorizedException } from '@nestjs/common';
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

  @Post('control/security/:ticker')
  async updateSecurity(@Param('ticker') ticker: string,
      @Headers('x-risk-control-token') token: string,
      @Headers('x-risk-operator') operator: string,
      @Body() body: { enabled: boolean; halted: boolean; expectedVersion: number }) {
    if (token !== (process.env.RISK_CONTROL_TOKEN ?? 'dev-risk-control') || !operator?.trim()) {
      throw new UnauthorizedException('invalid risk-control credentials');
    }
    try {
      return await this.stocksService.updateSecurity(ticker, body.enabled, body.halted,
        body.expectedVersion, operator.trim());
    } catch (failure) {
      const message = String((failure as Error).message ?? failure);
      if (message.startsWith('stale expectedVersion=')) throw new ConflictException(message);
      if (message.startsWith('unknown authoritative security:')) throw new NotFoundException(message);
      throw failure;
    }
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
