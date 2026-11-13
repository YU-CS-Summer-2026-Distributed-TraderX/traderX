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

    /**
     * FR-DRS-61: after a leader change the killed member comes back as a follower with an empty window and fills it as
     * it replays. Asked first, its page starts after the side the recon asked for, or is empty, or it answers 503 cold;
     * the member that holds the side is the better answer.
     */
    @Test
    void aBlotterReadPassesOverAMemberWhoseWindowStartsLate() throws Exception {
        final LiveTradeRing healthy = new LiveTradeRing(1 << 10), restarted = new LiveTradeRing(1 << 10);
        fill(healthy, 1, 5_000);
        fill(restarted, 4_900, 5_000);   // replayed only the log after its snapshot
        final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        final String fromHealthy = json.writeValueAsString(wire(healthy, 4_500)),
            fromRestarted = json.writeValueAsString(wire(restarted, 4_500)), empty = "[]";
        assertEquals(4_501, ClusterGatewayMain.firstTradeSeq(fromHealthy), "the member's JSON, as the gateway reads it");
        assertEquals(4_900, ClusterGatewayMain.firstTradeSeq(fromRestarted));
        assertEquals(Long.MAX_VALUE, ClusterGatewayMain.firstTradeSeq(empty));
        assertEquals(4_500, ClusterGatewayMain.sinceSeqOf("limit=10000&sinceSeq=4500"));
        assertEquals(0, ClusterGatewayMain.sinceSeqOf(null));
        assertTrue(ClusterGatewayMain.betterBlotter(200, fromHealthy, 200, fromRestarted), "the page that starts earlier");
        assertTrue(!ClusterGatewayMain.betterBlotter(200, fromRestarted, 200, fromHealthy));
        assertTrue(ClusterGatewayMain.betterBlotter(200, fromRestarted, 200, empty), "a page over an empty one");
        assertTrue(ClusterGatewayMain.betterBlotter(200, empty, 503, "{\"error\":\"cold\"}"), "an answer over an error");
    }

    /** A page as the member serves it: records with their tradeSeq (ClusterRecon.liveSince, through Jackson). */
    private static List<java.util.Map<String, Object>> wire(final LiveTradeRing ring, final long since) {
        final List<java.util.Map<String, Object>> out = new ArrayList<>();
        ring.since(since, 1_000, (seq, a, c, d, q, p, t) -> {
            final java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("id", "e1-1-" + seq + "-B");
            r.put("tradeSeq", seq);
            out.add(r);
        });
        return out;
    }
}
