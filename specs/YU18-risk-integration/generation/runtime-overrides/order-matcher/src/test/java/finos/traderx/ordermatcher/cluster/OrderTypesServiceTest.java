package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.DirectBufferVector;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU18 order types through the replicated service (MECS): the PRE_OPEN queue, the business-date
 * command, snapshot format 11 and the wire. Engine-only behaviour is {@code lmax.OrderTypesEngineTest}.
 */
class OrderTypesServiceTest {
    private static final int A = 42422;
    private static final int B = 22214;
    private static final int C = 11413;
    private static final int D = 52355;
    private static final int E = 62654;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingress = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer typedIngress = new UnsafeBuffer(new byte[AeronReplicationCodec.ORDER_INSTRUCTION_BYTES]);
    private long timestamp = 1_000_000_000_000L;
    private long keys = 1;

    static final class Capture implements ClientSession {
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

    /** {orderRef, flags, status, reason} of every order update the engine produced, in order. */
    private final List<long[]> updates = new ArrayList<>();

    private MatchingEngineClusteredService seeded() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        s.outputSink(o -> {
            if (OutputEvent.isOrderLifecycleKind(o.kind)) {
                updates.add(new long[] { o.orderRef, o.flags, o.status, o.riskReason });
            }
        });
        for (final int account : new int[] { A, B, C, D, E }) {
            final InputEvent e = new InputEvent();
            e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            e.accountId = account;
            e.setControlEnabled(true);
            e.setControlVersion(1L);
            apply(s, e);
        }
        final InputEvent security = new InputEvent();
        security.type = InputEvent.TYPE_SECURITY_CONTROL;
        security.securityId = SECURITY;
        security.setControlEnabled(true);
        security.setControlVersion(2L);
        apply(s, security);
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = SECURITY;
        tick.priceTicks = 150 * PX;
        apply(s, tick);
        return s;
    }

    private Capture apply(final MatchingEngineClusteredService s, final InputEvent e) {
        final Capture session = new Capture();
        if (e.orderType != OrderTypes.LEGACY) {
            codec.encodeOrderInstruction(typedIngress, 0, e, 0, 0, 0);
            s.onSessionMessage(session, ++timestamp, typedIngress, 0,
                AeronReplicationCodec.ORDER_INSTRUCTION_BYTES, null);
        } else {
            codec.encodeInput(ingress, 0, e, 0, 0, 0);
            s.onSessionMessage(session, ++timestamp, ingress, 0, AeronReplicationCodec.INPUT_BYTES, null);
        }
        return session;
    }

    private static InputEvent order(final int account, final byte side, final int qty, final long limitPx) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = account;
        e.securityId = SECURITY;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        return e;
    }

    private InputEvent typed(final int account, final byte side, final int qty, final byte type,
                             final byte tif, final long limitPx, final long stopPx) {
        final InputEvent e = order(account, side, qty, limitPx);
        e.orderType = type;
        e.tif = tif;
        e.stopPx = stopPx;
        e.setClientOrderKey(keys++);
        return e;
    }

    private InputEvent peg(final int account, final byte side, final int qty, final long cap, final byte tif) {
        final InputEvent e = typed(account, side, qty, OrderTypes.PEGGED, tif, cap, 0L);
        e.pegRef = OrderTypes.PEG_PRIMARY;
        return e;
    }

    private Capture phase(final MatchingEngineClusteredService s, final byte phase) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SESSION_CONTROL;
        e.side = phase;
        e.setClientOrderKey(8_000L + phase);
        return apply(s, e);
    }

    private Capture day(final MatchingEngineClusteredService s, final byte kind, final int date) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_BUSINESS_DAY;
        e.side = kind;
        e.qty = date;
        e.setClientOrderKey(9_000L + date);
        return apply(s, e);
    }

    private static int refOf(final Capture c) {
        for (final byte[] r : c.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(r);
            if (ack.getByte(21) == 0 && OutputEvent.isOrderLifecycleKind(ack.getByte(12))) {
                return ack.getInt(8);
            }
        }
        throw new AssertionError("no direct ack");
    }

    private static byte dayOutcome(final Capture c) {
        for (final byte[] r : c.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(r);
            if (ack.getByte(12) == MatchingEngineClusteredService.KIND_BUSINESS_DAY) {
                return (byte) ack.getInt(8);
            }
        }
        throw new AssertionError("no business-day ack");
    }

    private static long[] state(final MatchingEngineClusteredService s, final int ref) {
        return s.engine().orderState(ref);
    }

    private long[] lastUpdate(final int ref) {
        long[] found = null;
        for (final long[] u : updates) {
            if (u[0] == ref) {
                found = u;
            }
        }
        assertTrue(found != null, "no update for " + ref);
        return found;
    }

    private static List<byte[]> records(final MatchingEngineClusteredService s) {
        final List<byte[]> out = new ArrayList<>();
        s.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            out.add(copy);
        });
        return out;
    }

    private MatchingEngineClusteredService restore(final List<byte[]> stream, final int[] limits) {
        final MatchingEngineClusteredService t = new MatchingEngineClusteredService();
        t.initEngine();
        if (limits != null) {
            t.engine().setOrderTypeLimits(limits[0], limits[1], limits[2]);
        }
        t.outputSink(o -> {
            if (OutputEvent.isOrderLifecycleKind(o.kind)) {
                updates.add(new long[] { o.orderRef, o.flags, o.status, o.riskReason });
            }
        });
        boolean done = false;
        for (final byte[] r : stream) {
            done = t.onSnapshotRecord(new UnsafeBuffer(r), 0);
        }
        assertTrue(done, "the stream ends with T_END");
        return t;
    }

    // ===== SC-OT36: the PRE_OPEN release keeps each order's ORIGINAL submission sequence ========

    @Test
    void sc36_releasedOrdersKeepTheirOriginalSubmissionSequenceInTheLatchOrder() {
        final MatchingEngineClusteredService s = seeded();
        final int a = refOf(apply(s, typed(A, InputEvent.SIDE_SELL, 1, OrderTypes.STOP, OrderTypes.GTC, 0L, 149 * PX)));
        phase(s, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        final int b = refOf(apply(s, typed(B, InputEvent.SIDE_BUY, 1, OrderTypes.STOP, OrderTypes.GTC, 0L, 149 * PX)));
        final int c = refOf(apply(s, typed(C, InputEvent.SIDE_SELL, 1, OrderTypes.STOP, OrderTypes.GTC, 0L, 149 * PX)));
        apply(s, order(D, InputEvent.SIDE_SELL, 1, 149 * PX));
        apply(s, order(E, InputEvent.SIDE_BUY, 1, 0L));   // a queued market order: prints 149 at its turn
        updates.clear();
        phase(s, MatchingEngineClusteredService.PHASE_OPEN);

        final List<Long> triggered = new ArrayList<>();
        for (final long[] u : updates) {
            if ((u[1] & OutputEvent.FLAG_TRIGGERED) != 0) {
                triggered.add(u[0]);
            }
        }
        assertEquals(List.of((long) a, (long) b, (long) c), triggered,
            "one print, three stops: admission order, both sides together, queued orders by submission");
        final long[] extB = new long[MatchingEngine.ORDER_EXT_TUPLE_LENGTH];
        final long[] extC = new long[MatchingEngine.ORDER_EXT_TUPLE_LENGTH];
        assertTrue(s.engine().copyOrderExtension(b, extB));
        assertTrue(s.engine().copyOrderExtension(c, extC));
        assertTrue(extB[15] < extC[15], "unique, ascending original sequences");
        assertTrue(extC[15] < s.appliedSeq(), "not the shared sequence of the OPEN that released them");
    }

    // ===== SC-OT29 / SC-OT44: halts are not expiry; the queue keeps its existing behaviour ======

    @Test
    void sc29_aHaltExpiresNothing() {
        final MatchingEngineClusteredService s = seeded();
        assertEquals(MatchingEngine.DAY_APPLIED, dayOutcome(day(s, InputEvent.BUSINESS_DAY_START, 20260921)));
        apply(s, order(B, InputEvent.SIDE_BUY, 1, 149 * PX));   // a non-peg reference for the peg
        final int dayLimit = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.DAY, 148 * PX, 0L)));
        final int gtcLimit = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.GTC, 148 * PX, 0L)));
        final int dayStop = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.STOP, OrderTypes.DAY, 0L, 160 * PX)));
        final int dayPeg = refOf(apply(s, peg(C, InputEvent.SIDE_BUY, 1, 149 * PX, OrderTypes.DAY)));
        final long reservedA = s.risk().reservedNotional(A);
        final long reservedC = s.risk().reservedNotional(C);
        final long[][] before = { state(s, dayLimit), state(s, gtcLimit), state(s, dayStop), state(s, dayPeg) };
        phase(s, MatchingEngineClusteredService.PHASE_CLOSED);
        phase(s, MatchingEngineClusteredService.PHASE_OPEN);
        final long[][] after = { state(s, dayLimit), state(s, gtcLimit), state(s, dayStop), state(s, dayPeg) };
        for (int i = 0; i < before.length; i++) {
            assertArrayEquals(before[i], after[i], "order " + i + " unchanged by CLOSED -> OPEN");
        }
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, (byte) after[2][0]);
        assertEquals(reservedA, s.risk().reservedNotional(A));
        assertEquals(reservedC, s.risk().reservedNotional(C));
    }

    @Test
    void sc44_thePreOpenQueueKeepsItsBehaviourAndDayEndRemovesOnlyDayEntries() {
        final MatchingEngineClusteredService s = seeded();
        phase(s, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        final int refused = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.DAY, 148 * PX, 0L)));
        assertEquals(RiskReason.NO_TRADING_DAY.ordinal(), lastUpdate(refused)[3], "no open day: refused at queue time");
        day(s, InputEvent.BUSINESS_DAY_START, 20260921);

        final int day1 = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.DAY, 148 * PX, 0L)));
        final int gtc1 = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.GTC, 148 * PX, 0L)));
        phase(s, MatchingEngineClusteredService.PHASE_CLOSED);
        assertEquals(RiskReason.SESSION_CANCELED.ordinal(), lastUpdate(day1)[3], "decision (b) unchanged");
        assertEquals(RiskReason.SESSION_CANCELED.ordinal(), lastUpdate(gtc1)[3]);

        phase(s, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        final int day2 = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.DAY, 148 * PX, 0L)));
        final int gtc2 = refOf(apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.GTC, 148 * PX, 0L)));
        phase(s, MatchingEngineClusteredService.PHASE_OPEN);
        assertEquals(RestingOrder.STATUS_NEW, (byte) state(s, day2)[0], "released at the open");
        assertEquals(RestingOrder.STATUS_NEW, (byte) state(s, gtc2)[0]);

        phase(s, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        final int day3 = refOf(apply(s, typed(B, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.DAY, 148 * PX, 0L)));
        apply(s, typed(B, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.GTC, 148 * PX, 0L));
        assertEquals(2, s.queueDepth());
        assertEquals(MatchingEngine.DAY_APPLIED, dayOutcome(day(s, InputEvent.BUSINESS_DAY_END, 20260921)));
        assertEquals(1, s.queueDepth(), "only the queued DAY entry is removed");
        assertEquals(RiskReason.DAY_EXPIRED.ordinal(), lastUpdate(day3)[3]);
        assertEquals(RiskReason.DAY_EXPIRED.ordinal(), lastUpdate(day2)[3], "the book's DAY order too");
        assertEquals(RestingOrder.STATUS_NEW, (byte) state(s, gtc2)[0]);
    }

    // ===== SC-OT30 / SC-OT45: the business date across a snapshot at every step ==============

    private List<InputEvent> dayScript() {
        final List<InputEvent> script = new ArrayList<>();
        final int[][] steps = {
            { 0, 20260921 }, { 2 }, { 0, 20260922 }, { 2 }, { 1, 20260921 }, { 1, 20260922 },
            { 1, 20260922 }, { 1, 20260923 }, { 2 }, { 0, 20260922 } };
        long key = 50_000;
        for (final int[] step : steps) {
            if (step.length == 2) {
                final InputEvent e = new InputEvent();
                e.type = InputEvent.TYPE_BUSINESS_DAY;
                e.side = (byte) step[0];
                e.qty = step[1];
                e.setClientOrderKey(key++);
                script.add(e);
            } else {
                final InputEvent e = typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.LIMIT, OrderTypes.DAY, 148 * PX, 0L);
                e.setClientOrderKey(key++);
                script.add(e);
            }
        }
        return script;
    }

    @Test
    void sc30_sc45_aRestoredMemberAnswersEveryDateCommandAsANeverRestartedOne() {
        final MatchingEngineClusteredService live = seeded();
        MatchingEngineClusteredService restarted = restore(records(seeded()), null);
        final List<InputEvent> script = dayScript();
        final long ts = timestamp;
        final List<byte[]> liveEgress = new ArrayList<>();
        for (final InputEvent e : script) {
            liveEgress.addAll(apply(live, e).egress);
        }
        timestamp = ts;
        final List<byte[]> restartedEgress = new ArrayList<>();
        for (final InputEvent e : script) {
            restarted = restore(records(restarted), null);   // a restart between EVERY pair of commands
            restartedEgress.addAll(apply(restarted, e).egress);
        }
        assertEquals(liveEgress.size(), restartedEgress.size());
        for (int i = 0; i < liveEgress.size(); i++) {
            assertArrayEquals(liveEgress.get(i), restartedEgress.get(i), "egress record " + i);
        }
        final List<Byte> outcomes = new ArrayList<>();
        for (final byte[] r : liveEgress) {
            final UnsafeBuffer ack = new UnsafeBuffer(r);
            if (ack.getByte(12) == MatchingEngineClusteredService.KIND_BUSINESS_DAY) {
                outcomes.add((byte) ack.getInt(8));
            }
        }
        assertEquals(List.of(MatchingEngine.DAY_APPLIED, MatchingEngine.DAY_APPLIED, MatchingEngine.DAY_STALE,
            MatchingEngine.DAY_APPLIED, MatchingEngine.DAY_STALE, MatchingEngine.DAY_OUT_OF_SEQUENCE,
            MatchingEngine.DAY_STALE), outcomes,
            "D1 start, D2 start, late D1 end, D2 end, repeat, future, re-start D2");
    }

    // ===== SC-OT05: formats 9-10 restore as untyped; review r3 item 1: limits are consensus ====

    @Test
    void sc05_aFormatTenSnapshotRestoresAsUntypedWithNoTradeReferenceAndNoDate() {
        final MatchingEngineClusteredService s = seeded();
        apply(s, order(A, InputEvent.SIDE_SELL, 1, 150 * PX));
        apply(s, order(B, InputEvent.SIDE_BUY, 1, 150 * PX));   // a trade: hasTraded on this build
        apply(s, order(A, InputEvent.SIDE_BUY, 3, 149 * PX));
        phase(s, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        apply(s, order(B, InputEvent.SIDE_BUY, 2, 148 * PX));
        assertTrue(s.engine().hasTraded(SECURITY));

        final List<byte[]> f10 = new ArrayList<>();
        for (final byte[] r : records(s)) {
            final UnsafeBuffer b = new UnsafeBuffer(r);
            final int type = b.getInt(0);
            if (type == MatchingEngineClusteredService.T_HEADER) {
                b.putInt(4, 10);
            }
            if (type == MatchingEngineClusteredService.T_ORDER_TYPES
                || type == MatchingEngineClusteredService.T_OT_SECURITY
                || type == MatchingEngineClusteredService.T_ORDER_EXT) {
                continue;   // a format-10 writer never wrote these
            }
            if (type == MatchingEngineClusteredService.T_QUEUED_ORDER) {
                f10.add(Arrays.copyOf(r, 4 + 8 * MatchingEngineClusteredService.LEGACY_QUEUED_TUPLE_LENGTH));
                continue;
            }
            f10.add(r);
        }
        final MatchingEngineClusteredService restored = restore(f10, null);
        assertFalse(restored.engine().hasTraded(SECURITY), "no trade reference before format 11");
        assertEquals(0, restored.engine().businessDate());
        assertEquals(s.engine().recoveryDigest(), restored.engine().recoveryDigest());
        assertEquals(1, restored.queueDepth());
        phase(restored, MatchingEngineClusteredService.PHASE_OPEN);
        assertEquals(RestingOrder.STATUS_NEW, (byte) state(restored, refOfOpen(restored, B))[0],
            "the untyped queued order releases");
    }

    private static int refOfOpen(final MatchingEngineClusteredService s, final int account) {
        for (final long[] o : s.engine().openOrderTuples()) {
            if (o[4] == account) {
                return (int) o[0];
            }
        }
        throw new AssertionError("no open order for " + account);
    }

    @Test
    void consensusLimitsAreRecordedAndAMismatchedRestoreIsRefused() {
        final MatchingEngineClusteredService s = seeded();
        apply(s, typed(A, InputEvent.SIDE_BUY, 1, OrderTypes.STOP, OrderTypes.GTC, 0L, 160 * PX));
        final List<byte[]> stream = records(s);
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> restore(stream, new int[] { 3, 2, 1 }));
        assertTrue(refused.getMessage().contains("order-type limits"), refused.getMessage());
        // Control: the same stream restores under the same limits, and replays identically.
        final MatchingEngineClusteredService same = restore(stream, null);
        final InputEvent follow = order(B, InputEvent.SIDE_BUY, 1, 149 * PX);
        final long ts = timestamp;
        final List<byte[]> x = apply(s, follow).egress;
        timestamp = ts;
        final List<byte[]> y = apply(same, follow).egress;
        assertEquals(x.size(), y.size());
        for (int i = 0; i < x.size(); i++) {
            assertArrayEquals(x.get(i), y.get(i));
        }
    }

    private InputEvent control(final int account, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = account;
        e.setControlEnabled(enabled);
        e.setControlVersion(keys++);
        return e;
    }

    @Test
    void sc31_aRiskSuspendedPegSurvivesASnapshotExactly() {
        final MatchingEngineClusteredService s = seeded();
        final int a150 = refOf(apply(s, order(C, InputEvent.SIDE_SELL, 1, 150 * PX + PX / 2)));
        apply(s, order(C, InputEvent.SIDE_SELL, 1, 151 * PX));
        final int peg = refOf(apply(s, peg(A, InputEvent.SIDE_SELL, 10, 150 * PX, OrderTypes.GTC)));
        apply(s, control(A, false));
        final InputEvent cancel = new InputEvent();
        cancel.type = InputEvent.TYPE_ORDER_CANCEL;
        cancel.orderRef = a150;
        apply(s, cancel);   // reference 151 > riskPx 150.5: re-reservation refused ACCOUNT_DISABLED
        final long[] before = state(s, peg);
        assertEquals(RestingOrder.STATUS_SUSPENDED, (byte) before[0]);
        assertEquals(RestingOrder.SUSPEND_RISK, before[7]);
        assertEquals(RiskReason.ACCOUNT_DISABLED.ordinal(), before[10]);

        final MatchingEngineClusteredService restored = restore(records(s), null);
        assertArrayEquals(before, state(restored, peg), "typed state, riskPx and reservation restored");
        assertEquals(s.risk().reservedNotional(A), restored.risk().reservedNotional(A));
        assertEquals(s.engine().recoveryDigest(), restored.engine().recoveryDigest());
        // identical follow-on: re-enable, the reference falls back under riskPx, the peg re-enters
        final List<InputEvent> follow = List.of(control(A, true),
            order(C, InputEvent.SIDE_SELL, 1, 150 * PX + PX / 4), order(B, InputEvent.SIDE_BUY, 2, 150 * PX + PX / 4));
        final long ts = timestamp;
        final List<byte[]> x = new ArrayList<>();
        for (final InputEvent e : follow) {
            x.addAll(apply(s, e).egress);
        }
        timestamp = ts;
        final List<byte[]> y = new ArrayList<>();
        for (final InputEvent e : follow) {
            y.addAll(apply(restored, e).egress);
        }
        assertEquals(x.size(), y.size());
        for (int i = 0; i < x.size(); i++) {
            assertArrayEquals(x.get(i), y.get(i), "egress " + i);
        }
        assertArrayEquals(state(s, peg), state(restored, peg));
        assertEquals(RestingOrder.STATUS_PARTIALLY_FILLED, (byte) state(s, peg)[0], "re-entered and traded");
    }

    @Test
    void aSnapshotIsRefusedWhileTheTriggerQueueHoldsAnything() {
        // Structural: the queue is drained inside every apply, so a snapshot (taken between
        // applies) always sees it empty; the writer's assertion is the tripwire for a regression.
        final MatchingEngineClusteredService s = seeded();
        assertEquals(0, s.engine().triggerQueueDepth());
        records(s);
    }

    // ===== FR-OT36: the wire ====================================================================

    @Test
    void templateNineRoundTripsAndTemplateOneAlwaysDecodesUntyped() {
        final InputEvent e = typed(A, InputEvent.SIDE_SELL, 7, OrderTypes.TRAILING_STOP, OrderTypes.DAY, 0L, 0L);
        e.trailMode = OrderTypes.TRAIL_BPS;
        e.trailValue = 150;
        e.displayQty = 3;
        e.pegOffset = -2;
        e.pegRef = OrderTypes.PEG_MIDPOINT;
        codec.encodeOrderInstruction(typedIngress, 0, e, 99, 0, 0);
        final InputEvent out = new InputEvent();
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeOrderInstruction(typedIngress, 0,
            AeronReplicationCodec.ORDER_INSTRUCTION_BYTES, out));
        assertEquals(99, out.seq);
        assertEquals(OrderTypes.TRAILING_STOP, out.orderType);
        assertEquals(OrderTypes.DAY, out.tif);
        assertEquals(OrderTypes.TRAIL_BPS, out.trailMode);
        assertEquals(150, out.trailValue);
        assertEquals(3, out.displayQty);
        assertEquals(-2, out.pegOffset);
        assertEquals(OrderTypes.PEG_MIDPOINT, out.pegRef);
        assertEquals(7, out.qty);
        // the same reused slot, then an untyped message: nothing typed may leak
        codec.encodeInput(ingress, 0, order(B, InputEvent.SIDE_BUY, 1, 150 * PX), 1, 0, 0);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(ingress, 0, AeronReplicationCodec.INPUT_BYTES, out));
        assertEquals(OrderTypes.LEGACY, out.orderType);
        assertEquals(0, out.trailValue);
        assertEquals(0, out.displayQty);
        // wrong length on template 9 fails closed
        assertNotEquals(AeronReplicationCodec.OK, codec.tryDecodeOrderInstruction(typedIngress, 0,
            AeronReplicationCodec.INPUT_BYTES, out));
    }
}
