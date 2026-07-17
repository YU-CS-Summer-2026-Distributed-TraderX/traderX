package finos.traderx.ordermatcher.lmax;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC authentication for the bootstrap-only HTTP request and bundle manifest. */
public final class AeronBootstrapAuth {
    public static final long MAX_CLOCK_SKEW_MS = 30_000L;

    private AeronBootstrapAuth() { }

    public static String requestTag(byte[] secret, String peerId, long epoch,
                                    long correlationId, long issuedAtMillis) {
        String canonical = peerId + '\n' + epoch + '\n' + correlationId + '\n' + issuedAtMillis;
        return HexFormat.of().formatHex(hmac(secret,
            canonical.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean verifyRequest(byte[] secret, String expectedPeerId, long epoch,
                                        long correlationId, long issuedAtMillis, String tag,
                                        long nowMillis) {
        if (tag == null || Math.abs(nowMillis - issuedAtMillis) > MAX_CLOCK_SKEW_MS) return false;
        try {
            byte[] received = HexFormat.of().parseHex(tag);
            byte[] expected = HexFormat.of().parseHex(requestTag(secret, expectedPeerId,
                epoch, correlationId, issuedAtMillis));
            return MessageDigest.isEqual(received, expected);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static byte[] hmac(byte[] secret, byte[] bytes) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("replication HMAC secret must contain at least 32 bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
