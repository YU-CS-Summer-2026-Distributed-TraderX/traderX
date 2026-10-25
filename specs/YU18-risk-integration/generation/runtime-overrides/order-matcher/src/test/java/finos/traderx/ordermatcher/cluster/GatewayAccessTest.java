package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.auth.JwtPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENGINE-REVIEW S1: the account a gateway request may act on is bound to the JWT principal. */
class GatewayAccessTest {
    static final Optional<JwtPrincipal> NONE = Optional.empty();
    static final Optional<JwtPrincipal> ONE = Optional.of(new JwtPrincipal("a", Set.of(11), false));
    static final Optional<JwtPrincipal> TWO = Optional.of(new JwtPrincipal("b", Set.of(11, 12), false));
    static final Optional<JwtPrincipal> ADMIN = Optional.of(new JwtPrincipal("ops", Set.of(), true));

    @Test void noTokenIsUnauthenticatedWhateverTheBodySays() {
        assertEquals(GatewayAccess.UNAUTHENTICATED, GatewayAccess.accountFor(NONE, 11, true));
        assertEquals(GatewayAccess.UNAUTHENTICATED, GatewayAccess.accountFor(NONE, 0, false));
    }

    @Test void bodyAccountMustBeEntitled() {
        assertEquals(11, GatewayAccess.accountFor(ONE, 11, true));
        assertEquals(GatewayAccess.FORBIDDEN, GatewayAccess.accountFor(ONE, 12, true));
        assertEquals(12, GatewayAccess.accountFor(TWO, 12, true));
        assertEquals(99, GatewayAccess.accountFor(ADMIN, 99, true), "admin may name any account");
    }

    @Test void omittedAccountDefaultsOnlyWhenUnambiguous() {
        assertEquals(11, GatewayAccess.accountFor(ONE, 0, true), "single entitlement is the default");
        assertEquals(GatewayAccess.ACCOUNT_REQUIRED, GatewayAccess.accountFor(TWO, 0, true));
        assertEquals(GatewayAccess.ACCOUNT_REQUIRED, GatewayAccess.accountFor(ADMIN, 0, true),
            "an order needs an account even from an operator");
        assertEquals(0, GatewayAccess.accountFor(ADMIN, 0, false), "an operator cancel may carry no claim");
        assertEquals(GatewayAccess.ACCOUNT_REQUIRED, GatewayAccess.accountFor(TWO, 0, false),
            "a client cancel must claim one of its accounts");
    }
}
