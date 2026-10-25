package finos.traderx.ordermatcher.cluster;

import com.sun.net.httpserver.HttpExchange;
import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import finos.traderx.ordermatcher.auth.JwtPrincipal;

import java.util.Optional;

/**
 * ENGINE-REVIEW S1: who may act on which account over the gateway's HTTP order routes.
 *
 * <p>Order entry carried no identity at all — the body's {@code accountId} was the whole claim —
 * on a listener the GKE manifests expose through a LoadBalancer. The members already validate the
 * same HS256 JWT ({@code AUTH_JWT_SECRET}, ADR-025) for their admin routes; the gateway now
 * requires it on every route that creates, changes or books against an account, and the account
 * must be one the principal is entitled to. That also makes the G3 ownership claim a real one.
 *
 * <p>The decision is a pure function ({@link #accountFor}) so it is unit-tested without a server.
 */
final class GatewayAccess {
    static final int UNAUTHENTICATED = -401;
    static final int FORBIDDEN = -403;
    static final int ACCOUNT_REQUIRED = -400;

    private final JwtAuthenticator jwt;

    GatewayAccess(final JwtAuthenticator jwt) {
        this.jwt = jwt;
    }

    Optional<JwtPrincipal> principal(final HttpExchange exchange) {
        return jwt.validate(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    /**
     * The account a request may act on. {@code requested} is the body's accountId (0 = absent).
     * Returns the account, 0 for "no claim" (admin only, and only when the route does not
     * {@code require} one — a cancel/replace by an operator), or a negative HTTP status.
     * A non-admin with exactly one entitled account may omit the field.
     */
    static int accountFor(final Optional<JwtPrincipal> principal, final int requested, final boolean require) {
        if (principal.isEmpty()) {
            return UNAUTHENTICATED;
        }
        final JwtPrincipal p = principal.get();
        if (requested != 0) {
            return p.isEntitledTo(requested) ? requested : FORBIDDEN;
        }
        if (p.entitledAccounts().size() == 1) {
            return p.entitledAccounts().iterator().next();
        }
        return p.admin() && !require ? 0 : ACCOUNT_REQUIRED;
    }

    /** {@link #accountFor} for a live exchange: answers the refusal itself and returns a negative. */
    int requireAccount(final HttpExchange exchange, final int requested, final boolean require) {
        final int account = accountFor(principal(exchange), requested, require);
        switch (account) {
            case UNAUTHENTICATED -> ClusterGatewayMain.respond(exchange, 401, "{\"error\":\"bearer JWT required\"}");
            case FORBIDDEN -> ClusterGatewayMain.respond(exchange, 403, "{\"error\":\"not entitled to accountId\"}");
            case ACCOUNT_REQUIRED -> ClusterGatewayMain.respond(exchange, 400, "{\"error\":\"accountId required\"}");
            default -> { }
        }
        return account;
    }

    /** Every element of a batch must name an account the principal is entitled to. */
    boolean requireAll(final HttpExchange exchange, final Iterable<com.fasterxml.jackson.databind.JsonNode> orders) {
        final Optional<JwtPrincipal> p = principal(exchange);
        if (p.isEmpty()) {
            ClusterGatewayMain.respond(exchange, 401, "{\"error\":\"bearer JWT required\"}");
            return false;
        }
        for (final com.fasterxml.jackson.databind.JsonNode o : orders) {
            if (!p.get().isEntitledTo(o.path("accountId").asInt())) {
                ClusterGatewayMain.respond(exchange, 403, "{\"error\":\"not entitled to accountId\"}");
                return false;
            }
        }
        return true;
    }
}
