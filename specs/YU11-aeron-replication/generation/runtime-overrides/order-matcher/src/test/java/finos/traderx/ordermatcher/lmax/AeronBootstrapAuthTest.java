package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AeronBootstrapAuthTest {
    private static final byte[] SECRET =
        "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void authenticatesTheExpectedPeerAndRequestCoordinates() {
        long now = 1_700_000_000_000L;
        String tag = AeronBootstrapAuth.requestTag(SECRET, "order-matcher-1", 7L, 99L, now);

        assertThat(AeronBootstrapAuth.verifyRequest(
            SECRET, "order-matcher-1", 7L, 99L, now, tag, now)).isTrue();
        assertThat(AeronBootstrapAuth.verifyRequest(
            SECRET, "order-matcher-0", 7L, 99L, now, tag, now)).isFalse();
        assertThat(AeronBootstrapAuth.verifyRequest(
            SECRET, "order-matcher-1", 8L, 99L, now, tag, now)).isFalse();
    }

    @Test
    void rejectsExpiredAndMalformedTags() {
        long issuedAt = 1_700_000_000_000L;
        String tag = AeronBootstrapAuth.requestTag(
            SECRET, "order-matcher-1", 7L, 99L, issuedAt);

        assertThat(AeronBootstrapAuth.verifyRequest(SECRET, "order-matcher-1", 7L, 99L,
            issuedAt, tag, issuedAt + AeronBootstrapAuth.MAX_CLOCK_SKEW_MS + 1L)).isFalse();
        assertThat(AeronBootstrapAuth.verifyRequest(SECRET, "order-matcher-1", 7L, 99L,
            issuedAt, "not-hex", issuedAt)).isFalse();
    }
}
