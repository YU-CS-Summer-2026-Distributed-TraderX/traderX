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

  /** The wire before FR-DRS-41 (one record per message) and the bridges' drain batching under load (64 per message). */
  @org.junit.jupiter.params.ParameterizedTest(name = "records per message: {0}")
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 64})
  void a_million_record_backlog_drains_in_under_a_minute(int perMessage) throws Exception {
    String chUrl = "http://" + CH.getHost() + ":" + CH.getMappedPort(8123);
    String natsUrl = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    ClickHouseStore ch = new ClickHouseStore(chUrl);
    HistoryFeedKillIT.waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    for (String t : new String[]{"trades", "orders", "history_cursor"}) { try { ch.exec("TRUNCATE TABLE " + t); } catch (Exception first) { } }
    try (Connection nc = Nats.connect(natsUrl)) {
      try { nc.jetStreamManagement().deleteStream("TRADERX_FEED"); } catch (Exception first) { }
      nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
          .subjects("/trades", "/trades.*", "/orders", "/orders.*").storageType(StorageType.File).build());
      JetStream js = nc.jetStream();
      List<CompletableFuture<PublishAck>> acks = new ArrayList<>();
      Packer orders = new Packer(js, "/orders", perMessage, acks), trades = new Packer(js, "/trades", perMessage, acks);
      for (int s = 0; s < CROSSES; s++) {                // the backlog, before the feed starts
        long seq = s + 1, ms = 1761782400000L + s;
        orders.add(HistoryFeedKillIT.order(2 * seq - 1, s % 20, "NEW", 0, ms, 0));
        // The bridges' numbering: a tradeSeq per side and order positions that rise, so the lanes take the plain INSERT
        // (FR-THT-11), as production's do.
        trades.add(HistoryFeedKillIT.trade(2 * seq - 1, "B", s % 20, 2 * seq, ms));
        trades.add(HistoryFeedKillIT.trade(2 * seq, "S", (s + 7) % 20, 2 * seq - 1, ms));
        orders.add(HistoryFeedKillIT.order(2 * seq, s % 20, "FILLED", 100, ms, 1));
        orders.add(HistoryFeedKillIT.order(2 * seq, (s + 7) % 20, "FILLED", 100, ms, 2));
        if (acks.size() > 20_000) { for (var f : acks) f.get(); acks.clear(); }
      }
      orders.flush(); trades.flush();
      for (var f : acks) f.get();
      long messages = nc.jetStreamManagement().getStreamInfo("TRADERX_FEED").getStreamState().getMsgCount();
      assertEquals((3L * CROSSES + perMessage - 1) / perMessage + (2L * CROSSES + perMessage - 1) / perMessage, messages, "the stream holds the packed messages");

      ProcessBuilder pb = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar",
          System.getProperty("historyfeed.jar")).inheritIO();
      pb.environment().putAll(Map.of("NATS_ADDRESS", natsUrl, "CLICKHOUSE_URL", chUrl, "HISTORY_HTTP_PORT", "18395"));
      long t0 = System.nanoTime();
      feed = pb.start();
      HistoryFeedKillIT.waitFor(() -> HistoryFeedKillIT.rows(ch) >= 5L * CROSSES, 60_000);
      System.out.printf("FR-THT-10: %d rows (%d per message) in %.1f s%n", 5L * CROSSES, perMessage, (System.nanoTime() - t0) / 1e9);
      for (String d : new String[]{"history-trades-p0", "history-orders-p0"}) {
        long granted = nc.jetStreamManagement().getConsumerInfo("TRADERX_FEED", d).getConsumerConfiguration().getMaxAckPending();
        assertTrue(granted >= 100_000, d + " maxAckPending " + granted);
      }
      // Each record once (FR-DRS-41: rows of one message share its stream sequence, so the identity is the record's).
      assertEquals(2 * CROSSES + "\t" + 2 * CROSSES, ch.query("SELECT count(), uniqExact(trade_seq) FROM trades", Map.of()).trim());
      assertEquals(3 * CROSSES + "\t" + 3 * CROSSES, ch.query("SELECT count(), uniqExact(cseq, ordinal) FROM orders", Map.of()).trim());
      feed.destroyForcibly().waitFor();
    }
  }

  /** Packs records onto one subject as a bridge lane does (FR-DRS-41): up to n per message, newline-separated. */
  static final class Packer {
    final JetStream js; final String subject; final int n; final List<CompletableFuture<PublishAck>> acks;
    final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(); int count;
    Packer(JetStream js, String subject, int n, List<CompletableFuture<PublishAck>> acks) { this.js = js; this.subject = subject; this.n = n; this.acks = acks; }
    void add(byte[] record) { if (count > 0) buf.write('\n'); buf.writeBytes(record); if (++count == n) flush(); }
    void flush() { if (count == 0) return; acks.add(js.publishAsync(subject, buf.toByteArray())); buf.reset(); count = 0; }
  }
}
