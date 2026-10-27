package finos.traderx.algoengine.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.algoengine.model.OrderSide;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** The child order must carry a gateway bearer bound to its own account, and none without a secret. */
class OrderMatcherClientTest {

  private static String submitAndCaptureAuthorization(String secret) throws Exception {
    AtomicReference<String> auth = new AtomicReference<>("<unset>");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/orders", exchange -> {
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] reply = "{\"orderRef\":\"1-7\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, reply.length);
      exchange.getResponseBody().write(reply);
      exchange.close();
    });
    server.start();
    try {
      OrderMatcherClient client = new OrderMatcherClient(
          "http://127.0.0.1:" + server.getAddress().getPort(), secret);
      assertThat(client.submitChildOrder("c-1", 42422, "IBM", OrderSide.Sell, 10, new BigDecimal("187.28")))
          .isEqualTo("1-7");
      return auth.get();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void signsAnAccountBoundNonAdminBearerWhenTheSecretIsSet() throws Exception {
    String header = submitAndCaptureAuthorization("s3cret");
    assertThat(header).startsWith("Bearer ");
    String[] jwt = header.substring(7).split("\\.");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
        mac.doFinal((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.UTF_8)));
    assertThat(jwt[2]).isEqualTo(expected);
    JsonNode payload = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(jwt[1]));
    assertThat(payload.get("accounts").get(0).asInt()).isEqualTo(42422);
    assertThat(payload.get("admin").asBoolean()).isFalse();
  }

  @Test
  void postsBareWithoutASecret() throws Exception {
    assertThat(submitAndCaptureAuthorization("")).isNull();
  }
}
