package finos.traderx.historyfeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-HST-03 on real infrastructure: the history feed, as its shipped jar in its own JVM, SIGKILLed mid-drain twice and
 * restarted, with load arriving throughout, ends with every captured message stored exactly once: rows = distinct
 * (part, seq) = distinct business key = the streams' own per-subject counts. The partition stream layout (FR-DRS-32),
 * P = 2, small batches so each kill lands between and inside writes.
 */
@Tag("integration")
@Testcontainers
@Timeout(600)
class HistoryFeedKillIT {
  static final int P = 2;
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222);
  @Container static final GenericContainer<?> CH = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
      .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1").withExposedPorts(8123);

  static String natsUrl() { return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222); }
  static String chUrl() { return "http://" + CH.getHost() + ":" + CH.getMappedPort(8123); }

  /** Every child feed JVM; all destroyed after the test, pass or fail (an orphan holds Gradle's inherited stdout). */
  final List<Process> children = new ArrayList<>();

  @org.junit.jupiter.api.AfterEach void killChildren() throws InterruptedException {
    for (Process p : children) p.destroyForcibly().waitFor();
  }

  @Test void kill_9_twice_mid_drain_loses_and_duplicates_nothing() throws Exception {
    ClickHouseStore ch = new ClickHouseStore(chUrl());
    waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    long[] published = new long[2];   // trades, orders
    try (Connection nc = Nats.connect(natsUrl())) {
      for (int p = 0; p < P; p++) {
        nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED_P" + p)
            .subjects("/trades." + p, "/orders." + p).storageType(StorageType.File).build());
      }
      JetStream js = nc.jetStream();
      publish(js, 0, 60_000, published);                 // a backlog before the feed exists

      Process a = start();
      waitFor(() -> rows(ch) > 20_000, 120_000);
      a.destroyForcibly().waitFor();                     // SIGKILL
      long afterA = rows(ch);
      publish(js, 60_000, 90_000, published);            // load while the feed is down

      Process b = start();
      waitFor(() -> rows(ch) > afterA + 20_000, 300_000);
      publish(js, 90_000, 120_000, published);           // load while it drains
      b.destroyForcibly().waitFor();                     // SIGKILL again, mid-drain

      Process c = start();
      long expect = published[0] + published[1];
      waitFor(() -> rows(ch) >= expect, 300_000);
      Thread.sleep(3000);                                // a late duplicate would land now
      {
        for (String t : new String[]{"trades", "orders"}) {
          String key = t.equals("trades") ? "trade_seq * 2 + (side = 'S')" : "order_ref * 4 + ordinal";
          String[] v = ch.query("SELECT count(), uniqExact(part, seq), uniqExact(" + key + ") FROM " + t, Map.of()).trim().split("\t");
          long want = t.equals("trades") ? published[0] : published[1];
          assertEquals(want, Long.parseLong(v[0]), t + " rows");
          assertEquals(want, Long.parseLong(v[1]), t + " distinct (part, seq)");
          assertEquals(want, Long.parseLong(v[2]), t + " distinct business key");
        }
        // The query paths answer from the same tables (FR-HST-05).
        HttpClient http = HttpClient.newHttpClient();
        String fills = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/history/accounts/7/fills?day=2025-10-30&limit=5")).build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(fills.contains("\"rows\": 5"), fills);
        String w = "from=2025-10-30T00:00:00Z&to=2025-10-30T00:05:00Z";
        for (String path : new String[]{"accounts/7/tca?" + w, "accounts/7/positions?asOf=2025-10-30T00:01:00Z", "vwap?" + w}) {
          HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/history/" + path)).build(),
              HttpResponse.BodyHandlers.ofString());
          assertEquals(200, r.statusCode(), path + ": " + r.body());
          assertTrue(r.body().contains("\"rows\": ") && !r.body().contains("\"rows\": 0,"), path + ": " + r.body());
        }
        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/history/accounts/7/positions?asOf=1%3B%20DROP%20TABLE%20trades")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode(), bad.body());
        String health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
            HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(health.startsWith("{\"ready\":true"), health);
      }
    }
  }

  int port = 18195;

  Process start() throws Exception {
    port++;
    ProcessBuilder pb = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar",
        System.getProperty("historyfeed.jar")).inheritIO();
    pb.environment().putAll(Map.of("NATS_ADDRESS", natsUrl(), "CLICKHOUSE_URL", chUrl(), "PROJECTION_FEED_PARTITIONS", "" + P,
        "PROJECTION_FEED_STREAM_LAYOUT", "partition", "HISTORY_BATCH_ROWS", "2000", "HISTORY_FLUSH_MS", "50",
        "HISTORY_FETCH", "500", "HISTORY_HTTP_PORT", "" + port));
    Process p = pb.start();
    children.add(p);
    return p;
  }

  /** Crosses [from, to): 2 trade sides + 3 order updates each, on the bridges' wire, accounts across both partitions. */
  static void publish(JetStream js, int from, int to, long[] published) throws Exception {
    List<CompletableFuture<PublishAck>> acks = new ArrayList<>();
    for (int s = from; s < to; s++) {
      long seq = s + 1; int buyer = s % 20, seller = (s + 7) % 20;
      long ms = 1761782400000L + s;                      // 2025-10-30 UTC
      acks.add(js.publishAsync("/orders." + seller % P, order(2 * seq - 1, seller, "NEW", 0, ms, 0)));
      acks.add(js.publishAsync("/trades." + buyer % P, trade(seq, "B", buyer, 2 * seq, ms)));
      acks.add(js.publishAsync("/trades." + seller % P, trade(seq, "S", seller, 2 * seq - 1, ms)));
      acks.add(js.publishAsync("/orders." + buyer % P, order(2 * seq, buyer, "FILLED", 100, ms, 1)));
      acks.add(js.publishAsync("/orders." + seller % P, order(2 * seq - 1, seller, "FILLED", 100, ms, 2)));
      published[0] += 2; published[1] += 3;
      if (acks.size() > 20_000) { for (var f : acks) f.get(); acks.clear(); }
    }
    for (var f : acks) f.get();
  }

  static byte[] trade(long seq, String side, int acct, long ref, long ms) {
    return ("{\"topic\":\"/trades\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":" + ms + ",\"payload\":{\"id\":\"e1-it-"
        + seq + "-" + side + "\",\"state\":\"New\",\"security\":\"S" + seq % 50 + "\",\"quantity\":100,\"price\":" + (10 + seq % 90)
        + ".500000,\"accountId\":" + acct + ",\"side\":\"" + (side.equals("B") ? "Buy" : "Sell") + "\",\"sourceOrderId\":\"it-" + ref
        + "\",\"consensusSequence\":" + seq + "}}").getBytes(StandardCharsets.UTF_8);
  }

  static byte[] order(long ref, int acct, String status, int filled, long ms, int ordinal) {
    return ("{\"topic\":\"/orders\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\",\"date\":" + ms + ",\"payload\":{\"id\":\"it-"
        + ref + "\",\"accountId\":" + acct + ",\"security\":\"S" + ref % 50 + "\",\"side\":\"" + (ref % 2 == 0 ? "Buy" : "Sell")
        + "\",\"quantity\":100,\"remainingQuantity\":" + (100 - filled) + ",\"limitPrice\":50.000000,\"status\":\"" + status
        + "\",\"lastExecutionPrice\":50.000000,\"lastFillQuantity\":" + filled + ",\"createdAt\":" + ms + ",\"updatedAt\":" + ms
        + ",\"consensusSequence\":" + ref + ",\"outputOrdinal\":" + ordinal + "}}").getBytes(StandardCharsets.UTF_8);
  }

  static long rows(ClickHouseStore ch) {
    try {
      return Long.parseLong(ch.query("SELECT (SELECT count() FROM trades) + (SELECT count() FROM orders)", Map.of()).trim());
    } catch (Exception e) { return 0; }
  }

  static void waitFor(BooleanSupplier c, long ms) throws InterruptedException {
    long end = System.currentTimeMillis() + ms;
    while (!c.getAsBoolean()) {
      if (System.currentTimeMillis() > end) throw new AssertionError("timed out");
      Thread.sleep(200);
    }
  }
}
