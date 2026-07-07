package finos.traderx.ordermatcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-025, FR-PTC40): order-matcher's copy of the JWT verifier
 * (mirrors trade-processor's — no shared library between the two Gradle modules). Full coverage
 * lives in trade-processor's {@code JwtAuthenticatorTest}; this confirms this module's copy
 * behaves identically for the two things order-matcher actually gates on: admin claim + a
 * tampered/wrong-secret token being rejected.
 */
class JwtAuthenticatorTest {
    private static final String SECRET = "test-shared-secret";

    @Test
    void validAdminTokenRoundTrips() {
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("ops-team", Set.of(), true, 3600);
        Optional<JwtPrincipal> principal = authenticator.validate("Bearer " + token);

        assertTrue(principal.isPresent());
        assertTrue(principal.get().admin());
        assertEquals("ops-team", principal.get().subject());
    }

    @Test
    void tokenSignedWithWrongSecretIsRejected() {
        JwtTokenMinter minter = new JwtTokenMinter("wrong-secret");
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("eve", Set.of(), true, 3600);
        assertTrue(authenticator.validate(token).isEmpty());
    }

    @Test
    void nonAdminTokenIsNotEntitledToAdminOnlyAction() {
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("trader", Set.of(22214), false, 3600);
        JwtPrincipal principal = authenticator.validate(token).orElseThrow();

        assertFalse(principal.admin());
    }
}
