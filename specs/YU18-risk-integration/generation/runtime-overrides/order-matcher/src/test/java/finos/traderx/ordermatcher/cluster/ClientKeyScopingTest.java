package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.InputEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** ENGINE-REVIEW G1: the idempotency key a pending order carries is scoped by account. */
class ClientKeyScopingTest {
    static ClusterGatewayMain.PendingOrder newOrder(final int account, final long key) {
        return new ClusterGatewayMain.PendingOrder(InputEvent.TYPE_ORDER_NEW, account, "AAPL", 'B',
            10, 100_000_000L, key, 0);
    }

    @Test void sameRawKeyFromDifferentAccountsNeverCollides() {
        assertNotEquals(newOrder(1, 42L).clientKey, newOrder(2, 42L).clientKey);
        assertNotEquals(newOrder(1, 1L).clientKey, newOrder(2, 1L).clientKey);
        assertNotEquals(42L, newOrder(1, 42L).clientKey, "the raw key is not used as-is");
    }

    @Test void sameAccountSameKeyIsStableAcrossRetries() {
        assertEquals(newOrder(7, 42L).clientKey, newOrder(7, 42L).clientKey);
    }

    @Test void noKeyStaysNoKeyAndUnclaimedAccountLeavesKeyRaw() {
        assertEquals(0L, newOrder(1, 0L).clientKey, "0 means 'no key' and must survive");
        assertEquals(42L, newOrder(0, 42L).clientKey, "no account claim: unscoped, consistent per path");
        assertNotEquals(0L, ClusterGatewayMain.PendingOrder.scopeKey(0x9e3779b97f4a7c15L, 1),
            "a non-zero input never maps onto the no-key sentinel");
    }
}
