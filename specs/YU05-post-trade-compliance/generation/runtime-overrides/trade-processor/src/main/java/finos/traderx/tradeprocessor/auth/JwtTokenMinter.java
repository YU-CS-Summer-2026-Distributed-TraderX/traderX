package finos.traderx.tradeprocessor.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * YU05 (post-trade-compliance, ADR-025): mints HS256 JWTs for local development/testing — no
 * live OIDC provider in this environment. Exposed via {@code POST /auth/dev-token} (gated by its
 * own `auth.dev-token.master-secret`, distinct from `auth.jwt.secret`), since trade-processor and
 * order-matcher share the same `auth.jwt.secret` and a token minted here validates against both.
 */
public final class JwtTokenMinter {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretKeySpec key;

    public JwtTokenMinter(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String mint(String subject, Set<Integer> accounts, boolean admin, long ttlSeconds) {
        try {
            ObjectNode header = mapper.createObjectNode();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            ObjectNode payload = mapper.createObjectNode();
            payload.put("sub", subject);
            payload.put("admin", admin);
            // ttlSeconds == 0 means non-expiring (no exp claim at all — used for this service's
            // own long-lived service-account token). Any other value, including negative, computes
            // a real exp (negative correctly produces an already-expired token, e.g. for tests).
            if (ttlSeconds != 0) {
                payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
            }
            ArrayNode accountsNode = payload.putArray("accounts");
            for (int accountId : accounts) {
                accountsNode.add(accountId);
            }

            String encodedHeader = encode(mapper.writeValueAsBytes(header));
            String encodedPayload = encode(mapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String signature = encode(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));

            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to mint JWT", ex);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
