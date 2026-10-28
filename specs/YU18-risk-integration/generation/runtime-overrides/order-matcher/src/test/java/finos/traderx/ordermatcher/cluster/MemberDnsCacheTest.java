package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FR-GMR-09: a member JVM never caches a failed name lookup (the JDK default keeps one for 10 s). */
class MemberDnsCacheTest {
    @Test
    void loadingTheMemberClassTurnsOffNegativeDnsCaching() throws Exception {
        Class.forName("finos.traderx.ordermatcher.cluster.ClusterNodeMain");
        assertEquals("0", java.security.Security.getProperty("networkaddress.cache.negative.ttl"));
    }
}
