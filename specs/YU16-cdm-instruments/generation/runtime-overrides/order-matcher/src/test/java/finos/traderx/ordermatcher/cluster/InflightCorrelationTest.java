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
 * The KEYED correlation core (ack-correlation fix, option B), with no cluster. YU17-layer override
 * of the YU13-layer test of the positional FIFO: composition pairs each branch's gateway with its
 * own semantics, so ancestors keep proving the FIFO they still run while this state proves the map.
 *
 * <p>The property everything here defends: an ack completes exactly the request whose id it
 * echoes, so a STRANDED offer — an election destroying the dying leader's un-egressed acks, or the
 * member's deliberate best-effort egress drop — harms NO other order. Under the positional pop one
 * strand shifted every later ack onto the wrong request permanently (measured K = 21→36→51 across
 * three leader kills, clients told strangers' orderRefs with HTTP 200); under the key that failure
 * class does not exist, and the deadline sweep is what returns a stranded slot.
 */
@Timeout(30)
class InflightCorrelationTest {

    private static ClusterGatewayMain.PendingOrder order(final long requestId) {
        final ClusterGatewayMain.PendingOrder p = new ClusterGatewayMain.PendingOrder(
            InputEvent.TYPE_ORDER_NEW, 11, "JPM", 'B', 10, 100_000_000L, 0L, 0);
        p.requestId = requestId;
        p.reapAtMillis = Long.MAX_VALUE; // tests that sweep set a real deadline explicitly
        return p;
    }

    /** THE option-B property, positively: acks complete exactly the request they name, whatever
     *  order they arrive in — arrival order carries no meaning at all any more. */
    @Test
    void acksCompleteExactlyTheRequestTheyName() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order(1);
        final ClusterGatewayMain.PendingOrder b = order(2);
        final ClusterGatewayMain.PendingOrder c = order(3);
        inflight.register(a);
        inflight.register(b);
        inflight.register(c);

        assertSame(b, inflight.onDirectAck(2), "the ack for 2 completes b, not the oldest waiter");
        assertSame(a, inflight.onDirectAck(1));
        assertSame(c, inflight.onDirectAck(3));
        assertNull(inflight.onDirectAck(4), "an id naming nothing completes nothing");
    }

    /**
     * THE option-B property, negatively — the exact shape of the measured defect: a stranded offer
     * (its ack destroyed or dropped, so it never arrives) must not shift any later order onto the
     * wrong request. Under the positional FIFO, b's ack would have completed a and every
     * subsequent client would have carried a stranger's orderRef with HTTP 200.
     */
    @Test
    void aStrandedOfferHarmsNoOtherOrder() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder stranded = order(1); // its ack will never arrive
        final ClusterGatewayMain.PendingOrder b = order(2);
        final ClusterGatewayMain.PendingOrder c = order(3);
        inflight.register(stranded);
        inflight.register(b);
        inflight.register(c);

        assertSame(b, inflight.onDirectAck(2), "b's own ack completes b — never the stranded head");
        assertSame(c, inflight.onDirectAck(3), "c's own ack completes c — the strand shifts nothing");
        assertFalse(stranded.future.isDone(),
            "the stranded order is still awaiting its own answer, not completed with someone else's");
    }

    /** A crossing order emits ACCEPTED then per-match-step FILLs, all under one apply and all
     *  echoing ONE request id. The first completes and removes the pending; the continuations must
     *  find nothing — under the FIFO a continuation popping again shifted every later order. */
    @Test
    void continuationAcksOfACompletedRequestAreIgnored() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order(1);
        final ClusterGatewayMain.PendingOrder b = order(2);
        inflight.register(a);
        inflight.register(b);

        assertSame(a, inflight.onDirectAck(1), "A's entry ack completes A");
        assertNull(inflight.onDirectAck(1), "A's fill echoes the same id — must complete nothing");
        assertNull(inflight.onDirectAck(1), "A's second fill — still nothing");
        assertSame(b, inflight.onDirectAck(2), "B is completed only by its own id");
    }

    /** Id 0 is the wire's "no request" — a pre-B log entry, another producer's input, a batch
     *  offer. It is never registered and must never complete anything, whatever is pending. */
    @Test
    void requestIdZeroNeverCompletesAnything() {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        inflight.register(order(1));
        assertNull(inflight.onDirectAck(0));
    }

    /** Request ids never restart (gateway-lifetime-monotonic), so an ack surviving from a drained
     *  session or an older epoch names an id that is simply gone — it must complete nothing, and
     *  there is no watermark or sequence space left to get wrong across the boundary. */
    @Test
    void acksFromADrainedSessionCompleteNothing() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder old = order(1);
        assertTrue(inflight.acquire(1_000));
        inflight.register(old);
        inflight.drain();
        assertNull(old.future.get(), "drained to ambiguous, never a false reject");

        final ClusterGatewayMain.PendingOrder fresh = order(2);
        assertTrue(inflight.acquire(1_000));
        inflight.register(fresh);
        assertNull(inflight.onDirectAck(1), "the drained order's late ack names nothing now");
        assertSame(fresh, inflight.onDirectAck(2), "the live order still completes by its own id");
    }

    /**
     * The deadline sweep is the ONLY thing that frees a stranded offer's slot under keyed
     * correlation: no foreign ack can complete it any more, the submitter's timeout deliberately
     * does not release (the owner owns the slot), and an election no longer bulk-drains the window.
     * Without the sweep every strand leaks a permit PERMANENTLY and the window is gone in
     * MAX_INFLIGHT strands — the leak the positional pop used to mask by misattributing.
     */
    @Test
    void sweepReapsOverdueStrandsAndReturnsTheirPermits() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(2);
        final ClusterGatewayMain.PendingOrder stranded = order(1);
        final ClusterGatewayMain.PendingOrder live = order(2);
        assertTrue(inflight.acquire(1_000));
        assertTrue(inflight.acquire(1_000));
        stranded.reapAtMillis = 1_000;
        live.reapAtMillis = 9_000;
        inflight.register(stranded);
        inflight.register(live);
        assertEquals(2, inflight.depth());

        assertEquals(0, inflight.sweepOverdue(999), "nothing due yet: nothing reaped");
        assertEquals(1, inflight.sweepOverdue(1_000), "the overdue strand is reaped, exactly it");
        assertNull(stranded.future.get(), "reaped ambiguous — post-publish, never a false reject");
        assertFalse(live.future.isDone(), "the not-yet-due order is untouched");
        assertEquals(1, inflight.depth(), "exactly one permit returned");

        assertNull(inflight.onDirectAck(1), "the reaped order's late ack completes nothing");
        assertSame(live, inflight.onDirectAck(2), "the live order still completes normally");
    }

    /** An entry its ack already completed is skipped when its reap turn comes — the map, not the
     *  sweep queue, says what is still pending — so one order can never release two permits. */
    @Test
    void sweepDoesNotDoubleReleaseAnAckCompletedOrder() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(2);
        final ClusterGatewayMain.PendingOrder a = order(1);
        assertTrue(inflight.acquire(1_000));
        a.reapAtMillis = 1_000;
        inflight.register(a);

        assertSame(a, inflight.onDirectAck(1));
        inflight.release(); // completePipelinedHead's release
        assertEquals(0, inflight.depth());

        assertEquals(0, inflight.sweepOverdue(2_000),
            "the completed order's queue entry is skipped, not reaped");
        assertEquals(0, inflight.depth(), "no second release: depth would have gone negative-ish");
    }

    /** A reconnect (or batch takeover) drains every outstanding order to ambiguous and frees its
     *  slot — the old session's undelivered egress is gone with the session. */
    @Test
    void drainCompletesOutstandingAmbiguousAndFreesSlots() throws Exception {
        final ClusterGatewayMain.Inflight inflight = new ClusterGatewayMain.Inflight(16);
        final ClusterGatewayMain.PendingOrder a = order(1);
        final ClusterGatewayMain.PendingOrder b = order(2);
        assertTrue(inflight.acquire(1_000));
        assertTrue(inflight.acquire(1_000));
        inflight.register(a);
        inflight.register(b);

        inflight.drain();

        assertNull(a.future.get(), "drained to ambiguous, never a false reject");
        assertNull(b.future.get(), "drained to ambiguous, never a false reject");
        assertEquals(0, inflight.depth(), "both slots returned");
        assertEquals(0, inflight.sweepOverdue(Long.MAX_VALUE),
            "the sweep queue was cleared with the map — a drained order cannot be reaped again");
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
