package finos.traderx.tradeservice.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ENGINE-REVIEW S1 put a bearer JWT, bound to the account, on the cluster gateway's order routes
 * ({@code /trades} included). trade-service forwards the legacy ticket there on the trader's behalf,
 * so it mints the same HS256 token shape the gateway's {@code JwtAuthenticator} validates
 * ({@code sub}, {@code accounts}, {@code admin}, {@code exp}), over the shared
 * {@code AUTH_JWT_SECRET}. Least privilege by construction: ONE account, never admin, sixty seconds.
 * This is the service's own credential, not the browser's -- the legacy UI has no token of its own.
 */
public final class GatewayBearer {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private GatewayBearer() {}

  public static String mint(String secret, String subject, int accountId, long ttlSeconds) {
    try {
      ObjectNode header = MAPPER.createObjectNode();
      header.put("alg", "HS256");
      header.put("typ", "JWT");
      ObjectNode payload = MAPPER.createObjectNode();
      payload.put("sub", subject);
      payload.put("admin", false);
      payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
      payload.putArray("accounts").add(accountId);
      String signingInput = encode(MAPPER.writeValueAsBytes(header)) + "." + encode(MAPPER.writeValueAsBytes(payload));
      return signingInput + "." + encode(sign(secret, signingInput));
    } catch (Exception ex) {
      throw new IllegalStateException("failed to mint gateway bearer", ex);
    }
  }

  /** HMAC-SHA256 over the signing input; exposed so a test can verify a token without a verifier class. */
  public static byte[] sign(String secret, String signingInput) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
