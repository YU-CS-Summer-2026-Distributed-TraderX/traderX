package finos.traderx.accountservice.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background poller (ADR-021): reads unpublished {@code account_control_outbox} rows in strictly
 * increasing version order and publishes each to the durable control feed. Runs off the BLP
 * thread entirely (order-matcher isn't even in this process) — NFR-IMRG04 is unaffected by
 * construction.
 */
@Component
public class AccountOutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(AccountOutboxPublisher.class);
  private static final String SOURCE = "account";
  private static final int BATCH_LIMIT = 100;

  private final AccountControlOutboxRepository outboxRepository;
  private final SourceEpochRepository epochRepository;
  private final ControlFeedPublisher publisher;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper mapper = new ObjectMapper();
  private final boolean enabled;
  private final AtomicInteger unpublishedGauge = new AtomicInteger();

  public AccountOutboxPublisher(
      AccountControlOutboxRepository outboxRepository,
      SourceEpochRepository epochRepository,
      ControlFeedPublisher publisher,
      MeterRegistry meterRegistry,
      @Value("${outbox.publisher.enabled:true}") boolean enabled) {
    this.outboxRepository = outboxRepository;
    this.epochRepository = epochRepository;
    this.publisher = publisher;
    this.meterRegistry = meterRegistry;
    this.enabled = enabled;
    meterRegistry.gauge("traderx_outbox_unpublished_rows", Tags.of("source", SOURCE), unpublishedGauge);
  }

  @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:250}")
  public void publishPending() {
    if (!enabled) {
      return;
    }
    List<OutboxRow> rows = outboxRepository.findUnpublished(BATCH_LIMIT);
    for (OutboxRow row : rows) {
      try {
        publisher.publish(SOURCE + ":" + row.version(), toJson(row));
        outboxRepository.markPublished(row.version());
        meterRegistry.timer("traderx_outbox_publish_lag_seconds", "source", SOURCE)
            .record(Duration.between(row.createdAt(), Instant.now()));
      } catch (Exception ex) {
        log.warn("Failed to publish account control outbox row version={} (will retry next poll): {}",
            row.version(), ex.toString());
        break; // preserve strict version order; do not skip ahead on failure
      }
    }
    unpublishedGauge.set(outboxRepository.unpublishedCount());
  }

  private String toJson(OutboxRow row) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", row.version());
    body.put("epoch", epochRepository.currentEpoch());
    body.put("accountId", row.accountId());
    body.put("displayName", row.displayName());
    return mapper.writeValueAsString(body);
  }
}
