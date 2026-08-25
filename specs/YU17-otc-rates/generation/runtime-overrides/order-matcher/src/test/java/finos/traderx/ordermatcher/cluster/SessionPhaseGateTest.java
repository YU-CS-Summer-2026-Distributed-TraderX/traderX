package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-open phase machine (ADR-069 decisions 3+4; {@code format-8-mint-scope.md} sections 1.2-1.6),
 * driven through the REAL sequenced ingress path with a capturing session and no cluster.
 *
 * <p>This is the in-process counterpart of {@code yu17-session-closed-rejects} and
 * {@code yu17-preopen-queue-open}. The shell proofs assert the same claims on a live three-member
 * rig; everything here is decided by one apply on one thread, so it needs no cluster and fails at
 * build time instead of after a PVC wipe.
 *
 * <p><b>Which gate answered is asserted everywhere</b>, off the engine ack's reason byte (22) and
 * never off a kind alone: a session refusal wearing another gate's reason is the audit-surface
 * defect ADR-069 forbids, and it would be invisible to a test that only checked "rejected".
 */
class SessionPhaseGateTest {
    private static final int ACCOUNT = 42422;   // real accounts in counterparties.csv
    private static final int ACCOUNT2 = 22214;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;
    private static final long LIMIT = 150 * PX;

    private static final byte KIND_REJECTED = OutputEvent.KIND_ORDER_REJECTED;
    private static final byte KIND_ACCEPTED = OutputEvent.KIND_ORDER_ACCEPTED;
    private static final byte KIND_CANCELED = OutputEvent.KIND_ORDER_CANCELED;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    /** Capturing egress session — the ack bytes exactly as a gateway would receive them. */
    private static final class CapturingSession implements ClientSession {
        final List<byte[]> egress = new ArrayList<>();
        @Override public long id() { return 7; }
        @Override public int responseStreamId() { return 0; }
        @Override public String responseChannel() { return "test"; }
        @Override public byte[] encodedPrincipal() { return new byte[0]; }
        @Override public void close() { }
        @Override public boolean isClosing() { return false; }
        @Override public long offer(final DirectBuffer buffer, final int offset, final int length) {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            egress.add(copy);
            return 1;
        }
        @Override public long offer(final DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        @Override public long tryClaim(final int length, final BufferClaim claim) { throw new UnsupportedOperationException(); }
    }

    // ----- decision (a): a fresh epoch is OPEN -------------------------------------------------

    @Test
    void aFreshServiceIsOpen() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        assertEquals("OPEN", service.phaseName(),
            "decision (a): every proof and fixture assumes a trading book, so a fresh epoch trades."
                + " CLOSED-until-commanded stays available by issuing the command at bring-up");
        assertEquals(0, service.queueDepth());
    }

    // ----- CLOSED (section 1.3) ---------------------------------------------------------------

    @Test
    void closedRefusesANewOrderWithItsOwnReason() {
        final MatchingEngineClusteredService service = seeded();

        // The control: the SAME order at the SAME price rests while OPEN. Without it, a refusal
        // below is indistinguishable from a dead ticker, an unseeded price or a collared limit.
        final CapturingSession open = new CapturingSession();
        apply(service, open, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 1L));
        assertEquals(KIND_ACCEPTED, kindOf(directAck(open)), "control: the venue takes this order while OPEN");
        assertEquals(1, service.engine().openOrderTuples().size());

        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 900L);

        final CapturingSession closed = new CapturingSession();
        apply(service, closed, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 2L));
        final UnsafeBuffer ack = directAck(closed);
        assertEquals(KIND_REJECTED, kindOf(ack));
        assertEquals((byte) RiskReason.MARKET_CLOSED.ordinal(), ack.getByte(22),
            "the refusal must name the SESSION, distinct from PRICE_COLLAR and every risk cap:"
                + " the control above rested at this exact price moments ago");
        assertEquals(1, service.engine().openOrderTuples().size(),
            "a refused order never reaches the book");
        assertEquals(0, service.queueDepth(), "CLOSED refuses; it does not queue");
    }

    @Test
    void closedStillAllowsACancel() {
        // Decision (c), which OVERRODE the recommendation: a cancel only ever REDUCES exposure --
        // it cannot cross, trade, move a price or re-open a halted book -- so permitting it during
        // a halt is safer than forbidding it. Forbidding it would lock a client into a resting
        // order until the open, where ADR-069 rule 7 may fill or cancel it on terms they never saw.
        final MatchingEngineClusteredService service = seeded();
        final CapturingSession open = new CapturingSession();
        apply(service, open, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 3L));
        final int ref = directAck(open).getInt(8);

        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 901L);

        final CapturingSession session = new CapturingSession();
        apply(service, session, cancel(ref));
        assertEquals(KIND_CANCELED, kindOf(directAck(session)),
            "a cancel reaches the engine while CLOSED and takes effect");
        assertEquals(0, service.engine().openOrderTuples().size());
    }

    @Test
    void closedRefusesAReplaceWithoutTouchingTheOrder() {
        final MatchingEngineClusteredService service = seeded();
        final CapturingSession open = new CapturingSession();
        apply(service, open, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 4L));
        final int ref = directAck(open).getInt(8);

        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 902L);

        final CapturingSession session = new CapturingSession();
        final InputEvent replace = new InputEvent();
        replace.type = InputEvent.TYPE_ORDER_REPLACE;
        replace.orderRef = ref;
        replace.qty = 5;
        replace.limitPx = LIMIT;
        apply(service, session, replace);
        final UnsafeBuffer ack = directAck(session);
        assertEquals(KIND_REJECTED, kindOf(ack));
        assertEquals((byte) RiskReason.MARKET_CLOSED.ordinal(), ack.getByte(22));
        // v1 refuses; the upgrade path is a queue-aware replace. The order must STAND -- a refusal
        // that terminated the order would answer a modify request with a termination.
        assertEquals(1, service.engine().openOrderTuples().size());
        final long[] tuple = new long[finos.traderx.ordermatcher.lmax.MatchingEngine.SNAPSHOT_ORDER_TUPLE_LENGTH];
        assertTrue(service.engine().copySnapshotOrderTuple(ref, tuple));
        assertEquals(10, tuple[4], "the original quantity is untouched by the refused replace");
        assertEquals(RestingOrder.STATUS_NEW, (byte) tuple[7], "and it is still working");
    }

    @Test
    void closedRefusesAMarketTradeOnTheTradeAckPath() {
        // A market trade correlates on KIND_TRADE_*, not on an order kind: refuse it in the shape
        // the /trades path reads or the gateway waits for an ack that never comes.
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 903L);

        final CapturingSession session = new CapturingSession();
        final InputEvent trade = new InputEvent();
        trade.type = InputEvent.TYPE_TRADE_NEW;
        trade.accountId = ACCOUNT;
        trade.securityId = SECURITY;
        trade.side = InputEvent.SIDE_BUY;
        trade.qty = 5;
        trade.priceTicks = 0L;
        apply(service, session, trade);

        assertFalse(session.egress.isEmpty(), "a halted market trade must still be answered");
        final UnsafeBuffer ack = new UnsafeBuffer(session.egress.get(0));
        assertEquals(OutputEvent.KIND_TRADE_REJECTED, ack.getByte(12));
        assertEquals((byte) RiskReason.MARKET_CLOSED.ordinal(), ack.getByte(22));
        assertEquals(0L, service.engine().tradeCounter(), "nothing booked");
    }

    @Test
    void theFeedNeverHalts() {
        // Decision 6, and it is what lets the band re-anchor ACROSS a halt so the open is judged
        // against a current reference rather than yesterday's.
        final MatchingEngineClusteredService service = seeded();
        for (final byte phase : new byte[] { MatchingEngineClusteredService.PHASE_CLOSED,
                                             MatchingEngineClusteredService.PHASE_PRE_OPEN }) {
            setPhase(service, phase, 910L + phase);
            final long px = (200 + phase) * PX;
            apply(service, new CapturingSession(), priceTick(px));
            assertEquals(px, service.risk().lastPrice(SECURITY),
                "a PRICE_TICK must apply in phase " + service.phaseName());
        }
    }

    @Test
    void otcBookingsDoNotHaltWithTheVenue() {
        // Decision (d): the halt is the VENUE'S BOOK. Bilateral desk business never touches it, and
        // one session concept spanning both would conflate two unrelated things.
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 904L);

        final InputEvent booking = new InputEvent();
        booking.type = InputEvent.TYPE_SWAP_BOOK;
        booking.accountId = ACCOUNT;
        booking.side = InputEvent.SWAP_RECEIVE_FIXED;
        booking.qty = 1_000_000;
        booking.limitPx = 42_000L;
        booking.securityId = SwapConventions.indexOf("USD-SOFR-1Y-ACT360");
        booking.setSwapDates(20_000, 21_000);
        booking.setClientOrderKey(0L);
        apply(service, new CapturingSession(), booking);

        assertEquals(1, service.contractCount(),
            "a swap booked while the venue is CLOSED: the halt is the book, not the desk");
    }

    // ----- PRE_OPEN (sections 1.4-1.6) --------------------------------------------------------

    @Test
    void preOpenQueuesInsteadOfTrading() {
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 905L);

        final CapturingSession session = new CapturingSession();
        apply(service, session, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 10L));
        final UnsafeBuffer ack = directAck(session);
        assertEquals(KIND_ACCEPTED, kindOf(ack), "a queued order is ACCEPTED, not rejected");
        assertNotEquals(0, ack.getInt(8), "the ack names the ref the order already holds");
        assertEquals(1, service.queueDepth());
        assertEquals(0, service.engine().openOrderTuples().size(),
            "a queued order is held BESIDE the engine: no book membership, no reservation");
        assertEquals(0L, service.engine().tradeCounter(), "nothing trades while queued");
    }

    @Test
    void aRetriedClientKeyFindsTheQueuedOriginal() {
        // Idempotency at QUEUE time. The engine's own table cannot answer here: the risk decision
        // has not run yet -- it runs at the open.
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 906L);

        final CapturingSession first = new CapturingSession();
        apply(service, first, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 77L));
        final int ref = directAck(first).getInt(8);

        final CapturingSession retry = new CapturingSession();
        apply(service, retry, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 77L));
        assertEquals(ref, directAck(retry).getInt(8),
            "the retry is answered with the ORIGINAL order's ref, not a second queued copy");
        assertEquals(1, service.queueDepth(), "one order queued, not two");
    }

    @Test
    void aCancelOfAQueuedOrderRemovesItFromTheQueue() {
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 907L);
        final CapturingSession queued = new CapturingSession();
        apply(service, queued, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 11L));
        final int ref = directAck(queued).getInt(8);

        final CapturingSession session = new CapturingSession();
        apply(service, session, cancel(ref));
        assertEquals(KIND_CANCELED, kindOf(directAck(session)));
        assertEquals(0, service.queueDepth());
    }

    @Test
    void theQueueIsBoundedAndRefusesCapacityDeterministically() {
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 908L);
        for (int i = 0; i < MatchingEngineClusteredService.MAX_QUEUED_ORDERS; i++) {
            apply(service, new CapturingSession(), newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 1000L + i));
        }
        assertEquals(MatchingEngineClusteredService.MAX_QUEUED_ORDERS, service.queueDepth());

        final CapturingSession over = new CapturingSession();
        apply(service, over, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 99_999L));
        final UnsafeBuffer ack = directAck(over);
        assertEquals(KIND_REJECTED, kindOf(ack));
        assertEquals((byte) RiskReason.CAPACITY.ordinal(), ack.getByte(22),
            "at the cap an order is REFUSED CAPACITY, exactly as MAX_CONTRACTS refuses -- never"
                + " silently dropped, and identically on every member");
        assertEquals(MatchingEngineClusteredService.MAX_QUEUED_ORDERS, service.queueDepth());
    }

    // ----- the open (section 1.5) -------------------------------------------------------------

    @Test
    void theOpenReleasesInInsertionOrderAndIssuesNoNewRefs() {
        // The claim a COUNT cannot see. A1 and A2 are identical buys from the same account at the
        // same price; only their queue position separates them. Released A1-then-A2 the crossing
        // sell takes A1 (first at the level) and A2 survives; released the other way A2 fills.
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 909L);

        final CapturingSession s1 = new CapturingSession();
        apply(service, s1, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 21L));
        final int a1 = directAck(s1).getInt(8);
        final CapturingSession s2 = new CapturingSession();
        apply(service, s2, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 22L));
        final int a2 = directAck(s2).getInt(8);
        final CapturingSession s3 = new CapturingSession();
        apply(service, s3, newOrder(ACCOUNT2, InputEvent.SIDE_SELL, LIMIT, 23L));
        final int sell = directAck(s3).getInt(8);
        assertEquals(3, service.queueDepth());
        assertEquals(0L, service.engine().tradeCounter(), "nothing traded while queued");

        final long refsBefore = service.nextOrderRef();
        final CapturingSession open = new CapturingSession();
        setPhase(service, MatchingEngineClusteredService.PHASE_OPEN, 4242L, open);

        assertEquals(refsBefore, service.nextOrderRef(),
            "the release issues ZERO new refs: every released order already holds the one it was"
                + " given at sequencing. Re-sequencing would break cross-epoch ref monotonicity"
                + " and the client's ack correlation both");
        assertEquals(0, service.queueDepth(), "the queue drained in one apply");
        assertEquals(2L, service.engine().tradeCounter(), "exactly one match, two trade legs");

        assertEquals(RestingOrder.STATUS_FILLED, statusOf(service, a1),
            "A1 was queued FIRST, so it is the one the sell takes. If A2 filled instead the release"
                + " ran in the wrong order -- insertion order IS the release order (section 1.5)");
        assertEquals(RestingOrder.STATUS_NEW, statusOf(service, a2),
            "A2 rested unfilled behind A1");
        assertEquals(RestingOrder.STATUS_FILLED, statusOf(service, sell));
    }

    @Test
    void thePhaseAckCorrelatesByItsOwnRequestIdAndTheReleaseEchoesZero() {
        // THE REGRESSION TRAP (section 1.2). The OPEN apply emits every released order's lifecycle
        // acks, which echo applyRequestId at bytes 24..31. The phase command is offered with
        // request id 0 so those echoes can complete NOTHING gateway-side, and the operator's own
        // answer arrives as its OWN kind carrying its OWN id at byte 13. Route the phase ack
        // through 24..31 instead and a released order's ack completes the operator's pending --
        // the ratcheting-offset bug that wedged the gateway once already.
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 111L);
        apply(service, new CapturingSession(), newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 31L));

        final CapturingSession open = new CapturingSession();
        setPhase(service, MatchingEngineClusteredService.PHASE_OPEN, 222L, open);

        assertTrue(open.egress.size() >= 2,
            "the open emits the released order's lifecycle acks AND the phase ack");
        int phaseAcks = 0;
        for (final byte[] record : open.egress) {
            assertEquals(MatchingEngineClusteredService.EGRESS_ACK_LENGTH, record.length);
            final UnsafeBuffer ack = new UnsafeBuffer(record);
            if (ack.getByte(12) == MatchingEngineClusteredService.KIND_SESSION_PHASE) {
                phaseAcks++;
                assertEquals(222L, ack.getLong(13), "the phase ack carries its own request id at 13");
                assertEquals(0L, ack.getLong(24),
                    "and NEVER at 24..31, where a released order's ack would collide with it");
                assertEquals(MatchingEngineClusteredService.PHASE_OPEN, ack.getInt(8));
            } else {
                assertEquals(0L, ack.getLong(24),
                    "a released order's ack echoes request id 0, so it can complete no pending");
            }
        }
        assertEquals(1, phaseAcks, "exactly one phase ack answers the command");
    }

    // ----- decision (b) -----------------------------------------------------------------------

    @Test
    void closingOnANonEmptyQueueCancelsItWithItsOwnReason() {
        // Decision (b): a halt that pending client orders can block is not a halt. The reason is
        // deliberately NOT MARKET_CLOSED -- "we refused you at the door" and "the order you already
        // hold is gone" are different events calling for different client actions.
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 333L);
        final CapturingSession queued = new CapturingSession();
        apply(service, queued, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 41L));
        final int ref = directAck(queued).getInt(8);
        assertEquals(1, service.queueDepth());

        final CapturingSession close = new CapturingSession();
        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 444L, close);

        assertEquals(0, service.queueDepth(), "the close emptied the queue");
        UnsafeBuffer cancelAck = null;
        for (final byte[] record : close.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(record);
            if (ack.getByte(12) == KIND_CANCELED && ack.getInt(8) == ref) {
                cancelAck = ack;
            }
        }
        assertTrue(cancelAck != null, "each queued order is CANCELED on the way out, never dropped");
        assertEquals((byte) RiskReason.SESSION_CANCELED.ordinal(), cancelAck.getByte(22),
            "SESSION_CANCELED, not MARKET_CLOSED: a client must be able to tell 'refused because we"
                + " were closed' from 'your queued order was cancelled when the session halted'");
        assertNotEquals(RiskReason.SESSION_CANCELED.ordinal(), RiskReason.MARKET_CLOSED.ordinal(),
            "the two session reasons are distinct values, or the assertion above is vacuous");
    }

    // ----- harness ----------------------------------------------------------------------------

    /** Two enabled accounts, one enabled security with a price -- everything an order needs to
     *  rest, so a refusal below can only be the session gate. */
    private MatchingEngineClusteredService seeded() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        final CapturingSession sink = new CapturingSession();
        for (final int account : new int[] { ACCOUNT, ACCOUNT2 }) {
            final InputEvent e = new InputEvent();
            e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            e.accountId = account;
            e.setControlEnabled(true);
            e.setControlVersion(1L);
            apply(service, sink, e);
        }
        final InputEvent security = new InputEvent();
        security.type = InputEvent.TYPE_SECURITY_CONTROL;
        security.securityId = SECURITY;
        security.setControlEnabled(true);
        security.setControlVersion(2L);
        apply(service, sink, security);
        apply(service, sink, priceTick(LIMIT));
        return service;
    }

    private void setPhase(final MatchingEngineClusteredService service, final byte phase,
                          final long requestId) {
        setPhase(service, phase, requestId, new CapturingSession());
    }

    private void setPhase(final MatchingEngineClusteredService service, final byte phase,
                          final long requestId, final CapturingSession session) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SESSION_CONTROL;
        e.side = phase;
        e.setClientOrderKey(requestId);
        apply(service, session, e);
        assertEquals(MatchingEngineClusteredService.PHASE_NAMES[phase], service.phaseName());
    }

    private InputEvent newOrder(final int account, final byte side, final long limitPx, final long key) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = account;
        e.securityId = SECURITY;
        e.side = side;
        e.qty = 10;
        e.limitPx = limitPx;
        e.setClientOrderKey(key);
        return e;
    }

    private InputEvent cancel(final int orderRef) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_CANCEL;
        e.orderRef = orderRef;
        return e;
    }

    private InputEvent priceTick(final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SECURITY;
        e.priceTicks = px;
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session,
                       final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    /** The FIRST non-resting order-lifecycle ack — the one the gateway completes a pending on. */
    private UnsafeBuffer directAck(final CapturingSession session) {
        for (final byte[] record : session.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(record);
            final byte kind = ack.getByte(12);
            if (ack.getByte(21) == 0
                && (OutputEvent.isOrderLifecycleKind(kind) || kind == OutputEvent.KIND_ORDER_NOT_FOUND)) {
                return ack;
            }
        }
        throw new AssertionError("no direct order-lifecycle ack in " + session.egress.size() + " records");
    }

    private static byte kindOf(final UnsafeBuffer ack) {
        return ack.getByte(12);
    }

    /** The engine's own status byte for an order ref, from the snapshot order tuple. */
    private static byte statusOf(final MatchingEngineClusteredService service, final int orderRef) {
        final long[] tuple = new long[finos.traderx.ordermatcher.lmax.MatchingEngine.SNAPSHOT_ORDER_TUPLE_LENGTH];
        assertTrue(service.engine().copySnapshotOrderTuple(orderRef, tuple),
            "order " + orderRef + " is not addressable in the engine");
        return (byte) tuple[7];
    }
}
