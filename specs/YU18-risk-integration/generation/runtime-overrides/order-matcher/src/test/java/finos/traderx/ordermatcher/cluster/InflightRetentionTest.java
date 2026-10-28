package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.InputEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-092 FR-FGM-06: the gateway holds the in-flight window, not every order of the last 12 s. f15hx (400K/s, 18
 * gateways) kept 266,633 answered orders per gateway in the deadline queue: ~110 MB of a 155 MB live heap.
 */
class InflightRetentionTest {
    private static final int WINDOW = 64;

    private static ClusterGatewayMain.PendingOrder offered(final long requestId, final long reapAt) {
        final var p = new ClusterGatewayMain.PendingOrder(InputEvent.TYPE_ORDER_NEW, 1, "IBM", 'B', 10, 1, 0, 0);
        p.requestId = requestId;
        p.reapAtMillis = reapAt;
        return p;
    }

    @Test
    void answeredOrdersAreNotHeldUntilTheirDeadline() throws Exception {
        final var inflight = new ClusterGatewayMain.Inflight(WINDOW);
        final int orders = 100 * WINDOW;
        for (int id = 1; id <= orders; id++) {
            assertTrue(inflight.acquire(0));
            inflight.register(offered(id, 12_000));
            assertEquals(id, inflight.onDirectAck(id).requestId);   // answered at once, as at rate
            inflight.release();
        }
        assertTrue(inflight.queued() <= 2 * WINDOW, orders + " answered orders left " + inflight.queued()
            + " queued; the bound is " + 2 * WINDOW);
    }

    @Test
    void aStrandedOrderIsStillReapedAtItsDeadlineInOrder() throws Exception {
        final var inflight = new ClusterGatewayMain.Inflight(WINDOW);
        final var stranded = offered(1, 1_000);
        assertTrue(inflight.acquire(0));
        inflight.register(stranded);
        for (int id = 2; id <= 10 * WINDOW; id++) {
            assertTrue(inflight.acquire(0));
            inflight.register(offered(id, 1_000 + id));
            inflight.onDirectAck(id);
            inflight.release();
        }
        final var late = offered(10 * WINDOW + 1, 5_000);
        assertTrue(inflight.acquire(0));
        inflight.register(late);

        assertEquals(0, inflight.sweepOverdue(999));
        assertEquals(1, inflight.sweepOverdue(1_000), "the stranded order is reaped at its own deadline");
        assertNull(stranded.future.get());
        assertEquals(0, inflight.sweepOverdue(4_999));
        assertEquals(1, inflight.sweepOverdue(5_000));
        assertEquals(0, inflight.depth(), "every permit returned");
    }
}
