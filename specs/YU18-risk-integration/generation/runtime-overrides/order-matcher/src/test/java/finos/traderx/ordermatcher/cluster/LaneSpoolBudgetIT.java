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
}
