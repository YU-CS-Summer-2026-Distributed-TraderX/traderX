import { ControlFeedPublisher } from './control-feed-publisher.interface';
import { SourceEpochRepository } from './source-epoch.repository';
import { OutboxRow, StocksControlOutboxRepository } from './stocks-control-outbox.repository';
import { StocksOutboxPublisher } from './stocks-outbox-publisher.service';

/**
 * Exercises the poll-and-publish logic directly with fakes — no real DB or NATS broker needed —
 * mirroring the account-service side's `AccountOutboxPublisherTest` (a `FakePublisher`, not a
 * mocked NATS client), and this project's existing convention of isolating real-broker behavior
 * behind a thin, separately-verified adapter.
 */
describe('StocksOutboxPublisher', () => {
  function fakeOutboxRepository(rows: OutboxRow[]): jest.Mocked<StocksControlOutboxRepository> {
    return {
      findUnpublished: jest.fn().mockResolvedValue(rows),
      markPublished: jest.fn().mockResolvedValue(undefined),
      recordChange: jest.fn(),
      publishedWatermark: jest.fn(),
      unpublishedCount: jest.fn(),
    } as unknown as jest.Mocked<StocksControlOutboxRepository>;
  }

  function fakeEpochRepository(): jest.Mocked<SourceEpochRepository> {
    return {
      currentEpoch: jest.fn().mockResolvedValue(1),
      onModuleInit: jest.fn(),
    } as unknown as jest.Mocked<SourceEpochRepository>;
  }

  const rows: OutboxRow[] = [
    { version: 1, ticker: 'IBM', companyName: 'International Business Machines', createdAt: new Date() },
    { version: 2, ticker: 'MSFT', companyName: 'Microsoft Corporation', createdAt: new Date() },
  ];

  it('publishes unpublished rows in order and marks them published', async () => {
    const outboxRepository = fakeOutboxRepository(rows);
    const publisher: jest.Mocked<ControlFeedPublisher> = { publish: jest.fn().mockResolvedValue(undefined) };

    const poller = new StocksOutboxPublisher(outboxRepository, fakeEpochRepository(), publisher);
    await poller.publishPending();

    expect(publisher.publish).toHaveBeenNthCalledWith(1, 'security:1', expect.stringContaining('"ticker":"IBM"'));
    expect(publisher.publish).toHaveBeenNthCalledWith(2, 'security:2', expect.stringContaining('"ticker":"MSFT"'));
    expect(outboxRepository.markPublished).toHaveBeenCalledWith(1);
    expect(outboxRepository.markPublished).toHaveBeenCalledWith(2);
  });

  it('stops at the failing row without skipping ahead or marking it published', async () => {
    const outboxRepository = fakeOutboxRepository(rows);
    const publisher: jest.Mocked<ControlFeedPublisher> = {
      publish: jest.fn().mockRejectedValueOnce(new Error('simulated publish failure')),
    };

    const poller = new StocksOutboxPublisher(outboxRepository, fakeEpochRepository(), publisher);
    await poller.publishPending();

    expect(publisher.publish).toHaveBeenCalledTimes(1);
    expect(outboxRepository.markPublished).not.toHaveBeenCalled();
  });

  it('does nothing when disabled', async () => {
    process.env.SECURITY_OUTBOX_ENABLED = 'false';
    try {
      const outboxRepository = fakeOutboxRepository(rows);
      const publisher: jest.Mocked<ControlFeedPublisher> = { publish: jest.fn() };

      const poller = new StocksOutboxPublisher(outboxRepository, fakeEpochRepository(), publisher);
      await poller.publishPending();

      expect(outboxRepository.findUnpublished).not.toHaveBeenCalled();
      expect(publisher.publish).not.toHaveBeenCalled();
    } finally {
      delete process.env.SECURITY_OUTBOX_ENABLED;
    }
  });
});
