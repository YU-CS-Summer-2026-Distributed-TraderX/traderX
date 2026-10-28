export const CONTROL_FEED_PUBLISHER = 'CONTROL_FEED_PUBLISHER';

/**
 * Publishes one durable control-feed message. Kept as a small seam (rather than calling the NATS
 * client directly from {@code StocksOutboxPublisher}) so the outbox-selection/marking logic is
 * unit-testable without a live broker — mirrors the same seam on the account-service side
 * (`ControlFeedPublisher.java`) and this project's existing convention of isolating real-NATS
 * behavior behind a thin, separately-verified adapter.
 */
export interface ControlFeedPublisher {
  /**
   * @param natsMsgId the JetStream `Nats-Msg-Id` for publish-side idempotency
   *   (`"security:<version>"` — NFR-IMRG-OUTBOX-02)
   * @param payloadJson the message body (JSON)
   */
  publish(natsMsgId: string, payloadJson: string): Promise<void>;

  /**
   * What the stream actually holds: its message count and the version of its last message (0 and 0
   * when the stream is missing or empty; a missing stream is recreated). The outbox's `published`
   * flags are a claim about this stream; this is the evidence they are checked against.
   */
  streamState(): Promise<{ messages: number; lastVersion: number }>;
}
