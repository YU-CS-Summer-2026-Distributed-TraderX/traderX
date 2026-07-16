package finos.traderx.ordermatcher.auth;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (FR-PTC42 / FR-IMRG02 / FR-IMRG30): the entitlement gate on the order-admission path.
 *
 * <p>The gap this closes: YU03 built the two-tier risk gateway and YU05 built real JWT auth, but
 * nothing ever checked a caller's entitlement to the order's account before the command was
 * sequenced — order submission was wide open. This resolves the caller's principal from the same
 * HS256 JWT the post-trade endpoints already use and checks entitlement to the order's account
 * before the command enters the input ring.
 *
 * <p>Entitlement is carried by the token itself ({@link JwtPrincipal#entitledAccounts()} / {@code
 * admin}), so the check is a memory-only lookup against the resolved principal — no synchronous
 * REST/DB call on the admission path (FR-IMRG01), reusing the existing {@link JwtAuthenticator}.
 *
 * <p>ponytail: gated by {@code risk.entitlement.enforced}, default false, so an unauthenticated UI
 * keeps working until entitlement is turned on. Flip it to true (and have callers send a Bearer
 * token) to enforce full authn on the command path (FR-IMRG30). Upgrade path if entitlements ever
 * need to be revocable independently of the token's lifetime: resolve them from a control-fed
 * gateway replica (FR-IMRG02's original design) instead of the token claim.
 */
public final class EntitlementGate {
    private EntitlementGate() {}

    /**
     * Authentication result for one HTTP request. The HMAC verification and JWT decode happen
     * once in {@link EntitlementGate#resolve}; account checks reuse this immutable principal.
     */
    public record ResolvedPrincipal(boolean enforced, JwtPrincipal principal) {
        public ResolvedPrincipal {
            if (enforced && principal == null) {
                throw new IllegalArgumentException("an enforced entitlement requires a principal");
            }
        }

        public void checkAccount(int accountId) {
            if (enforced && !principal.isEntitledTo(accountId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "not entitled to account " + accountId);
            }
        }
    }

    /** Resolve and validate the caller once for the whole request. */
    public static ResolvedPrincipal resolve(
        JwtAuthenticator jwt,
        boolean enforced,
        String authorization
    ) {
        if (!enforced) {
            return new ResolvedPrincipal(false, null);
        }
        Optional<JwtPrincipal> principal = jwt.validate(authorization);
        if (principal.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing or invalid token");
        }
        return new ResolvedPrincipal(true, principal.get());
    }

    /**
     * @throws ResponseStatusException 401 if enforcement is on and the token is missing/invalid,
     *                                 403 if the token is valid but not entitled to {@code accountId}.
     */
    public static void check(JwtAuthenticator jwt, boolean enforced, int accountId, String authorization) {
        resolve(jwt, enforced, authorization).checkAccount(accountId);
    }
}
