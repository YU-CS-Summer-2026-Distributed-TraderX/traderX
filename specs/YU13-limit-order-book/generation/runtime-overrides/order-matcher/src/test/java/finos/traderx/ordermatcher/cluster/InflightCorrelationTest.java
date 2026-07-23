package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.InputEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pipelined-ingress correlation core, with no cluster (ClusterGatewayMain.Inflight is the one
 * non-trivial piece the gateway change adds). It proves the property the whole lever rests on: an
 * ack stream reconciles back to the RIGHT awaiting request even with many orders in flight and
 * multi-fill crossing orders — the failure that would silently answer request N+1 with order N's
 * fill. The live GKE run proves throughput; this proves it does not corrupt outcomes doing so.
 */
@Timeout(30)
class InflightCorrelationTest {

    private static ClusterGatewayMain.PendingOrder order() {
        return new ClusterGatewayMain.PendingOrder(
            InputEvent.TYPE_ORDER_NEW, 11, "JPM", 'B', 10, 100_000_000L, 0L, 0);
    }

    /** Distinct inputSeqs (each order's own entry ack) pop the FIFO head in strict offer order. */
    @Test
    void entryAcksCompleteInFifoOrder() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order();
        final ClusterGatewayMain.PendingOrder b = order();
        final ClusterGatewayMain.PendingOrder c = order();
        inflight.register(a);
        inflight.register(b);
        inflight.register(c);

        assertSame(a, inflight.onDirectAck(100));
        assertSame(b, inflight.onDirectAck(101));
        assertSame(c, inflight.onDirectAck(102));
        assertNull(inflight.onDirectAck(103), "empty window: nothing to complete");
    }

    /**
     * THE case the inputSeq boundary exists for: a crossing order emits ACCEPTED then one or more
     * FILL acks, all under one applied-sequence. Only the FIRST (the entry ack) may pop the head;
     * the continuation fill sharing that inputSeq must NOT pop the next order, or every later order
     * shifts onto the wrong request. Without the boundary, order B would be answered by A's fill.
     */
    @Test
    void continuationFillsOfOneInputDoNotPopTheNextOrder() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order();
        final ClusterGatewayMain.PendingOrder b = order();
        inflight.register(a);
        inflight.register(b);

        assertSame(a, inflight.onDirectAck(200), "A's entry ack (ACCEPTED) pops A");
        assertNull(inflight.onDirectAck(200), "A's fill shares inputSeq 200 — must not pop B");
        assertNull(inflight.onDirectAck(200), "A's second fill — still not B");
        assertSame(b, inflight.onDirectAck(201), "B's entry ack (new inputSeq) pops B");
    }

    /** A reconnect drains every outstanding order to ambiguous (null) and resets the boundary so the
     *  fresh session's first ack pops again even if its applied-sequence restarts lower. */
    @Test
    void drainCompletesOutstandingAmbiguousAndResetsBoundary() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order();
        final ClusterGatewayMain.PendingOrder b = order();
        assertTrue(inflight.acquire(1_000));
        assertTrue(inflight.acquire(1_000));
        inflight.register(a);
        inflight.register(b);
        // a's entry ack pops it and advances the boundary to 5000 (mimic completePipelinedHead,
        // which completes the returned pending and releases its slot). b stays outstanding.
        assertSame(a, inflight.onDirectAck(5_000));
        inflight.release();

        inflight.drain();

        assertNull(b.future.get(), "the outstanding order is drained to ambiguous, never a false reject");
        // Boundary reset: an ack at a LOWER inputSeq than before the drain must still pop.
        final ClusterGatewayMain.PendingOrder c = order();
        inflight.register(c);
        assertSame(c, inflight.onDirectAck(1), "post-drain first ack pops despite lower inputSeq");
    }

    /** The permit semaphore is the in-flight bound and the client backpressure: a full window blocks
     *  a new submitter until a slot is released; drain and release both return slots. */
    @Test
    void permitsBoundInFlightAndBackpressure() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(2);
        assertTrue(inflight.acquire(1_000));
        assertTrue(inflight.acquire(1_000));
        assertEquals(2, inflight.depth());
        assertFalse(inflight.acquire(50), "window full: a third submitter is backpressured");

        inflight.release();
        assertEquals(1, inflight.depth());
        assertTrue(inflight.acquire(1_000), "a freed slot admits the next submitter");
    }
}
