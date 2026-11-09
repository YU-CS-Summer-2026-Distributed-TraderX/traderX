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
        .withCopyToContainer(Transferable.of("jetstream {\n store_dir: /data\n max_buffered_msgs: 64\n}\n"), "/etc/nats/tiny.conf")
        .withCommand("-c", "/etc/nats/tiny.conf")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @Test
    void everyIngestDropIsCountedAndEachPartitionStaysInOrder() throws Exception {
        final String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
        try (Connection nc = Nats.connect(url)) {
            nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("T")
                .subjects("/trades.*").storageType(StorageType.File).build());
            final TradeNatsPublisher bridge = new TradeNatsPublisher(url, "/trades", "e1", null, 1 << 17, PARTITIONS);
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
            assertEquals(bridge.published(), stored + bridge.rejected(), "a drop went uncounted (stored + rejected != published)");
            assertTrue(bridge.rejected() > 0, "the 64-message ingest queue forced no drops: the test proves nothing");
            assertEquals(stored, bridge.acked(), "every stored message was acked");
            assertEquals(bridge.rejected(), bridge.rejected(0) + bridge.rejected(1));

            // Order: within each partition subject, stored tradeSeqs strictly increase.
            final Pattern id = Pattern.compile("\"id\":\"(\\d+)-[BS]\"");
            final Map<String, Long> last = new HashMap<>();
            final JetStreamSubscription sub = nc.jetStream().subscribe("/trades.*",
                PushSubscribeOptions.builder().stream("T").ordered(true).build());
            long seen = 0;
            for (Message m; (m = sub.nextMessage(Duration.ofSeconds(2))) != null; seen++) {
                final Matcher x = id.matcher(new String(m.getData(), StandardCharsets.UTF_8));
                assertTrue(x.find());
                final long seq = Long.parseLong(x.group(1));
                final Long prev = last.put(m.getSubject(), seq);
                assertTrue(prev == null || seq > prev, "partition " + m.getSubject() + " reordered: " + prev + " then " + seq);
                if (seen + 1 == stored) { break; }
            }
            assertEquals(stored, seen + 1, "read back every stored message");
            bridge.stop();
        }
    }

    /**
     * FR-DRS-37: a promoted member publishes exactly what the stream is missing, in apply order. The old leader
     * stored trade sides 1..4 and order events up to (11,0), then died. The new leader applied sides 1..2 and the
     * (10,*) events while following, and the rest after its promotion - including 3..4 and (11,0), which the log
     * applied on it after the role change although the old leader had stored them. Each subject must read back
     * every record once: trades by managed id, orders by (consensusSequence, outputOrdinal).
     */
    @Test
    void aPromotedMemberPublishesOnlyWhatTheStreamIsMissing(@TempDir final Path dir) throws Exception {
        try (GenericContainer<?> js = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
                .withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))) {
            js.start();
            final String url = "nats://" + js.getHost() + ":" + js.getMappedPort(4222);
            final Path run = dir.resolve("run.json");
            Files.writeString(run, "{\"schema\":\"traderx.run.v1\",\"epoch\":\"e9\",\"eventIdScheme\":\"epoch-v1\","
                + "\"storageLineage\":\"l\",\"projectionScope\":\"s\",\"adoptionEvidenceSha256\":null}");
            final RunDescriptor d = RunDescriptor.read(run);
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

                assertEquals(List.of("e1-e9-1-S", "e1-e9-2-B", "e1-e9-3-S", "e1-e9-4-B", "e1-e9-5-S", "e1-e9-6-B",
                    "e1-e9-7-S"), stored(nc, "/trades", "id"), "each trade side once, in apply order");
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
            out.add(ReceiptLanes.field(m.getData(), field));
        }
        sub.unsubscribe();
        return out;
    }
}
