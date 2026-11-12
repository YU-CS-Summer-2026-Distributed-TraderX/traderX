package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * FR-XOC-07: the gateway's order reads against a real HTTP read-model stand-in. Each test fails on the unbounded
 * reader (one String and one JSON tree per listing, any number at once), which GKE run 7's gateways died of.
 */
@Timeout(30)
class ReadModelOrdersTest {
    private HttpServer server;
    private String base;
    private volatile int rows = 3;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private volatile boolean hold;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/accounts/11/orders", x -> {
            final String q = x.getRequestURI().getQuery();
            final boolean byKey = q != null && q.startsWith("clientOrderKey=");
            if (hold && !byKey) {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            x.sendResponseHeaders(200, 0);   // chunked: the size is not known to the reader up front
            try (var out = x.getResponseBody()) {
                out.write('[');
                final int n = byKey ? 1 : rows;
                for (int i = 0; i < n; i++) {
                    out.write(((i == 0 ? "" : ",") + "{\"id\":\"e7-" + (42 + i) + "\",\"side\":\"Buy\",\"quantity\":5,"
                        + "\"remainingQuantity\":0,\"status\":\"FILLED\",\"security\":\"IBM\"" + (i == 0 ? ",\"filledQuantity\":5" : "")
                        + "}").getBytes(StandardCharsets.UTF_8));
                }
                out.write(']');
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/accounts/11/orders";
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void aListingIsParsedRowByRowIntoTheSameViews() {
        final ReadModelOrders r = new ReadModelOrders(HttpClient.newHttpClient(), 2, 100, 100);
        final List<OrderStatusSource.OrderView> got = r.list(base + "?status=all", 11);
        assertNotNull(got);
        assertEquals(3, got.size());
        assertEquals(new OrderStatusSource.OrderView(42, "Buy", 5, 0, "FILLED", "IBM", 5), got.get(0));
        assertEquals(-1, got.get(1).filled(), "a row without filledQuantity");
        assertEquals(44, got.get(2).orderRef());
    }

    @Test
    void aListingOverTheRowBoundIsNotLoadedAndReadsAsUnavailable() {
        rows = 250_000;   // run 7's accounts held millions; the old reader took the whole body as one String
        final ReadModelOrders r = new ReadModelOrders(HttpClient.newHttpClient(), 2, 1_000, 100);
        assertNull(r.list(base + "?status=all", 11), "a list past the bound must read as unavailable, never as a short list");
        assertEquals(1, r.refusedTooLarge());
        final List<OrderStatusSource.OrderView> one = r.byKey(base + "?clientOrderKey=9", 11);
        assertNotNull(one, "the by-key read is one row and unaffected");
        assertEquals(1, one.size());
    }

    @Test
    void listingsBeyondThePermitsAreRefusedWhileByKeyReadsGoThrough() throws Exception {
        hold = true;
        final ReadModelOrders r = new ReadModelOrders(HttpClient.newHttpClient(), 1, 100, 50);
        final var pool = Executors.newSingleThreadExecutor();
        try {
            final var first = pool.submit(() -> r.list(base + "?status=all", 11));
            assertEquals(true, entered.await(10, TimeUnit.SECONDS), "the first listing never reached the read model");
            for (int i = 0; i < 20; i++) {   // a storm behind it: each is refused after its wait, none is queued or held
                assertNull(r.list(base + "?status=all", 11));
            }
            assertEquals(20, r.refusedBusy());
            assertEquals(1, r.listings(), "only the permitted listing reached the read model");
            assertEquals(1, r.byKey(base + "?clientOrderKey=9", 11).size(), "a by-key read takes no permit");
            release.countDown();
            assertEquals(3, first.get(10, TimeUnit.SECONDS).size());
        } finally {
            pool.shutdownNow();
        }
    }
}
