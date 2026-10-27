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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
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
}
