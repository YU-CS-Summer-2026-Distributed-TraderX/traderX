package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskAllocationGateTest {
    @Test
    void authoritativeCheckAndReserveAllocatesZeroBytesInSteadyState() {
        GatewayReplicaStore.Snapshot snapshot = new GatewayReplicaStore.Snapshot(1L, 2L, 2L, 1L, true,
            List.of(new GatewayReplicaStore.AccountRecord(22214, true, 1L)),
            List.of(new GatewayReplicaStore.SecurityRecord(0, "IBM", true, false, 1L, 1L, 2L)));
        BlpRiskState state = new BlpRiskState(16, 16, 65_536, 65_536,
            Long.MAX_VALUE, 1_000_000, Long.MAX_VALUE, Long.MAX_VALUE, new RiskMetrics());
        state.bootstrap(snapshot);

        for (int i = 1; i <= 5_000; i++) {
            assertEquals(RiskReason.ACCEPTED,
                state.decideAndReserve(i, i, 22214, 0, 1, 1L, 1L));
        }

        com.sun.management.ThreadMXBean bean =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 5_001; i <= 15_000; i++) {
            state.decideAndReserve(i, i, 22214, 0, 1, 1L, 1L);
        }
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        assertEquals(0L, allocated, "BLP risk decision allocated bytes");
    }

    @Test
    void mixedDecisionControlAndReleasePathAllocatesZeroBytesAfterWarmup() {
        GatewayReplicaStore.Snapshot snapshot = new GatewayReplicaStore.Snapshot(1L, 2L, 2L, 1L, true,
            List.of(new GatewayReplicaStore.AccountRecord(22214, true, 1L)),
            List.of(new GatewayReplicaStore.SecurityRecord(0, "IBM", true, false, 10L, 1L, 2L)));
        BlpRiskState state = new BlpRiskState(16, 16, 32_768, 32_768,
            Long.MAX_VALUE, 1_000_000, Long.MAX_VALUE, Long.MAX_VALUE, new RiskMetrics());
        state.bootstrap(snapshot);

        for (int i = 1; i <= 4_000; i++) {
            state.putRestriction(0, (i & 7) == 0);
            state.decideAndReserve(i, i, 22214, 0, 1, 10L, 1L);
            state.putRestriction(0, false);
            state.release(22214, i);
        }

        com.sun.management.ThreadMXBean bean =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        // Exercise the measurement boundary once as well; management/JIT bookkeeping is not part
        // of the risk path and can otherwise appear as a one-time sub-kilobyte allocation.
        bean.getThreadAllocatedBytes(threadId);
        for (int i = 4_001; i <= 8_000; i++) {
            state.putRestriction(0, (i & 7) == 0);
            state.decideAndReserve(i, i, 22214, 0, 1, 10L, 1L);
            state.putRestriction(0, false);
            state.release(22214, i);
        }
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 8_001; i <= 16_000; i++) {
            state.putRestriction(0, (i & 7) == 0);
            state.decideAndReserve(i, i, 22214, 0, 1, 10L, 1L);
            state.putRestriction(0, false);
            state.release(22214, i);
        }
        assertEquals(0L, bean.getThreadAllocatedBytes(threadId) - before,
            "mixed BLP risk path allocated bytes");
    }
}
