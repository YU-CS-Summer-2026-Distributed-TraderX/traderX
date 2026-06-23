package finos.traderx.accountservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.accountservice.model.RiskControlEvent;
import finos.traderx.accountservice.repository.RiskControlEventRepository;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ack-before-delete publisher for authoritative policy, restriction, and kill-switch deltas. */
@Component
public final class RiskControlOutboxDrainer {
  private static final Logger log = LoggerFactory.getLogger(RiskControlOutboxDrainer.class);
  private final RiskControlEventRepository outbox;
  private final ObjectMapper json;
  private final boolean enabled;
  private final String natsAddress;
  private Connection connection;
  private JetStream jetStream;

  public RiskControlOutboxDrainer(RiskControlEventRepository outbox, ObjectMapper json,
      @Value("${risk.control.outbox.enabled:false}") boolean enabled,
      @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress) {
    this.outbox = outbox;
    this.json = json;
    this.enabled = enabled;
    this.natsAddress = natsAddress;
  }

  @Scheduled(fixedDelayString = "${risk.control.outbox.poll-ms:250}")
  public void drain() {
    if (!enabled) return;
    try {
      ensureConnected();
      for (RiskControlEvent event : outbox.unpublished(256)) {
        String aggregate = switch (event.eventType()) {
          case "POLICY" -> "risk-policy";
          case "RESTRICTION" -> "restriction";
          case "KILL_SWITCH" -> "kill-switch";
          default -> throw new IllegalArgumentException("unsupported risk event " + event.eventType());
        };
        jetStream.publish("traderx.control." + aggregate + "." + event.aggregateKey(),
            json.writeValueAsBytes(event));
        outbox.markPublished(event.version());
      }
    } catch (Exception failure) {
      log.warn("Risk control outbox drain deferred: {}", failure.toString());
      close();
    }
  }

  private void ensureConnected() throws Exception {
    if (connection != null && connection.getStatus() == Connection.Status.CONNECTED) return;
    connection = Nats.connect(new Options.Builder().server(natsAddress)
        .connectionTimeout(Duration.ofSeconds(2)).maxReconnects(-1).build());
    jetStream = connection.jetStream();
  }

  @PreDestroy
  public void close() {
    try {
      if (connection != null) connection.close();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      connection = null;
      jetStream = null;
    }
  }
}
