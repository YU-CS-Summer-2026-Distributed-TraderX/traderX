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
        // FR-DRS-55: a claimed name carries its stream; FillsRouterTest pins the same
        assertEquals(7, ClusterGatewayMain.fillsShard("gw-a_s7", 20));
        assertArrayEquals(new String[] {"TRADERX_FILLS_7", "/fillsk.7.gw-a_s7"}, ClusterGatewayMain.routedShape("gw-a_s7", "TRADERX_FILLS", 20));
        assertEquals(Math.floorMod("gw-a_s20".hashCode(), 20), ClusterGatewayMain.fillsShard("gw-a_s20", 20));   // outside the count: hashed
        assertEquals(Math.floorMod("gw-a_sx".hashCode(), 20), ClusterGatewayMain.fillsShard("gw-a_sx", 20));
        assertEquals(Math.floorMod("gw-a_s".hashCode(), 20), ClusterGatewayMain.fillsShard("gw-a_s", 20));
    }

    /** FR-DRS-55: run 6's 18 gateways over 20 streams on 5 servers, one stream each and the servers within one of each
     *  other (the hash gave 13 streams, three gateways on one, and 2 / 3 / 4 / 3 / 6 by server). */
    @Test
    void claimsSpreadTheGatewaysOverStreamsAndServers() throws Exception {
        final java.util.Set<Integer> held = new java.util.TreeSet<>();
        final int[] lostRaces = {0};
        final FillsShardClaim.Claims claims = new FillsShardClaim.Claims() {
            public java.util.Set<Integer> held() { return new java.util.TreeSet<>(held); }
            public boolean take(final int k) {
                if (held.size() == 5 && lostRaces[0]++ == 0) { held.add(k); return false; }   // another gateway got there first
                return held.add(k);
            }
        };
        final int[] perServer = new int[5];
        final java.util.Set<Integer> mine = new java.util.TreeSet<>();
        for (int g = 0; g < 18; g++) {
            final int k = FillsShardClaim.choose(claims, "cluster-gateway-76c6dcd95f-" + g, 20, 5);
            org.junit.jupiter.api.Assertions.assertTrue(k >= 0 && mine.add(k), "gateway " + g + " got its own stream, got " + k);
        }
        for (final int k : held) perServer[k % 5]++;   // the 18 plus the one the lost race left held
        assertEquals(19, held.size());
        assertEquals(1, java.util.Arrays.stream(perServer).max().getAsInt() - java.util.Arrays.stream(perServer).min().getAsInt());
        held.add(java.util.stream.IntStream.range(0, 20).filter(k -> !held.contains(k)).findFirst().getAsInt());
        assertEquals(-1, FillsShardClaim.choose(claims, "cluster-gateway-late", 20, 5), "a full table: the hashed stream");
        assertEquals("gw_s3", FillsShardClaim.named("gw", 3));
        assertEquals(3, FillsShardClaim.claimed("gw_s3", 20));
    }
}
