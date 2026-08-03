import { Module } from '@nestjs/common';
import { ScheduleModule } from '@nestjs/schedule';
import { DatabaseModule } from './database/database.module';
import { HealthModule } from './health/health.module';
import { StocksModule } from './stocks/stocks.module';

@Module({
  imports: [ScheduleModule.forRoot(), DatabaseModule, StocksModule, HealthModule],
})
export class AppModule {}
