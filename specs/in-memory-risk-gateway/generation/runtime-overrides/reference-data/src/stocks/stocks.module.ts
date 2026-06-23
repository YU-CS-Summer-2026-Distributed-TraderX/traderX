import { Module } from '@nestjs/common';
import { SecurityControlStore } from './security-control.store';
import { StocksController } from './stocks.controller';
import { StocksService } from './stocks.service';

@Module({
  controllers: [StocksController],
  providers: [StocksService, SecurityControlStore],
  exports: [StocksService],
})
export class StocksModule {}
