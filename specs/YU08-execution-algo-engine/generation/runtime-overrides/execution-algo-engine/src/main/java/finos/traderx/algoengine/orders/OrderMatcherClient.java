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
    // Any 2xx is an acceptance. This used to demand exactly 201, which is what the single-BLP
    // Spring matcher answers -- the cluster gateway answers 200. Against the cluster tier every
    // child was therefore BOOKED and then thrown away as a failure, and because AlgoScheduler
    // retries a bucket it could not confirm, the same slice was resubmitted every tick: the engine
    // reported "no child submitted" while the venue filled a growing pile of them. A rejection is
    // a non-2xx (the gateway answers 422 with a risk reason), so it still throws here.
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("child order submit " + clientOrderId + " -> HTTP "
          + response.statusCode() + ": " + response.body());
    }
    JsonNode responseBody = mapper.readTree(response.body());
    // ...and the id field differs with it: "orderId" from the Spring matcher, "orderRef" from the
    // cluster gateway. Reading only the first returned a null node whose asText() then failed, so
    // fixing the status check alone would have moved the failure rather than removed it.
    JsonNode id = responseBody.hasNonNull("orderId")
        ? responseBody.get("orderId") : responseBody.get("orderRef");
    if (id == null) {
      throw new IllegalStateException("child order submit " + clientOrderId
          + " -> accepted (HTTP " + response.statusCode() + ") but no order id in: "
          + response.body());
    }
    return id.asText();
  }
}
