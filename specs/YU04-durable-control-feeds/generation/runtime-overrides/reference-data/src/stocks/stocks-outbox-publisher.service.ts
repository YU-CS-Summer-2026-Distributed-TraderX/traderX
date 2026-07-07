import { Inject, Injectable, Logger } from '@nestjs/common';
import { Interval } from '@nestjs/schedule';
import { CONTROL_FEED_PUBLISHER, ControlFeedPublisher } from './control-feed-publisher.interface';
import { SourceEpochRepository } from './source-epoch.repository';
import { StocksControlOutboxRepository } from './stocks-control-outbox.repository';

const SOURCE = 'security';
const BATCH_LIMIT = 100;

/**
 * Background poller (ADR-021): reads unpublished `stocks_control_outbox` rows in strictly
 * increasing version order and publishes each to the durable control feed. Runs off the BLP
 * thread entirely (order-matcher isn't even in this process) — NFR-IMRG04 is unaffected by
 * construction.
 */
@Injectable()
export class StocksOutboxPublisher {
  private static readonly logger = new Logger(StocksOutboxPublisher.name);
  private readonly enabled: boolean;

  constructor(
    private readonly outboxRepository: StocksControlOutboxRepository,
    private readonly epochRepository: SourceEpochRepository,
    @Inject(CONTROL_FEED_PUBLISHER) private readonly publisher: ControlFeedPublisher,
  ) {
    this.enabled = (process.env.SECURITY_OUTBOX_ENABLED ?? 'true') !== 'false';
  }

  @Interval(Number(process.env.SECURITY_OUTBOX_POLL_INTERVAL_MS ?? 250))
  async publishPending(): Promise<void> {
    if (!this.enabled) {
      return;
    }
    const rows = await this.outboxRepository.findUnpublished(BATCH_LIMIT);
    for (const row of rows) {
      try {
        const epoch = await this.epochRepository.currentEpoch();
        const payload = JSON.stringify({
          version: row.version,
          epoch,
          ticker: row.ticker,
          companyName: row.companyName,
        });
        await this.publisher.publish(`${SOURCE}:${row.version}`, payload);
        await this.outboxRepository.markPublished(row.version);
      } catch (err) {
        StocksOutboxPublisher.logger.warn(
          `Failed to publish security control outbox row version=${row.version} (will retry next poll): ${err}`,
        );
        break; // preserve strict version order; do not skip ahead on failure
      }
    }
  }
}
