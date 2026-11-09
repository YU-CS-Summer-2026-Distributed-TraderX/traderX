package finos.traderx.historyfeed;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-THT-11 on real infrastructure: the stream holds trade sides and order events twice, as the bridges' catch-up leaves
 * it (FR-DRS-37/38: at least once), and the store holds each once. The shipped jar in its own JVM, P = 2, small batches.
 * The repeats arrive every way they can: of rows stored before a SIGKILL of the feed (the trade sides with a publish
 * time a day later, so in another date partition), back to back inside one batch, and at the stream's tail, read again
 * by a third feed. A cross held back and published late is new and is kept. The store then answers G3h's own questions:
 * sides 1..t, each once. Last, what is not a repeat is not left out: the same trade id with a different quantity is
 * stored (and G3h's duplicate count sees it), and two identical order events with no position are both stored.
 */
@Tag("integration")
@Testcontainers
@Timeout(600)
class HistoryFeedDuplicateIT {
  static final int P = 2, CROSSES = 30_000, LATE = 21_500;
  static final long DAY = 1761782400000L, DAY_MS = 86_400_000L;   // 2025-10-30 UTC
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222);
  @Container static final GenericContainer<?> CH = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
      .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1").withExposedPorts(8123);

  final List<Process> children = new ArrayList<>();
  final HttpClient http = HttpClient.newHttpClient();
  int port = 18395;

  @org.junit.jupiter.api.AfterEach void killChildren() throws InterruptedException {
    for (Process p : children) p.destroyForcibly().waitFor();
  }

  @Test void a_side_the_stream_holds_twice_is_stored_once() throws Exception {
    ClickHouseStore ch = new ClickHouseStore("http://" + CH.getHost() + ":" + CH.getMappedPort(8123));
    HistoryFeedKillIT.waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      for (int p = 0; p < P; p++) {
        nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED_P" + p)
            .subjects("/trades." + p, "/orders." + p).storageType(StorageType.File).build());
      }
      JetStream js = nc.jetStream();
      publish(js, 0, 20_000, 0);
      Process a = start();
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) == 5 * 20_000, 120_000);
      a.destroyForcibly().waitFor();

      publish(js, 15_000, 20_000, DAY_MS);                // stored before the kill; the sides' publish time a day on
      publish(js, 20_000, 22_000, 0);                     // new (the late cross left out)
      for (int s = 22_000; s < 23_000; s++) { cross(js, null, s, 0); cross(js, null, s, 0); }   // twice, back to back
      publish(js, 23_000, CROSSES, 0);
      cross(js, null, LATE, 0);                           // new, behind 8,500 later crosses
      publish(js, 29_000, CROSSES, 0);                    // the stream's tail is all repeats
      int repeats = 5 * (5_000 + 1_000 + 1_000);

      start();
      caughtUp();
      assertEquals(repeats, sum("duplicates"), "messages left out: " + health());
      stored(ch);
      // First stored wins: a republished side keeps the row, and the publish time, of its first message.
      assertEquals("2025-10-30", ch.query("SELECT toDate(ts) FROM trades WHERE trade_seq = 30001", Map.of()).trim());

      children.get(1).destroyForcibly().waitFor();        // the store's cursor is behind the tail's repeats
      start();
      caughtUp();
      stored(ch);

      // The same id with another quantity is not a repeat: it is written, and G3h's duplicate count refuses the store.
      byte[] other = new String(HistoryFeedKillIT.trade(2 * 100 + 1, "B", 100 % 20, 2 * 100 + 2, DAY)).replace("\"quantity\":100", "\"quantity\":101").getBytes();
      js.publish("/trades." + 100 % 20 % P, other);
      // Two identical order events with no position (a legacy id scheme) are two events.
      byte[] legacy = new String(HistoryFeedKillIT.order(7, 7, "NEW", 0, DAY, 0)).replaceAll(",\"consensusSequence\":\\d+,\"outputOrdinal\":\\d+", "").getBytes();
      js.publish("/orders." + 7 % P, legacy);
      js.publish("/orders." + 7 % P, legacy);
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) == 5 * CROSSES + 3, 60_000);
      assertEquals("1", ch.query("SELECT count() - uniqExact(trade_seq) FROM trades", Map.of()).trim());
    }
  }

  /** G3h's questions (rig.sh HISTORY_CHECK_SQL): the sides are exactly 1..t, each once; and the same of the order events. */
  static void stored(ClickHouseStore ch) throws Exception {
    assertEquals(2 * CROSSES + "\t" + 2 * CROSSES + "\t" + 2 * CROSSES + "\t1",
        ch.query("SELECT count(), uniqExact(trade_seq), max(trade_seq), min(trade_seq) FROM trades", Map.of()).trim(), "trade sides");
    assertEquals(3 * CROSSES + "\t" + 3 * CROSSES,
        ch.query("SELECT count(), uniqExact(cseq, ordinal) FROM orders", Map.of()).trim(), "order events");
  }

  /** Crosses [from, to) but the late one, acked by the stream. */
  static void publish(JetStream js, int from, int to, long publishedLater) throws Exception {
    List<CompletableFuture<PublishAck>> acks = new ArrayList<>();
    for (int s = from; s < to; s++) if (s != LATE) cross(js, acks, s, publishedLater);
    for (var f : acks) f.get();
  }

  /**
   * One cross on the bridges' wire: a tradeSeq per side (2s+1, 2s+2), an order event's position (consensusSequence,
   * outputOrdinal), so every lane's positions rise. A trade side's date is the bridge's publish time; an order event's
   * is the engine's.
   */
  static void cross(JetStream js, List<CompletableFuture<PublishAck>> acks, int s, long publishedLater) throws Exception {
    int buyer = s % 20, seller = (s + 7) % 20;
    long ms = DAY + s, b = 2L * s + 1, sl = 2L * s + 2;
    Object[][] msgs = {
        {"/orders." + seller % P, HistoryFeedKillIT.order(sl, seller, "NEW", 0, ms, 0)},
        {"/trades." + buyer % P, HistoryFeedKillIT.trade(b, "B", buyer, b, ms + publishedLater)},
        {"/trades." + seller % P, HistoryFeedKillIT.trade(sl, "S", seller, sl, ms + publishedLater)},
        {"/orders." + buyer % P, HistoryFeedKillIT.order(b, buyer, "FILLED", 100, ms, 1)},
        {"/orders." + seller % P, HistoryFeedKillIT.order(sl, seller, "FILLED", 100, ms, 2)}};
    for (Object[] m : msgs) {
      if (acks == null) js.publish((String) m[0], (byte[]) m[1]);
      else acks.add(js.publishAsync((String) m[0], (byte[]) m[1]));
    }
  }

  Process start() throws Exception {
    port++;
    ProcessBuilder pb = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar",
        System.getProperty("historyfeed.jar")).inheritIO();
    pb.environment().putAll(Map.of("NATS_ADDRESS", "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222),
        "CLICKHOUSE_URL", "http://" + CH.getHost() + ":" + CH.getMappedPort(8123), "PROJECTION_FEED_PARTITIONS", "" + P,
        "PROJECTION_FEED_STREAM_LAYOUT", "partition", "HISTORY_BATCH_ROWS", "2000", "HISTORY_FLUSH_MS", "50",
        "HISTORY_FETCH", "500", "HISTORY_HTTP_PORT", "" + port));
    Process p = pb.start();
    children.add(p);
    return p;
  }

  String health() {
    try {
      return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
          HttpResponse.BodyHandlers.ofString()).body();
    } catch (Exception e) { return ""; }
  }

  long sum(String field) {
    long n = 0;
    for (Matcher m = Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(health()); m.find(); ) n += Long.parseLong(m.group(1));
    return n;
  }

  /** Every lane open and nothing pending, as G3h waits for it. */
  void caughtUp() throws InterruptedException {
    HistoryFeedKillIT.waitFor(() -> { String h = health(); return h.startsWith("{\"ready\":true") && !h.matches(".*\"pending\":(-1|[1-9]).*"); }, 300_000);
  }
}
