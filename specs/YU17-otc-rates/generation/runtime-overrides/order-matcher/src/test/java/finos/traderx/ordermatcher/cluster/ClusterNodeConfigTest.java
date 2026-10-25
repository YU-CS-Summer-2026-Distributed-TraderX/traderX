package finos.traderx.ordermatcher.cluster;

import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClusterNodeConfigTest {

    @Test
    void memberThreadingDefaultsToDedicatedAndParsesTheOthers() {
        assertEquals(ThreadingMode.DEDICATED, ClusterNodeConfig.memberThreading(null));
        assertEquals(ThreadingMode.DEDICATED, ClusterNodeConfig.memberThreading(" "));
        assertEquals(ThreadingMode.SHARED, ClusterNodeConfig.memberThreading("shared"));
        assertEquals(ThreadingMode.SHARED_NETWORK, ClusterNodeConfig.memberThreading(" SHARED_NETWORK "));
        assertThrows(IllegalArgumentException.class, () -> ClusterNodeConfig.memberThreading("dedicate"));
    }

    @Test
    void maxSessionsDefaultsTo64() {
        assertEquals(64, ClusterNodeConfig.maxSessions(null));
        assertEquals(64, ClusterNodeConfig.maxSessions(""));
        assertEquals(128, ClusterNodeConfig.maxSessions("128"));
    }

    @Test
    void contextsCarryTheDefaultsWhenNothingIsSet() {
        // Guards the wiring, not just the parsers: an unset environment must reach the contexts.
        assertNull(System.getenv("CLUSTER_MEDIA_DRIVER_THREADING"));
        assertNull(System.getenv("CLUSTER_MAX_SESSIONS"));
        assertNull(System.getProperty("aeron.cluster.max.sessions"));
        final ClusterNodeConfig.Contexts c = ClusterNodeConfig.contexts(0, List.of("localhost", "localhost", "localhost"),
            9000, "/tmp/unused-aeron", new File("/tmp/unused-cluster"), null, false);
        assertEquals(ThreadingMode.DEDICATED, c.mediaDriver().threadingMode());
        assertEquals(64, c.consensusModule().maxConcurrentSessions());
    }
}
