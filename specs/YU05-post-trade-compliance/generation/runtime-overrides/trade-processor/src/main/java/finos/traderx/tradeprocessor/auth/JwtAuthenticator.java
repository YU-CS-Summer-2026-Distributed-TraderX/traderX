package finos.traderx.tradeprocessor.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * YU05 (post-trade-compliance, ADR-025, FR-PTC40): real HS256 JWT signature verification + claim
 * extraction. Mirrors order-matcher's class of the same name exactly (no shared library between
 * these two Gradle modules) — see that copy's javadoc for the full rationale.
 */
public final class JwtAuthenticator {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretKeySpec key;

    public JwtAuthenticator(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public Optional<JwtPrincipal> validate(String authorizationHeaderOrToken) {
        if (authorizationHeaderOrToken == null || authorizationHeaderOrToken.isBlank()) {
            return Optional.empty();
        }
        String token = authorizationHeaderOrToken.startsWith("Bearer ")
            ? authorizationHeaderOrToken.substring(7).trim()
            : authorizationHeaderOrToken.trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            String signingInput = parts[0] + "." + parts[1];
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] expectedSignature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actualSignature = decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                return Optional.empty();
            }
            JsonNode payload = mapper.readTree(decode(parts[1]));
            long exp = payload.path("exp").asLong(0);
            if (exp > 0 && exp < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            String subject = payload.path("sub").asText(null);
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            boolean admin = payload.path("admin").asBoolean(false);
            Set<Integer> accounts = new HashSet<>();
            if (payload.has("accounts") && payload.get("accounts").isArray()) {
                for (JsonNode n : payload.get("accounts")) {
                    accounts.add(n.asInt());
                }
            }
            return Optional.of(new JwtPrincipal(subject, accounts, admin));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static byte[] decode(String base64url) {
        int rem = base64url.length() % 4;
        String padded = rem == 0 ? base64url : base64url + "====".substring(rem);
        return Base64.getUrlDecoder().decode(padded);
    }
}
