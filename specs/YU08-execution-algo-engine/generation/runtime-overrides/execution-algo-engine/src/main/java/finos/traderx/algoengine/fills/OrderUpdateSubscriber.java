package finos.traderx.algoengine.fills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.algoengine.service.AlgoOrderService;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * research.md Decision 5: subscribes to the existing {@code /accounts/<id>/orders} broadcast
 * subject family and correlates each lifecycle event to a child order by {@code orderId} via
 * {@link AlgoOrderService#onOrderUpdate}. No new subject, no publisher change.
 *
 * <p>These subjects use a literal {@code /}, not NATS's {@code .}-delimited token hierarchy
 * (`order-matcher` publishes the literal string {@code "/accounts/" + accountId + "/orders"} —
 * see {@code NatsBridgeHandler}), so the whole thing is one token from NATS's perspective and
 * {@code *} cannot be embedded inside it as a wildcard (jnats's subject validator rejects
 * {@code "/accounts/*}{@code /orders"} outright: "Subject wildcard improperly placed"). Instead
 * this subscribes to NATS's full match-all ({@code ">"}) and filters client-side by subject
 * prefix/suffix — the only correct way to select every {@code /accounts/<id>/orders} subject
 * given the existing publisher's non-hierarchical naming, at the cost of also receiving every
 * other subject on the connection (pricing ticks, trades, positions) and discarding non-matches.
 */
@Component
public class OrderUpdateSubscriber {
  private static final Logger log = LoggerFactory.getLogger(OrderUpdateSubscriber.class);
  private static final String CATCH_ALL_SUBJECT = ">";
  private static final String SUBJECT_PREFIX = "/accounts/";
  private static final String SUBJECT_SUFFIX = "/orders";

  private final String natsAddress;
  private final AlgoOrderService algoOrderService;
  private final ObjectMapper mapper = new ObjectMapper();
  private volatile Connection connection;

  public OrderUpdateSubscriber(
      @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress,
      AlgoOrderService algoOrderService) {
    this.natsAddress = natsAddress;
    this.algoOrderService = algoOrderService;
  }

  @PostConstruct
  void start() throws Exception {
    connection = Nats.connect(new Options.Builder()
        .server(natsAddress)
        .connectionTimeout(Duration.ofSeconds(10))
        .maxReconnects(-1)
        .build());
    connection.createDispatcher(this::onMessage).subscribe(CATCH_ALL_SUBJECT);
    log.info("subscribed to {} (filtering for {}*{}) for child-order fill tracking",
        CATCH_ALL_SUBJECT, SUBJECT_PREFIX, SUBJECT_SUFFIX);
  }

  void onMessage(io.nats.client.Message msg) {
    String subject = msg.getSubject();
    if (subject == null || !subject.startsWith(SUBJECT_PREFIX) || !subject.endsWith(SUBJECT_SUFFIX)) {
      return;
    }
    try {
      JsonNode body = mapper.readTree(msg.getData());
      handle(body);
    } catch (Exception ex) {
      log.warn("failed to process order-update message: {}", ex.toString());
    }
  }

  /** Extracted from {@link #onMessage} so the correlation logic is testable without a live NATS
   * connection. Every message on this bus is wrapped in a {@code NatsEnvelope}
   * ({@code topic}/{@code payload}/{@code date}/{@code from}/{@code type} —
   * {@code messaging/nats/NatsJSONPublisher}, used by every publisher on this branch including
   * order-matcher's {@code NatsBridgeHandler}); the actual {@code OrderResponse} fields live under
   * {@code payload}, not at the envelope's top level. */
  void handle(JsonNode envelope) {
    JsonNode body = envelope.hasNonNull("payload") ? envelope.get("payload") : envelope;
    if (!body.hasNonNull("orderId")) {
      return;
    }
    String orderId = body.get("orderId").asText();
    Integer remainingQuantity = body.hasNonNull("remainingQuantity") ? body.get("remainingQuantity").asInt() : null;
    BigDecimal lastExecutionPrice = body.hasNonNull("lastExecutionPrice")
        ? new BigDecimal(body.get("lastExecutionPrice").asText())
        : null;
    algoOrderService.onOrderUpdate(orderId, remainingQuantity, lastExecutionPrice);
  }

  @PreDestroy
  public void close() {
    Connection conn = connection;
    if (conn == null) {
      return;
    }
    try {
      conn.close();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
