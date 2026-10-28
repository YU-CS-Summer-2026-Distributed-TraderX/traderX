package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-FIX05 / ADR-084 §8: the gateway's fill feed against a real JetStream server. Both subscribers
 * reach NATS through a TCP proxy the test cuts in the middle of a cross — the gateway's connection
 * drops, the rest of the cross is published, the connection comes back. The production mode reads
 * the TRADERX_FEED stream through ordered consumers, so the fills published while it was cut still
 * arrive, once each, in order. The core mode, with the same cut, loses them — which is why the
 * production render requires the stream and the acceptor ALERTs on the gap it can see
 * (FixGatewayFillReportTest).
 */
@Testcontainers(disabledWithoutDocker = true)
class FixFillFeedIT {
    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCommand("-js")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private final OrderNatsPublisher encoder = new OrderNatsPublisher("", "/orders", "7", null, 16, 1);

    private byte[] update(final int ref, final int remaining, final byte status, final int lastFill) {
        final OrderNatsPublisher.Rec r = new OrderNatsPublisher.Rec();
        r.orderRef = ref;
        r.accountId = 11;
        r.security = "IBM";
        r.quantity = 10;
        r.remainingQty = remaining;
        r.limitPx = 50_000_000L;
        r.status = status;
        r.lastExecPx = 50_000_000L;
        r.lastFillQty = lastFill;
        return encoder.encode(new StringBuilder(1024), r);
    }

    private static boolean await(final BooleanSupplier done, final Duration max) throws InterruptedException {
        final long deadline = System.nanoTime() + max.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return done.getAsBoolean();
    }

    @Test
    void droppingTheSubscriptionMidCrossLosesNoFillOnTheStream_andLosesThemOnCore() throws Exception {
        final String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
        try (Connection admin = Nats.connect(url)) {
            final JetStreamManagement jsm = admin.jetStreamManagement();
            // Precondition: the production mode waits for the stream instead of falling back to core.
            assertThrows(IllegalStateException.class,
                () -> ClusterGatewayMain.connectFillFeed(url, "jetstream", "TRADERX_FEED", b -> { }));
            jsm.addStream(StreamConfiguration.builder().name("TRADERX_FEED").storageType(StorageType.File)
                .subjects("/trades", "/trades.*", "/orders", "/orders.*").build());

            final List<Integer> durable = new CopyOnWriteArrayList<>();
            final List<Integer> core = new CopyOnWriteArrayList<>();
            try (CutProxy proxy = new CutProxy(NATS.getHost(), NATS.getMappedPort(4222));
                 Connection js = ClusterGatewayMain.connectFillFeed(proxy.url(), "jetstream", "TRADERX_FEED",
                     b -> durable.add(FixGatewayAcceptor.feedRef(b) * 100 + remainingOf(b)));
                 Connection cs = ClusterGatewayMain.connectFillFeed(proxy.url(), "core", "TRADERX_FEED",
                     b -> core.add(FixGatewayAcceptor.feedRef(b) * 100 + remainingOf(b)))) {
                // The cross begins: ref 1's NEW and its first partial reach both.
                admin.publish("/orders.1", update(1, 10, (byte) 0, 0));
                admin.publish("/orders.1", update(1, 6, (byte) 1, 4));
                assertTrue(await(() -> durable.size() == 2 && core.size() == 2, Duration.ofSeconds(10)),
                    "the first two updates never arrived: " + durable + " " + core);

                // Drop both subscriptions mid-cross: cut their connections, publish the rest of the
                // cross while they are down, then let them reconnect.
                proxy.cut();
                assertTrue(await(() -> js.getStatus() != Connection.Status.CONNECTED
                    && cs.getStatus() != Connection.Status.CONNECTED, Duration.ofSeconds(10)), "the cut did not drop them");
                admin.publish("/orders.1", update(1, 2, (byte) 1, 4));
                admin.publish("/orders.1", update(1, 0, (byte) 2, 2));
                admin.flush(Duration.ofSeconds(2));
                proxy.restore();
                assertTrue(await(() -> js.getStatus() == Connection.Status.CONNECTED
                    && cs.getStatus() == Connection.Status.CONNECTED, Duration.ofSeconds(30)), "they never reconnected");

                assertTrue(await(() -> durable.size() >= 4, Duration.ofSeconds(60)),
                    "the stream lost the fills published while the consumer was gone: " + durable);
                Thread.sleep(500);
                assertEquals(List.of(110, 106, 102, 100), durable, "ordered, once each, nothing lost");
                assertEquals(List.of(110, 106), core, "core keeps nothing it was not subscribed for");
            }
        }
    }

    /** A TCP forwarder to NATS that can drop every connection through it and refuse new ones. */
    private static final class CutProxy implements AutoCloseable {
        private final java.net.ServerSocket server = new java.net.ServerSocket(0);
        private final List<java.net.Socket> live = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;

        CutProxy(final String host, final int port) throws java.io.IOException {
            final Thread accept = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        final java.net.Socket in = server.accept();
                        if (!open) {
                            in.close();
                            continue;
                        }
                        final java.net.Socket out = new java.net.Socket(host, port);
                        live.add(in);
                        live.add(out);
                        pump(in, out);
                        pump(out, in);
                    } catch (final java.io.IOException e) {
                        // closed, or a refused connection: the client retries
                    }
                }
            }, "cut-proxy");
            accept.setDaemon(true);
            accept.start();
        }

        private static void pump(final java.net.Socket from, final java.net.Socket to) {
            final Thread t = new Thread(() -> {
                try {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (final java.io.IOException ignore) {
                    // a cut
                }
                try {
                    from.close();
                    to.close();
                } catch (final java.io.IOException ignore) {
                    // already closed
                }
            });
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "nats://127.0.0.1:" + server.getLocalPort();
        }

        void cut() throws java.io.IOException {
            open = false;
            for (final java.net.Socket s : live) {
                s.close();
            }
            live.clear();
        }

        void restore() {
            open = true;
        }

        @Override
        public void close() throws java.io.IOException {
            cut();
            server.close();
        }
    }

    private static int remainingOf(final byte[] json) {
        final String s = new String(json, java.nio.charset.StandardCharsets.US_ASCII);
        final int i = s.indexOf("\"remainingQuantity\":") + 20;
        int j = i;
        while (Character.isDigit(s.charAt(j))) {
            j++;
        }
        return Integer.parseInt(s.substring(i, j));
    }
}
