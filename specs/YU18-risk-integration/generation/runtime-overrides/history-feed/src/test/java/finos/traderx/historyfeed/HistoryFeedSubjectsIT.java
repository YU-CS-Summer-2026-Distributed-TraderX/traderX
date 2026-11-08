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
 * FR-THT-02 on production's default shape (e1107b, 2026-11-07): one partition, layout one, so the members publish the
 * bare /trades and /orders on TRADERX_FEED. The feed must store every message and report nothing unowned. Then the
 * shape that recorded nothing on production, a feed configured for 2 partitions over those bare subjects: its lanes
 * match nothing and report nothing pending, and /health must name the unread subjects with their counts.
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class HistoryFeedSubjectsIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222);
  @Container static final GenericContainer<?> CH = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
      .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1").withExposedPorts(8123);

  final List<Process> children = new ArrayList<>();
  int port = 18295;

  @AfterEach void killChildren() throws InterruptedException {
    for (Process p : children) p.destroyForcibly().waitFor();
  }

  @Test void one_partition_reads_the_bare_subjects_and_a_mismatch_is_reported() throws Exception {
    ClickHouseStore ch = new ClickHouseStore(ch());
    HistoryFeedKillIT.waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
          .subjects("/trades", "/trades.*", "/orders", "/orders.*").storageType(StorageType.File).build());   // as trade-processor
      JetStream js = nc.jetStream();
      for (long seq = 1; seq <= 3; seq++) {             // the bridges at P=1: bare subjects
        js.publish("/orders", HistoryFeedKillIT.order(2 * seq - 1, 3, "NEW", 0, 1761782400000L + seq, 0));
        js.publish("/trades", HistoryFeedKillIT.trade(seq, "B", 2, 2 * seq, 1761782400000L + seq));
        js.publish("/trades", HistoryFeedKillIT.trade(seq, "S", 3, 2 * seq - 1, 1761782400000L + seq));
      }

      Process one = start("1");
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) >= 9, 120_000);
      String h = health();
      assertTrue(h.contains("\"unowned\":{}"), h);
      assertEquals("6", ch.query("SELECT count() FROM trades", Map.of()).trim());
      assertEquals("3", ch.query("SELECT count() FROM orders", Map.of()).trim());
      one.destroyForcibly().waitFor();

      // Production's failure: lanes on /trades.0, /trades.1 over a stream of bare subjects.
      ch.exec("TRUNCATE TABLE trades"); ch.exec("TRUNCATE TABLE orders"); ch.exec("TRUNCATE TABLE history_cursor");
      nc.jetStreamManagement().deleteConsumer("TRADERX_FEED", "history-trades-p0");
      nc.jetStreamManagement().deleteConsumer("TRADERX_FEED", "history-orders-p0");
      start("2");
      HistoryFeedKillIT.waitFor(() -> { try { return health().contains("\"ready\":true"); } catch (Exception e) { return false; } }, 120_000);
      h = health();
      assertTrue(h.contains("\"unowned\":{\"/orders\":3,\"/trades\":6}"), h);
      assertEquals(0, HistoryFeedKillIT.rows(ch), "the mismatched lanes stored something");
    }
  }

  String ch() { return "http://" + CH.getHost() + ":" + CH.getMappedPort(8123); }

  String health() throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
        HttpResponse.BodyHandlers.ofString()).body();
  }

  Process start(String partitions) throws Exception {
    port++;
    ProcessBuilder pb = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar",
        System.getProperty("historyfeed.jar")).inheritIO();
    pb.environment().putAll(Map.of("NATS_ADDRESS", "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222),
        "CLICKHOUSE_URL", ch(), "PROJECTION_FEED_PARTITIONS", partitions, "PROJECTION_FEED_STREAM_LAYOUT", "one",
        "HISTORY_BATCH_ROWS", "2000", "HISTORY_FLUSH_MS", "50", "HISTORY_HTTP_PORT", "" + port));
    Process p = pb.start();
    children.add(p);
    return p;
  }
}
