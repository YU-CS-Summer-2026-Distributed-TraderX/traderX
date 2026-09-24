package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import io.aeron.DirectBufferVector;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 (RI-07) replay consistency: the trailing-stop ratchet updates are part of the deterministic
 * output. A member that replays the whole log, and a member restored from a snapshot taken between
 * ratchets that then applies the tail, must emit the SAME ratchet updates (same order refs, flags,
 * status and published stop level) and the same egress bytes, and end in the same order state.
 */
class TrailingStopRatchetReplayTest {
    private static final int A = 42422;
    private static final int B = 22214;
    private static final int C = 11413;
    private static final int D = 52355;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;
    private static final long T0 = 1_000_000_000_000L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingress = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer typedIngress = new UnsafeBuffer(new byte[AeronReplicationCodec.ORDER_INSTRUCTION_BYTES]);

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

    /** One member under test: its service, every order update {ref, flags, status, stopPx}, its egress. */
    static final class Member {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        final List<long[]> updates = new ArrayList<>();
        final List<byte[]> egress = new ArrayList<>();

        Member() {
            s.initEngine();
            sink();
        }

        void sink() {
            s.outputSink(o -> {
                if (OutputEvent.isOrderLifecycleKind(o.kind)) {
                    updates.add(new long[] { o.orderRef, o.flags, o.status, o.typed.stopPx });
                }
            });
        }
    }

    private void apply(final Member m, final InputEvent e, final long timestamp) {
        final Capture session = new Capture();
        if (e.orderType != OrderTypes.LEGACY) {
            codec.encodeOrderInstruction(typedIngress, 0, e, 0, 0, 0);
            m.s.onSessionMessage(session, timestamp, typedIngress, 0, AeronReplicationCodec.ORDER_INSTRUCTION_BYTES, null);
        } else {
            codec.encodeInput(ingress, 0, e, 0, 0, 0);
            m.s.onSessionMessage(session, timestamp, ingress, 0, AeronReplicationCodec.INPUT_BYTES, null);
        }
        m.egress.addAll(session.egress);
    }

    private static InputEvent control(final byte type, final int account, final long version) {
        final InputEvent e = new InputEvent();
        e.type = type;
        e.accountId = account;
        e.securityId = SECURITY;
        e.setControlEnabled(true);
        e.setControlVersion(version);
        return e;
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

    private static InputEvent trailing(final int account, final byte side, final byte mode, final long value, final long key) {
        final InputEvent e = order(account, side, 1, 0L);
        e.orderType = OrderTypes.TRAILING_STOP;
        e.tif = OrderTypes.GTC;
        e.trailMode = mode;
        e.trailValue = value;
        e.setClientOrderKey(key);
        return e;
    }

    /** The log: controls, a first print, one sell and one buy trail of each mode, then a price walk
     *  that stays inside every trail (146..153 against 10.00 and 5%), so all four stay pending and
     *  the tail still ratchets (the buys at 147 and 146). */
    private static List<InputEvent> log() {
        final List<InputEvent> in = new ArrayList<>();
        long v = 1;
        for (final int account : new int[] { A, B, C, D }) {
            in.add(control(InputEvent.TYPE_ACCOUNT_CONTROL, account, v++));
        }
        in.add(control(InputEvent.TYPE_SECURITY_CONTROL, 0, v++));
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = SECURITY;
        tick.priceTicks = 150 * PX;
        in.add(tick);
        in.add(order(C, InputEvent.SIDE_SELL, 1, 150 * PX));
        in.add(order(D, InputEvent.SIDE_BUY, 1, 150 * PX));
        in.add(trailing(A, InputEvent.SIDE_SELL, OrderTypes.TRAIL_AMOUNT, 10 * PX, 101));
        in.add(trailing(A, InputEvent.SIDE_SELL, OrderTypes.TRAIL_BPS, 500, 102));
        in.add(trailing(B, InputEvent.SIDE_BUY, OrderTypes.TRAIL_AMOUNT, 10 * PX, 103));
        in.add(trailing(B, InputEvent.SIDE_BUY, OrderTypes.TRAIL_BPS, 500, 104));
        final long[] walk = { 151, 152, 149, 148, 153, 151, 147, 150, 152, 146 };
        for (final long px : walk) {
            in.add(order(C, InputEvent.SIDE_SELL, 1, px * PX));
            in.add(order(D, InputEvent.SIDE_BUY, 1, px * PX));
        }
        return in;
    }

    private static List<byte[]> snapshot(final Member m) {
        final List<byte[]> out = new ArrayList<>();
        m.s.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            out.add(copy);
        });
        return out;
    }

    private static List<long[]> ratchets(final List<long[]> updates) {
        final List<long[]> out = new ArrayList<>();
        for (final long[] u : updates) {
            if ((u[1] & OutputEvent.FLAG_RESTING_UPDATE) != 0 && u[2] == RestingOrder.STATUS_PENDING_TRIGGER) {
                out.add(u);
            }
        }
        return out;
    }

    private static void assertSameRows(final List<long[]> x, final List<long[]> y, final String what) {
        assertEquals(x.size(), y.size(), what + " count");
        for (int i = 0; i < x.size(); i++) {
            assertArrayEquals(x.get(i), y.get(i), what + " " + i);
        }
    }

    private static void assertSameBytes(final List<byte[]> x, final List<byte[]> y) {
        assertEquals(x.size(), y.size(), "egress count");
        for (int i = 0; i < x.size(); i++) {
            assertArrayEquals(x.get(i), y.get(i), "egress " + i);
        }
    }

    @Test
    void aFullLogReplayEmitsTheSameRatchetUpdatesAndBytes() {
        final List<InputEvent> in = log();
        final Member live = new Member();
        final Member replay = new Member();
        for (int i = 0; i < in.size(); i++) {
            apply(live, in.get(i), T0 + i);
        }
        for (int i = 0; i < in.size(); i++) {
            apply(replay, in.get(i), T0 + i);
        }
        assertTrue(ratchets(live.updates).size() >= 4, "the walk ratchets buy and sell trails: "
            + ratchets(live.updates).size());
        assertSameRows(live.updates, replay.updates, "order update");
        assertSameBytes(live.egress, replay.egress);
        assertEquals(live.s.engine().recoveryDigest(), replay.s.engine().recoveryDigest());
    }

    @Test
    void aSnapshotBetweenRatchetsPlusTheTailEmitsTheSameRatchetUpdates() {
        final List<InputEvent> in = log();
        final int cut = in.size() - 8;   // after some ratchets, before the last four prints
        final Member live = new Member();
        for (int i = 0; i < cut; i++) {
            apply(live, in.get(i), T0 + i);
        }
        final int ratchetsBeforeCut = ratchets(live.updates).size();
        assertTrue(ratchetsBeforeCut >= 1, "a ratchet happened before the snapshot");
        final Member restored = new Member();
        boolean done = false;
        for (final byte[] r : snapshot(live)) {
            done = restored.s.onSnapshotRecord(new UnsafeBuffer(r), 0);
        }
        assertTrue(done, "the stream ends with T_END");
        assertEquals(live.s.engine().recoveryDigest(), restored.s.engine().recoveryDigest(),
            "watermark and published level restored");
        final int liveUpdatesAtCut = live.updates.size();
        final int liveEgressAtCut = live.egress.size();
        for (int i = cut; i < in.size(); i++) {
            apply(live, in.get(i), T0 + i);
            apply(restored, in.get(i), T0 + i);
        }
        final List<long[]> liveTail = live.updates.subList(liveUpdatesAtCut, live.updates.size());
        assertTrue(ratchets(liveTail).size() >= 1, "the tail ratchets too");
        assertSameRows(liveTail, restored.updates, "tail order update");
        assertSameBytes(live.egress.subList(liveEgressAtCut, live.egress.size()), restored.egress);
        assertEquals(live.s.engine().recoveryDigest(), restored.s.engine().recoveryDigest());
    }
}
