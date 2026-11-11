package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.SessionRing;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-104 (FR-XOD-19..24): FIX exactly-once in the core. A FIX session-day's ClOrdIDs are held in its own ring, so a
 * resend is the original however many other sessions' orders the FR-IMRG14 window has seen since. The negative
 * controls show today's window booking the same resends twice.
 */
class SessionRingTest {
    static final long SESSION = 0x5E55_10E0L;
    static final long KEY = 0x0C10_0000_0000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    final ExactlyOnceStreamTest harness = new ExactlyOnceStreamTest();
    final ExactlyOnceStreamTest.Capture session = harness.session;

    static int check(final long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 32);
    }

    /** A FIX NEW keyed by its session's ring: flag + 16-byte tail (session stream, check, MsgSeqNum). */
    void keyed(final MatchingEngineClusteredService s, final int acct, final byte side, final long key, final int msgSeq,
               final int flags) {
        final InputEvent e = harness.order(acct, side, 1, key);
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED | flags);
        final int len = AeronReplicationCodec.appendSessionTail(in, 0, AeronReplicationCodec.INPUT_BYTES, SESSION,
            check(key), msgSeq);
        s.onSessionMessage(session, ++harness.ts, in, 0, len, null);
    }

    void plain(final MatchingEngineClusteredService s, final int acct, final byte side, final long key) {
        harness.apply(s, harness.order(acct, side, 1, key));
    }

    /** Account 1 buys (keyed when asked), account 2's unkeyed sell crosses it: one side per order that books. */
    void cross(final MatchingEngineClusteredService s, final boolean keyedBuy, final long key, final int msgSeq,
               final int flags) {
        if (keyedBuy) keyed(s, 1, InputEvent.SIDE_BUY, key, msgSeq, flags); else plain(s, 1, InputEvent.SIDE_BUY, key);
        plain(s, 2, InputEvent.SIDE_SELL, 0L);
    }

    int[] last() { return session.last(); }

    /**
     * Acceptance run 3's case: one session's 1,464 orders, a 47 s outage while 262,145 other keys pass through the
     * FR-IMRG14 window, then all 1,464 resent with PossResend. Keyed: every resend is its original, nothing books twice.
     * Control (the same orders unkeyed, today's path): every resend books a second time.
     */
    @Test
    void run3ResendsAfterA47SecondOutageAreTheOriginals() {
        for (final boolean keyedPath : new boolean[] { true, false }) {
            final MatchingEngineClusteredService s = harness.venue();
            for (int i = 0; i < 1_464; i++) {
                cross(s, keyedPath, KEY + i, i + 2, 0);
            }
            assertEquals(1_464, s.engine().positionQuantity(1, 0));
            harness.flushKeyWindow(s);
            harness.ts += 47_000;
            for (int i = 0; i < 1_464; i++) {
                cross(s, keyedPath, KEY + i, 2_000 + i, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
            }
            if (keyedPath) {
                assertEquals(1_464, s.engine().positionQuantity(1, 0), "booked exactly once");
            } else {
                assertEquals(2 * 1_464, s.engine().positionQuantity(1, 0), "control: the window forgot every key");
            }
        }
    }

    /** A resend inside the ring is the original: ref and state re-emitted, never bridged, nothing reserved. */
    @Test
    void aResendIsAnsweredWithTheOriginalAndNotBridged() {
        final MatchingEngineClusteredService s = harness.venue();
        final List<long[]> outs = new ArrayList<>();   // {kind, orderRef, publishNats}
        s.outputSink(o -> outs.add(new long[] { o.kind, o.orderRef, o.publishNats ? 1 : 0 }));
        keyed(s, 1, InputEvent.SIDE_BUY, KEY, 2, 0);
        final int originalRef = (int) s.nextOrderRef() - 1;
        outs.clear();
        keyed(s, 1, InputEvent.SIDE_BUY, KEY, 3, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        assertEquals(1, outs.size(), "one answer");
        assertEquals(originalRef, (int) outs.get(0)[1], "the ORIGINAL ref");
        assertEquals(0L, outs.get(0)[2], "never bridged: no row in the read model or the history store");
        plain(s, 2, InputEvent.SIDE_SELL, 0L);
        assertEquals(1, s.engine().positionQuantity(1, 0), "booked once");
    }

    /**
     * FR-XOD-22: once the ring has evicted, a may-be-resend that misses it is refused AGED_OUT, never booked, except a
     * FIX-level resend (PossDup) whose own MsgSeqNum is above every evicted one, which provably never reached the
     * engine. An order with neither flag is new and books.
     */
    @Test
    void theAgedRuleAndTheMsgSeqNumFrontier() {
        final MatchingEngineClusteredService s = harness.venue();
        keyed(s, 1, InputEvent.SIDE_BUY, KEY - 1, 1, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) last()[0], "an unaged ring holds every order: a miss is new");
        final int n = MatchingEngineClusteredService.SESSION_RING;
        for (int i = 0; i < n + 52; i++) {
            cross(s, true, KEY + i, i + 2, 0);                       // MsgSeqNum 2..n+53: the first 53 evicted
        }
        assertTrue(s.risk().sessionRing().aged(SESSION));
        final long position = s.engine().positionQuantity(1, 0);
        for (final int flag : new int[] { AeronReplicationCodec.INPUT_FLAG_POSS_RESEND, AeronReplicationCodec.INPUT_FLAG_POSS_DUP }) {
            keyed(s, 1, InputEvent.SIDE_BUY, KEY, 2, flag);          // evicted, maybe executed
            assertEquals(OutputEvent.KIND_ORDER_REJECTED, (byte) last()[0]);
            assertEquals(RiskReason.SESSION_AGED_OUT.ordinal(), last()[2], "never booked by this request");
        }
        keyed(s, 1, InputEvent.SIDE_BUY, KEY + 99_000, n + 60, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        assertEquals(RiskReason.SESSION_AGED_OUT.ordinal(), last()[2], "97=Y carries a new MsgSeqNum: no frontier");
        keyed(s, 1, InputEvent.SIDE_BUY, KEY + 99_001, n + 61, AeronReplicationCodec.INPUT_FLAG_POSS_DUP);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) last()[0], "43=Y above the frontier never reached the engine");
        keyed(s, 1, InputEvent.SIDE_BUY, KEY + 99_002, n + 62, 0);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) last()[0], "no resend flag: a new order");
        keyed(s, 1, InputEvent.SIDE_BUY, KEY + n, n + 2, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        plain(s, 2, InputEvent.SIDE_SELL, 0L);
        assertEquals(position + 1, s.engine().positionQuantity(1, 0),
            "only the 43=Y order and the plain one book; the held resend is its original");
    }

    /** A collision (same 64-bit key, different check) is a different order: it books, and each resend finds its own. */
    @Test
    void aKeyCollisionNeverRefusesARealOrder() {
        final SessionRing r = new SessionRing(8, 2, 1_000L);
        assertEquals(SessionRing.ADMIT, r.admit(SESSION, KEY, 1, 1, false, false, 0L));
        r.record(10, (byte) RiskReason.ACCEPTED.ordinal());
        assertEquals(SessionRing.ADMIT, r.admit(SESSION, KEY, 2, 2, false, false, 0L), "same key, other check: new");
        r.record(11, (byte) RiskReason.ACCEPTED.ordinal());
        assertEquals(SessionRing.DUPLICATE, r.admit(SESSION, KEY, 1, 3, false, true, 0L));
        assertEquals(10, r.duplicateRef());
        assertEquals(SessionRing.DUPLICATE, r.admit(SESSION, KEY, 2, 4, false, true, 0L));
        assertEquals(11, r.duplicateRef());
    }

    /** FR-XOD-21: sessions are evicted by the caller's (log) time only, least-recently-used and idle first. */
    @Test
    void aFullSessionTableEvictsOnlyAnIdleSession() {
        final SessionRing r = new SessionRing(4, 2, 1_000L);
        assertEquals(SessionRing.ADMIT, r.admit(10L, 1L, 1, 1, false, false, 0L));
        assertEquals(SessionRing.ADMIT, r.admit(11L, 1L, 1, 1, false, false, 0L));
        assertEquals(SessionRing.LIMIT, r.admit(12L, 1L, 1, 1, false, false, 999L), "no session idle 1,000 ms yet");
        assertEquals(SessionRing.ADMIT, r.admit(12L, 1L, 1, 1, false, false, 1_000L), "evicts session 10 (index 0)");
        assertEquals(SessionRing.DUPLICATE, r.admit(11L, 1L, 1, 2, false, true, 1_000L), "session 11 kept its ring");
        assertEquals(SessionRing.LIMIT, r.admit(10L, 1L, 1, 2, false, true, 1_000L), "11 and 12 were used at 1,000");
        assertEquals(SessionRing.ADMIT, r.admit(10L, 1L, 1, 2, false, true, 2_000L), "an evicted session starts empty");
    }

    /** FR-XOD-23: snapshot round trip over several chunks, then the same tail on both members: same answers, same bytes. */
    @Test
    void snapshotPlusTailEqualsTheFullLog() {
        final MatchingEngineClusteredService a = harness.venue();
        for (int i = 0; i < 3 * SessionRing.CHUNK + 7; i++) {
            cross(a, true, KEY + i, i + 2, 0);
        }
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        final MatchingEngineClusteredService b = SnapshotPackedRoundTripTest.restore(records);
        assertEquals(1, b.risk().sessionRing().size());
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(b));
        final long t0 = harness.ts;
        final List<List<int[]>> answers = new ArrayList<>();
        for (final MatchingEngineClusteredService m : List.of(a, b)) {
            harness.ts = t0;                                        // the same log: the same cluster timestamps
            session.acks.clear();
            keyed(m, 1, InputEvent.SIDE_BUY, KEY + 3, 9_000, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);   // held
            keyed(m, 1, InputEvent.SIDE_BUY, KEY + 9_999, 9_001, 0);                                         // new
            answers.add(new ArrayList<>(session.acks));
        }
        assertEquals(answers.get(0).size(), answers.get(1).size());
        for (int i = 0; i < answers.get(0).size(); i++) {
            assertArrayEquals(answers.get(0).get(i), answers.get(1).get(i), "answer " + i);
        }
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(b));
    }

    /** Two members fed one log hold the same rings: byte-identical snapshots (determinism, FR-XOD-08). */
    @Test
    void twoMembersFedOneLogWriteTheSameSnapshot() {
        final long t0 = harness.ts;
        final MatchingEngineClusteredService a = harness.venue();
        harness.ts = t0;
        final MatchingEngineClusteredService b = harness.venue();
        final long start = harness.ts;
        for (final MatchingEngineClusteredService m : List.of(a, b)) {
            harness.ts = start;
            for (int i = 0; i < SessionRing.CHUNK + 40; i++) {
                cross(m, true, KEY + i, i + 2, 0);
            }
            keyed(m, 1, InputEvent.SIDE_BUY, KEY + 5, 999, AeronReplicationCodec.INPUT_FLAG_POSS_DUP);
        }
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(b));
    }

    /** FR-XOD-23: a format-14 snapshot (no T_SESSION_RING) restores with empty rings. */
    @Test
    void aFormat14SnapshotRestoresWithEmptyRings() {
        final MatchingEngineClusteredService a = harness.venue();
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        new UnsafeBuffer(records.get(0)).putInt(4, 14);
        final MatchingEngineClusteredService b = SnapshotPackedRoundTripTest.restore(records);
        assertEquals(0, b.risk().sessionRing().size());
        keyed(b, 1, InputEvent.SIDE_BUY, KEY, 2, 0);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) last()[0]);
    }

    /** FR-XOD-23: a member never restores rings written with another N (N changes the answers it gives). */
    @Test
    void aSnapshotWrittenWithAnotherRingSizeIsRefused() {
        final SessionRing four = new SessionRing(4, 2, 1_000L);
        four.admit(SESSION, KEY, 1, 1, false, false, 0L);
        final long[] tuple = four.tuple(0, 0);
        new SessionRing(4, 2, 1_000L).restore(tuple);
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new SessionRing(8, 2, 1_000L).restore(tuple));
        assertTrue(e.getMessage().contains("EXACTLY_ONCE_SESSION_RING"), e.getMessage());
    }

    /**
     * FR-XOD-20: the session tail and its flag must agree, it excludes the ADR-090 tail, the resend bits need it, and a
     * reused slot keeps nothing. A member built before ADR-104 knows only SHADOW and SEQUENCED, so it refuses every
     * session-keyed message (UNKNOWN_FLAGS): members roll before gateways.
     */
    @Test
    void theSessionTailAndItsFlagsMustAgree() {
        final InputEvent e = harness.order(1, InputEvent.SIDE_BUY, 1, KEY);
        final InputEvent out = new InputEvent();
        final int bytes = AeronReplicationCodec.INPUT_BYTES;
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, bytes, out), "flag without tail");
        int len = AeronReplicationCodec.appendSessionTail(in, 0, bytes, SESSION, 7, 42);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, len, out));
        assertEquals(SESSION, out.sessionStream);
        assertEquals(7, out.sessionCheck);
        assertEquals(42, out.msgSeqNum);
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED
            | AeronReplicationCodec.INPUT_FLAG_POSS_DUP | AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        len = AeronReplicationCodec.appendSessionTail(in, 0, bytes, SESSION, 7, 42);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, len, out));
        assertTrue(out.possDup && out.possResend);
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED | AeronReplicationCodec.INPUT_FLAG_SEQUENCED);
        AeronReplicationCodec.appendSessionTail(in, 0, bytes, SESSION, 7, 42);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, bytes + 16, out), "two tails");
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, bytes, out), "resend bit, no session");
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED);
        AeronReplicationCodec.appendSessionTail(in, 0, bytes, 0L, 7, 42);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, bytes + 16, out), "session 0");
        codec.encodeInput(in, 0, e, 0, 0, 0);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, bytes, out));
        assertEquals(0L, out.sessionStream, "a reused slot does not keep the previous tail");
        assertTrue(!out.possDup && !out.possResend);
        final int preAdr104 = AeronReplicationCodec.INPUT_FLAG_SHADOW | AeronReplicationCodec.INPUT_FLAG_SEQUENCED;
        for (final int flag : new int[] { AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED,
                                          AeronReplicationCodec.INPUT_FLAG_POSS_DUP, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND }) {
            assertNotEquals(0, flag & ~preAdr104, "a pre-ADR-104 member refuses flag " + flag);
        }
    }
}
