package finos.traderx.ordermatcher.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (FR-PTC42/FR-IMRG30): the entitlement gate on the order-admission path. Exercises the four
 * meaningful states — disabled, no/invalid token, entitled, not entitled — with real HS256 tokens
 * minted by {@link JwtTokenMinter} and verified by {@link JwtAuthenticator}, so the security
 * decision is tested end to end (not against a mock).
 */
class EntitlementGateTest {
    private static final String SECRET = "test-jwt-secret";
    private final JwtAuthenticator jwt = new JwtAuthenticator(SECRET);
    private final JwtTokenMinter minter = new JwtTokenMinter(SECRET);

    private String token(Set<Integer> accounts, boolean admin) {
        return "Bearer " + minter.mint("demo", accounts, admin, 600);
    }

    @Test
    void disabledGateAllowsEverythingIncludingNoToken() {
        assertDoesNotThrow(() -> EntitlementGate.check(jwt, false, 42, null));
        assertDoesNotThrow(() -> EntitlementGate.check(jwt, false, 42, "garbage"));
    }

    @Test
    void enforcedWithoutTokenIsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> EntitlementGate.check(jwt, true, 42, null));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void enforcedWithInvalidTokenIsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> EntitlementGate.check(jwt, true, 42, "Bearer not.a.jwt"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void enforcedWithEntitledTokenPasses() {
        assertDoesNotThrow(() -> EntitlementGate.check(jwt, true, 42, token(Set.of(42, 7), false)));
    }

    @Test
    void oneResolvedPrincipalCanAuthorizeEveryAccountInABatch() {
        EntitlementGate.ResolvedPrincipal principal =
            EntitlementGate.resolve(jwt, true, token(Set.of(42, 7), false));

        assertDoesNotThrow(() -> principal.checkAccount(42));
        assertDoesNotThrow(() -> principal.checkAccount(7));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> principal.checkAccount(9));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void enforcedWithUnentitledTokenIsForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> EntitlementGate.check(jwt, true, 42, token(Set.of(7, 9), false)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void enforcedWithAdminTokenPassesAnyAccount() {
        assertDoesNotThrow(() -> EntitlementGate.check(jwt, true, 999, token(Set.of(), true)));
    }

    @Test
    void enforcedRejectsAWrongSecretToken() {
        String forged = "Bearer " + new JwtTokenMinter("other-secret").mint("demo", Set.of(42), true, 600);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> EntitlementGate.check(jwt, true, 42, forged));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }
}
