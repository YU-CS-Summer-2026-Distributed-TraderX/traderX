package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** FR-DRS-53: the gateway's fills stream and subject. The read model's FillsRouterTest pins the same values: the two
 *  must agree, or a gateway reads a stream nothing is routed to. */
class RoutedFillsShardTest {
    @Test
    void aGatewaysShardIsPinned() {
        assertEquals(4, ClusterGatewayMain.fillsShard("gw-a", 20));
        assertEquals(17, ClusterGatewayMain.fillsShard("cluster-gateway-6f8d9c7b5-x2k4q", 20));   // a negative hashCode
        assertArrayEquals(new String[] {"TRADERX_FILLS_5", "/fillsk.5.gw-b"}, ClusterGatewayMain.routedShape("gw-b", "TRADERX_FILLS", 20));
        assertArrayEquals(new String[] {"TRADERX_FILLS", "/fills.gw-b"}, ClusterGatewayMain.routedShape("gw-b", "TRADERX_FILLS", 1));
    }
}
