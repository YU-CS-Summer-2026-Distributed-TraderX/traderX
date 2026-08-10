import { Module } from '@nestjs/common';
import { ScheduleModule } from '@nestjs/schedule';
import { DatabaseModule } from './database/database.module';
import { HealthModule } from './health/health.module';
import { InstrumentsModule } from './instruments/instruments.module';
import { StocksModule } from './stocks/stocks.module';

// YU16: InstrumentsModule added BESIDE StocksModule — /stocks is retained (FR-CDM09, ADR-058).
@Module({
  imports: [ScheduleModule.forRoot(), DatabaseModule, StocksModule, InstrumentsModule, HealthModule],
})
export class AppModule {}
