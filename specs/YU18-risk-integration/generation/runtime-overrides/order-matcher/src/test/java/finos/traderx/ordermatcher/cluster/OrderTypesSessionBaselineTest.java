package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RI-01 baseline (YU18 order-types component, spec Support Matrix, Time in force): the order LIFETIME behaviour the
 * support matrix reports, pinned through the clustered service's sequenced path before stop
 * orders change it. The matrix calls resting limits "effectively GTC" and the market remainder
 * "IOC-like"; these are the tests that make those two words measured rather than read.
 * FR-OT14 builds pending stops on exactly this session behaviour.
 *
 * <p>Additive: overrides no inherited test. Harness mirrors {@code SessionPhaseGateTest} (YU17).
 */
class OrderTypesSessionBaselineTest {
    private static final int ACCOUNT = 42422;   // real accounts in counterparties.csv
    private static final int ACCOUNT2 = 22214;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;
    private static final long LIMIT = 150 * PX;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    /**
     * G1: a resting limit survives CLOSED and the next OPEN, and still matches afterwards. There
     * is no Day expiry anywhere on the path, which is why the spec's Support Matrix calls today's limit
     * lifetime effectively GTC. A later Day TIF must change this deliberately, not by accident.
     */
    @Test
    void aRestingLimitSurvivesACloseAndTradesAfterTheReopen() {
        final MatchingEngineClusteredService service = seeded();
        final CapturingSession s = new CapturingSession();
        apply(service, s, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 10, 101L));
        final int buy = directAckRef(s);
        assertEquals(RestingOrder.STATUS_NEW, statusOf(service, buy));

        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED, 900L);
        assertEquals(1, service.engine().openOrderTuples().size(),
            "CLOSED halts trading; it does not expire resting orders");
        setPhase(service, MatchingEngineClusteredService.PHASE_OPEN, 901L);
        assertEquals(RestingOrder.STATUS_NEW, statusOf(service, buy), "still resting after the reopen");

        apply(service, new CapturingSession(), newOrder(ACCOUNT2, InputEvent.SIDE_SELL, LIMIT, 10, 102L));
        assertEquals(RestingOrder.STATUS_FILLED, statusOf(service, buy),
            "the order that rode through the close is live: it fills against the next seller");
        assertEquals(0, service.engine().openOrderTuples().size());
    }

    /**
     * G2: a market order submitted in PRE_OPEN is queued like any order. At the OPEN release it
     * fills what depth exists and cancels the rest inside that same apply: it never rests,
     * even when it was held rather than arriving live. FR-OT14 queues pending stops this way.
     */
    @Test
    void aQueuedMarketOrderFillsAtTheOpenAndCancelsItsRemainder() {
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN, 910L);

        final CapturingSession s1 = new CapturingSession();
        apply(service, s1, newOrder(ACCOUNT2, InputEvent.SIDE_SELL, LIMIT, 10, 111L));
        final int sell = directAckRef(s1);
        final CapturingSession s2 = new CapturingSession();
        apply(service, s2, newOrder(ACCOUNT, InputEvent.SIDE_BUY, 0L, 15, 112L));   // market: no limit
        final int mkt = directAckRef(s2);
        assertEquals(2, service.queueDepth());
        assertEquals(0L, service.engine().tradeCounter(), "nothing trades while queued");

        setPhase(service, MatchingEngineClusteredService.PHASE_OPEN, 911L);

        assertEquals(0, service.queueDepth());
        assertEquals(2L, service.engine().tradeCounter(), "one match of 10, two trade legs");
        assertEquals(RestingOrder.STATUS_FILLED, statusOf(service, sell));
        assertEquals(RestingOrder.STATUS_CANCELED, statusOf(service, mkt),
            "5 of 15 had no counterparty: cancelled at the open, never rested (FR-LOB04)");
        assertEquals(0, service.engine().openOrderTuples().size(), "nothing left resting");
    }

    // ----- harness (mirrors SessionPhaseGateTest) ------------------------------------------------

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
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = SECURITY;
        tick.priceTicks = LIMIT;
        apply(service, sink, tick);
        return service;
    }

    private void setPhase(final MatchingEngineClusteredService service, final byte phase, final long requestId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SESSION_CONTROL;
        e.side = phase;
        e.setClientOrderKey(requestId);
        apply(service, new CapturingSession(), e);
        assertEquals(MatchingEngineClusteredService.PHASE_NAMES[phase], service.phaseName());
    }

    private InputEvent newOrder(final int account, final byte side, final long limitPx, final int qty,
                                final long key) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = account;
        e.securityId = SECURITY;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        e.setClientOrderKey(key);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session,
                       final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    /** The order ref named by the FIRST non-resting order-lifecycle ack (bytes 8..11). */
    private static int directAckRef(final CapturingSession session) {
        for (final byte[] record : session.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(record);
            if (ack.getByte(21) == 0
                && finos.traderx.ordermatcher.lmax.OutputEvent.isOrderLifecycleKind(ack.getByte(12))) {
                final int ref = ack.getInt(8);
                assertTrue(ref != 0, "the ack names a ref");
                return ref;
            }
        }
        throw new AssertionError("no direct order-lifecycle ack in " + session.egress.size() + " records");
    }

    private static byte statusOf(final MatchingEngineClusteredService service, final int orderRef) {
        final long[] tuple = new long[MatchingEngine.SNAPSHOT_ORDER_TUPLE_LENGTH];
        assertTrue(service.engine().copySnapshotOrderTuple(orderRef, tuple),
            "order " + orderRef + " is not addressable in the engine");
        return (byte) tuple[7];
    }
}
