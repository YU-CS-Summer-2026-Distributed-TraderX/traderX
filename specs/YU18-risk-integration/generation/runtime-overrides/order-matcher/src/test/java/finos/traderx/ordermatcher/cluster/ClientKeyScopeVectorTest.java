package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ADR-093: the read model scopes a raw client key exactly as the gateway does; OrderViewTest pins the same vectors. */
class ClientKeyScopeVectorTest {
    @Test
    void theGatewayScopesTheClientKeyAsTheReadModelDoes() {
        assertEquals("1509528116303909258",
            Long.toUnsignedString(ClusterGatewayMain.PendingOrder.scopeKey((0x00012345L << 32) | 7L, 65000)));
        assertEquals("11752876707446874652", Long.toUnsignedString(ClusterGatewayMain.PendingOrder.scopeKey(-1L, 11)));
    }
}
