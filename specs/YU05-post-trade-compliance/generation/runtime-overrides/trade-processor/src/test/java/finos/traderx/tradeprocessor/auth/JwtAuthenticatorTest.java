package finos.traderx.tradeprocessor.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-025, FR-PTC40): real HS256 signature verification — a token
 * signed with the wrong secret, tampered after signing, or expired must all be rejected; a
 * validly-signed token must round-trip its claims exactly.
 */
class JwtAuthenticatorTest {
    private static final String SECRET = "test-shared-secret";

    @Test
    void validTokenRoundTripsClaimsExactly() {
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("alice", Set.of(22214, 44044), false, 3600);
        Optional<JwtPrincipal> principal = authenticator.validate("Bearer " + token);

        assertTrue(principal.isPresent());
        assertEquals("alice", principal.get().subject());
        assertEquals(Set.of(22214, 44044), principal.get().entitledAccounts());
        assertFalse(principal.get().admin());
    }

    @Test
    void bareTokenWithoutBearerPrefixAlsoValidates() {
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("bob", Set.of(), true, 3600);
        Optional<JwtPrincipal> principal = authenticator.validate(token);

        assertTrue(principal.isPresent());
        assertTrue(principal.get().admin());
    }

    @Test
    void tokenSignedWithWrongSecretIsRejected() {
        JwtTokenMinter minter = new JwtTokenMinter("wrong-secret");
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("eve", Set.of(1), false, 3600);
        assertTrue(authenticator.validate(token).isEmpty());
    }

    @Test
    void tamperedPayloadIsRejectedEvenWithOriginalSignature() {
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("mallory", Set.of(1), false, 3600);
        String[] parts = token.split("\\.");
        // Flip the payload to claim admin, keeping the original (now-invalid) signature.
        String forgedAdminPayloadB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"mallory\",\"admin\":true,\"accounts\":[]}".getBytes());
        String forged = parts[0] + "." + forgedAdminPayloadB64 + "." + parts[2];

        assertTrue(authenticator.validate(forged).isEmpty());
    }

    @Test
    void expiredTokenIsRejected() {
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        String token = minter.mint("carol", Set.of(1), false, -10); // expired 10s ago
        assertTrue(authenticator.validate(token).isEmpty());
    }

    @Test
    void nonExpiringTokenWhenTtlIsZeroOrNegativeButNotUsedForExpiryCheck() {
        // ttlSeconds<=0 means "no exp claim at all" per JwtTokenMinter.mint's contract when 0 is
        // passed explicitly (used by service-to-service tokens) — verified via the ReconciliationService
        // usage pattern (mint(..., 0L)), not re-tested here since it's exercised by that class's own tests.
        JwtTokenMinter minter = new JwtTokenMinter(SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);
        String token = minter.mint("service-account", Set.of(), true, 0L);
        assertTrue(authenticator.validate(token).isPresent());
    }

    @Test
    void malformedTokenIsRejected() {
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);
        assertTrue(authenticator.validate("not-a-jwt").isEmpty());
        assertTrue(authenticator.validate(null).isEmpty());
        assertTrue(authenticator.validate("").isEmpty());
    }

    @Test
    void isEntitledToRespectsAdminOverride() {
        JwtPrincipal admin = new JwtPrincipal("root", Set.of(), true);
        JwtPrincipal scoped = new JwtPrincipal("alice", Set.of(22214), false);

        assertTrue(admin.isEntitledTo(99999));
        assertTrue(scoped.isEntitledTo(22214));
        assertFalse(scoped.isEntitledTo(99999));
    }
}
