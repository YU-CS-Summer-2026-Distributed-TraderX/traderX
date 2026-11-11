package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.SessionRing;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        keyed(s, SESSION, acct, side, key, msgSeq, flags);
    }

    void keyed(final MatchingEngineClusteredService s, final long stream, final int acct, final byte side, final long key,
               final int msgSeq, final int flags) {
        final InputEvent e = harness.order(acct, side, 1, key);
        codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SESSION_KEYED | flags);
        final int len = AeronReplicationCodec.appendSessionTail(in, 0, AeronReplicationCodec.INPUT_BYTES, stream,
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

    // ----- format 16: compact ring records (ADR-104 §5, FR-XOD-23) ------------------------------------------------

    /** The ring shapes a snapshot must carry: none, partial, exactly full, wrapped many times, several sessions,
     *  colliding keys, a rejected answer and a PENDING one. */
    static SessionRing shaped(final int n, final String shape) {
        final SessionRing r = new SessionRing(n, 8, 1_000L);
        int ref = 1;
        switch (shape) {
            case "empty" -> { }
            case "partial" -> {
                for (int i = 0; i < n / 2 + 3 && i < n; i++) admitted(r, 10L, KEY + i, i + 2, ref++, 1);
            }
            case "full" -> {
                for (int i = 0; i < n; i++) admitted(r, 10L, KEY + i, i + 2, ref++, 1);
            }
            case "wrapped" -> {
                for (int i = 0; i < 3 * n + 17; i++) admitted(r, 10L, KEY + i, i + 2, ref++, i % 7 == 0 ? 5 : 1);
            }
            default -> {   // "mixed": four sessions of different depths, a collision, a pending entry, a reopened index
                for (int i = 0; i < n + 5; i++) admitted(r, 10L, KEY + i, i + 2, ref++, 1);
                admitted(r, 11L, KEY, 1, ref++, 1);
                admitted(r, 11L, KEY, 2, ref++, 3);        // same key, other check below
                r.admit(11L, KEY, 99, 3, false, false, 2L);  // PENDING: admitted, never recorded
                for (int i = 0; i < 64; i++) admitted(r, 12L, KEY + 7 * i, i + 1, ref++, 1);
                for (int i = 0; i < 65 && i < n; i++) admitted(r, 13L, KEY - i, i + 1, ref++, 1);
            }
        }
        return r;
    }

    static void admitted(final SessionRing r, final long stream, final long key, final int msgSeq, final int ref,
                         final int decision) {
        if (r.admit(stream, key, check(key) + msgSeq % 2, msgSeq, false, false, msgSeq) == SessionRing.ADMIT) {
            r.record(ref, (byte) decision);
        }
    }

    /** Format 16's ring records, each [int type][body], as writeSessionRings frames them. */
    static List<byte[]> write16(final SessionRing r) {
        final List<byte[]> out = new ArrayList<>();
        final UnsafeBuffer b = new UnsafeBuffer(new byte[4 + SessionRing.ENTRIES_HEADER_BYTES
            + SessionRing.ENTRY_BYTES * SessionRing.ENTRIES_PER_RECORD]);
        for (int s = 0; s < r.size(); s++) {
            b.putInt(0, MatchingEngineClusteredService.T_SESSION_HEAD);
            out.add(SnapshotPackedRoundTripTest.copy(b, 0, 4 + r.writeHead(s, b, 4)));
            for (int c = 0; c < r.entryRecords(s); c++) {
                b.putInt(0, MatchingEngineClusteredService.T_SESSION_ENTRIES);
                out.add(SnapshotPackedRoundTripTest.copy(b, 0, 4 + r.writeEntries(s, c, b, 4)));
            }
        }
        return out;
    }

    static SessionRing restore16(final SessionRing like, final List<byte[]> records) {
        final SessionRing r = new SessionRing(like.n(), like.maxSessions(), 1_000L);
        for (final byte[] rec : records) {
            final UnsafeBuffer b = new UnsafeBuffer(rec);
            if (b.getInt(0) == MatchingEngineClusteredService.T_SESSION_HEAD) r.restoreHead(b, 4, rec.length - 4);
            else r.restoreEntries(b, 4, rec.length - 4);
        }
        r.requireRestoreComplete();
        return r;
    }

    /** Format 15's path: every {@link SessionRing#tuple} into {@link SessionRing#restore}. */
    static SessionRing restore15(final SessionRing from) {
        final SessionRing r = new SessionRing(from.n(), from.maxSessions(), 1_000L);
        for (final long[] t : tuples(from)) r.restore(t);
        return r;
    }

    static List<long[]> tuples(final SessionRing r) {
        final List<long[]> out = new ArrayList<>();
        for (int s = 0; s < r.size(); s++) for (int c = 0; c < r.chunks(s); c++) out.add(r.tuple(s, c));
        return out;
    }

    /** Every field of two rings equal, the hash indexes and the restore cursor included (reflection, deep). */
    static void assertSameRing(final SessionRing a, final SessionRing b, final String what) throws Exception {
        for (final java.lang.reflect.Field f : SessionRing.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            assertTrue(java.util.Objects.deepEquals(f.get(a), f.get(b)), what + ": field " + f.getName() + " differs");
        }
    }

    static void assertSameTuples(final SessionRing a, final SessionRing b, final String what) {
        final List<long[]> x = tuples(a), y = tuples(b);
        assertEquals(x.size(), y.size(), what + ": records");
        for (int i = 0; i < x.size(); i++) assertArrayEquals(x.get(i), y.get(i), what + ": tuple " + i);
    }

    /**
     * (a) Format 16 round trip at N = 256 and the configured N, on every shape: the restored ring holds what the
     * original holds (every tuple equal) and is field-for-field the ring format 15's path restores, indexes included.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "empty", "partial", "full", "wrapped", "mixed" })
    void format16RestoresTheRingFormat15Restores(final String shape) throws Exception {
        for (final int n : new int[] { 256, MatchingEngineClusteredService.SESSION_RING }) {
            final SessionRing original = shaped(n, shape);
            final List<byte[]> records = write16(original);
            final SessionRing via16 = restore16(original, records);
            assertSameTuples(original, via16, shape + " N=" + n);
            assertSameRing(restore15(original), via16, shape + " N=" + n);
            assertEquals(original.size(), via16.size());
            int entries = 0;
            for (int s = 0; s < original.size(); s++) entries += original.entryRecords(s);
            assertEquals(original.size() + entries, records.size(), shape + ": a head per session, then its entries");
            if (!shape.equals("empty")) {
                assertTrue(records.size() > 1, "the shape holds entries");
                assertSameRing(restore16(original, write16(via16)), via16, shape + ": re-written, re-restored");
            }
        }
    }

    /** (a), whole service at the configured N: full, partial and empty rings round-trip byte-identical. */
    @Test
    void format16SnapshotRoundTripsFullPartialAndEmptyRings() throws Exception {
        final MatchingEngineClusteredService empty = harness.venue();
        final MatchingEngineClusteredService a = harness.venue();
        final int n = MatchingEngineClusteredService.SESSION_RING;
        for (int i = 0; i < n + 100; i++) cross(a, true, KEY + i, i + 2, 0);                    // full, evicting
        for (int i = 0; i < 77; i++) keyed(a, SESSION + 1, 1, InputEvent.SIDE_BUY, KEY + i, i + 1, 0);   // partial
        for (final MatchingEngineClusteredService m : List.of(empty, a)) {
            final List<byte[]> records = new ArrayList<>();
            m.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
            assertEquals(16, new UnsafeBuffer(records.get(0)).getInt(4));
            assertEquals(0, count(records, MatchingEngineClusteredService.T_SESSION_RING), "format 16 writes no type 22");
            final MatchingEngineClusteredService b = SnapshotPackedRoundTripTest.restore(records);
            assertEquals(m.risk().sessionRing().size(), b.risk().sessionRing().size());
            assertSameTuples(m.risk().sessionRing(), b.risk().sessionRing(), "service");
            assertArrayEquals(SnapshotPackedRoundTripTest.flat(m), SnapshotPackedRoundTripTest.flat(b));
        }
    }

    static int count(final List<byte[]> records, final int type) {
        int k = 0;
        for (final byte[] r : records) if (new UnsafeBuffer(r).getInt(0) == type) k++;
        return k;
    }

    /** A format 15 stream of {@code source}'s snapshot: the header says 15 and the rings are type-22 tuples where
     *  format 16 put its head and entry records, byte for byte what the format 15 writer (writeTuple) produced. */
    static List<byte[]> asFormat15(final MatchingEngineClusteredService source, final List<byte[]> records16) {
        final List<byte[]> out = new ArrayList<>();
        boolean ringsWritten = false;
        for (final byte[] r : records16) {
            final int type = new UnsafeBuffer(r).getInt(0);
            if (type == MatchingEngineClusteredService.T_SESSION_HEAD || type == MatchingEngineClusteredService.T_SESSION_ENTRIES) {
                if (!ringsWritten) {
                    for (final long[] t : tuples(source.risk().sessionRing())) {
                        final UnsafeBuffer b = new UnsafeBuffer(new byte[4 + 8 * t.length]);
                        b.putInt(0, MatchingEngineClusteredService.T_SESSION_RING);
                        for (int i = 0; i < t.length; i++) b.putLong(4 + 8 * i, t[i]);
                        out.add(b.byteArray());
                    }
                    ringsWritten = true;
                }
                continue;
            }
            out.add(r.clone());
        }
        new UnsafeBuffer(out.get(0)).putInt(4, 15);
        return out;
    }

    /** (b) A format 15 snapshot (type-22 rings) restores into the state format 16 restores. */
    @Test
    void aFormat15SnapshotLoadsIntoTheIdenticalState() throws Exception {
        final MatchingEngineClusteredService a = harness.venue();
        for (int i = 0; i < 2 * MatchingEngineClusteredService.SESSION_RING + 33; i++) cross(a, true, KEY + i, i + 2, 0);
        for (int i = 0; i < 300; i++) keyed(a, SESSION + 1, 1, InputEvent.SIDE_BUY, KEY + i, i + 1, 0);
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        final List<byte[]> f15 = asFormat15(a, records);
        assertEquals(15, new UnsafeBuffer(f15.get(0)).getInt(4));
        assertTrue(count(f15, MatchingEngineClusteredService.T_SESSION_RING) > 8, "the format 15 stream carries type 22");
        assertEquals(0, count(f15, MatchingEngineClusteredService.T_SESSION_HEAD) + count(f15, MatchingEngineClusteredService.T_SESSION_ENTRIES));
        final MatchingEngineClusteredService via15 = SnapshotPackedRoundTripTest.restore(f15);
        final MatchingEngineClusteredService via16 = SnapshotPackedRoundTripTest.restore(records);
        assertSameRing(via16.risk().sessionRing(), via15.risk().sessionRing(), "format 15 vs 16");
        assertSameTuples(a.risk().sessionRing(), via15.risk().sessionRing(), "format 15 vs the original");
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(via15),
            "a format 15 restore re-snapshots as the original's format 16");
    }

    /**
     * (c) Replay determinism across members: two independent restores of one packed snapshot (the production framing),
     * plus the same log tail, hold identical rings and books, and give the same answers as the member that wrote it.
     */
    @Test
    void twoIndependentRestoresPlusOneTailAreIdentical() throws Exception {
        final MatchingEngineClusteredService a = harness.venue();
        final int n = MatchingEngineClusteredService.SESSION_RING;
        for (int i = 0; i < n + 70; i++) cross(a, true, KEY + i, i + 2, 0);
        for (int i = 0; i < 130; i++) keyed(a, SESSION + 1, 1, InputEvent.SIDE_BUY, KEY + i, i + 1, 0);
        final List<byte[]> messages = new ArrayList<>();
        final MatchingEngineClusteredService.SnapshotPacker packer = new MatchingEngineClusteredService.SnapshotPacker(
            SnapshotPackedRoundTripTest.PAYLOAD, (b, o, l) -> messages.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        a.writeSnapshot(packer);
        packer.flush();
        final MatchingEngineClusteredService b = SnapshotPackedRoundTripTest.restore(messages);
        final MatchingEngineClusteredService c = SnapshotPackedRoundTripTest.restore(messages);
        final long t0 = harness.ts;
        final List<List<int[]>> answers = new ArrayList<>();
        for (final MatchingEngineClusteredService m : List.of(a, b, c)) {
            harness.ts = t0;
            session.acks.clear();
            keyed(m, 1, InputEvent.SIDE_BUY, KEY + n + 69, 9_000, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);   // held
            keyed(m, 1, InputEvent.SIDE_BUY, KEY + 3, 9_001, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);        // aged out
            keyed(m, SESSION + 1, 1, InputEvent.SIDE_BUY, KEY + 5, 9_002, AeronReplicationCodec.INPUT_FLAG_POSS_DUP); // held
            for (int i = 0; i < 100; i++) cross(m, true, KEY + 50_000 + i, 10_000 + i, 0);                       // new, evicting
            keyed(m, SESSION + 2, 1, InputEvent.SIDE_BUY, KEY, 1, 0);                                            // new session
            answers.add(new ArrayList<>(session.acks));
        }
        assertEquals(1, answers.get(1).stream().filter(x -> x[2] == RiskReason.SESSION_AGED_OUT.ordinal()).count(),
            "the tail really reached the restored ring: one aged-out refusal");
        for (int m = 1; m < 3; m++) {
            assertEquals(answers.get(0).size(), answers.get(m).size());
            for (int i = 0; i < answers.get(0).size(); i++) {
                assertArrayEquals(answers.get(0).get(i), answers.get(m).get(i), "member " + m + " answer " + i);
            }
        }
        assertSameRing(b.risk().sessionRing(), c.risk().sessionRing(), "two restores plus one tail");
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(b), SnapshotPackedRoundTripTest.flat(c), "rings and books");
        assertArrayEquals(SnapshotPackedRoundTripTest.flat(a), SnapshotPackedRoundTripTest.flat(b), "and the writer's");
    }

    /**
     * (e) Every ring record fits one packed snapshot message (FR-XOD-18 then sends none unpacked). Fails on format 15's
     * record size: a full 256-entry T_SESSION_RING is 6,212 bytes, and the control below proves the check rejects it.
     */
    @Test
    void everyRingRecordFitsOnePackedMessage() {
        final MatchingEngineClusteredService a = harness.venue();
        final int n = MatchingEngineClusteredService.SESSION_RING;
        for (int i = 0; i < n + 10; i++) cross(a, true, KEY + i, i + 2, 0);
        for (int i = 0; i < 65; i++) keyed(a, SESSION + 1, 1, InputEvent.SIDE_BUY, KEY + i, i + 1, 0);
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        int ring = 0;
        for (final byte[] r : records) {
            final int type = new UnsafeBuffer(r).getInt(0);
            if (type == MatchingEngineClusteredService.T_SESSION_RING || type == MatchingEngineClusteredService.T_SESSION_HEAD
                || type == MatchingEngineClusteredService.T_SESSION_ENTRIES) {
                ring++;
                assertTrue(fitsPacked(r.length), "ring record type " + type + " of " + r.length
                    + " bytes does not fit a " + SnapshotPackedRoundTripTest.PAYLOAD + "-byte packed message");
            }
        }
        assertEquals(2 + n / SessionRing.ENTRIES_PER_RECORD + 2, ring, "two heads, 32 + 2 entry records");
        // the packed stream: every message is a T_PACKED container of at most the payload
        final List<byte[]> messages = new ArrayList<>();
        final MatchingEngineClusteredService.SnapshotPacker packer = new MatchingEngineClusteredService.SnapshotPacker(
            SnapshotPackedRoundTripTest.PAYLOAD, (b, o, l) -> messages.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        a.writeSnapshot(packer);
        packer.flush();
        for (final byte[] m : messages) {
            assertTrue(m.length <= SnapshotPackedRoundTripTest.PAYLOAD, "a " + m.length + "-byte message");
            assertEquals(MatchingEngineClusteredService.T_PACKED, new UnsafeBuffer(m).getInt(0), "a record went unpacked");
        }
        // control: the check refuses format 15's full chunk (4 + 8 x (8 + 3 x 256) = 6,212 bytes)
        final long[] legacy = a.risk().sessionRing().tuple(0, 0);
        assertEquals(6_212, 4 + 8 * legacy.length);
        assertTrue(!fitsPacked(4 + 8 * legacy.length), "the control: a 6 KB record must fail the check");
    }

    /** The packer's own test (SnapshotPacker.write): its 4-byte container type, a 4-byte length slot, the record. */
    static boolean fitsPacked(final int length) {
        return 4 + 4 + length <= SnapshotPackedRoundTripTest.PAYLOAD;
    }

    /** Format 16 fails closed: another N, entries without their head, a gap, an overlap, an overrun, a missing tail. */
    @Test
    void format16RingRecordsFailClosed() {
        final SessionRing full = shaped(256, "full");
        final List<byte[]> ok = write16(full);
        assertEquals(1 + 4, ok.size());
        final IllegalStateException n = assertThrows(IllegalStateException.class,
            () -> restore16(new SessionRing(512, 8, 1_000L), ok));
        assertTrue(n.getMessage().contains("EXACTLY_ONCE_SESSION_RING"), n.getMessage());
        assertRefused(List.of(ok.get(1)), "without their head");
        assertRefused(List.of(ok.get(0), ok.get(2)), "entries 64+64");                    // gap: starts at 64
        assertRefused(List.of(ok.get(0), ok.get(1), ok.get(1)), "entries 0+64");        // overlap
        final byte[] overrun = ok.get(1).clone();
        new UnsafeBuffer(overrun).putInt(12, 65);                                        // count 65 in a 64-entry record
        assertRefused(List.of(ok.get(0), overrun), "entries 0+65");
        assertRefused(ok.subList(0, 4), "snapshot incomplete");                          // the last record missing
        final SessionRing two = shaped(256, "mixed");
        final List<byte[]> both = write16(two);
        final List<byte[]> torn = new ArrayList<>(both);
        torn.remove(3);                                                                  // session 0 loses a record
        assertRefused(torn, "entries");
    }

    static void assertRefused(final List<byte[]> records, final String why) {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> restore16(new SessionRing(256, 8, 1_000L), records));
        assertTrue(e.getMessage().contains(why), why + " -> " + e.getMessage());
    }

    /** The service's loader runs the same checks: a snapshot whose last entry record is dropped is refused at END. */
    @Test
    void aSnapshotMissingARingRecordIsRefused() {
        final MatchingEngineClusteredService a = harness.venue();
        for (int i = 0; i < 200; i++) cross(a, true, KEY + i, i + 2, 0);
        final List<byte[]> records = new ArrayList<>();
        a.writeSnapshot((b, o, l) -> records.add(SnapshotPackedRoundTripTest.copy(b, o, l)));
        int last = -1;
        for (int i = 0; i < records.size(); i++) {
            if (new UnsafeBuffer(records.get(i)).getInt(0) == MatchingEngineClusteredService.T_SESSION_ENTRIES) last = i;
        }
        records.remove(last);
        assertTrue(SnapshotPackedRoundTripTest.refused(records, "snapshot incomplete: session ring 0"));
    }
}
