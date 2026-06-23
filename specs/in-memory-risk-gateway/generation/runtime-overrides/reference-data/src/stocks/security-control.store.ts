import { Injectable, Logger, OnModuleDestroy } from '@nestjs/common';
import { connect, JetStreamClient, NatsConnection } from 'nats';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

export interface SecurityControlRecord {
  version: number;
  sourceEpoch: number;
  securityId: number;
  ticker: string;
  enabled: boolean;
  halted: boolean;
  operator: string;
  sourceTimeMillis: number;
  published: boolean;
}

interface SecurityControlImage {
  sourceEpoch: number;
  nextVersion: number;
  records: SecurityControlRecord[];
  outbox: SecurityControlRecord[];
}

/** Atomically persisted source state plus ack-before-delete outbox. */
@Injectable()
export class SecurityControlStore implements OnModuleDestroy {
  private readonly logger = new Logger(SecurityControlStore.name);
  private readonly path = process.env.REFERENCE_DATA_CONTROL_PATH ?? '/tmp/traderx-security-control.json';
  private image?: SecurityControlImage;
  private initializing?: Promise<void>;
  private connection?: NatsConnection;
  private jetstream?: JetStreamClient;
  private timer?: NodeJS.Timeout;

  async initialize(tickers: string[]): Promise<void> {
    if (this.image) return;
    if (this.initializing) return this.initializing;
    this.initializing = this.loadOrCreate(tickers);
    await this.initializing;
    if ((process.env.RISK_CONTROL_OUTBOX_ENABLED ?? 'false') === 'true') {
      this.timer = setInterval(() => void this.drain(), 250);
      this.timer.unref();
      void this.drain();
    }
  }

  snapshot() {
    const image = this.required();
    const securities = image.records.map(({ published: _published, operator: _operator, ...record }) => record);
    const watermark = image.nextVersion - 1;
    return { sourceEpoch: image.sourceEpoch, watermark, highWatermark: watermark, securities };
  }

  deltas(after: number) {
    return this.required().outbox.filter((record) => record.version > after)
      .map(({ published: _published, ...record }) => record);
  }

  async mutate(tickerInput: string, enabled: boolean, halted: boolean,
               expectedVersion: number, operator: string): Promise<SecurityControlRecord> {
    const image = this.required();
    const outboxCapacity = Number(process.env.REFERENCE_DATA_OUTBOX_CAPACITY ?? 100_000);
    if (image.outbox.length >= outboxCapacity) {
      throw new Error(`security control outbox capacity exceeded: ${outboxCapacity}`);
    }
    const ticker = tickerInput.trim().toUpperCase();
    const index = image.records.findIndex((record) => record.ticker === ticker);
    if (index < 0) throw new Error(`unknown authoritative security: ${ticker}`);
    const current = image.records[index];
    if (current.version !== expectedVersion) {
      throw new Error(`stale expectedVersion=${expectedVersion}, currentVersion=${current.version}`);
    }
    const event: SecurityControlRecord = {
      version: image.nextVersion++, sourceEpoch: image.sourceEpoch,
      securityId: current.securityId, ticker, enabled, halted,
      operator, sourceTimeMillis: Date.now(), published: false,
    };
    image.records[index] = event;
    image.outbox.push(event);
    await this.persist();
    return event;
  }

  async drain(): Promise<void> {
    try {
      const image = this.required();
      if (!image.outbox.some((event) => !event.published)) return;
      if (!this.connection || this.connection.isClosed()) {
        const server = process.env.NATS_ADDRESS ?? `nats://${process.env.NATS_BROKER_HOST ?? 'localhost'}:4222`;
        this.connection = await connect({ servers: server, maxReconnectAttempts: -1 });
        this.jetstream = this.connection.jetstream();
      }
      for (const event of image.outbox) {
        if (event.published) continue;
        const payload = new TextEncoder().encode(JSON.stringify(event));
        await this.jetstream!.publish(`traderx.control.security.${event.securityId}`, payload);
        event.published = true;
        const retained = Number(process.env.REFERENCE_DATA_OUTBOX_RETAINED ?? 10_000);
        while (image.outbox.length > retained && image.outbox[0]?.published) image.outbox.shift();
        await this.persist();
      }
    } catch (failure) {
      this.logger.warn(`security control outbox drain deferred: ${String(failure)}`);
    }
  }

  async onModuleDestroy(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    if (this.connection) await this.connection.close();
  }

  private async loadOrCreate(tickers: string[]): Promise<void> {
    try {
      this.image = JSON.parse(await readFile(this.path, 'utf8')) as SecurityControlImage;
      return;
    } catch (failure: unknown) {
      if ((failure as NodeJS.ErrnoException).code !== 'ENOENT') throw failure;
    }
    const sourceEpoch = Date.now();
    const records = tickers.map((ticker, securityId): SecurityControlRecord => ({
      version: securityId + 1, sourceEpoch, securityId, ticker: ticker.toUpperCase(),
      enabled: true, halted: false, operator: 'bootstrap', sourceTimeMillis: Date.now(), published: false,
    }));
    this.image = { sourceEpoch, nextVersion: records.length + 1, records, outbox: [...records] };
    await this.persist();
  }

  private async persist(): Promise<void> {
    await mkdir(dirname(this.path), { recursive: true });
    const temporary = `${this.path}.tmp`;
    await writeFile(temporary, JSON.stringify(this.required()), { encoding: 'utf8', mode: 0o600 });
    await rename(temporary, this.path);
  }

  private required(): SecurityControlImage {
    if (!this.image) throw new Error('security control store is not initialized');
    return this.image;
  }
}
