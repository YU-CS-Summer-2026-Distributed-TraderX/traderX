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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * research.md Decision 2: submits a child order through order-matcher's existing {@code POST
 * /orders} — the same endpoint and {@code OrderCreateRequest} shape the web front end's order
 * ticket already uses. No new field, no bypass.
 *
 * <p>ENGINE-REVIEW S1 put a bearer JWT, bound to the account, on the cluster gateway's order routes.
 * With {@code AUTH_JWT_SECRET} set, each child carries the same HS256 shape trade-service mints
 * ({@code sub}, {@code accounts}, {@code admin}, {@code exp}): ONE account, never admin, sixty
 * seconds. Without the secret it posts bare, as before S1. Missing it on the cluster tier made every
 * child a 401, and the scheduler retried that bucket every tick forever.
 */
@Component
public class OrderMatcherClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final String BEARER_SUBJECT = "execution-algo-engine";
  private static final long BEARER_TTL_SECONDS = 60;

  private final String orderMatcherUrl;
  private final String authJwtSecret;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public OrderMatcherClient(@Value("${order.matcher.url}") String orderMatcherUrl,
      @Value("${auth.jwt.secret:}") String authJwtSecret) {
    this.orderMatcherUrl = orderMatcherUrl;
    this.authJwtSecret = authJwtSecret;
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

    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(orderMatcherUrl + "/orders"))
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json");
    if (authJwtSecret != null && !authJwtSecret.isBlank()) {
      builder.header("Authorization", "Bearer " + bearer(accountId));
    }
    HttpRequest request = builder
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

  private String bearer(int accountId) throws Exception {
    ObjectNode header = mapper.createObjectNode().put("alg", "HS256").put("typ", "JWT");
    ObjectNode payload = mapper.createObjectNode()
        .put("sub", BEARER_SUBJECT)
        .put("admin", false)
        .put("exp", Instant.now().getEpochSecond() + BEARER_TTL_SECONDS);
    payload.putArray("accounts").add(accountId);
    String signingInput = b64(mapper.writeValueAsBytes(header)) + "." + b64(mapper.writeValueAsBytes(payload));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(authJwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return signingInput + "." + b64(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
