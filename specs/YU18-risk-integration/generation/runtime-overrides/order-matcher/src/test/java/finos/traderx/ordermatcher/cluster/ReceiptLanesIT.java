package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * YU18 (docs/engineering/downstream-at-400k.md §7.9): a NATS stream's ingest queue drops what overflows it,
 * and a core publish is never told. With the receipt lanes every such drop must come back as a 429 and be
 * COUNTED: stored + rejected == published, exactly. A tiny ingest queue forces the drops; if none happen the
 * test fails rather than passing vacuously. Each partition's stored messages stay in tradeSeq order.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReceiptLanesIT {
    private static final int SIDES = 60_000, PARTITIONS = 2;

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        // 64 messages, or 64 KiB: the count forces drops of one-record messages, the size of batched ones (FR-DRS-41).
        .withCopyToContainer(Transferable.of("jetstream {\n store_dir: /data\n max_buffered_msgs: 64\n max_buffered_size: 65536\n}\n"), "/etc/nats/tiny.conf")
        .withCommand("-c", "/etc/nats/tiny.conf")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    /** FR-DRS-41: with one record per message (the wire before batching) and with the default drain batching; the
     *  counters are in records either way, and every stored message's records are in tradeSeq order. */
    @ParameterizedTest(name = "records per message: {0}")
    @ValueSource(ints = {1, ReceiptLanes.BATCH_DEFAULT})
    void everyIngestDropIsCountedAndEachPartitionStaysInOrder(final int batchMax) throws Exception {
        final String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
        try (Connection nc = Nats.connect(url)) {
            try { nc.jetStreamManagement().deleteStream("T"); } catch (final Exception first) { }   // the other variant's
            nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("T")
                .subjects("/trades.*").storageType(StorageType.File).build());
            final TradeNatsPublisher bridge = new TradeNatsPublisher(url, "/trades", "e1", null, 1 << 17, PARTITIONS);
            bridge.batchMax(batchMax);
            bridge.start();
            for (int i = 1; i <= SIDES; i++) {
                bridge.offer(i, i, "IBM", (byte) (i & 1), 1, 100_000_000L, i, 0L);
            }
            final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline
                && (bridge.published() + bridge.dropped() < SIDES || bridge.acked() + bridge.rejected() < bridge.published())) {
                Thread.sleep(50);
            }
            final long stored = nc.jetStreamManagement().getStreamInfo("T").getStreamState().getMsgCount();
            assertEquals(0, bridge.dropped(), "lane queues were sized to hold the whole burst");
            assertEquals(SIDES, bridge.published(), "every side was published");
            assertTrue(bridge.rejected() > 0, "the 64-message ingest queue forced no drops: the test proves nothing");
            assertEquals(bridge.rejected(), bridge.rejected(0) + bridge.rejected(1));
            if (batchMax == 1) { assertEquals(bridge.published(), bridge.messages(), "one record per message"); }
            else { assertTrue(bridge.messages() < bridge.published() / 4, "drains were batched: " + bridge.json()); }

            // Order: within each partition subject, stored tradeSeqs strictly increase, across records and messages.
            final Pattern id = Pattern.compile("\"id\":\"(\\d+)-[BS]\"");
            final Map<String, Long> last = new HashMap<>();
            final JetStreamSubscription sub = nc.jetStream().subscribe("/trades.*",
                PushSubscribeOptions.builder().stream("T").ordered(true).build());
            long seen = 0, records = 0;
            for (Message m; (m = sub.nextMessage(Duration.ofSeconds(2))) != null; seen++) {
                for (final byte[] record : ReceiptLanes.records(m.getData())) {
                    final Matcher x = id.matcher(new String(record, StandardCharsets.UTF_8));
                    assertTrue(x.find());
                    final long seq = Long.parseLong(x.group(1));
                    final Long prev = last.put(m.getSubject(), seq);
                    assertTrue(prev == null || seq > prev, "partition " + m.getSubject() + " reordered: " + prev + " then " + seq);
                    records++;
                }
                if (seen + 1 == stored) { break; }
            }
            assertEquals(stored, seen + 1, "read back every stored message");
            assertEquals(bridge.published(), records + bridge.rejected(), "a drop went uncounted (stored records + rejected != published)");
            assertEquals(records, bridge.acked(), "every stored record was acked");
            bridge.stop();
        }
    }

    /**
     * FR-DRS-37: a promoted member publishes exactly what the stream is missing, in apply order. The old leader
     * stored trade sides 1..4 and order events up to (11,0), then died. The new leader applied sides 1..2 and the
     * (10,*) events while following, and the rest after its promotion - including 3..4 and (11,0), which the log
     * applied on it after the role change although the old leader had stored them. Each subject must read back
     * every record once: trades by id, orders by (consensusSequence, outputOrdinal). FR-DRS-40: with managed
     * ids and with legacy ids (no run descriptor, as production and kind run).
     */
    @ParameterizedTest(name = "managed ids: {0}")
    @ValueSource(booleans = {true, false})
    void aPromotedMemberPublishesOnlyWhatTheStreamIsMissing(final boolean managed, @TempDir final Path dir)
            throws Exception {
        try (GenericContainer<?> js = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
                .withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))) {
            js.start();
            final String url = "nats://" + js.getHost() + ":" + js.getMappedPort(4222);
            final RunDescriptor d = managed ? descriptor(dir) : null;
            try (Connection nc = Nats.connect(url)) {
                nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
                    .subjects("/trades", "/orders").storageType(StorageType.Memory).build());
                final TradeNatsPublisher oldTrades = new TradeNatsPublisher(url, "/trades", "e9", d, 1024, 1);
                final OrderNatsPublisher oldOrders = new OrderNatsPublisher(url, "/orders", "e9", d, 1024, 1);
                final TradeNatsPublisher trades = new TradeNatsPublisher(url, "/trades", "e9", d, 1024, 1);
                final OrderNatsPublisher orders = new OrderNatsPublisher(url, "/orders", "e9", d, 1024, 1);
                trades.role(false);
                orders.role(false);
                oldTrades.start();
                oldOrders.start();
                trades.start();
                orders.start();

                for (int seq = 1; seq <= 4; seq++) { trade(oldTrades, seq); }
                order(oldOrders, 10, 0);
                order(oldOrders, 10, 1);
                order(oldOrders, 11, 0);
                final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while ((stored(nc, "/trades", "id").size() < 4 || stored(nc, "/orders", "outputOrdinal").size() < 3)
                    && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                oldTrades.stop();
                oldOrders.stop();

                trade(trades, 1);
                trade(trades, 2);
                order(orders, 10, 0);
                order(orders, 10, 1);
                trades.role(true);
                orders.role(true);
                for (int seq = 3; seq <= 7; seq++) { trade(trades, seq); }
                order(orders, 11, 0);
                order(orders, 12, 0);
                order(orders, 12, 1);
                while ((trades.published() < 3 || orders.published() < 2) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                Thread.sleep(500); // a duplicate would land after the counts are reached

                assertEquals(tradeIds(managed, 7), stored(nc, "/trades", "id"), "each trade side once, in apply order");
                final List<String> positions = new ArrayList<>();
                final List<String> seqs = stored(nc, "/orders", "consensusSequence");
                final List<String> ordinals = stored(nc, "/orders", "outputOrdinal");
                for (int i = 0; i < seqs.size(); i++) { positions.add(seqs.get(i) + "/" + ordinals.get(i)); }
                assertEquals(List.of("10/0", "10/1", "11/0", "12/0", "12/1"), positions,
                    "each order event once, in apply order");
                assertEquals(3, trades.published(), "the new leader published only sides 5..7");
                assertEquals(2, orders.published(), "and only the (12,*) order events");
                trades.stop();
                orders.stop();
            }
        }
    }

    /**
     * FR-DRS-38: NATS restarts under a steady leader. Trade sides 1..4 and order events (10,*) are stored; then NATS
     * freezes, the bridges send sides 5..9 and (11,*), (12,0) into the frozen socket, and NATS is SIGKILLed and
     * started again, so none of those was stored. On its new connection each lane reads the stream's last message
     * and republishes what follows it before anything new: each subject reads back every record once, in order.
     * FR-DRS-40: with managed ids and with legacy ids (before it, the legacy order lane lost (11,*) and (12,0)).
     */
    @ParameterizedTest(name = "managed ids: {0}")
    @ValueSource(booleans = {true, false})
    void aNatsRestartRepublishesWhatTheStreamLost(final boolean managed, @TempDir final Path dir) throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final RunDescriptor d = managed ? descriptor(dir) : null;
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", d, 1024, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", d, 1024, 1);
            trades.start();
            orders.start();
            for (int seq = 1; seq <= 4; seq++) { trade(trades, seq); }
            order(orders, 10, 0);
            order(orders, 10, 1);
            awaitStored(js.url, 4, 2);

            js.pause();
            for (int seq = 5; seq <= 9; seq++) { trade(trades, seq); }
            order(orders, 11, 0);
            order(orders, 11, 1);
            order(orders, 12, 0);
            final long sent = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((trades.published() < 9 || orders.published() < 5) && System.nanoTime() < sent) { Thread.sleep(20); }
            Thread.sleep(200); // into the frozen socket
            js.killAndStart();

            for (int seq = 10; seq <= 12; seq++) { trade(trades, seq); }
            order(orders, 13, 0);
            awaitStored(js.url, 12, 6);
            Thread.sleep(500); // a duplicate would land after the counts are reached

            try (Connection nc = Nats.connect(js.url)) {
                assertEquals(tradeIds(managed, 12), stored(nc, "/trades", "id"), "each trade side once, in apply order");
                assertEquals(List.of("10/0", "10/1", "11/0", "11/1", "12/0", "13/0"), positions(nc),
                    "each order event once, in apply order");
            }
            assertEquals(5, trades.replayed(), "sides 5..9 were republished on the new connection");
            assertEquals(3, orders.replayed());
            assertEquals(0, trades.gaps());
            assertEquals(0, orders.gaps());
            trades.stop();
            orders.stop();
        }
    }

    /**
     * FR-DRS-40: the stream's last order event has no position (a member build before FR-DRS-40 wrote it), so a
     * promoted lane cannot tell whether the two events it applied while following are stored. It publishes neither
     * and counts a gap (before: an ALERT line and gaps 0). What it applies as leader follows.
     */
    @Test
    void aStreamWhoseLastOrderEventHasNoPositionIsACountedGap() throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            try (Connection nc = Nats.connect(js.url)) {
                nc.jetStream().publish("/orders", "{\"topic\":\"/orders\",\"payload\":{\"id\":\"e9-9\"}}"
                    .getBytes(StandardCharsets.UTF_8));
                final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", null, 1024, 1);
                orders.role(false);
                orders.start();
                order(orders, 10, 0);
                order(orders, 10, 1);
                orders.role(true);
                final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (orders.gaps() == 0 && System.nanoTime() < deadline) { Thread.sleep(50); }
                assertEquals(1, orders.gaps(), "an unknowable catch-up is a counted gap");
                order(orders, 11, 0);
                while (stored(nc, "/orders", "id").size() < 2 && System.nanoTime() < deadline) { Thread.sleep(50); }
                assertEquals(List.of("e9-9", "e9-11"), stored(nc, "/orders", "id"));
                orders.stop();
            }
        }
    }

    /**
     * FR-DRS-38, past the ring: an outage longer than the lane's ring loses the oldest records the stream never stored -
     * loudly. Sides 1..4 are stored, NATS freezes, sides 5..24 are published into the frozen socket by a lane with a
     * 16-slot ring, and NATS restarts. The lane released nothing the stream had not confirmed (FR-DRS-49; before it, it
     * kept only the last 8), so the apply thread overwrote 5..8: it counts a gap (the oldest it holds, 9, is past the
     * stream's last, 4), republishes the 16 it holds, in order, and then 25.
     */
    @Test
    void aNatsOutageLongerThanTheRingIsALoudGap() throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e1", null, 16, 1);
            trades.start();
            for (int seq = 1; seq <= 4; seq++) { trade(trades, seq); }
            awaitStored(js.url, 4, 0);
            js.pause();
            for (int seq = 5; seq <= 24; seq++) { trade(trades, seq); Thread.sleep(5); }
            Thread.sleep(300);
            js.killAndStart();
            final long caught = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (trades.gaps() == 0 && System.nanoTime() < caught) { Thread.sleep(20); }
            trade(trades, 25); // after the catch-up, so the ring it read held 9..24
            awaitStored(js.url, 21, 0);
            Thread.sleep(500);
            try (Connection nc = Nats.connect(js.url)) {
                final List<Long> seqs = stored(nc, "/trades", "id").stream()
                    .map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList();
                assertEquals(List.of(1L, 2L, 3L, 4L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L), seqs,
                    "the kept sides, in order, after the gap");
            }
            assertEquals(1, trades.gaps(), "the gap is counted");
            assertTrue(trades.json().contains("\"gaps\":1"), "and on /health: " + trades.json());
            trades.stop();
        }
    }

    /**
     * FR-DRS-47 (RI37): a member promoted while the stream cannot be read keeps everything it applies meanwhile and
     * publishes it once the read completes. The old leader stored sides 1..100 and order events (1..100, 0) and died;
     * the new member applied the same while following. NATS freezes, the member is promoted and applies 100,000 more of
     * each - past the 65,536 the lane's queue held before this rule, which refused the rest (GKE 2026-11-10: 22.9M sides
     * at ~510K orders/s) - and NATS thaws. Each subject must read back every record once, in apply order, with nothing
     * counted lost.
     */
    @Test
    void aPromotedLaneThatCannotReadTheStreamKeepsWhatItAppliesMeanwhile() throws Exception {
        final int stored = 100, more = 100_000;
        try (RestartableNats js = new RestartableNats(); Connection nc = Nats.connect(js.url)) {
            js.addFeedStream();
            final TradeNatsPublisher oldTrades = new TradeNatsPublisher(js.url, "/trades", "e9", null, 1024, 1);
            final OrderNatsPublisher oldOrders = new OrderNatsPublisher(js.url, "/orders", "e9", null, 1024, 1);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            trades.role(false);
            orders.role(false);
            oldTrades.start();
            oldOrders.start();
            trades.start();
            orders.start();
            for (int seq = 1; seq <= stored; seq++) { trade(oldTrades, seq); order(oldOrders, seq, 0); trade(trades, seq); order(orders, seq, 0); }
            awaitStored(js.url, stored, stored);
            oldTrades.stop();
            oldOrders.stop();
            final long read = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while ((!trades.json().contains("\"queuedByPartition\":[0]") || !orders.json().contains("\"queuedByPartition\":[0]"))
                && System.nanoTime() < read) { Thread.sleep(20); }

            js.pause();
            trades.role(true);
            orders.role(true);
            for (int seq = stored + 1; seq <= stored + more; seq++) { trade(trades, seq); order(orders, seq, 0); }
            Thread.sleep(3_000); // the lanes' stream reads time out meanwhile; the old queue was full in microseconds
            js.unpause();
            final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while ((trades.published() < more || orders.published() < more) && System.nanoTime() < deadline) { Thread.sleep(50); }
            awaitStored(js.url, stored + more, stored + more);
            Thread.sleep(500); // a duplicate would land after the counts are reached

            assertEquals(tradeIds(false, stored + more), stored(nc, "/trades", "id"), "each trade side once, in apply order");
            final List<String> want = new ArrayList<>();
            for (int seq = 1; seq <= stored + more; seq++) { want.add(seq + "/0"); }
            assertEquals(want, positions(nc), "each order event once, in apply order");
            assertEquals(more, trades.published(), "the new leader published only what the stream was missing");
            assertEquals(more, orders.published());
            assertEquals(0, trades.dropped(), "nothing lost: " + trades.json());
            assertEquals(0, orders.dropped(), "nothing lost: " + orders.json());
            assertEquals(0, trades.gaps() + orders.gaps());
            assertTrue(backlog(trades.json()) > ReceiptLanes.KEEP && backlog(orders.json()) > ReceiptLanes.KEEP,
                "the stall held more than the old queue's 65,536: " + trades.json() + " " + orders.json());
            trades.stop();
            orders.stop();
        }
    }

    /**
     * FR-DRS-47, past the ring: a stall longer than the lane's ring loses the overwritten records - loudly. A follower
     * with a 1,024-slot ring applied sides 1..100, which the old leader stored; NATS freezes, the member is promoted and
     * applies 101..5,100, and NATS thaws. The catch-up finds the stream's last (100) before the oldest record the ring
     * still holds (4,077): it counts a gap and publishes the 1,024 it holds, in order.
     */
    @Test
    void aPromotedLaneLappedPastItsRingIsACountedGap() throws Exception {
        try (RestartableNats js = new RestartableNats(); Connection nc = Nats.connect(js.url)) {
            js.addFeedStream();
            final TradeNatsPublisher old = new TradeNatsPublisher(js.url, "/trades", "e1", null, 1024, 1);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e1", null, 1024, 1);
            trades.role(false);
            old.start();
            trades.start();
            for (int seq = 1; seq <= 100; seq++) { trade(old, seq); trade(trades, seq); }
            awaitStored(js.url, 100, 0);
            old.stop();
            js.pause();
            trades.role(true);
            for (int seq = 101; seq <= 5_100; seq++) { trade(trades, seq); }
            Thread.sleep(3_000);
            js.unpause();
            awaitStored(js.url, 1_124, 0);
            Thread.sleep(500);
            final List<Long> want = new ArrayList<>();
            for (long seq = 1; seq <= 100; seq++) { want.add(seq); }
            for (long seq = 4_077; seq <= 5_100; seq++) { want.add(seq); }
            assertEquals(want, stored(nc, "/trades", "id").stream().map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList(),
                "the kept sides, in order, after the gap");
            assertEquals(1, trades.gaps(), "the hole is counted: " + trades.json());
            trades.stop();
        }
    }

    /**
     * FR-DRS-49: a leader dies with more than {@value ReceiptLanes#KEEP} records the stream never stored (11-09: a stalled
     * volume, then a full archive killed the leader with 1.9M sides unacked). NATS freezes after sides and order events
     * 1..100 are stored; both members apply 101..100,100, the leader publishing them into the frozen socket; NATS is
     * killed, so none of them is stored, the leader dies and the follower is promoted. It kept every record the stream
     * had not confirmed, not just its last 65,536, so each subject reads back every record once, in order.
     */
    @Test
    void aDeadLeadersUnstoredTailLongerThanTheKeepIsRepublishedByItsSuccessor() throws Exception {
        final int stored = 100, more = 100_000;
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final TradeNatsPublisher oldTrades = new TradeNatsPublisher(js.url, "/trades", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            final OrderNatsPublisher oldOrders = new OrderNatsPublisher(js.url, "/orders", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            trades.role(false);
            orders.role(false);
            oldTrades.start();
            oldOrders.start();
            trades.start();
            orders.start();
            for (int seq = 1; seq <= stored; seq++) { trade(oldTrades, seq); order(oldOrders, seq, 0); trade(trades, seq); order(orders, seq, 0); }
            awaitStored(js.url, stored, stored);

            js.pause();
            for (int seq = stored + 1; seq <= stored + more; seq++) { trade(oldTrades, seq); order(oldOrders, seq, 0); trade(trades, seq); order(orders, seq, 0); }
            final long sent = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while ((oldTrades.published() < stored + more || oldOrders.published() < stored + more
                || !trades.json().contains("\"queuedByPartition\":[0]") || !orders.json().contains("\"queuedByPartition\":[0]"))
                && System.nanoTime() < sent) { Thread.sleep(20); }
            assertEquals(stored + more, oldTrades.published(), "the leader sent its tail into the frozen socket");
            js.killAndStart();     // NATS never reads it
            oldTrades.stop();      // the leader dies
            oldOrders.stop();
            trades.role(true);
            orders.role(true);
            awaitStored(js.url, stored + more, stored + more);
            Thread.sleep(500);     // a duplicate would land after the counts are reached

            try (Connection nc = Nats.connect(js.url)) {
                assertEquals(tradeIds(false, stored + more), stored(nc, "/trades", "id"), "each trade side once, in apply order: " + trades.json());
                final List<String> want = new ArrayList<>();
                for (int seq = 1; seq <= stored + more; seq++) { want.add(seq + "/0"); }
                assertEquals(want, positions(nc), "each order event once, in apply order: " + orders.json());
            }
            assertEquals(more, trades.replayed(), "the successor republished the dead leader's whole tail");
            assertEquals(0, trades.gaps() + orders.gaps(), trades.json() + " " + orders.json());
            trades.stop();
            orders.stop();
        }
    }

    /**
     * FR-DRS-38's stall check counts silence only while publishes wait for receipts. A lane that followed for longer than
     * the 5 s window and is then promoted keeps its connection: before, its first publish read as 5 s without a receipt
     * and every lane reconnected and caught up a second time (GKE 2026-11-10 run E3: all 16 lanes at the promotion).
     */
    @Test
    void aPromotionDoesNotReadTheTimeSpentFollowingAsSilence() throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e1", null, ReceiptLanes.LANE_RECORDS_DEFAULT, 1);
            trades.role(false);
            trades.start();
            for (int seq = 1; seq <= 10; seq++) { trade(trades, seq); }
            Thread.sleep(6_000);   // following, past the stall window
            trades.role(true);
            for (int seq = 11; seq <= 2_000; seq++) { trade(trades, seq); }
            awaitStored(js.url, 2_000, 0);
            Thread.sleep(1_500);   // the stall check runs while idle
            assertEquals("0", ReceiptLanes.field(trades.json().getBytes(StandardCharsets.UTF_8), "reconnects"),
                "the promotion kept its connection: " + trades.json());
            try (Connection nc = Nats.connect(js.url)) {
                assertEquals(tradeIds(false, 2_000), stored(nc, "/trades", "id"), "each side once, in order");
            }
            assertEquals(0, trades.gaps());
            trades.stop();
        }
    }

    /** FR-DRS-47: /health's maxBacklog for one bridge's json. */
    private static long backlog(final String json) {
        return Long.parseLong(ReceiptLanes.field(json.getBytes(StandardCharsets.UTF_8), "maxBacklog"));
    }

    /**
     * FR-DRS-39: before a lane takes its first record it sends sample records through its own encode-and-publish
     * path to a scratch subject, so the JIT compiles that path before real load arrives (a cold lane ran at half the
     * 400K offer rate and overflowed its queue). The samples reach NATS, are stored by no stream and counted by no
     * counter, and the first real record is then published as usual.
     */
    @Test
    void aLaneWarmsItsPublishPathOnAScratchSubjectBeforeItPublishes(@TempDir final Path dir) throws Exception {
        try (RestartableNats js = new RestartableNats(); Connection nc = Nats.connect(js.url)) {
            js.addFeedStream();
            final java.util.concurrent.atomic.AtomicLong warmTrades = new java.util.concurrent.atomic.AtomicLong();
            final java.util.concurrent.atomic.AtomicLong warmOrders = new java.util.concurrent.atomic.AtomicLong();
            nc.createDispatcher(m -> (m.getSubject().endsWith(".trade") ? warmTrades : warmOrders).incrementAndGet())
                .subscribe(ReceiptLanes.WARM_SUBJECT + ".>");
            nc.flush(Duration.ofSeconds(5));
            final RunDescriptor d = descriptor(dir);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", d, 1024, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", d, 1024, 1);
            trades.start();
            orders.start();
            trade(trades, 1);
            order(orders, 10, 0);
            awaitStored(js.url, 1, 1);
            assertEquals(ReceiptLanes.WARM_PUBLISHES, warmTrades.get(), "the trade lane's samples, all before its first record");
            assertEquals(ReceiptLanes.WARM_PUBLISHES, warmOrders.get(), "the order lane's samples");
            assertEquals(List.of("e1-e9-1-S"), stored(nc, "/trades", "id"), "no sample is stored");
            assertEquals(List.of("10/0"), positions(nc));
            assertEquals(1, trades.published(), "no sample is counted: " + trades.json());
            assertEquals(1, orders.published());
            trades.stop();
            orders.stop();
        }
    }

    /**
     * FR-DRS-41 with FR-DRS-37: the stream's last message holds several records, and the catch-up cuts at its LAST
     * record. The old leader stored sides 1..300 in batched messages and died; the new leader applied 1..280 while
     * following and 281..310 after its promotion. The stream must read back exactly 1..310, each once, and the
     * catch-up's own messages are batched too.
     */
    @Test
    void aCatchUpCutsAtTheLastRecordOfABatchedMessage() throws Exception {
        try (RestartableNats js = new RestartableNats(); Connection nc = Nats.connect(js.url)) {
            js.addFeedStream();
            final TradeNatsPublisher old = new TradeNatsPublisher(js.url, "/trades", "e9", null, 1024, 1);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, 1024, 1);
            trades.role(false);
            old.start();
            trades.start();
            for (int seq = 1; seq <= 300; seq++) { trade(old, seq); }
            final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (stored(nc, "/trades", "id").size() < 300 && System.nanoTime() < deadline) { Thread.sleep(50); }
            final long before = storedMessages(nc, "/trades");
            assertTrue(before < 300, "the old leader's drains were batched: " + before + " messages for 300 sides");
            old.stop();
            for (int seq = 1; seq <= 280; seq++) { trade(trades, seq); }
            trades.role(true);
            for (int seq = 281; seq <= 310; seq++) { trade(trades, seq); }
            while (stored(nc, "/trades", "id").size() < 310 && System.nanoTime() < deadline) { Thread.sleep(50); }
            assertEquals(tradeIds(false, 310), stored(nc, "/trades", "id"), "exactly 1..310, each once, in order");
            assertTrue(storedMessages(nc, "/trades") - before < 30, "the catch-up's 30 sides went in batched messages too");
            trades.stop();
        }
    }

    private static RunDescriptor descriptor(final Path dir) throws Exception {
        final Path run = dir.resolve("run.json");
        Files.writeString(run, "{\"schema\":\"traderx.run.v1\",\"epoch\":\"e9\",\"eventIdScheme\":\"epoch-v1\","
            + "\"storageLineage\":\"l\",\"projectionScope\":\"s\",\"adoptionEvidenceSha256\":null}");
        return RunDescriptor.read(run);
    }

    private static void awaitStored(final String url, final int trades, final int orders) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (Connection nc = Nats.connect(url)) {
            while ((stored(nc, "/trades", "id").size() < trades || stored(nc, "/orders", "outputOrdinal").size() < orders)
                && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
        }
    }

    /** Trade side ids 1..n as the bridge writes them: epoch-v1 with a run descriptor, legacy without. */
    private static List<String> tradeIds(final boolean managed, final int n) {
        final List<String> out = new ArrayList<>();
        for (int seq = 1; seq <= n; seq++) { out.add((managed ? "e1-e9-" : "") + seq + ((seq & 1) == 1 ? "-S" : "-B")); }
        return out;
    }

    private static List<String> positions(final Connection nc) throws Exception {
        final List<String> out = new ArrayList<>();
        final List<String> seqs = stored(nc, "/orders", "consensusSequence");
        final List<String> ordinals = stored(nc, "/orders", "outputOrdinal");
        for (int i = 0; i < seqs.size(); i++) { out.add(seqs.get(i) + "/" + ordinals.get(i)); }
        return out;
    }

    private static void trade(final TradeNatsPublisher bridge, final int seq) {
        bridge.offer(seq, 7, "IBM", (byte) (seq & 1), 1, 100_000_000L, seq, 0L);
    }

    private static void order(final OrderNatsPublisher bridge, final long consensusSequence, final int ordinal) {
        final finos.traderx.ordermatcher.lmax.OutputEvent o = new finos.traderx.ordermatcher.lmax.OutputEvent();
        o.kind = finos.traderx.ordermatcher.lmax.OutputEvent.KIND_ORDER_ACCEPTED;
        o.orderRef = (int) consensusSequence;
        o.inputSeq = consensusSequence;
        o.accountId = 7;
        o.quantity = 10;
        o.remainingQty = 10;
        bridge.offer(o, "IBM", 0L, ordinal);
    }

    /** One field of every message stored on {@code subject}, in stream order. */
    private static List<String> stored(final Connection nc, final String subject, final String field) throws Exception {
        final List<String> out = new ArrayList<>();
        final JetStreamSubscription sub = nc.jetStream().subscribe(subject,
            PushSubscribeOptions.builder().stream("TRADERX_FEED").ordered(true).build());
        for (Message m; (m = sub.nextMessage(Duration.ofMillis(300))) != null; ) {
            for (final byte[] record : ReceiptLanes.records(m.getData())) { out.add(ReceiptLanes.field(record, field)); }   // FR-DRS-41
        }
        sub.unsubscribe();
        return out;
    }

    /** Messages stored on {@code subject}. */
    private static long storedMessages(final Connection nc, final String subject) throws Exception {
        final JetStreamSubscription sub = nc.jetStream().subscribe(subject,
            PushSubscribeOptions.builder().stream("TRADERX_FEED").ordered(true).build());
        long n = 0;
        for (Message m; (m = sub.nextMessage(Duration.ofMillis(300))) != null; ) { n++; }
        sub.unsubscribe();
        return n;
    }
}
