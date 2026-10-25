package finos.traderx.ordermatcher.cluster;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Test support (ENGINE-REVIEW S1): mint an HS256 bearer token the gateway under test accepts — signed
 *  with the suite's AUTH_JWT_SECRET (build.gradle sets one for every worker), else the code default. */
final class DevJwt {
    static final String DEV_SECRET = System.getenv().getOrDefault("AUTH_JWT_SECRET", "dev-jwt-shared-secret");

    private DevJwt() {
    }

    static String bearer(final int... accounts) {
        final StringBuilder list = new StringBuilder();
        for (final int a : accounts) {
            list.append(list.length() == 0 ? "" : ",").append(a);
        }
        return "Bearer " + token("{\"sub\":\"test\",\"accounts\":[" + list + "]}", DEV_SECRET);
    }

    static String adminBearer() {
        return "Bearer " + token("{\"sub\":\"ops\",\"admin\":true}", DEV_SECRET);
    }

    static String token(final String payloadJson, final String secret) {
        try {
            final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            final String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            final String payload = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final String sig = b64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return header + "." + payload + "." + sig;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
