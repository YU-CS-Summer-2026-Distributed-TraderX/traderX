package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exact-zero allocation gate for the cluster service apply path (NFR-AC01): SBE decode →
 * generator assign → engine (match + risk) → output drain, driven through the real
 * {@code onSessionMessage} entry on one dedicated thread, exactly as the clustered service
 * container runs it. Two consecutive exact-zero steady-state windows are required, mirroring
 * the inherited base/risk/Aeron gates.
 *
 * The order-reference index grows by amortized doubling as refs climb; that is startup-shaped,
 * not steady-state, so setup pre-grows it past every reference this run can issue (a single
 * bootstrap row at a high ref) — the same reasoning as the inherited gates' warm-up contracts.
 */
class ClusterServiceAllocationGateTest {
    private static final long PX = 1_000_000L;
    private static final int ACCOUNT = 11;
    private static final int SECURITY = 1;
    private static final int PRESIZE_REF = (1 << 20) - 1;

    @Test
    void clusterApplyPathIsAllocationFreeInSteadyState() throws Exception {
        final var baseMx = ManagementFactory.getThreadMXBean();
        assumeTrue(baseMx instanceof com.sun.management.ThreadMXBean);
        final var threadMx = (com.sun.management.ThreadMXBean) baseMx;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported());
        threadMx.setThreadAllocatedMemoryEnabled(true);

        final int warmupEvents = Integer.getInteger("gate.warmupEvents", 250_000);
        final int steadyStateEvents = Integer.getInteger("gate.steadyStateEvents", 1_000_000);

        final AtomicLong threadId = new AtomicLong(-1);
        final int[] consecutiveZero = { 0 };
        final long[] lastDelta = { -1 };
        final Thread gate = new Thread(() -> {
            threadId.set(Thread.currentThread().threadId());
            final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
            service.initEngine();

            final AeronReplicationCodec codec = new AeronReplicationCodec();
            final InputEvent event = new InputEvent();
            final UnsafeBuffer order = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
            final UnsafeBuffer tick = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
            final UnsafeBuffer cancel = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
            final UnsafeBuffer control = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
            long timestamp = 1_000_000_000_000L;

            // Seed control state through the real ingress path.
            event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            event.accountId = ACCOUNT;
            event.setControlEnabled(true);
            event.setControlVersion(1L);
            codec.encodeInput(control, 0, event, 0, 0, 0);
            service.onSessionMessage(null, ++timestamp, control, 0, AeronReplicationCodec.INPUT_BYTES, null);
            event.type = InputEvent.TYPE_SECURITY_CONTROL;
            event.accountId = 0;
            event.securityId = SECURITY;
            event.setControlVersion(2L);
            codec.encodeInput(control, 0, event, 0, 0, 0);
            service.onSessionMessage(null, ++timestamp, control, 0, AeronReplicationCodec.INPUT_BYTES, null);

            // Pre-grow the ref index past every reference this run can issue.
            service.engine().bootstrapOrder(PRESIZE_REF, ACCOUNT, SECURITY + 1, InputEvent.SIDE_BUY,
                1, 1, PX, RestingOrder.STATUS_NEW, PX, 0, 0L, 0L);

            // Marketable order (fills fully, goes terminal, recycles), ticks, and a
            // cancel-of-unknown (not-found path) — encoded once, replayed forever.
            event.type = InputEvent.TYPE_PRICE_TICK;
            event.side = 0;
            event.securityId = SECURITY;
            event.priceTicks = 150 * PX;
            codec.encodeInput(tick, 0, event, 0, 0, 0);
            event.type = InputEvent.TYPE_ORDER_NEW;
            event.side = InputEvent.SIDE_BUY;
            event.orderRef = 0;
            event.accountId = ACCOUNT;
            event.securityId = SECURITY;
            event.qty = 10;
            event.limitPx = 200 * PX;
            event.priceTicks = 0;
            codec.encodeInput(order, 0, event, 0, 0, 0);
            event.type = InputEvent.TYPE_ORDER_CANCEL;
            event.orderRef = 1; // long since evicted in steady state -> not-found path
            codec.encodeInput(cancel, 0, event, 0, 0, 0);

            for (int i = 0; i < warmupEvents; i++) {
                timestamp = applyMix(service, i, order, tick, cancel, timestamp);
            }

            final long ownId = Thread.currentThread().threadId();
            for (int window = 0; window < 4 && consecutiveZero[0] < 2; window++) {
                threadMx.getThreadAllocatedBytes(ownId);
                final long before = threadMx.getThreadAllocatedBytes(ownId);
                for (int i = 0; i < steadyStateEvents; i++) {
                    timestamp = applyMix(service, i, order, tick, cancel, timestamp);
                }
                final long delta = threadMx.getThreadAllocatedBytes(ownId) - before;
                lastDelta[0] = delta;
                if (delta == 0) {
                    consecutiveZero[0]++;
                } else {
                    consecutiveZero[0] = 0;
                }
            }
        }, "cluster-service-gate");

        gate.start();
        gate.join(300_000);
        assertEquals(2, consecutiveZero[0],
            "cluster apply path did not produce two consecutive exact-zero windows; last delta="
                + lastDelta[0] + " bytes");
    }

    private static long applyMix(final MatchingEngineClusteredService service, final int i,
                                 final UnsafeBuffer order, final UnsafeBuffer tick,
                                 final UnsafeBuffer cancel, final long timestamp) {
        final UnsafeBuffer next = switch (i % 5) {
            case 0 -> order;
            case 4 -> cancel;
            default -> tick;
        };
        final long ts = timestamp + 1;
        service.onSessionMessage(null, ts, next, 0, AeronReplicationCodec.INPUT_BYTES, null);
        return ts;
    }
}
