package finos.traderx.accountservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.accountservice.model.AccountControlEvent;
import finos.traderx.accountservice.repository.AccountControlEventRepository;
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

/** Retrying transactional-outbox drainer. Rows are marked only after a JetStream ack. */
@Component
public final class AccountControlOutboxDrainer {
  private static final Logger log = LoggerFactory.getLogger(AccountControlOutboxDrainer.class);
  private final AccountControlEventRepository outbox;
  private final ObjectMapper json;
  private final boolean enabled;
  private final String natsAddress;
  private Connection connection;
  private JetStream jetStream;

  public AccountControlOutboxDrainer(AccountControlEventRepository outbox, ObjectMapper json,
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
      for (AccountControlEvent event : outbox.unpublished(256)) {
        byte[] payload = json.writeValueAsBytes(event);
        String aggregate = "ENTITLEMENT".equals(event.eventType()) ? "entitlement" : "account";
        jetStream.publish("traderx.control." + aggregate + "." + event.accountId(), payload);
        outbox.markPublished(event.version());
      }
    } catch (Exception failure) {
      log.warn("Account control outbox drain deferred: {}", failure.toString());
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
