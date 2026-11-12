package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.SessionRing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-105 (FR-ERH-F17): the order reference is 64-bit. L0 hit ADR-100's 32-bit ceiling at applied 2,147,483,957 on
 * 2026-11-11 (about 80 minutes of one epoch at 450K/s). Negative control, the first test against the build before
 * this change: the first NEW got ref 2,147,483,647 and the next two were refused ORDER_REF_EXHAUSTED (reason 36),
 * ref 0.
 */
class OrderRefWideningTest {
    static final long WIDE = (1L << 32) + 5;
    final ExactlyOnceStreamTest harness = new ExactlyOnceStreamTest();
    final ExactlyOnceStreamTest.Capture session = harness.session;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);

    /** One message through the codec, at whatever length the encoder chose (the wide tail included). */
    int apply(final MatchingEngineClusteredService s, final InputEvent e) {
        final int len = codec.encodeInput(in, 0, e, 0, 0, 0);
        s.onSessionMessage(session, ++harness.ts, in, 0, len, null);
        return len;
    }

    InputEvent cancel(final int acct, final long ref) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_CANCEL; e.accountId = acct; e.orderRef = ref;
        return e;
    }

    @Test void threeOrdersAcrossTheOld32BitCeilingAreAllAccepted() throws Exception {
        final MatchingEngineClusteredService s = harness.venue();
        OrderRefExhaustionTest.setNextOrderRef(s, Integer.MAX_VALUE);
        session.acks.clear();
        for (long key = 1; key <= 3; key++) apply(s, harness.order(1, InputEvent.SIDE_BUY, 1, key));
        assertEquals(3, session.acks.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, session.acks.get(i)[0], "order " + i);
            assertEquals((long) Integer.MAX_VALUE + i, session.acks.get(i)[1], "its ref, whole, on the egress ack");
        }
        session.acks.clear();
        assertEquals(AeronReplicationCodec.INPUT_BYTES, apply(s, cancel(1, (long) Integer.MAX_VALUE + 2)),
            "below 2^32 the ref still fits the uint32 field: no tail");
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, session.last()[0]);
        assertEquals((long) Integer.MAX_VALUE + 2, session.last()[1]);
    }

    @Test void aFreshEpochStartsAbove32BitsAndAWideCancelTakesTheTail() {
        final MatchingEngineClusteredService s = harness.venue();
        assertEquals(MatchingEngineClusteredService.FIRST_ORDER_REF, s.nextOrderRef(), "no order yet");
        assertEquals(0L, s.operatorOrderRefs(), "a count, from the epoch's first ref");
        session.acks.clear();
        apply(s, harness.order(1, InputEvent.SIDE_BUY, 1, 0xA1L));
        final long ref = session.last()[1];
        assertEquals(MatchingEngineClusteredService.FIRST_ORDER_REF, ref);
        assertEquals(1L, s.operatorOrderRefs());

        final int len = apply(s, cancel(1, ref));
        assertEquals(AeronReplicationCodec.INPUT_BYTES + AeronReplicationCodec.WIDE_REF_TAIL_BYTES, len);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, session.last()[0]);
        assertEquals(ref, session.last()[1]);

        final InputEvent back = new InputEvent();
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeInput(in, 0, len, back));
        assertEquals(ref, back.orderRef, "decoded whole");
        assertTrue((codec.inputFlags() & AeronReplicationCodec.INPUT_FLAG_WIDE_REF) != 0);
        assertEquals((int) ref, in.getInt(8 + 32, java.nio.ByteOrder.LITTLE_ENDIAN), "the uint32 field: the low half");
    }

    @Test void theWideTailHasOneEncoding() {
        final InputEvent e = cancel(1, WIDE);
        final int len = codec.encodeInput(in, 0, e, 0, 0, 0);
        final InputEvent back = new InputEvent();
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, len - 8, back), "flag without tail");
        in.putLong(AeronReplicationCodec.INPUT_BYTES, 7L, java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, len, back), "a tail naming a narrow ref");
        in.putLong(AeronReplicationCodec.INPUT_BYTES, -WIDE, java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(AeronReplicationCodec.WRONG_LENGTH, codec.tryDecodeInput(in, 0, len, back), "a negative ref");
        assertThrows(IllegalArgumentException.class,
            () -> codec.encodeInput(in, 0, e, 0, 0, AeronReplicationCodec.INPUT_FLAG_SEQUENCED), "exclusive tails");
        // Template 9: a typed REPLACE of a wide ref round-trips the same way.
        e.type = InputEvent.TYPE_ORDER_REPLACE; e.qty = 3; e.orderType = 1; e.tif = 1;
        final int typed = codec.encodeOrderInstruction(in, 0, e, 0, 0, 0);
        assertEquals(AeronReplicationCodec.ORDER_INSTRUCTION_BYTES + 8, typed);
        assertEquals(AeronReplicationCodec.OK, codec.tryDecodeOrderInstruction(in, 0, typed, back));
        assertEquals(WIDE, back.orderRef);
    }

    /** Every place a ref is held survives a snapshot whole, and each still answers its duplicate with that ref. */
    @Test void aSnapshotKeepsEveryWideRefAndItsAnswers() throws Exception {
        final SessionRingTest ring = new SessionRingTest();
        final MatchingEngineClusteredService s = ring.harness.venue();
        final ExactlyOnceStreamTest.Capture acks = ring.session;
        ring.harness.apply(s, ring.harness.order(1, InputEvent.SIDE_BUY, 1, 0xB0L));         // idempotency key
        final long keyedRef = acks.last()[1];
        ring.harness.applySeq(s, 1, InputEvent.SIDE_BUY, 1);                                 // stream ring
        final long seqRef = acks.last()[1];
        ring.keyed(s, 1, InputEvent.SIDE_BUY, SessionRingTest.KEY, 2, 0);                    // session ring
        final long sessionRef = acks.last()[1];
        ring.harness.apply(s, ring.harness.order(1, InputEvent.SIDE_BUY, 1, 0xB1L));
        final long canceledRef = acks.last()[1];
        final int len = codec.encodeInput(in, 0, cancel(1, canceledRef), 0, 0, 0);         // a terminal order
        s.onSessionMessage(acks, ++ring.harness.ts, in, 0, len, null);
        for (final long r : new long[] { keyedRef, seqRef, sessionRef, canceledRef }) {
            assertTrue(r > MatchingEngineClusteredService.FIRST_ORDER_REF - 1, "wide: " + r);
        }

        final List<byte[]> records = new ArrayList<>();
        s.writeSnapshot((b, o, l) -> { byte[] c = new byte[l]; b.getBytes(o, c); records.add(c); });
        assertEquals(MatchingEngineClusteredService.SNAPSHOT_FORMAT, new UnsafeBuffer(records.get(0)).getInt(4));
        final MatchingEngineClusteredService r = SnapshotPackedRoundTripTest.restore(records);
        assertEquals(s.nextOrderRef(), r.nextOrderRef());
        assertEquals(s.operatorOrderRefs(), r.operatorOrderRefs(), "the first ref is replicated (format 17 header)");
        assertArrayEquals(s.engine().terminalOrderRefsFifo(), r.engine().terminalOrderRefsFifo());
        assertArrayEquals(s.engine().orderState(canceledRef), r.engine().orderState(canceledRef));

        acks.acks.clear();
        ring.harness.apply(r, ring.harness.order(1, InputEvent.SIDE_BUY, 1, 0xB0L));
        assertEquals(keyedRef, acks.last()[1], "idempotency answers with the original wide ref");
        ring.harness.applySeq(r, 1, InputEvent.SIDE_BUY, 1);
        assertEquals(seqRef, acks.last()[1], "the stream ring answers with it");
        ring.keyed(r, 1, InputEvent.SIDE_BUY, SessionRingTest.KEY, 3, AeronReplicationCodec.INPUT_FLAG_POSS_RESEND);
        assertEquals(sessionRef, acks.last()[1], "the session ring answers with it");
    }

    /** A format 16 ring record (4-byte refs, 21 bytes an entry) still restores: an epoch before ADR-105 keeps loading. */
    @Test void aFormat16SessionRingRecordStillRestores() {
        final SessionRing a = new SessionRing(8, 4, 60_000);
        for (int i = 0; i < 5; i++) {
            a.admit(77L, 1000 + i, 9 + i, i + 1, false, false, 0);
            a.record(100 + i, (byte) 0);
        }
        final UnsafeBuffer b = new UnsafeBuffer(new byte[4096]);
        a.writeHead(0, b, 0);
        final UnsafeBuffer head = new UnsafeBuffer(Arrays.copyOf(b.byteArray(), SessionRing.HEAD_BYTES));
        final int len = a.writeEntries(0, 0, b, 0);
        final int count = b.getInt(8), col = SessionRing.ENTRIES_HEADER_BYTES;
        // Repack format 17's columns as format 16 wrote them: key 8, check 4, msgSeq 4, ref 4, decision 1.
        final UnsafeBuffer old = new UnsafeBuffer(new byte[col + SessionRing.FORMAT_16_ENTRY_BYTES * count]);
        old.putBytes(0, b, 0, col + 16 * count);
        for (int i = 0; i < count; i++) {
            old.putInt(col + 16 * count + 4 * i, (int) b.getLong(col + 16 * count + 8 * i));
            old.putByte(col + 20 * count + i, b.getByte(col + 24 * count + i));
        }
        assertEquals(col + SessionRing.ENTRY_BYTES * count, len);
        final SessionRing r = new SessionRing(8, 4, 60_000);
        r.restoreHead(head, 0, SessionRing.HEAD_BYTES);
        r.restoreEntries(old, 0, old.capacity(), false);
        r.requireRestoreComplete();
        for (int i = 0; i < 5; i++) {
            assertEquals(SessionRing.DUPLICATE, r.admit(77L, 1000 + i, 9 + i, 50 + i, false, true, 1));
            assertEquals(100 + i, r.duplicateRef());
        }
        final SessionRing wrongWidth = new SessionRing(8, 4, 60_000);
        wrongWidth.restoreHead(head, 0, SessionRing.HEAD_BYTES);
        assertThrows(IllegalStateException.class, () -> wrongWidth.restoreEntries(old, 0, old.capacity(), true),
            "a format 16 record read at format 17's width is refused by its length");
    }

    /** Members apply the same log to the same bytes, and a member restored mid-log converges with one that was not. */
    @Test void membersAgreeOnWideRefsAcrossASnapshot() {
        final MatchingEngineClusteredService a = harness.venue();
        final MatchingEngineClusteredService b = new ExactlyOnceStreamTest().venue();
        final List<byte[]> log = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final InputEvent e = i % 4 == 3 ? cancel(1, MatchingEngineClusteredService.FIRST_ORDER_REF + i - 1)
                : harness.order(i % 2 == 0 ? 1 : 2, i % 2 == 0 ? InputEvent.SIDE_BUY : InputEvent.SIDE_SELL, 1 + i % 3, 0xD0L + i);
            final int len = codec.encodeInput(in, 0, e, 0, 0, 0);
            log.add(Arrays.copyOf(in.byteArray(), len));
        }
        long ts = 5_000_000_000_000L;
        for (final byte[] m : log.subList(0, 20)) { a.onSessionMessage(session, ++ts, new UnsafeBuffer(m), 0, m.length, null);
            b.onSessionMessage(session, ts, new UnsafeBuffer(m), 0, m.length, null); }
        final MatchingEngineClusteredService c = SnapshotPackedRoundTripTest.restore(snapshot(a));
        for (final byte[] m : log.subList(20, 40)) { ++ts;
            for (final MatchingEngineClusteredService x : List.of(a, b, c)) x.onSessionMessage(session, ts, new UnsafeBuffer(m), 0, m.length, null); }
        final List<byte[]> sa = snapshot(a), sb = snapshot(b), sc = snapshot(c);
        assertEquals(sa.size(), sb.size());
        assertEquals(sa.size(), sc.size());
        for (int i = 0; i < sa.size(); i++) {
            assertArrayEquals(sa.get(i), sb.get(i), "record " + i + ": a and b");
            assertArrayEquals(sa.get(i), sc.get(i), "record " + i + ": a and the restored member");
        }
        assertTrue(a.nextOrderRef() > MatchingEngineClusteredService.FIRST_ORDER_REF + 20);
    }

    static List<byte[]> snapshot(final MatchingEngineClusteredService s) {
        final List<byte[]> records = new ArrayList<>();
        s.writeSnapshot((b, o, l) -> { byte[] c = new byte[l]; b.getBytes(o, c); records.add(c); });
        return records;
    }

    /** REST cancel/replace: Jackson's asInt() kept the low 32 bits, so 2^32 + 5 named order 5. */
    @Test void restRefsAreReadWholeOrRefused() throws Exception {
        final ObjectMapper json = new ObjectMapper();
        assertEquals(5, json.readTree("4294967301").asInt(), "the trap: asInt() truncates");
        assertEquals(WIDE, ClusterGatewayMain.requestOrderRef(json.readTree("4294967301")));
        assertEquals(WIDE, ClusterGatewayMain.requestOrderRef(json.readTree("\"4294967301\"")));
        assertEquals(MatchingEngineClusteredService.MAX_ORDER_REF,
            ClusterGatewayMain.requestOrderRef(json.readTree("9007199254740991")));
        for (final String bad : new String[] { "9007199254740992", "0", "-5", "1.5", "\"abc\"", "\"\"", "true", "null",
            "18446744073709551621" }) {
            assertEquals(-1L, ClusterGatewayMain.requestOrderRef(json.readTree(bad)), bad);
        }
    }

    /** The egress ack: 40 bytes, both halves of the ref, and a format word every reader checks. */
    @Test void theAckCarriesTheWholeRefAndAFormatWord() {
        final UnsafeBuffer ack = new UnsafeBuffer(new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH]);
        MatchingEngineClusteredService.putAckRef(ack, WIDE);
        assertEquals(WIDE, MatchingEngineClusteredService.ackRef(ack, 0));
        assertTrue(MatchingEngineClusteredService.ackFormatOk(ack, 0));
        MatchingEngineClusteredService.putAckRef(ack, -1);
        assertEquals(-1L, MatchingEngineClusteredService.ackRef(ack, 0), "a non-order ack's int result reads back");
        // A 32-byte member's coalesced 160 bytes split at 40: every record fails the word.
        final UnsafeBuffer old = new UnsafeBuffer(new byte[160]);
        for (int r = 0; r < 5; r++) { old.putLong(r * 32, 1000 + r); old.putInt(r * 32 + 8, 7); old.putLong(r * 32 + 24, 1 + r); }
        assertEquals(4, MatchingEngineClusteredService.egressRecordCount(160));
        for (int r = 0; r < 4; r++) assertTrue(!MatchingEngineClusteredService.ackFormatOk(old, r * 40), "record " + r);
    }
}
