package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.Nats;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-57: past the spool's byte budget the loss is counted, exactly, and the spool keeps what it had. NATS freezes
 * with sides 1..100 stored; the member applies 60,000 more against a 16,384-record ring and a budget that holds about
 * 5,000 sides. The stream must read back 1..100, then the run the spool kept, then - after one hole - the ring's
 * tail, and {@code queueDropped} must equal the hole.
 */
@Testcontainers(disabledWithoutDocker = true)
class LaneSpoolBudgetIT {
    @Test
    void pastTheBudgetTheSpoolKeepsItsPrefixAndTheLossIsCountedExactly() throws Exception {
        final long before = LaneSpool.budget;
        LaneSpool.budget = 256 * 1024;
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final int ring = 16_384, stored = 100, more = 60_000;
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, ring, 1);
            trades.start();
            for (int seq = 1; seq <= stored; seq++) { trades.offer(seq, 7, "IBM", (byte) (seq & 1), 1, 100_000_000L, seq, 0L); }
            LaneSpoolIT.awaitStored(js.url, stored, 0);
            js.pause();
            for (int seq = stored + 1; seq <= stored + more; seq++) {
                trades.offer(seq, 7, "IBM", (byte) (seq & 1), 1, 100_000_000L, seq, 0L);
                if (seq % 20 == 0) { Thread.sleep(1); }
            }
            js.killAndStart();
            final long deadline = System.currentTimeMillis() + 60_000;
            while (trades.dropped() == 0 && System.currentTimeMillis() < deadline) { Thread.sleep(100); }
            trades.offer(stored + more + 1, 7, "IBM", (byte) 1, 1, 100_000_000L, stored + more + 1, 0L);
            Thread.sleep(3_000);
            final List<Long> seqs;
            try (Connection nc = Nats.connect(js.url)) {
                seqs = LaneSpoolIT.stored(nc, "/trades", "id").stream().map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList();
            }
            final String runs = LaneSpoolIT.runs(seqs);
            final String[] parts = runs.split(" ");
            assertEquals(2, parts.length, "one hole: " + runs + " " + trades.json());
            final long keptTo = Long.parseLong(parts[0].substring(parts[0].indexOf("..") + 2));
            final long tailFrom = Long.parseLong(parts[1].substring(0, parts[1].indexOf("..")));
            assertTrue(parts[0].startsWith("1..") && parts[1].endsWith(".." + (stored + more + 1)), runs);
            assertTrue(keptTo > stored + 1_000, "the spool kept a run past what was stored before the freeze: " + runs);
            assertTrue(tailFrom >= stored + more - ring, "the tail is what the ring held: " + runs);
            assertEquals(tailFrom - keptTo - 1, trades.dropped(), "queueDropped is the hole, exactly: " + runs + " " + trades.json());
            assertEquals(1, trades.gaps(), trades.json());
            trades.stop();
            assertEquals(0, LaneSpool.held(), "a stopped bridge leaves nothing on disk");
        } finally {
            LaneSpool.budget = before;
        }
    }

    /**
     * A catch-up that lands past a hole counts it. The follower's spool froze at its budget, so it holds a prefix, a
     * hole, and its ring's tail. The leader got further into the stream than the follower's spool reaches (its publish
     * window, read by NATS after it died), so the stream's last falls in the follower's hole: what the hole held after
     * it nobody can resend. Promoted, the follower must count a gap and publish its ring's tail. Before the review of
     * FR-DRS-57 the bisection stepped over the hole and counted nothing.
     */
    @Test
    void aCatchUpThatLandsPastAHoleBehindAFrozenSpoolCountsAGap() throws Exception {
        final long before = LaneSpool.budget;
        LaneSpool.budget = 256 * 1024;
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final int ring = 16_384, stored = 100, more = 200_000;
            final TradeNatsPublisher old = new TradeNatsPublisher(js.url, "/trades", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, ring, 1);
            trades.role(false);
            old.start();
            trades.start();
            for (int seq = 1; seq <= stored; seq++) { side(old, seq); side(trades, seq); }
            LaneSpoolIT.awaitStored(js.url, stored, 0);
            js.pause();
            for (int seq = stored + 1; seq <= stored + more; seq++) {
                side(old, seq); side(trades, seq);
                if (seq % 50 == 0) { Thread.sleep(1); }
            }
            final long sent = System.currentTimeMillis() + 20_000;
            while (old.published() < stored + ReceiptLanes.PUBLISH_WINDOW && System.currentTimeMillis() < sent) { Thread.sleep(20); }
            Thread.sleep(500);
            final long window = old.published();           // what the leader got into the socket: its publish window
            old.stop();                                     // the leader dies
            js.unpause();                                   // NATS reads what the dead leader had sent, and stores it
            Thread.sleep(5_000);                            // NATS drains the dead connection's bytes
            trades.role(true);
            final long deadline = System.currentTimeMillis() + 60_000;
            while (trades.gaps() == 0 && System.currentTimeMillis() < deadline) { Thread.sleep(100); }
            Thread.sleep(3_000);
            final List<Long> seqs;
            try (Connection nc = Nats.connect(js.url)) {
                seqs = LaneSpoolIT.stored(nc, "/trades", "id").stream().map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList();
            }
            final String runs = LaneSpoolIT.runs(seqs);
            final String[] parts = runs.split(" ");
            assertEquals(2, parts.length, "the leader's part, one hole, the follower's ring: " + runs + " " + trades.json());
            // how much of its window had left the leader's client for the socket varies; it is past the follower's spool
            final long last = Long.parseLong(parts[0].substring(parts[0].indexOf("..") + 2));
            assertTrue(parts[0].startsWith("1..") && last <= window && last > LaneSpoolIT.spooled(trades.json()) + stored,
                "the stream's last is in the dead leader's window, past the follower's spool: " + runs + " " + trades.json());
            assertTrue(Long.parseLong(parts[1].substring(0, parts[1].indexOf(".."))) >= stored + more - ring && parts[1].endsWith(".." + (stored + more)), runs);
            assertEquals(1, trades.gaps(), "the hole is a gap: " + trades.json());
            assertTrue(LaneSpoolIT.spooled(trades.json()) > 1_000, "the follower spooled until its budget: " + trades.json());
            trades.stop();
        } finally {
            LaneSpool.budget = before;
        }
    }

    private static void side(final TradeNatsPublisher bridge, final int seq) {
        bridge.offer(seq, 7, "IBM", (byte) (seq & 1), 1, 100_000_000L, seq, 0L);
    }
}
