package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-61: a lane that led before its stream existed is still held to the stream's receipts once the stream is
 * there. On a fresh epoch the members lead before the read model has created the feed streams, so every lane's first
 * catch-up finds no stream. That verdict was kept for the life of the connection: no publish window, no stall check.
 * GKE 2026-11-12 (bridge-overflow run 3, stage 2): with one NATS server's writes throttled the leader had 34,000
 * messages a lane unanswered against a 1,024 window, NATS refused 7,424,684 records at ingest (429), nothing spooled
 * and both stores ended 2,206,391 sides short.
 *
 * <p>Here: the bridges start, then the stream is created, then NATS is frozen while 200,000 one-record messages a
 * lane are applied. Thawed, the stream's 4,096-message ingest queue takes two lanes' windows (2,048) and nothing
 * more. Uses only what the bridges had before the rule, so it compiles and FAILS on the parent build: the lanes send
 * all 200,000 at once and NATS refuses what its queue cannot hold.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(300)
class LaneStreamAfterStartIT {
    static final int RING = 131_072, STORED = 100, MORE = 200_000;

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCopyToContainer(Transferable.of(
            "jetstream {\n store_dir: /data\n max_buffered_msgs: 4096\n max_buffered_size: 67108864\n}\n"), "/etc/nats/q.conf")
        .withCommand("-c", "/etc/nats/q.conf")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @Test
    void lanesThatLedBeforeTheirStreamExistedAreHeldToItsReceipts() throws Exception {
        final String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
        final TradeNatsPublisher trades = new TradeNatsPublisher(url, "/trades", "e9", null, RING, 1);
        final OrderNatsPublisher orders = new OrderNatsPublisher(url, "/orders", "e9", null, RING, 1);
        trades.batchMax(1);
        orders.batchMax(1);
        trades.start();
        orders.start();
        Thread.sleep(2_000);   // each lane has caught up against a server with no stream on its subject
        try (Connection nc = Nats.connect(url)) {
            nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
                .subjects("/trades", "/orders").storageType(StorageType.File).build());
        }
        boolean paused = false;
        try {
            LaneSpoolIT.apply(1, STORED, trades, orders);
            LaneSpoolIT.awaitStored(url, STORED, STORED);
            Thread.sleep(1_000);   // the lanes have seen the stream answer
            NATS.getDockerClient().pauseContainerCmd(NATS.getContainerId()).exec();
            paused = true;
            LaneSpoolIT.apply(STORED + 1, STORED + MORE, trades, orders);
            Thread.sleep(1_000);   // the lanes have published what they will while NATS cannot answer
            // What a lane published while the server is frozen sits in the NATS client's outgoing queue, in the
            // member's heap. Bounded by the window (one record a message here), plus the messages before the freeze
            // still unanswered. GKE run 3: about 34,000 messages a lane, and the members slowed to a third of the rate.
            final long queuedT = trades.published() - trades.acked() - trades.rejected();
            final long queuedO = orders.published() - orders.acked() - orders.rejected();
            System.out.println("published and unanswered while NATS was frozen: trades " + queuedT + ", orders " + queuedO);
            assertTrue(queuedT <= ReceiptLanes.PUBLISH_WINDOW && queuedO <= ReceiptLanes.PUBLISH_WINDOW,
                "the client queued past the window while NATS was frozen: trades " + queuedT + ", orders " + queuedO);
            NATS.getDockerClient().unpauseContainerCmd(NATS.getContainerId()).exec();
            paused = false;
            final int n = STORED + MORE;
            LaneSpoolIT.awaitStored(url, n, n);
            Thread.sleep(1_000);
            try (Connection nc = Nats.connect(url)) {
                assertEquals(LaneSpoolIT.runs(LaneSpoolIT.seqs(1, n)), LaneSpoolIT.runs(LaneSpoolIT.stored(nc, "/trades", "id").stream()
                    .map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList()),
                    "each trade side once, in apply order (stored runs): " + trades.json());
                assertEquals(LaneSpoolIT.runs(LaneSpoolIT.seqs(1, n)),
                    LaneSpoolIT.runs(LaneSpoolIT.stored(nc, "/orders", "consensusSequence").stream().map(Long::parseLong).toList()),
                    "each order event once, in apply order (stored runs): " + orders.json());
            }
            assertEquals(0, trades.rejected() + orders.rejected() + trades.dropped() + orders.dropped() + trades.gaps() + orders.gaps(),
                "NATS refused nothing and nothing was lost: " + trades.json() + " " + orders.json());
        } finally {
            if (paused) { NATS.getDockerClient().unpauseContainerCmd(NATS.getContainerId()).exec(); }
            trades.stop();
            orders.stop();
        }
    }
}
