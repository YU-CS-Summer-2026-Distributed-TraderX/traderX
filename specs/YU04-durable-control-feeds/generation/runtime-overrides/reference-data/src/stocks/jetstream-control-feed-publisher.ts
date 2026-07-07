import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { connect, JetStreamClient, JetStreamManager, NatsConnection, StorageType, StringCodec } from 'nats';
import { ControlFeedPublisher } from './control-feed-publisher.interface';

const STREAM_NAME = 'TRADERX_CONTROL_SECURITY';
const SUBJECT = 'traderx.control.security.deltas';

/**
 * Durable outbox publisher for `reference-data` (ADR-021, FR-IMRG32/33): a file-backed JetStream
 * stream of versioned security existence/identity deltas, consumed by order-matcher's
 * `ControlFeedSubscriber`. Same connection-config convention (`NATS_ADDRESS`/`NATS_BROKER_HOST`)
 * as the account-service/order-matcher side — an unrelated stream, same broker, same conventions.
 *
 * <p>Connects lazily on the first {@link publish} call rather than at module init: this is a
 * control-plane path (250ms poll interval; NFR-IMRG01 does not apply — ADR-021), so there's no
 * reason to couple application startup to broker availability, and it means this provider never
 * opens a socket in a test context where `SECURITY_OUTBOX_ENABLED=false` keeps
 * `StocksOutboxPublisher` from ever calling {@link publish}.
 */
@Injectable()
export class JetStreamControlFeedPublisher implements ControlFeedPublisher, OnModuleDestroy {
  private connection?: NatsConnection;
  private jetStreamClient?: JetStreamClient;
  private connecting?: Promise<JetStreamClient>;
  private readonly codec = StringCodec();

  private async jetStream(): Promise<JetStreamClient> {
    if (this.jetStreamClient) {
      return this.jetStreamClient;
    }
    if (!this.connecting) {
      this.connecting = this.connectAndEnsureStream();
    }
    return this.connecting;
  }

  private async connectAndEnsureStream(): Promise<JetStreamClient> {
    const servers = process.env.NATS_ADDRESS ?? `nats://${process.env.NATS_BROKER_HOST ?? 'localhost'}:4222`;
    this.connection = await connect({ servers, maxReconnectAttempts: -1 });
    const jsm: JetStreamManager = await this.connection.jetstreamManager();
    try {
      await jsm.streams.info(STREAM_NAME);
    } catch {
      await jsm.streams.add({ name: STREAM_NAME, subjects: [SUBJECT], storage: StorageType.File });
    }
    this.jetStreamClient = this.connection.jetstream();
    return this.jetStreamClient;
  }

  async publish(natsMsgId: string, payloadJson: string): Promise<void> {
    const js = await this.jetStream();
    await js.publish(SUBJECT, this.codec.encode(payloadJson), { msgID: natsMsgId });
  }

  async onModuleDestroy(): Promise<void> {
    await this.connection?.close();
  }
}
