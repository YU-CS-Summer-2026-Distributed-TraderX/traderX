package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-100 (codeX review §13): the order-reference generator fails closed at {@code Integer.MAX_VALUE}
 * instead of wrapping through the int cast onto the reserved ref 0 and then recycling 1, 2, ...
 * Before the fix the second NEW below was issued ref -2147483648 and, two refs later, ref 0.
 */
class OrderRefExhaustionTest {
    static final long PX = 1_000_000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    final ExactlyOnceStreamTest.Capture session = new ExactlyOnceStreamTest.Capture();
    long ts = 1_000_000_000_000L;

    void apply(MatchingEngineClusteredService s, InputEvent e) {
        codec.encodeInput(in, 0, e, 0, 0, 0);
        s.onSessionMessage(session, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    InputEvent ctl(byte type, int acct, int sec) {
        InputEvent e = new InputEvent();
        e.type = type; e.accountId = acct; e.securityId = sec;
        e.setControlEnabled(true); e.setControlVersion(1L);
        return e;
    }

    InputEvent order(long key) {
        InputEvent o = new InputEvent();
        o.type = InputEvent.TYPE_ORDER_NEW; o.accountId = 1; o.securityId = 0;
        o.side = InputEvent.SIDE_BUY; o.qty = 10; o.limitPx = 100 * PX; o.priceTicks = key;
        return o;
    }

    static MatchingEngineClusteredService open() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        return s;
    }

    void bootstrap(MatchingEngineClusteredService s) {
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = 100 * PX;
        apply(s, tick);
    }

    /** The generator is replicated state; a snapshot header is its only legitimate writer, so the test sets it the
     *  way the codeX repro did: directly. */
    static void setNextOrderRef(MatchingEngineClusteredService s, long v) throws Exception {
        final Field f = MatchingEngineClusteredService.class.getDeclaredField("nextOrderRef");
        f.setAccessible(true);
        f.setLong(s, v);
    }

    @Test void lastRefIsIssuedThenEveryNewIsRefusedWithoutWrapping() throws Exception {
        final MatchingEngineClusteredService s = open();
        bootstrap(s);
        setNextOrderRef(s, Integer.MAX_VALUE);        // one reference left in the epoch
        final long seqBefore = s.appliedSeq();
        final List<Integer> issued = new ArrayList<>();
        s.outputSink(o -> { if (o.kind == OutputEvent.KIND_ORDER_ACCEPTED) issued.add(o.orderRef); });

        apply(s, order(1L));
        assertEquals(List.of(Integer.MAX_VALUE), issued, "the last reference is issued normally");
        assertEquals(0L, s.orderRefHeadroom());

        session.acks.clear();
        for (long key = 2; key <= 4; key++) {
            apply(s, order(key));                     // 2^31, 2^31 + 1 (the pre-fix 0 lands at key 3), 2^31 + 2
        }
        assertEquals(List.of(Integer.MAX_VALUE), issued, "nothing is issued past the ceiling");
        assertEquals(3, session.acks.size());
        for (int[] ack : session.acks) {
            assertEquals(OutputEvent.KIND_ORDER_REJECTED, ack[0]);
            assertEquals(0, ack[1], "a refusal names no reference: never 0 as an ISSUED ref, never negative");
            assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), ack[2]);
        }
        assertEquals((long) Integer.MAX_VALUE + 1, s.nextOrderRef(), "the generator does not advance past the ceiling");
        assertEquals(seqBefore + 1, s.appliedSeq(), "refused NEWs apply nothing (same posture as a stream gap)");

        // Cancels of a live order still work: the epoch is closed to NEW orders only.
        InputEvent cancel = new InputEvent();
        cancel.type = InputEvent.TYPE_ORDER_CANCEL; cancel.accountId = 1; cancel.orderRef = Integer.MAX_VALUE;
        session.acks.clear();
        apply(s, cancel);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, session.acks.get(0)[0]);
    }

    /** codeX pass 3 §15: the ack of the LAST order is lost and the client retries its key. Before the fix the
     *  ceiling check ran ahead of deduplication and answered ORDER_REF_EXHAUSTED, ref 0, while the order existed. */
    @Test void retryOfACommittedKeyRecoversItsAnswerWithoutARef() throws Exception {
        final MatchingEngineClusteredService s = open();
        bootstrap(s);
        setNextOrderRef(s, Integer.MAX_VALUE);
        apply(s, order(42L));                             // accepted with the last ref
        assertEquals(Integer.MAX_VALUE, session.last()[1]);
        session.acks.clear();
        apply(s, order(42L));                             // retry of the same client key
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, session.last()[0], "the original answer, not a fresh rejection");
        assertEquals(Integer.MAX_VALUE, session.last()[1], "names the original order");
        assertEquals((long) Integer.MAX_VALUE + 1, s.nextOrderRef(), "no ref consumed by the retry");

        apply(s, order(43L));                             // a genuinely new key is still refused
        assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), session.last()[2]);
        apply(s, order(43L));                             // and its retry gets the same refusal
        assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), session.last()[2]);

        // A key waiting in the PRE_OPEN queue is committed too: its retry is re-acked from the queue.
        setNextOrderRef(s, Integer.MAX_VALUE - 1);
        InputEvent halt = new InputEvent();
        halt.type = InputEvent.TYPE_SESSION_CONTROL; halt.side = MatchingEngineClusteredService.PHASE_PRE_OPEN;
        apply(s, halt);
        apply(s, order(44L));                             // queued, holding the last ref
        assertEquals(1, s.queueDepth());
        session.acks.clear();
        apply(s, order(44L));
        assertEquals(1, s.queueDepth(), "not queued twice");
        assertTrue(session.acks.get(0)[2] != RiskReason.ORDER_REF_EXHAUSTED.ordinal(), "answered from the queue");
    }

    /** The sequenced path (ADR-090): a resend of the last accepted sequence is answered from the ring with no ref;
     *  the next sequence is refused, and a resend of THAT recovers the same refusal from the ring. */
    @Test void sequencedResendRecoversItsAnswerAtExhaustion() throws Exception {
        final MatchingEngineClusteredService s = open();
        bootstrap(s);
        setNextOrderRef(s, Integer.MAX_VALUE);
        applySeq(s, 1L);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, session.last()[0]);
        assertEquals(Integer.MAX_VALUE, session.last()[1]);
        session.acks.clear();
        applySeq(s, 1L);                                  // go-back-N resend of the accepted sequence
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, session.last()[0], "the ring's answer, not a fresh rejection");
        assertEquals(Integer.MAX_VALUE, session.last()[1]);
        applySeq(s, 2L);                                  // genuinely new
        assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), session.last()[2]);
        applySeq(s, 2L);                                  // its resend: the same refusal, recorded in the ring
        assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), session.last()[2]);
        applySeq(s, 3L);                                  // the stream advanced past the refusal: still refused
        assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), session.last()[2]);
        assertEquals((long) Integer.MAX_VALUE + 1, s.nextOrderRef());
    }

    static final long STREAM = 0x5EEDL;

    void applySeq(MatchingEngineClusteredService s, long seq) {
        final InputEvent e = order((STREAM << 32) | seq);
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SEQUENCED);
        final int len = AeronReplicationCodec.appendSequencedTail(in, 0, AeronReplicationCodec.INPUT_BYTES, STREAM, seq);
        s.onSessionMessage(session, ++ts, in, 0, len, null);
    }

    @Test void exhaustionSurvivesASnapshotAndAWrappedHeaderIsRefused() throws Exception {
        final MatchingEngineClusteredService s = open();
        bootstrap(s);
        setNextOrderRef(s, (long) Integer.MAX_VALUE + 1);
        final List<byte[]> records = new ArrayList<>();
        s.writeSnapshot((b, o, l) -> { byte[] c = new byte[l]; b.getBytes(o, c); records.add(c); });

        final MatchingEngineClusteredService restored = SnapshotPackedRoundTripTest.restore(records);
        assertEquals(0L, restored.orderRefHeadroom());
        session.acks.clear();
        apply(restored, order(9L));
        assertEquals(RiskReason.ORDER_REF_EXHAUSTED.ordinal(), session.acks.get(0)[2], "still closed after restore");

        // A header a wrapping build wrote (nextOrderRef = 2^32, i.e. a real order already got ref 0) is refused.
        final byte[] header = Arrays.copyOf(records.get(0), records.get(0).length);
        new UnsafeBuffer(header).putLong(8, 1L << 32);
        final List<byte[]> wrapped = new ArrayList<>(records);
        wrapped.set(0, header);
        assertTrue(SnapshotPackedRoundTripTest.refused(wrapped, "outside [1, 2147483648]"));
    }

    @Test void ticketBookingsCarryAnExplicitCategoryAndNoOrderKey() {
        final TradeNatsPublisher p = new TradeNatsPublisher("", "/trades", "ep9", 4);
        final String ticket = payload(p, 0);
        final String match = payload(p, 7);
        assertTrue(ticket.contains("\"bookingSource\":\"TICKET\""), ticket);
        assertFalse(ticket.contains("sourceOrderId"), "a ticket has no originating order: no sentinel key");
        assertTrue(match.contains("\"bookingSource\":\"MATCH\",\"sourceOrderId\":\"ep9-7\""), match);
    }

    static String payload(TradeNatsPublisher p, int orderRef) {
        p.offer(1L, 1, "IBM", InputEvent.SIDE_BUY, 5, 100 * PX, orderRef);
        return new String(p.encodeQueued(), StandardCharsets.UTF_8);
    }
}
