package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FR-XOC-07: the gateway's reads of the order read model ({@code GET /accounts/{id}/orders…}), bounded. GKE run 7
 * (2026-11-12): after a leader kill at 450K orders/s every gateway asked for a whole account's order list
 * ({@code ?status=all}) once per restated order, each read as one String and one JSON tree, and all 18 ran out of
 * their 768 MB heap within 13 s. Here a listing holds one of {@code listPermits} permits (a caller that cannot get
 * one within the wait is told "unavailable", as for a read model that is down), is parsed row by row from the
 * stream, and is given up past {@code maxRows}. A by-key read is one row and takes no permit.
 */
final class ReadModelOrders {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final Semaphore listPermits;
    private final int maxRows;
    private final long permitWaitMs;
    private final AtomicLong listings = new AtomicLong();
    private final AtomicLong keyReads = new AtomicLong();
    private final AtomicLong refusedBusy = new AtomicLong();
    private final AtomicLong refusedTooLarge = new AtomicLong();

    ReadModelOrders(final HttpClient client, final int listConcurrency, final int maxRows, final long permitWaitMs) {
        this.client = client;
        this.listPermits = new Semaphore(Math.max(1, listConcurrency));
        this.maxRows = Math.max(1, maxRows);
        this.permitWaitMs = permitWaitMs;
    }

    /** A whole-account listing (open, or every state): at most {@code listPermits} at once, at most {@code maxRows}. */
    List<OrderStatusSource.OrderView> list(final String url, final int accountId) {
        try {
            if (!listPermits.tryAcquire(permitWaitMs, TimeUnit.MILLISECONDS)) {
                refusedBusy.incrementAndGet();
                return null;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            listings.incrementAndGet();
            return read(url, accountId, maxRows);
        } finally {
            listPermits.release();
        }
    }

    /** ADR-093: the one order booked under a client key (an empty list when none). */
    List<OrderStatusSource.OrderView> byKey(final String url, final int accountId) {
        keyReads.incrementAndGet();
        return read(url, accountId, 16);
    }

    private List<OrderStatusSource.OrderView> read(final String url, final int accountId, final int limit) {
        try {
            final HttpResponse<InputStream> resp = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    return null;
                }
                try (JsonParser p = JSON.getFactory().createParser(in)) {
                    if (p.nextToken() != JsonToken.START_ARRAY) {
                        return null;
                    }
                    final List<OrderStatusSource.OrderView> out = new ArrayList<>();
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        if (out.size() >= limit) {
                            if (refusedTooLarge.getAndIncrement() % 1000 == 0) {
                                System.err.println("read-model order list for account " + accountId + " is over "
                                    + limit + " rows: not loaded (FR-XOC-07); answered as unavailable");
                            }
                            return null;   // closing the stream drops the rest unread
                        }
                        final JsonNode n = JSON.readTree(p);
                        out.add(new OrderStatusSource.OrderView(
                            refOf(n.path("id").asText("")),
                            n.path("side").asText(""),
                            n.path("quantity").asInt(),
                            n.path("remainingQuantity").asInt(),
                            n.path("status").asText(""),
                            n.path("security").asText(""),
                            n.path("filledQuantity").asInt(-1)));
                    }
                    return out;
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception e) {
            System.err.println("read-model status query failed for account " + accountId + ": " + e);
            return null;
        }
    }

    /** orderRef out of the read model's epoch-qualified id {@code <epoch>-<orderRef>}; -1 if malformed. */
    static long refOf(final String id) {
        final int dash = id.lastIndexOf('-');
        if (dash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(id.substring(dash + 1));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    long listings() { return listings.get(); }
    long keyReads() { return keyReads.get(); }
    long refusedBusy() { return refusedBusy.get(); }
    long refusedTooLarge() { return refusedTooLarge.get(); }
}
