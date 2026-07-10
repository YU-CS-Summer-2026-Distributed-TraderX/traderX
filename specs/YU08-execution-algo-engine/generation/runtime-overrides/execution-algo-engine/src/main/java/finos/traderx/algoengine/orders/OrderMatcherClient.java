package finos.traderx.algoengine.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import finos.traderx.algoengine.model.OrderSide;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * research.md Decision 2: submits a child order through order-matcher's existing {@code POST
 * /orders} — the same endpoint and {@code OrderCreateRequest} shape the web front end's order
 * ticket already uses. No new field, no bypass.
 */
@Component
public class OrderMatcherClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final String orderMatcherUrl;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public OrderMatcherClient(@Value("${order.matcher.url}") String orderMatcherUrl) {
    this.orderMatcherUrl = orderMatcherUrl;
  }

  /** Returns order-matcher's assigned {@code orderId} for the accepted child order. Throws if the
   * risk gateway/BLP rejects it or the call otherwise fails — the caller (AlgoScheduler) retries
   * the same bucket on its next tick rather than treating a rejection as submitted. */
  public String submitChildOrder(String clientOrderId, Integer accountId, String security,
      OrderSide side, int quantity, BigDecimal limitPrice) throws Exception {
    ObjectNode body = mapper.createObjectNode();
    body.put("clientOrderId", clientOrderId);
    body.put("accountId", accountId);
    body.put("security", security);
    body.put("side", side.name());
    body.put("quantity", quantity);
    body.put("limitPrice", limitPrice);

    HttpRequest request = HttpRequest.newBuilder(URI.create(orderMatcherUrl + "/orders"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 201) {
      throw new IllegalStateException("child order submit " + clientOrderId + " -> HTTP "
          + response.statusCode() + ": " + response.body());
    }
    JsonNode responseBody = mapper.readTree(response.body());
    return responseBody.get("orderId").asText();
  }
}
