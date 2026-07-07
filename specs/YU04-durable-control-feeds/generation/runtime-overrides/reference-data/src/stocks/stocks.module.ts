import { Module } from '@nestjs/common';
import { CONTROL_FEED_PUBLISHER } from './control-feed-publisher.interface';
import { JetStreamControlFeedPublisher } from './jetstream-control-feed-publisher';
import { SourceEpochRepository } from './source-epoch.repository';
import { StocksControlOutboxRepository } from './stocks-control-outbox.repository';
import { StocksController } from './stocks.controller';
import { StocksOutboxPublisher } from './stocks-outbox-publisher.service';
import { StocksRepository } from './stocks.repository';
import { StocksService } from './stocks.service';

@Module({
  controllers: [StocksController],
  providers: [
    StocksService,
    StocksRepository,
    StocksControlOutboxRepository,
    SourceEpochRepository,
    StocksOutboxPublisher,
    { provide: CONTROL_FEED_PUBLISHER, useClass: JetStreamControlFeedPublisher },
  ],
  exports: [StocksService],
})
export class StocksModule {}
