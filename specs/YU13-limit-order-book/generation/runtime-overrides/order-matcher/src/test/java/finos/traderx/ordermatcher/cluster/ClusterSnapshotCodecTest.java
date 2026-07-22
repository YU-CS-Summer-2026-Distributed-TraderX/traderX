package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.collections.IntHashSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buffer-level proofs of the cluster snapshot record codec — no cluster required. Covers the
 * unit slice of the recovery matrix (T-AC11): corruption and out-of-order records fail closed,
 * the generator invariant refuses inconsistent snapshots, bounded terminal retention restores
 * in exact eviction-FIFO order, and (YU13, format 2) the crossing book restores completely —
 * band geometry, band anchors, and per-level FIFO time priority are replica-identical, proven
 * by driving the same post-restore crossing order through the source and the restored service
 * and comparing the resulting deterministic state.
 */
class ClusterSnapshotCodecTest {
    private static final long PX = 1_000_000L;
    private static final long CENT = 10_000L;
    private static final int ACCOUNT = 11;
    // A SECOND account for the aggressor side. These scenarios crossed one account against itself,
    // which cancel-oldest self-trade prevention (ADR-057) now correctly refuses to fill — the
    // restored-book proofs measure FIFO and band survival, not STP, so the taker is made distinct
    // rather than the proof weakened.
    private static final int ACCOUNT_TAKER = 12;
    private static final int SECURITY = 1;
    private static final int HEADER_BYTES = 52;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    // ----- round-trip ------------------------------------------------------------------------

    @Test
    void terminalRetentionRestoresInEvictionFifoOrder() {
        final MatchingEngineClusteredService source = newLiveService();
        // Terminal transitions in NON-ascending ref order: cancel 3, then 1. The retention ring
        // is [3, 1]; a restore ordered by ref would flip it to [1, 3] and later evict a
        // different order than a never-restarted replica.
        apply(source, cancel(3));
        apply(source, cancel(1));
        assertArrayEquals(new int[] { 3, 1 }, source.engine().terminalOrderRefsFifo());

        final MatchingEngineClusteredService restored = restore(source);
        assertArrayEquals(new int[] { 3, 1 }, restored.engine().terminalOrderRefsFifo(),
            "terminal eviction order must be replica-identical after recovery");
        assertEquals(source.engine().openOrderTuples().size(), restored.engine().openOrderTuples().size());
        assertEquals(source.risk().reservedNotional(ACCOUNT), restored.risk().reservedNotional(ACCOUNT),
            "aggregates rebuilt from order rows equal the live aggregates");
        assertEquals(source.risk().policyVersion(), restored.risk().policyVersion());
    }

    @Test
    void idempotencyEntriesSurviveRestore() {
        final MatchingEngineClusteredService source = newLiveService();
        final MatchingEngineClusteredService restored = restore(source);
        // Key 77 decided order ref 2 on the source; the restored service must answer the retry
        // with the original ref, not a new order.
        apply(restored, newOrder(InputEvent.SIDE_BUY, 100 * PX, 77L));
        assertEquals(4, restored.engine().openOrderTuples().size(),
            "duplicate retry creates no order");
        assertEquals(4, restored.engine().openOrderTuples().stream().mapToLong(t -> t[0]).max().orElse(0),
            "no new ref appears for a replayed duplicate");
    }

    @Test
    void orderRecordsRemainByteIdenticalToLegacyOrdering() {
        final MatchingEngineClusteredService source = newLiveService();
        apply(source, cancel(3));
        apply(source, cancel(1));

        final List<byte[]> actual = captureOrderRecords(source);
        final List<byte[]> legacy = legacyOrderRecords(source);

        assertEquals(legacy.size(), actual.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertArrayEquals(legacy.get(i), actual.get(i), "order record " + i);
        }
    }

    // ----- YU13 format 2: the resting book restores completely -------------------------------

    @Test
    void headerCarriesBookGeometryAndBandAnchorsRoundTrip() {
        final MatchingEngineClusteredService source = newLiveService();
        final MatchingEngineClusteredService restored = restore(source);
        assertEquals(source.engine().bookLevels(), restored.engine().bookLevels());
        assertEquals(source.engine().bookTickPx(), restored.engine().bookTickPx());
        assertEquals(1, source.engine().bookBaseTuples().size(), "the security's book is anchored");
        assertArrayEquals(source.engine().bookBaseTuples().get(0),
            restored.engine().bookBaseTuples().get(0),
            "band anchor must be replica-identical after recovery");
    }

    @Test
    void restoredBookPreservesPriceTimePriority() {
        // A book with structure that only survives if levels AND per-level FIFO survive:
        // two asks at 150.00 (the first partially filled, keeping queue head priority),
        // one ask at 150.01, and a resting bid at 149.98.
        final MatchingEngineClusteredService source = newLiveService(); // refs 1..4 resting bids @100
        apply(source, newOrder(InputEvent.SIDE_SELL, 150 * PX, 0L));            // ref 5: ask 150.00, 1st
        apply(source, newOrder(InputEvent.SIDE_SELL, 150 * PX, 0L));            // ref 6: ask 150.00, 2nd
        apply(source, newOrder(InputEvent.SIDE_SELL, 150 * PX + CENT, 0L));     // ref 7: ask 150.01
        apply(source, takerOrder(InputEvent.SIDE_BUY, 150 * PX, 88L, 4));       // ref 8: fills 4 of ref 5
        assertEquals(7, source.engine().openOrderTuples().size(),
            "sanity: 4 resting bids + 3 resting asks; the crossing buy (ref 8) went terminal");

        final MatchingEngineClusteredService restored = restore(source);

        // Same crossing order into both: it must consume ref 5's REMAINDER first (queue head),
        // then ref 6, then ref 7 — identical fills, identical resulting state.
        final InputEvent sweep = takerOrder(InputEvent.SIDE_BUY, 151 * PX, 0L, 30);
        apply(source, sweep);
        apply(restored, copyOf(sweep));

        final var sourceDigest = source.engine().recoveryDigest();
        final var restoredDigest = restored.engine().recoveryDigest();
        assertEquals(sourceDigest.orderHash(), restoredDigest.orderHash(),
            "post-restore crossing must reproduce identical book state (FIFO preserved)");
        assertEquals(sourceDigest.openOrders(), restoredDigest.openOrders());
        assertEquals(sourceDigest.tradeCounter(), restoredDigest.tradeCounter(),
            "identical fills book identical trades");
        assertEquals(source.engine().tradeCounter(), restored.engine().tradeCounter());
    }

    @Test
    void openOrderOutsideRestoredBandFailsClosed() {
        final MatchingEngineClusteredService source = newLiveService();
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        final MatchingEngineClusteredService restored = newRestoreTarget();
        boolean sawOrder = false;
        for (final byte[] record : records) {
            final UnsafeBuffer buffer = new UnsafeBuffer(record);
            if (buffer.getInt(0) == MatchingEngineClusteredService.T_BOOK) {
                // Corrupt the band anchor: every restored open order now falls outside the band.
                buffer.putLong(12, buffer.getLong(12) + 1_000_000L);
            }
            if (buffer.getInt(0) == MatchingEngineClusteredService.T_ORDER) {
                sawOrder = true;
                assertThrows(IllegalStateException.class,
                    () -> restored.onSnapshotRecord(buffer, 0),
                    "an open order the restored band cannot hold must fail closed");
                break;
            }
            restored.onSnapshotRecord(buffer, 0);
        }
        assertTrue(sawOrder, "scenario must reach an order record");
    }

    // ----- fail-closed matrix (unit slice of T-AC11) -----------------------------------------

    @Test
    void unknownRecordTypeFailsClosed() {
        final MatchingEngineClusteredService target = newRestoreTarget();
        feedHeader(target, 10, 9, 0, 0);
        final UnsafeBuffer bad = new UnsafeBuffer(new byte[16]);
        bad.putInt(0, 99);
        assertThrows(IllegalStateException.class, () -> target.onSnapshotRecord(bad, 0));
    }

    @Test
    void unknownFormatFailsClosed() {
        final MatchingEngineClusteredService target = newRestoreTarget();
        final UnsafeBuffer header = new UnsafeBuffer(new byte[HEADER_BYTES]);
        header.putInt(0, MatchingEngineClusteredService.T_HEADER);
        header.putInt(4, MatchingEngineClusteredService.SNAPSHOT_FORMAT + 1);
        assertThrows(IllegalStateException.class, () -> target.onSnapshotRecord(header, 0));
    }

    @Test
    void legacyFormatOneFailsClosed() {
        // A YU12 (format 1) snapshot has no book records; loading it silently would resurrect a
        // matcher with an empty band state. Cross-format restore is not a supported flow.
        final MatchingEngineClusteredService target = newRestoreTarget();
        final UnsafeBuffer header = new UnsafeBuffer(new byte[HEADER_BYTES]);
        header.putInt(0, MatchingEngineClusteredService.T_HEADER);
        header.putInt(4, 1);
        assertThrows(IllegalStateException.class, () -> target.onSnapshotRecord(header, 0));
    }

    @Test
    void recordBeforeHeaderFailsClosed() {
        final MatchingEngineClusteredService target = newRestoreTarget();
        final UnsafeBuffer price = new UnsafeBuffer(new byte[24]);
        price.putInt(0, MatchingEngineClusteredService.T_PRICE);
        assertThrows(IllegalStateException.class, () -> target.onSnapshotRecord(price, 0));
    }

    @Test
    void truncatedSnapshotFailsClosed() {
        final MatchingEngineClusteredService target = newRestoreTarget();
        feedHeader(target, 10, 9, 0, 0);
        // No END record arrived: the final invariant must not be silently skipped.
        final UnsafeBuffer end = new UnsafeBuffer(new byte[4]);
        end.putInt(0, MatchingEngineClusteredService.T_END);
        assertTrue(target.onSnapshotRecord(end, 0)); // sanity: END with header is fine
        final MatchingEngineClusteredService fresh = newRestoreTarget();
        assertThrows(IllegalStateException.class, fresh::finishLoad,
            "finishLoad without a header record fails closed");
    }

    @Test
    void generatorAtOrBelowRestoredIdFailsClosed() {
        final MatchingEngineClusteredService target = newRestoreTarget();
        feedHeader(target, 3, 2, 0, 0); // nextOrderRef 3
        final UnsafeBuffer order = new UnsafeBuffer(new byte[124]);
        order.putInt(0, MatchingEngineClusteredService.T_ORDER);
        order.putLong(4, 5); // restored ref 5 >= nextOrderRef 3
        assertThrows(IllegalStateException.class, () -> target.onSnapshotRecord(order, 0));
    }

    @Test
    void generatorNotAboveHighestIssuedFailsClosed() {
        final MatchingEngineClusteredService target = newRestoreTarget();
        feedHeader(target, 5, 7, 0, 0); // nextOrderRef 5 <= highestIssuedRef 7
        final UnsafeBuffer end = new UnsafeBuffer(new byte[4]);
        end.putInt(0, MatchingEngineClusteredService.T_END);
        assertThrows(IllegalStateException.class, () -> target.onSnapshotRecord(end, 0));
    }

    // ----- helpers ---------------------------------------------------------------------------

    /** Controls, a tick, and four resting bids at 100.00 (ref 2 carrying idempotency key 77). */
    private MatchingEngineClusteredService newLiveService() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        apply(service, accountControl(ACCOUNT, true));
        apply(service, accountControl(ACCOUNT_TAKER, true));
        apply(service, securityControl(SECURITY, true));
        apply(service, priceTick(150 * PX));
        apply(service, newOrder(InputEvent.SIDE_BUY, 100 * PX, 0L));
        apply(service, newOrder(InputEvent.SIDE_BUY, 100 * PX, 77L));
        apply(service, newOrder(InputEvent.SIDE_BUY, 100 * PX, 0L));
        apply(service, newOrder(InputEvent.SIDE_BUY, 100 * PX, 0L));
        return service;
    }

    private MatchingEngineClusteredService newRestoreTarget() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        return service;
    }

    /** Serialize source through the record codec and restore into a fresh service. */
    private MatchingEngineClusteredService restore(final MatchingEngineClusteredService source) {
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        final MatchingEngineClusteredService restored = newRestoreTarget();
        boolean done = false;
        for (final byte[] record : records) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "snapshot record stream must terminate with END");
        return restored;
    }

    private List<byte[]> captureOrderRecords(final MatchingEngineClusteredService source) {
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            if (buffer.getInt(offset) == MatchingEngineClusteredService.T_ORDER) {
                final byte[] copy = new byte[length];
                buffer.getBytes(offset, copy);
                records.add(copy);
            }
        });
        return records;
    }

    /** The pre-fix order phase, retained here as an executable byte-order contract. */
    private List<byte[]> legacyOrderRecords(final MatchingEngineClusteredService source) {
        final int[] terminalFifo = source.engine().terminalOrderRefsFifo();
        final IntHashSet terminalSet = new IntHashSet(terminalFifo.length * 2);
        final List<long[]> allOrders = source.engine().allOrderTuples();
        final List<byte[]> records = new ArrayList<>(allOrders.size());
        for (final int ref : terminalFifo) {
            terminalSet.add(ref);
        }
        for (final long[] order : allOrders) {
            if (!terminalSet.contains((int) order[0])) {
                records.add(orderRecord(order));
            }
        }
        for (final int ref : terminalFifo) {
            for (final long[] order : allOrders) {
                if ((int) order[0] == ref) {
                    records.add(orderRecord(order));
                    break;
                }
            }
        }
        return records;
    }

    private byte[] orderRecord(final long[] tuple) {
        final byte[] record = new byte[4 + Long.BYTES * tuple.length];
        final UnsafeBuffer buffer = new UnsafeBuffer(record);
        buffer.putInt(0, MatchingEngineClusteredService.T_ORDER);
        for (int i = 0; i < tuple.length; i++) {
            buffer.putLong(4 + Long.BYTES * i, tuple[i]);
        }
        return record;
    }

    private void feedHeader(final MatchingEngineClusteredService target, final long nextRef,
                            final long highestIssued, final long appliedSeq, final long tradeCounter) {
        final UnsafeBuffer header = new UnsafeBuffer(new byte[HEADER_BYTES]);
        header.putInt(0, MatchingEngineClusteredService.T_HEADER);
        header.putInt(4, MatchingEngineClusteredService.SNAPSHOT_FORMAT);
        header.putLong(8, nextRef);
        header.putLong(16, highestIssued);
        header.putLong(24, appliedSeq);
        header.putLong(32, tradeCounter);
        header.putInt(40, target.engine().bookLevels());
        header.putLong(44, target.engine().bookTickPx());
        target.onSnapshotRecord(header, 0);
    }

    /** Apply through the real ingress path: SBE-encoded, decoded by onSessionMessage. */
    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private InputEvent newOrder(final byte side, final long limitPx, final long clientOrderKey) {
        return newOrder(side, limitPx, clientOrderKey, 10);
    }

    /** Aggressor variant: same order, submitted by the OTHER account so the cross is not a
     *  self-trade (ADR-057). */
    private InputEvent takerOrder(final byte side, final long limitPx, final long clientOrderKey,
                                  final int qty) {
        final InputEvent e = newOrder(side, limitPx, clientOrderKey, qty);
        e.accountId = ACCOUNT_TAKER;
        return e;
    }

    private InputEvent newOrder(final byte side, final long limitPx, final long clientOrderKey,
                                final int qty) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.side = side;
        e.accountId = ACCOUNT;
        e.securityId = SECURITY;
        e.qty = qty;
        e.limitPx = limitPx;
        e.priceTicks = clientOrderKey;
        return e;
    }

    private InputEvent copyOf(final InputEvent original) {
        final InputEvent e = new InputEvent();
        e.type = original.type;
        e.side = original.side;
        e.accountId = original.accountId;
        e.securityId = original.securityId;
        e.qty = original.qty;
        e.limitPx = original.limitPx;
        e.priceTicks = original.priceTicks;
        return e;
    }

    private InputEvent cancel(final int orderRef) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_CANCEL;
        e.orderRef = orderRef;
        e.accountId = ACCOUNT;
        e.securityId = SECURITY;
        return e;
    }

    private InputEvent priceTick(final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SECURITY;
        e.priceTicks = px;
        return e;
    }

    private InputEvent accountControl(final int accountId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(enabled);
        e.setControlVersion(1L);
        return e;
    }

    private InputEvent securityControl(final int securityId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SECURITY_CONTROL;
        e.securityId = securityId;
        e.setControlEnabled(enabled);
        e.setControlVersion(2L);
        return e;
    }
}
