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

    /**
     * A leader change strands the offers the dying leader sequenced but never egressed — the
     * promotion destroys those acks, so they never arrive. The resync answers the at-risk set
     * ambiguous and returns its slots, which is what stops the FIFO staying permanently N ahead.
     */
    @Test
    void newLeaderResyncAnswersTheAtRiskSetAndFreesItsSlots() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order();
        final ClusterGatewayMain.PendingOrder b = order();
        assertTrue(inflight.acquire(1_000));
        assertTrue(inflight.acquire(1_000));
        inflight.register(a);
        inflight.register(b);
        assertEquals(2, inflight.depth());

        inflight.onNewLeaderResync();

        assertNull(a.future.get(), "at-risk order answered ambiguous, never a false reject");
        assertNull(b.future.get(), "at-risk order answered ambiguous, never a false reject");
        assertEquals(0, inflight.depth(), "both slots returned — no offset left behind");
    }

    /**
     * THE STALE-ACK WATERMARK. An ack that was in flight across the election lands after the resync;
     * without the watermark it would find the newly-registered orders and pop one — re-seeding the
     * very offset the resync just repaired. Acks at or below the high-water mark are ignored;
     * anything above it is the new leader's work and must still complete normally.
     */
    @Test
    void staleAcksFromBeforeALeaderChangeDoNotPopLaterOrders() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        inflight.observeInputSeq(900);   // evidence of the old leader's applied-sequence
        inflight.onNewLeaderResync();    // watermark := 900

        final ClusterGatewayMain.PendingOrder afterElection = order();
        inflight.register(afterElection);

        assertNull(inflight.onDirectAck(880), "an in-flight ack from before the election is stale");
        assertNull(inflight.onDirectAck(900), "an ack AT the watermark is stale too");
        assertSame(afterElection, inflight.onDirectAck(901),
            "the new leader's own ack is above the watermark and completes the waiting order");
    }

    /** The watermark test and the continuation test answer DIFFERENT questions and must not be
     *  collapsed: continuation fills still share one inputSeq after a resync. */
    @Test
    void continuationFillsStillWorkAfterAResync() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        inflight.observeInputSeq(50);
        inflight.onNewLeaderResync();
        final ClusterGatewayMain.PendingOrder a = order();
        final ClusterGatewayMain.PendingOrder b = order();
        inflight.register(a);
        inflight.register(b);

        assertSame(a, inflight.onDirectAck(60), "entry ack pops A");
        assertNull(inflight.onDirectAck(60), "A's continuation fill must not pop B");
        assertSame(b, inflight.onDirectAck(61), "B's own entry ack pops B");
    }

    /**
     * THE EPOCH DETONATOR — the one test that fails if the sequence-space fields are reset
     * separately rather than together.
     *
     * <p>A fresh session may be a fresh EPOCH, in which the members restart {@code appliedSeq} at 0.
     * If {@code highestInputSeqSeen} survives {@code drain()} while the watermark is cleared, the
     * bug is latent: everything works until the first election in the new epoch, at which point the
     * watermark is recomputed from the OLD epoch's numbering, sits above every sequence the new
     * epoch will produce for a long time, and the gateway silently ignores every ack and 504s
     * forever while reporting {@code connected:true}.
     *
     * <p>Note this asserts on an election AFTER the fresh epoch, not merely on the fresh epoch: a
     * test that only reconnects and sends orders passes with the bug present.
     */
    @Test
    void anElectionAfterAFreshEpochMustNotInheritTheOldEpochsNumbering() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        // Old epoch ran a long way.
        inflight.observeInputSeq(5_000);
        final ClusterGatewayMain.PendingOrder old = order();
        inflight.register(old);
        assertSame(old, inflight.onDirectAck(5_000));

        // Fresh session onto a FRESH EPOCH: the members restart appliedSeq at 0.
        inflight.drain();

        // An election in the new epoch, before its sequence has climbed anywhere near 5000.
        inflight.onNewLeaderResync();

        final ClusterGatewayMain.PendingOrder fresh = order();
        inflight.register(fresh);
        assertSame(fresh, inflight.onDirectAck(1),
            "an election after a fresh epoch must not carry the old epoch's high-water mark: "
            + "a surviving highestInputSeqSeen sets the watermark above every ack the new epoch "
            + "will produce, and every order 504s forever while /ready says connected");
    }

    /**
     * The resync must MARK the orders it answers, and a session drain must NOT.
     *
     * <p>The mark is what stops the submitter counting a resync-completed order against the no-ack
     * streaks. Without it a strand of 20–99 fails readiness, removes the only gateway from the
     * Service, and then never recovers: the streak clears only on a SUCCESSFUL order and a pod out
     * of the Service is sent none, while liveness sits at 5× readiness and never fires. The measured
     * strands were 21, 15 and 15 — the first is inside that bracket.
     *
     * <p>The negative half matters just as much: a session drain answers orders the gateway really
     * could not complete, and those must still count, or `yu16-ready-tracks-commit`'s restored-quorum
     * assertion is laundered rather than failed.
     */
    @Test
    void resyncMarksItsOrdersAndASessionDrainDoesNot() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder viaResync = order();
        inflight.register(viaResync);
        inflight.onNewLeaderResync();
        assertNull(viaResync.future.get());
        assertTrue(viaResync.resyncAmbiguous,
            "a leader-change resync must mark its orders, or the streak counts the gateway's own "
            + "repair as ill-health and can strand the pod out of the Service permanently");

        final ClusterGatewayMain.PendingOrder viaDrain = order();
        inflight.register(viaDrain);
        inflight.drain();
        assertNull(viaDrain.future.get());
        assertFalse(viaDrain.resyncAmbiguous,
            "a session drain is honest evidence the gateway could not commit and must still count");
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
