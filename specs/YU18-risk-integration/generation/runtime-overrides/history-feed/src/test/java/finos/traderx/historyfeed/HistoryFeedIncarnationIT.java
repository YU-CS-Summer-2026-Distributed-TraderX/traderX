package finos.traderx.historyfeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-HST-07 (codeX review §6) on real infrastructure: TRADERX_FEED_P0 deleted and recreated under the same name,
 * refilled past the store's cursor while the feed is down. The feed must refuse P0 (no row written from the new stream,
 * /health 503, STREAM_RESET) instead of resuming at cursor + 1, which would skip the new stream's first messages;
 * P1, untouched, keeps flowing. Control: a restart on the unchanged streams resumes and takes new load. Then the
 * first-binding rule: rows past cursor 0 with no recorded stream are refused (STREAM_UNBOUND) until an operator attests
 * the exact stream and cursor reconciled; an attestation of a stream since recreated is refused (STREAM_ATTEST_MISMATCH).
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class HistoryFeedIncarnationIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222);
  @Container static final GenericContainer<?> CH = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
      .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1").withExposedPorts(8123);

  final List<Process> children = new ArrayList<>();
  int port = 18295;

  @AfterEach void killChildren() throws InterruptedException {
    for (Process p : children) p.destroyForcibly().waitFor();
  }

  @Test void a_recreated_stream_past_the_cursor_is_refused_not_skipped() throws Exception {
    ClickHouseStore ch = new ClickHouseStore("http://" + CH.getHost() + ":" + CH.getMappedPort(8123));
    HistoryFeedKillIT.waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    long[] published = new long[2];
    try (Connection nc = Nats.connect(nats())) {
      for (int p = 0; p < HistoryFeedKillIT.P; p++) stream(nc, p);
      JetStream js = nc.jetStream();
      HistoryFeedKillIT.publish(js, 0, 1000, published);
      Process a = start();
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) == 5000, 120_000);
      a.destroyForcibly().waitFor();

      // Control: same streams, a restart resumes at the cursor and takes new load.
      Process b = start();
      HistoryFeedKillIT.publish(js, 1000, 1200, published);
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) == 6000, 120_000);
      assertEquals(200, health().statusCode(), health().body());
      b.destroyForcibly().waitFor();
      long part0Before = count(ch, 0), part1Before = count(ch, 1), p0Last = nc.jetStreamManagement()
          .getStreamInfo("TRADERX_FEED_P0").getStreamState().getLastSequence();

      // The fault: P0 lost and recreated under the same name, then refilled past the old cursor.
      nc.jetStreamManagement().deleteStream("TRADERX_FEED_P0");
      stream(nc, 0);
      HistoryFeedKillIT.publish(js, 1200, 4000, published);
      long newP0Last = nc.jetStreamManagement().getStreamInfo("TRADERX_FEED_P0").getStreamState().getLastSequence();
      assertTrue(newP0Last > p0Last, "the new stream must reach past the old cursor (" + newP0Last + " vs " + p0Last + ")");

      Process c = start();
      HistoryFeedKillIT.waitFor(() -> count(ch, 1) > part1Before, 120_000);   // P1 untouched: flows
      Thread.sleep(5000);                                                     // time for P0 to (wrongly) write
      assertEquals(part0Before, count(ch, 0), "no row of the recreated P0 may be written");
      HttpResponse<String> h = health();
      assertEquals(503, h.statusCode(), h.body());
      assertTrue(h.body().contains("STREAM_RESET history-trades-p0") && h.body().contains("STREAM_RESET history-orders-p0"), h.body());
      assertTrue(!h.body().contains("STREAM_RESET history-trades-p1"), h.body());
      c.destroyForcibly().waitFor();

      // Rows with no recorded stream (from before the binding existed: P1's record removed) past cursor 0 are refused
      // too, STREAM_UNBOUND, not bound to whatever stream exists now. P0 is reconciled and attested by the operator.
      ch.exec("ALTER TABLE history_cursor DELETE WHERE part = 1 SETTINGS mutations_sync = 1");
      for (String t : new String[] {"trades", "orders"}) attest(nc, ch, 0, t);
      insert(ch, 1, "trades", "0");                                     // the bare, nameless attestation: refused
      long part1Unbound = count(ch, 1);
      HistoryFeedKillIT.publish(js, 4000, 4200, published);
      Process d = start();
      HistoryFeedKillIT.waitFor(() -> count(ch, 0) > part0Before, 120_000);   // attested P0 resumes on the new stream
      Thread.sleep(5000);
      assertEquals(part1Unbound, count(ch, 1), "no row may be written to an unbound lane past cursor 0");
      h = health();
      assertEquals(503, h.statusCode(), h.body());
      assertTrue(h.body().contains("STREAM_UNBOUND history-trades-p1") && h.body().contains("STREAM_UNBOUND history-orders-p1"), h.body());
      assertTrue(!h.body().contains("STREAM_RESET") && !h.body().contains("STREAM_UNBOUND history-trades-p0"), h.body());
      String newP0 = FeedLane.incarnationOf(nc.jetStreamManagement().getStreamInfo("TRADERX_FEED_P0"));
      assertEquals(newP0, ch.incarnation("TRADERX_FEED_P0", "trades", 0), "the attestation is used up by the bind");
      d.destroyForcibly().waitFor();

      // codeX §16: P1 reconciled and attested against the stream as it was, then recreated and refilled past the
      // cursor before the feed reads the attestation. It names the old stream, so it is refused, no row written.
      for (String t : new String[] {"trades", "orders"}) attest(nc, ch, 1, t);
      long p1Last = nc.jetStreamManagement().getStreamInfo("TRADERX_FEED_P1").getStreamState().getLastSequence();
      nc.jetStreamManagement().deleteStream("TRADERX_FEED_P1");
      stream(nc, 1);
      HistoryFeedKillIT.publish(js, 4200, 9000, published);
      assertTrue(nc.jetStreamManagement().getStreamInfo("TRADERX_FEED_P1").getStreamState().getLastSequence() > p1Last);
      long part0Attested = count(ch, 0);
      Process e = start();
      HistoryFeedKillIT.waitFor(() -> count(ch, 0) > part0Attested, 120_000);   // P0, bound, keeps flowing
      Thread.sleep(5000);
      assertEquals(part1Unbound, count(ch, 1), "an attestation of the old P1 may not approve the recreated one");
      h = health();
      assertEquals(503, h.statusCode(), h.body());
      assertTrue(h.body().contains("STREAM_ATTEST_MISMATCH history-trades-p1")
          && h.body().contains("STREAM_ATTEST_MISMATCH history-orders-p1"), h.body());
      e.destroyForcibly().waitFor();

      // Control: attested against P1 as it is now, every lane opens and the feed is healthy.
      for (String t : new String[] {"trades", "orders"}) attest(nc, ch, 1, t);
      start();
      HistoryFeedKillIT.waitFor(() -> count(ch, 1) > part1Unbound, 120_000);
      HistoryFeedKillIT.waitFor(() -> { try { return health().statusCode() == 200; } catch (Exception x) { return false; } }, 60_000);
    }
  }

  /** What the operator writes after reconciling: this stream's incarnation now and the store's cursor now. */
  static void attest(Connection nc, ClickHouseStore ch, int part, String table) throws Exception {
    String now = FeedLane.incarnationOf(nc.jetStreamManagement().getStreamInfo("TRADERX_FEED_P" + part));
    insert(ch, part, table, FeedLane.attestation(now, ch.maxSeq(table, part)));
  }

  static void insert(ClickHouseStore ch, int part, String table, String incarnation) throws Exception {
    ch.exec("INSERT INTO history_cursor (stream, tbl, part, incarnation) VALUES ('TRADERX_FEED_P" + part + "', '" + table
        + "', " + part + ", '" + incarnation + "')");
  }

  static void stream(Connection nc, int p) throws Exception {
    nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED_P" + p)
        .subjects("/trades." + p, "/orders." + p).storageType(StorageType.File).build());
  }

  static long count(ClickHouseStore ch, int part) {
    try {
      return Long.parseLong(ch.query("SELECT (SELECT count() FROM trades WHERE part = {p:UInt16})"
          + " + (SELECT count() FROM orders WHERE part = {p:UInt16})", Map.of("p", "" + part)).trim());
    } catch (Exception e) { return -1; }
  }

  String nats() { return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222); }

  HttpResponse<String> health() throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  Process start() throws Exception {
    port++;
    ProcessBuilder pb = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar",
        System.getProperty("historyfeed.jar")).inheritIO();
    pb.environment().putAll(Map.of("NATS_ADDRESS", nats(), "CLICKHOUSE_URL", "http://" + CH.getHost() + ":" + CH.getMappedPort(8123),
        "PROJECTION_FEED_PARTITIONS", "" + HistoryFeedKillIT.P, "PROJECTION_FEED_STREAM_LAYOUT", "partition",
        "HISTORY_BATCH_ROWS", "2000", "HISTORY_FLUSH_MS", "50", "HISTORY_HTTP_PORT", "" + port));
    Process p = pb.start();
    children.add(p);
    return p;
  }
}
