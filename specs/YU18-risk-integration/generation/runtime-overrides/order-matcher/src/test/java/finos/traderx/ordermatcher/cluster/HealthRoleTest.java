package finos.traderx.ordermatcher.cluster;

import io.aeron.AeronCounters;
import io.aeron.cluster.service.Cluster;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FR-GMR-15: {@code /health}'s role follows the consensus module's node-role counter. The case from GKE 2026-11-12:
 * the service still holds LEADER, the consensus module has closed and freed its counters, and the member must not
 * answer LEADER.
 */
class HealthRoleTest {
    private final CountersManager counters = new CountersManager(
        new UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(64 * 1024)), new UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(4 * 1024)));

    private int allocate(final int typeId) {
        return counters.allocate("role", typeId, key -> key.putInt(0, 0));   // key: clusterId 0
    }

    @Test
    void aClosedConsensusModuleIsNotALeader() {
        final ClusterNodeMain.ConsensusRoleCounter role = new ClusterNodeMain.ConsensusRoleCounter();
        assertEquals("FOLLOWER", ClusterNodeMain.healthRole(Cluster.Role.FOLLOWER, role.poll(counters)), "start-up: the service's role stands");

        final int id = allocate(AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID);
        counters.setCounterValue(id, Cluster.Role.LEADER.code());
        assertEquals("LEADER", ClusterNodeMain.healthRole(Cluster.Role.LEADER, role.poll(counters)));

        counters.free(id);   // the consensus module closed; the service was never told
        assertEquals("DOWN", ClusterNodeMain.healthRole(Cluster.Role.LEADER, role.poll(counters)));
    }

    @Test
    void theConsensusModulesRoleWinsOverTheServices() {
        final ClusterNodeMain.ConsensusRoleCounter role = new ClusterNodeMain.ConsensusRoleCounter();
        final int id = allocate(AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID);
        counters.setCounterValue(id, Cluster.Role.FOLLOWER.code());
        assertEquals("FOLLOWER", ClusterNodeMain.healthRole(Cluster.Role.LEADER, role.poll(counters)));
    }

    @Test
    void aReusedCounterIdIsNotTheRole() {
        final ClusterNodeMain.ConsensusRoleCounter role = new ClusterNodeMain.ConsensusRoleCounter();
        final int id = allocate(AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID);
        counters.setCounterValue(id, Cluster.Role.LEADER.code());
        role.poll(counters);
        counters.free(id);
        final int reused = counters.allocate("other", 0);
        counters.setCounterValue(reused, Cluster.Role.LEADER.code());
        assertEquals("DOWN", ClusterNodeMain.healthRole(Cluster.Role.LEADER, role.poll(counters)));
    }
}
