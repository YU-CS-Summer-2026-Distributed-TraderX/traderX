package finos.traderx.accountservice.outbox;

/**
 * Publishes one durable control-feed message. Kept as a small seam (rather than calling the NATS
 * client directly from {@link AccountOutboxPublisher}) so the outbox-selection/marking logic is
 * unit-testable without a live broker — mirrors this project's existing convention of gating
 * real-NATS behavior behind an isolated, thin adapter (see {@code JetStreamControlFeedPublisher}).
 */
public interface ControlFeedPublisher {

  /**
   * @param natsMsgId the JetStream {@code Nats-Msg-Id} for publish-side idempotency
   *     ({@code "<source>:<version>"} — NFR-IMRG-OUTBOX-02)
   * @param payloadJson the message body (JSON)
   */
  void publish(String natsMsgId, String payloadJson) throws Exception;
}
