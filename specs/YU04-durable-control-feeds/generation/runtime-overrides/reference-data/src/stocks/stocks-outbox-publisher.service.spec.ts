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
      publishedCount: jest.fn().mockResolvedValue(0),
      markAllUnpublished: jest.fn(),
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
    const publisher: jest.Mocked<ControlFeedPublisher> = { publish: jest.fn().mockResolvedValue(undefined), streamState: jest.fn() };

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
      streamState: jest.fn(),
    };

    const poller = new StocksOutboxPublisher(outboxRepository, fakeEpochRepository(), publisher);
    await poller.publishPending();

    expect(publisher.publish).toHaveBeenCalledTimes(1);
    expect(outboxRepository.markPublished).not.toHaveBeenCalled();
  });

  it('NFR-IMRG-DEDUPE retries with the same stable Nats-Msg-Id', async () => {
    const outboxRepository = fakeOutboxRepository([rows[0]]);
    const publisher: jest.Mocked<ControlFeedPublisher> = {
      publish: jest
        .fn()
        .mockRejectedValueOnce(new Error('ack lost after broker accepted publish'))
        .mockResolvedValueOnce(undefined),
      streamState: jest.fn(),
    };
    const poller = new StocksOutboxPublisher(outboxRepository, fakeEpochRepository(), publisher);

    await poller.publishPending();
    await poller.publishPending();

    expect(publisher.publish).toHaveBeenNthCalledWith(
      1,
      'security:1',
      expect.stringContaining('"version":1'),
    );
    expect(publisher.publish).toHaveBeenNthCalledWith(
      2,
      'security:1',
      expect.stringContaining('"version":1'),
    );
    expect(outboxRepository.markPublished).toHaveBeenCalledTimes(1);
    expect(outboxRepository.markPublished).toHaveBeenCalledWith(1);
  });

  it('does nothing when disabled', async () => {
    process.env.SECURITY_OUTBOX_ENABLED = 'false';
    try {
      const outboxRepository = fakeOutboxRepository(rows);
      const publisher: jest.Mocked<ControlFeedPublisher> = { publish: jest.fn(), streamState: jest.fn() };

      const poller = new StocksOutboxPublisher(outboxRepository, fakeEpochRepository(), publisher);
      await poller.publishPending();

      expect(outboxRepository.findUnpublished).not.toHaveBeenCalled();
      expect(publisher.publish).not.toHaveBeenCalled();
    } finally {
      delete process.env.SECURITY_OUTBOX_ENABLED;
    }
  });
});

/**
 * FR-IMRG36 with a real in-memory outbox and stream, not mocks: the outbox's `published` flags are checked
 * against what the stream holds, so a replaced NATS store gets the whole universe again.
 */
describe('StocksOutboxPublisher stream audit (FR-IMRG36)', () => {
  class Outbox {
    readonly published = new Set<number>();
    constructor(readonly rows: OutboxRow[]) {}
    async findUnpublished(limit: number) { return this.rows.filter((r) => !this.published.has(r.version)).slice(0, limit); }
    async markPublished(version: number) { this.published.add(version); }
    async publishedCount() { return this.published.size; }
    async publishedWatermark() { return Math.max(0, ...this.published); }
    async markAllUnpublished() { const n = this.published.size; this.published.clear(); return n; }
  }
  class Stream implements ControlFeedPublisher {
    messages: { version: number; ticker: string }[] = [];
    async publish(_id: string, payload: string) { const p = JSON.parse(payload); this.messages.push({ version: p.version, ticker: p.ticker }); }
    async streamState() {
      return { messages: this.messages.length, lastVersion: this.messages.length ? this.messages[this.messages.length - 1].version : 0 };
    }
    versions() { return this.messages.map((m) => m.version); }
  }
  const epoch = { currentEpoch: jest.fn().mockResolvedValue(1) } as unknown as SourceEpochRepository;
  const universe = (n: number): OutboxRow[] => Array.from({ length: n }, (_, i) =>
    ({ version: i + 1, ticker: `T${i + 1}`, companyName: `Company ${i + 1}`, createdAt: new Date() }));
  const poller = (outbox: Outbox, stream: Stream) =>
    new StocksOutboxPublisher(outbox as unknown as StocksControlOutboxRepository, epoch, stream);

  it('publish, wipe the stream, restart: the universe is republished in version order', async () => {
    const outbox = new Outbox(universe(3));
    const stream = new Stream();
    await poller(outbox, stream).publishPending();
    expect(stream.versions()).toEqual([1, 2, 3]);

    stream.messages = [];                                // the NATS store is replaced; every row still says published
    await poller(outbox, stream).publishPending();       // reference-data restarts
    expect(stream.versions()).toEqual([1, 2, 3]);
    expect(outbox.published.size).toBe(3);
  });

  it('a recreated stream that already took a newer delta is detected by its message count', async () => {
    // Production on 2026-10-27: 533 rows published to a store that was replaced, then one new security.
    const outbox = new Outbox(universe(3));
    const stream = new Stream();
    await poller(outbox, stream).publishPending();
    stream.messages = [];
    outbox.rows.push({ version: 4, ticker: 'NEW', companyName: 'New Co', createdAt: new Date() });
    const running = poller(outbox, stream);
    await running.auditStream();                         // stream empty, nothing yet lost from view of the flags
    await running.publishPending();                      // first poll audits again (lastAudit 0) and republishes
    expect(stream.versions()).toEqual([1, 2, 3, 4]);

    stream.messages = [{ version: 4, ticker: 'NEW' }];   // 1 message whose version equals the watermark
    await running.auditStream();
    await running.publishPending();
    expect(stream.versions()).toEqual([4, 1, 2, 3, 4]);
  });

  it('leaves a complete stream alone', async () => {
    const outbox = new Outbox(universe(3));
    const stream = new Stream();
    const running = poller(outbox, stream);
    await running.publishPending();
    await running.auditStream();
    await running.publishPending();
    expect(stream.versions()).toEqual([1, 2, 3]);
  });

  it('reports a stream ahead of the outbox and does not republish', async () => {
    const outbox = new Outbox(universe(2));
    const stream = new Stream();
    await poller(outbox, stream).publishPending();
    stream.messages.push({ version: 9, ticker: 'GONE' });  // the outbox was restored from an older dump
    const running = poller(outbox, stream);
    await running.publishPending();
    expect(stream.versions()).toEqual([1, 2, 9]);
    expect(outbox.published.size).toBe(2);
  });
});
