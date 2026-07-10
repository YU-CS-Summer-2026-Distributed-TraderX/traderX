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
 * research.md Decision 5: subscribes to the existing {@code /accounts/*}{@code /orders} broadcast
 * subject — the same precedent YU07's {@code tick-store} set for {@code pricing.*} — and
 * correlates each lifecycle event to a child order by {@code orderId} via {@link
 * AlgoOrderService#onOrderUpdate}. No new subject, no publisher change.
 */
@Component
public class OrderUpdateSubscriber {
  private static final Logger log = LoggerFactory.getLogger(OrderUpdateSubscriber.class);
  private static final String SUBJECT = "/accounts/*/orders";

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
    connection.createDispatcher(this::onMessage).subscribe(SUBJECT);
    log.info("subscribed to {} for child-order fill tracking", SUBJECT);
  }

  void onMessage(io.nats.client.Message msg) {
    try {
      JsonNode body = mapper.readTree(msg.getData());
      handle(body);
    } catch (Exception ex) {
      log.warn("failed to process order-update message: {}", ex.toString());
    }
  }

  /** Extracted from {@link #onMessage} so the correlation logic is testable without a live NATS
   * connection. */
  void handle(JsonNode body) {
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
