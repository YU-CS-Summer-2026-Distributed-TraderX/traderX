import { Module } from '@nestjs/common';
import { StocksModule } from '../stocks/stocks.module';
import { InstrumentsController } from './instruments.controller';
import { InstrumentsService } from './instruments.service';

/** YU16: the CDM view beside the retained stocks module — one store, two views (ADR-058). */
@Module({
  imports: [StocksModule],
  controllers: [InstrumentsController],
  providers: [InstrumentsService],
})
export class InstrumentsModule {}
