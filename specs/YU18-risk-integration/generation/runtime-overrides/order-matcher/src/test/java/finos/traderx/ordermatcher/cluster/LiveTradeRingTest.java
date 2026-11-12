package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** ADR-108 (FR-DRS-59): the recon's live window as a ring of primitives. */
class LiveTradeRingTest {
    private static void fill(final LiveTradeRing ring, final long from, final long to) {
        for (long s = from; s <= to; s++) { ring.record(s, (int) (s % 7), (int) (s % 5), (byte) (s & 1), (int) s, s * 3, s * 11); }
    }

    private static List<LiveTradeRing.Slot> page(final LiveTradeRing ring, final long since, final int limit) {
        final List<LiveTradeRing.Slot> out = new ArrayList<>();
        ring.since(since, limit, (seq, a, c, d, q, p, t) -> out.add(new LiveTradeRing.Slot(seq, a, c, d, q, p, t)));
        return out;
    }

    @Test
    void aPageIsStrictlyAfterSinceAscendingAndBounded() {
        final LiveTradeRing ring = new LiveTradeRing(1_000);
        assertEquals(1_024, ring.capacity(), "rounded up to a power of two");
        assertEquals(0, page(ring, 0, 10).size());
        fill(ring, 1, 100);
        assertArrayEquals(new long[] {41, 42, 43}, page(ring, 40, 3).stream().mapToLong(LiveTradeRing.Slot::tradeSeq).toArray());
        assertEquals(new LiveTradeRing.Slot(41, 41 % 7, 41 % 5, (byte) 1, 41, 123, 451), page(ring, 40, 1).get(0));
        assertEquals(0, page(ring, 100, 10).size(), "nothing at or above the caller: the epoch check's empty page");
        assertEquals(100, ring.size());
    }

    @Test
    void theWindowIsTheNewestCapacityAndAnOlderSinceGetsItsOldestSide() {
        final LiveTradeRing ring = new LiveTradeRing(1_024);
        fill(ring, 1, 5_000);
        final List<LiveTradeRing.Slot> oldest = page(ring, 0, 5);
        // 1,024 slots, less the one the writer may be overwriting: 3,978..5,000
        assertEquals(3_978, oldest.get(0).tradeSeq());
        assertEquals(1_024, ring.size());
        assertEquals(5_000, page(ring, 4_999, 5).get(0).tradeSeq());
    }

    @Test
    void aMemberThatRestartedMidLogStartsAtItsFirstSide() {
        final LiveTradeRing ring = new LiveTradeRing(1_024);
        fill(ring, 700_000, 700_010);
        assertEquals(700_000, page(ring, 0, 5).get(0).tradeSeq());
        assertEquals(11, ring.size());
    }

    /** A reader racing the writer round the ring never sees a slot of another side: every served slot is self-consistent. */
    @Test
    void aReaderLappedByTheWriterServesNoTornSlot() throws Exception {
        final LiveTradeRing ring = new LiveTradeRing(4_096);
        final AtomicBoolean writing = new AtomicBoolean(true);
        final AtomicLong served = new AtomicLong(), torn = new AtomicLong(), disorder = new AtomicLong();
        final Thread reader = new Thread(() -> {
            long since = 0;
            while (writing.get()) {
                final long[] last = {since};
                ring.since(since, 2_000, (seq, a, c, d, q, p, t) -> {
                    served.incrementAndGet();
                    if (a != (int) (seq % 7) || c != (int) (seq % 5) || d != (byte) (seq & 1) || q != (int) seq || p != seq * 3 || t != seq * 11) { torn.incrementAndGet(); }
                    if (seq <= last[0]) { disorder.incrementAndGet(); }
                    last[0] = seq;
                });
                since = last[0];
            }
        });
        reader.start();
        fill(ring, 1, 30_000_000);
        writing.set(false); reader.join();
        assertTrue(served.get() > 100_000, "the reader read while the writer lapped it: " + served.get());
        assertEquals(0, torn.get());
        assertEquals(0, disorder.get());
    }

    /** The service thread allocates nothing to record a side (the skip list: a record, an id, a price, a key, nodes). */
    @Test
    void recordingASideAllocatesNothing() {
        final LiveTradeRing ring = new LiveTradeRing(1 << 16);
        fill(ring, 1, 200_000);   // warm
        final var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long id = Thread.currentThread().threadId(), before = threads.getThreadAllocatedBytes(id);
        fill(ring, 200_001, 1_200_000);
        final long bytes = threads.getThreadAllocatedBytes(id) - before;
        assertTrue(bytes < 64_000, "1,000,000 sides recorded allocated " + bytes + " bytes");
    }

    @Test
    void theGatewayPutsTheLeaderLastForABlotterRead() {
        final String[] members = {"0=m0:9002", "1=m1:9002", "2=m2:9002"};
        assertArrayEquals(new String[] {"0=m0:9002", "2=m2:9002", "1=m1:9002"}, ClusterGatewayMain.leaderLast(members, 1));
        assertArrayEquals(new String[] {"1=m1:9002", "2=m2:9002", "0=m0:9002"}, ClusterGatewayMain.leaderLast(members, 0));
        assertArrayEquals(members, ClusterGatewayMain.leaderLast(members, -1), "no leader known: id order, as before");
    }
}
