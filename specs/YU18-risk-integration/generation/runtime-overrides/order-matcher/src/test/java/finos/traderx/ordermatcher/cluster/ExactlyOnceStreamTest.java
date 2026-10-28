package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.StreamDedupe;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-090 (FR-XOD-01..10): a sequenced NEW is booked exactly once for the life of its stream, however far behind
 * the FR-IMRG14 key window its resend arrives. The negative control shows today's window losing that race at the
 * same depth (it books twice), which is what makes the sequenced cases worth anything.
 */
class ExactlyOnceStreamTest {
    static final long PX = 1_000_000L;
    static final long STREAM = 0x5EEDL;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    long ts = 1_000_000_000_000L;

    /** Egress capture: {kind, orderRef, riskReason} of every ack the member offers. */
    static final class Capture implements ClientSession {
        final List<int[]> acks = new ArrayList<>();
        @Override public long id() { return 7; }
        @Override public int responseStreamId() { return 0; }
        @Override public String responseChannel() { return "test"; }
        @Override public byte[] encodedPrincipal() { return new byte[0]; }
        @Override public void close() { }
        @Override public boolean isClosing() { return false; }
        @Override public long offer(final DirectBuffer b, final int o, final int l) {
            for (int r = 0; r + 32 <= l; r += 32) {
                acks.add(new int[] { b.getByte(o + r + 12), b.getInt(o + r + 8), b.getByte(o + r + 22) });
            }
            return 1;
        }
        @Override public long offer(final DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        @Override public long tryClaim(final int length, final BufferClaim claim) { throw new UnsupportedOperationException(); }
        int[] last() { return acks.get(acks.size() - 1); }
    }

    final Capture session = new Capture();

    void apply(MatchingEngineClusteredService s, InputEvent e) {
        codec.encodeInput(in, 0, e, 0, 0, 0);
        s.onSessionMessage(session, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    /** A sequenced NEW: flag + 16-byte tail; its key is the binary wire's {@code streamId:u32 | seq:u32}. */
    void applySeq(MatchingEngineClusteredService s, int acct, byte side, long seq) {
        final InputEvent e = order(acct, side, 1, (STREAM << 32) | seq);
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SEQUENCED);
        final int len = AeronReplicationCodec.appendSequencedTail(in, 0, AeronReplicationCodec.INPUT_BYTES, STREAM, seq);
        s.onSessionMessage(session, ++ts, in, 0, len, null);
    }

    InputEvent ctl(byte type, int acct, int sec) {
        InputEvent e = new InputEvent();
        e.type = type; e.accountId = acct; e.securityId = sec;
        e.setControlEnabled(true); e.setControlVersion(1L);
        return e;
    }

    InputEvent order(int acct, byte side, int qty, long key) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = acct; e.securityId = 0;
        e.side = side; e.qty = qty; e.limitPx = 100 * PX; e.priceTicks = key;
        return e;
    }

    MatchingEngineClusteredService venue() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 2, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = 100 * PX;
        apply(s, tick);
        return s;
    }

    /** Push every unsequenced key the FR-IMRG14 table holds out of it (unknown account: rejected, remembered). */
    void flushKeyWindow(MatchingEngineClusteredService s) {
        for (long k = 1; k <= MatchingEngineClusteredService.IDEMPOTENCY_CAPACITY + 1; k++) {
            apply(s, order(99, InputEvent.SIDE_BUY, 1, 0x7000_0000_0000L + k));
        }
    }

    /** Negative control: today's key window at the same depth books the resend a second time. */
    @Test
    void unsequencedResendBehindTheKeyWindowBooksTwice() {
        final MatchingEngineClusteredService s = venue();
        final long key = 0xC0FFEEL;
        apply(s, order(1, InputEvent.SIDE_BUY, 1, key));
        apply(s, order(2, InputEvent.SIDE_SELL, 1, 0L));
        assertEquals(1, s.engine().positionQuantity(1, 0));
        flushKeyWindow(s);
        apply(s, order(1, InputEvent.SIDE_BUY, 1, key));        // the resend, 262,145 keys later
        apply(s, order(2, InputEvent.SIDE_SELL, 1, 0L));
        assertEquals(2, s.engine().positionQuantity(1, 0), "control: the window forgot the key and booked it twice");
    }

    @Test
    void sequencedResendBehindTheKeyWindowIsTheOriginal() {
        final MatchingEngineClusteredService s = venue();
        final List<long[]> outs = new ArrayList<>();   // {kind, orderRef, publishNats}
        s.outputSink(o -> outs.add(new long[] { o.kind, o.orderRef, o.publishNats ? 1 : 0 }));
        applySeq(s, 1, InputEvent.SIDE_BUY, 1);
        final int originalRef = (int) s.nextOrderRef() - 1;
        apply(s, order(2, InputEvent.SIDE_SELL, 1, 0L));
        flushKeyWindow(s);
        outs.clear();
        applySeq(s, 1, InputEvent.SIDE_BUY, 1);                 // the resend
        assertEquals(1, outs.size(), "one answer");
        assertEquals(originalRef, (int) outs.get(0)[1], "the ORIGINAL ref");
        assertEquals(0L, outs.get(0)[2], "a restatement, not published");
        apply(s, order(2, InputEvent.SIDE_SELL, 1, 0L));
        assertEquals(1, s.engine().positionQuantity(1, 0), "booked exactly once");
        assertEquals(0L, s.risk().reservedNotional(1), "nothing reserved by the resend");
    }

    @Test
    void contiguousSequencesBookAndTheirDuplicatesDoNot() {
        final MatchingEngineClusteredService s = venue();
        for (long seq = 1; seq <= 3; seq++) applySeq(s, 1, InputEvent.SIDE_BUY, seq);
        final int lastRef = (int) s.nextOrderRef() - 1;
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) session.last()[0]);
        applySeq(s, 1, InputEvent.SIDE_BUY, 2);                 // duplicate of a RESTING order
        assertEquals(lastRef - 1, session.last()[1], "answered with seq 2's original ref");
        applySeq(s, 1, InputEvent.SIDE_BUY, 4);
        for (int i = 0; i < 4; i++) apply(s, order(2, InputEvent.SIDE_SELL, 1, 0L));
        assertEquals(4, s.engine().positionQuantity(1, 0), "4 sequenced orders, 4 fills");
    }

    @Test
    void aGapIsRefusedWithTheExpectedSequenceAndChangesNothing() {
        final MatchingEngineClusteredService s = venue();
        applySeq(s, 1, InputEvent.SIDE_BUY, 1);
        final long nextRef = s.nextOrderRef();
        final byte[] before = SnapshotPackedRoundTripTest.flat(s);
        applySeq(s, 1, InputEvent.SIDE_BUY, 3);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, (byte) session.last()[0]);
        assertEquals(RiskReason.OUT_OF_SEQUENCE.ordinal(), session.last()[2]);
        assertEquals(2, session.last()[1], "the ack names the expected sequence");
        assertEquals(nextRef, s.nextOrderRef(), "no ref consumed");
        assertArrayEquals(before, SnapshotPackedRoundTripTest.flat(s), "no state changed");
        applySeq(s, 1, InputEvent.SIDE_BUY, 2);
        applySeq(s, 1, InputEvent.SIDE_BUY, 3);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) session.last()[0], "the go-back-N resend books");
    }

    @Test
    void anUnknownStreamStartsAtOneNeverLater() {
        final MatchingEngineClusteredService s = venue();
        applySeq(s, 1, InputEvent.SIDE_BUY, 2);                 // seq 1 was lost on the way
        assertEquals(RiskReason.OUT_OF_SEQUENCE.ordinal(), session.last()[2]);
        assertEquals(1, session.last()[1]);
        applySeq(s, 1, InputEvent.SIDE_BUY, 1);
        applySeq(s, 1, InputEvent.SIDE_BUY, 2);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) session.last()[0], "seq 2 books after seq 1");
        assertEquals(1, s.risk().streams().size(), "one stream");
    }

    @Test
    void aDuplicateOlderThanTheRingIsRefusedNotBooked() {
        final MatchingEngineClusteredService s = venue();
        final int w = MatchingEngineClusteredService.STREAM_WINDOW;
        for (long seq = 1; seq <= w + 10; seq++) applySeq(s, 1, InputEvent.SIDE_BUY, seq);
        final long nextRef = s.nextOrderRef();
        applySeq(s, 1, InputEvent.SIDE_BUY, 1);
        assertEquals(RiskReason.DUPLICATE.ordinal(), session.last()[2]);
        assertEquals(nextRef, s.nextOrderRef(), "nothing applied");
        for (int i = 0; i < w + 11; i++) apply(s, order(2, InputEvent.SIDE_SELL, 1, 0L));
        assertEquals(w + 10, s.engine().positionQuantity(1, 0), "every sequenced order booked once");
    }

    @Test
    void aRejectedOriginalIsReansweredWithItsRejection() {
        final MatchingEngineClusteredService s = venue();
        applySeq(s, 99, InputEvent.SIDE_BUY, 1);                // unknown account
        assertEquals(RiskReason.UNKNOWN_ACCOUNT.ordinal(), session.last()[2]);
        applySeq(s, 99, InputEvent.SIDE_BUY, 1);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, (byte) session.last()[0]);
        assertEquals(RiskReason.UNKNOWN_ACCOUNT.ordinal(), session.last()[2]);
    }

    /** FR-XOD-09: snapshot round trip, then the same tail on both members gives the same answers and bytes. */
    @Test
    void snapshotPlusTailEqualsTheFullLog() {
        final MatchingEngineClusteredService a = venue();
        for (long seq = 1; seq <= 5; seq++) applySeq(a, 1, InputEvent.SIDE_BUY, seq);
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        final MatchingEngineClusteredService b = SnapshotPackedRoundTripTest.restore(records);
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(b));
        final long t0 = ts;
        for (final MatchingEngineClusteredService m : List.of(a, b)) {
            ts = t0;                                            // the same log: the same cluster timestamps
            session.acks.clear();
            applySeq(m, 1, InputEvent.SIDE_BUY, 3);             // duplicate
            applySeq(m, 1, InputEvent.SIDE_BUY, 7);             // gap
            applySeq(m, 1, InputEvent.SIDE_BUY, 6);             // next
            assertEquals(3, session.acks.size());
        }
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(b));
    }

    /** FR-XOD-09: a format-13 snapshot (no T_STREAM) restores with no streams. */
    @Test
    void aFormat13SnapshotRestoresWithNoStreams() {
        final MatchingEngineClusteredService a = venue();
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        new UnsafeBuffer(records.get(0)).putInt(4, 13);
        final MatchingEngineClusteredService b = SnapshotPackedRoundTripTest.restore(records);
        assertEquals(0, b.risk().streams().size());
        applySeq(b, 1, InputEvent.SIDE_BUY, 1);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) session.last()[0]);
    }

    /** FR-XOD-07/08: eviction by the caller's (log) time only, least-recently-used first, ties by index. */
    @Test
    void aFullTableEvictsOnlyAnIdleStream() {
        final StreamDedupe d = new StreamDedupe(4, 2, 1_000L);
        assertEquals(StreamDedupe.ADMIT, d.admit(1, 10L, 1, 0L));
        assertEquals(StreamDedupe.ADMIT, d.admit(1, 11L, 1, 0L));
        assertEquals(StreamDedupe.LIMIT, d.admit(1, 12L, 1, 999L), "no stream idle 1,000 ms yet");
        assertEquals(StreamDedupe.ADMIT, d.admit(1, 12L, 1, 1_000L), "evicts stream 10 (index 0)");
        assertEquals(StreamDedupe.ADMIT, d.admit(1, 11L, 2, 1_000L), "stream 11 kept its sequence");
        assertEquals(StreamDedupe.GAP, d.admit(1, 10L, 2, 1_000L), "an evicted stream starts again at 1");
        assertEquals(1L, d.expected());
        assertEquals(StreamDedupe.GAP, d.admit(2, 11L, 2, 1_000L), "streams are per account");
    }

    /** FR-XOD-06: a member never restores streams written with another ring width (W changes the answers it gives). */
    @Test
    void aSnapshotWrittenWithAnotherWindowIsRefused() {
        final StreamDedupe four = new StreamDedupe(4, 2, 1_000L);
        four.admit(1, 10L, 1, 0L);
        final long[] tuple = four.tuple(0);
        new StreamDedupe(4, 2, 1_000L).restore(tuple);
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new StreamDedupe(8, 2, 1_000L).restore(tuple));
        assertTrue(e.getMessage().contains("EXACTLY_ONCE_STREAM_WINDOW"), e.getMessage());
    }

    /** FR-XOD-10: both lengths decode; a flag without its tail, or a tail without its flag, is refused. */
    @Test
    void theSequencedTailAndItsFlagMustAgree() {
        final InputEvent e = order(1, InputEvent.SIDE_BUY, 1, 9L);
        final InputEvent out = new InputEvent();
        codec.encodeInput(in, 0, e, 0, 0, 0);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, AeronReplicationCodec.INPUT_BYTES, out));
        assertEquals(0L, out.streamSeq);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, AeronReplicationCodec.INPUT_BYTES + 16, out));
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SEQUENCED);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, AeronReplicationCodec.INPUT_BYTES, out));
        final int len = AeronReplicationCodec.appendSequencedTail(in, 0, AeronReplicationCodec.INPUT_BYTES, 77L, 5L);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, len, out));
        assertEquals(77L, out.streamId);
        assertEquals(5L, out.streamSeq);
        assertEquals(5L, in.getLong(AeronReplicationCodec.INPUT_BYTES + 8, ByteOrder.LITTLE_ENDIAN));
        codec.encodeInput(in, 0, e, 0, 0, 0);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, AeronReplicationCodec.INPUT_BYTES, out));
        assertEquals(0L, out.streamSeq, "a reused slot does not keep the previous tail");
        assertEquals(0L, out.streamId);
    }
}
