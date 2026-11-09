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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-THT-10 on production's shape and defaults (one partition, layout one, HISTORY_BATCH_ROWS/FLUSH_MS/FETCH unset): a
 * backlog of 1M messages drains in under a minute, and each lane's consumer holds the server-granted ack window the
 * batch needs. Before the fix the server stored maxAckPending -1 as 1000 and the same backlog took about 6 minutes
 * (1,500 rows/s per lane, production e1108b 2026-11-08).
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class HistoryFeedRateIT {
  static final int CROSSES = 200_000;
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222);
  @Container static final GenericContainer<?> CH = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
      .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1").withExposedPorts(8123);

  Process feed;

  @AfterEach void kill() throws InterruptedException { if (feed != null) feed.destroyForcibly().waitFor(); }

  @Test void a_million_message_backlog_drains_in_under_a_minute() throws Exception {
    String chUrl = "http://" + CH.getHost() + ":" + CH.getMappedPort(8123);
    String natsUrl = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    ClickHouseStore ch = new ClickHouseStore(chUrl);
    HistoryFeedKillIT.waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    try (Connection nc = Nats.connect(natsUrl)) {
      nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
          .subjects("/trades", "/trades.*", "/orders", "/orders.*").storageType(StorageType.File).build());
      JetStream js = nc.jetStream();
      List<CompletableFuture<PublishAck>> acks = new ArrayList<>();
      for (int s = 0; s < CROSSES; s++) {                // the backlog, before the feed starts
        long seq = s + 1, ms = 1761782400000L + s;
        acks.add(js.publishAsync("/orders", HistoryFeedKillIT.order(2 * seq - 1, s % 20, "NEW", 0, ms, 0)));
        // The bridges' numbering: a tradeSeq per side and order positions that rise, so the lanes take the plain INSERT
        // (FR-THT-11), as production's do.
        acks.add(js.publishAsync("/trades", HistoryFeedKillIT.trade(2 * seq - 1, "B", s % 20, 2 * seq, ms)));
        acks.add(js.publishAsync("/trades", HistoryFeedKillIT.trade(2 * seq, "S", (s + 7) % 20, 2 * seq - 1, ms)));
        acks.add(js.publishAsync("/orders", HistoryFeedKillIT.order(2 * seq, s % 20, "FILLED", 100, ms, 1)));
        acks.add(js.publishAsync("/orders", HistoryFeedKillIT.order(2 * seq, (s + 7) % 20, "FILLED", 100, ms, 2)));
        if (acks.size() > 20_000) { for (var f : acks) f.get(); acks.clear(); }
      }
      for (var f : acks) f.get();

      ProcessBuilder pb = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar",
          System.getProperty("historyfeed.jar")).inheritIO();
      pb.environment().putAll(Map.of("NATS_ADDRESS", natsUrl, "CLICKHOUSE_URL", chUrl, "HISTORY_HTTP_PORT", "18395"));
      long t0 = System.nanoTime();
      feed = pb.start();
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) >= 5L * CROSSES, 60_000);
      System.out.printf("FR-THT-10: %d rows in %.1f s%n", 5L * CROSSES, (System.nanoTime() - t0) / 1e9);
      for (String d : new String[]{"history-trades-p0", "history-orders-p0"}) {
        long granted = nc.jetStreamManagement().getConsumerInfo("TRADERX_FEED", d).getConsumerConfiguration().getMaxAckPending();
        assertTrue(granted >= 100_000, d + " maxAckPending " + granted);
      }
      assertEquals("" + 2 * CROSSES, ch.query("SELECT uniqExact(part, seq) FROM trades", Map.of()).trim());
      assertEquals("" + 3 * CROSSES, ch.query("SELECT uniqExact(part, seq) FROM orders", Map.of()).trim());
    }
  }
}
