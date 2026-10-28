import { Inject, Injectable, Logger } from '@nestjs/common';
import { Interval } from '@nestjs/schedule';
import { CONTROL_FEED_PUBLISHER, ControlFeedPublisher } from './control-feed-publisher.interface';
import { SourceEpochRepository } from './source-epoch.repository';
import { StocksControlOutboxRepository } from './stocks-control-outbox.repository';

const SOURCE = 'security';
const BATCH_LIMIT = 100;
const AUDIT_INTERVAL_MS = Number(process.env.SECURITY_OUTBOX_AUDIT_INTERVAL_MS ?? 30000);

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
  private lastAudit = 0;

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
    if (Date.now() - this.lastAudit >= AUDIT_INTERVAL_MS) {
      this.lastAudit = Date.now();
      await this.auditStream();
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

  /**
   * FR-IMRG36: `published` is a claim that the stream holds the row, and a NATS store can be replaced or
   * wiped underneath it (an emptyDir restart, a new PVC). Checked on the first poll and every
   * SECURITY_OUTBOX_AUDIT_INTERVAL_MS: if the stream holds fewer messages than there are published rows, or
   * its last version is below the published watermark, every row is queued again and the poll above
   * republishes the universe in version order. Fewer messages catches a stream that was recreated and has
   * since taken newer deltas (its last version can equal the watermark while holding only those). A stream
   * AHEAD of the outbox (last version above the watermark: the database restored from an older dump) is not
   * repairable by republishing and is only reported.
   */
  async auditStream(): Promise<void> {
    try {
      const published = await this.outboxRepository.publishedCount();
      if (published === 0) {
        return;
      }
      const watermark = await this.outboxRepository.publishedWatermark();
      const held = await this.publisher.streamState();
      if (held.lastVersion > watermark) {
        StocksOutboxPublisher.logger.error(
          `ALERT control stream last version ${held.lastVersion} is ahead of the outbox watermark ${watermark}: `
            + 'the outbox was restored behind the stream; not republishing',
        );
        return;
      }
      if (held.messages >= published && held.lastVersion >= watermark) {
        return;
      }
      const queued = await this.outboxRepository.markAllUnpublished();
      StocksOutboxPublisher.logger.warn(
        `Control stream holds ${held.messages} messages (last version ${held.lastVersion}) but ${published} rows `
          + `are marked published (watermark ${watermark}); republishing the universe: ${queued} rows queued`,
      );
    } catch (err) {
      StocksOutboxPublisher.logger.warn(`Control stream audit failed (will retry next interval): ${err}`);
    }
  }
}
