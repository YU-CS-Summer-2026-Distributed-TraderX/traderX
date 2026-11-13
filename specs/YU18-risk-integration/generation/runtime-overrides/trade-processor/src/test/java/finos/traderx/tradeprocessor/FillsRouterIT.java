package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** FR-DRS-48: the router creates the fills stream and each gateway's subject holds only its own orders' events, one
 *  message per batch per gateway, records newline-joined in order; acks are counted. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class FillsRouterIT {
  @Container
  static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

  @Test
  void eachGatewaysSubjectHoldsOnlyItsOwnOrdersEvents() throws Exception {
    String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    try (Connection nc = Nats.connect(url)) {
      FillsRouter router = new FillsRouter(nc, "TRADERX_FILLS", 64L << 20, Duration.ofMinutes(5), null);
      FillsRouter.Batch b1 = new FillsRouter.Batch();
      b1.add("gw-a", FillsRouter.bytes("{\"id\":\"7-1\",\"status\":\"NEW\"}"));
      b1.add("gw-b", FillsRouter.bytes("{\"id\":\"7-2\",\"status\":\"NEW\"}"));
      b1.add("gw-a", FillsRouter.bytes("{\"id\":\"7-1\",\"status\":\"PARTIALLY_FILLED\"}"));
      router.route(b1);
      FillsRouter.Batch b2 = new FillsRouter.Batch();
      b2.add("gw-a", FillsRouter.bytes("{\"id\":\"7-1\",\"status\":\"FILLED\"}"));
      router.route(b2);
      assertThat(read(nc, "/fills.gw-a")).containsExactly(
          "{\"id\":\"7-1\",\"status\":\"NEW\"}\n{\"id\":\"7-1\",\"status\":\"PARTIALLY_FILLED\"}", "{\"id\":\"7-1\",\"status\":\"FILLED\"}");
      assertThat(read(nc, "/fills.gw-b")).containsExactly("{\"id\":\"7-2\",\"status\":\"NEW\"}");
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while ((long) router.stats().get("acked") < 3 && System.nanoTime() < deadline) { Thread.sleep(20); }
      assertThat(router.stats()).containsEntry("routed", 4L).containsEntry("messages", 3L).containsEntry("acked", 3L).containsEntry("failed", 0L);
      assertThat(nc.jetStreamManagement().getStreamInfo("TRADERX_FILLS").getConfiguration().getSubjects()).containsExactly("/fills.>");
    }
  }

  /**
   * FR-DRS-53: over four streams, each gateway's events are in its own shard's stream and on its own subject, and no
   * stream holds more than half of them. Falsifiable: with one stream (the single-stream shape, FILLS_STREAMS=1) that
   * stream holds all of them, every gateway's consumer filtering it on one server (run 5's ceiling).
   */
  /** FR-DRS-60: one gateway's share of a view batch larger than NATS's 1 MiB payload (GKE 2026-11-12, after a stall:
   *  1.06 to 2.7 MB, refused by the client) reaches its subject whole, in order. */
  @Test
  void aGatewaysShareLargerThanTheServersPayloadLimitIsRouted() throws Exception {
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      FillsRouter router = new FillsRouter(nc, "SPREAD_FILLS", 64L << 20, Duration.ofMinutes(5), null, 4, null);   // the streams of the test below
      FillsRouter.Batch b = new FillsRouter.Batch();
      List<String> want = new java.util.ArrayList<>();
      for (int i = 0; i < 6000; i++) { String r = "{\"n\":" + i + ",\"pad\":\"" + "p".repeat(280) + "\"}"; want.add(r); b.add("gw-big_s1", FillsRouter.bytes(r)); }   // ~1.8 MB
      router.route(b);
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while ((long) router.stats().get("acked") < (long) router.stats().get("messages") && System.nanoTime() < deadline) { Thread.sleep(20); }
      assertThat(router.stats()).containsEntry("failed", 0L).containsEntry("routed", 6000L);
      assertThat((long) router.stats().get("messages")).isGreaterThan(1L);
      assertThat(read(nc, "SPREAD_FILLS_1", "/fillsk.1.gw-big_s1").stream().flatMap(m -> java.util.Arrays.stream(m.split("\n"))).toList()).isEqualTo(want);
    }
  }

  @Test
  void theFillsAreSpreadOverStreamsEachGatewayInItsShard() throws Exception {
    String url = "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    int shards = 4;
    try (Connection nc = Nats.connect(url)) {
      FillsRouter router = new FillsRouter(nc, "SPREAD_FILLS", 64L << 20, Duration.ofMinutes(5), null, shards, null);
      for (int batch = 0; batch < 3; batch++) {
        FillsRouter.Batch b = new FillsRouter.Batch();
        for (int g = 0; g < 12; g++) { b.add("gw-" + g, FillsRouter.bytes("{\"id\":\"" + g + "-" + batch + "\"}")); }
        router.route(b);
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while ((long) router.stats().get("acked") < 36 && System.nanoTime() < deadline) { Thread.sleep(20); }
      assertThat(router.stats()).containsEntry("acked", 36L).containsEntry("failed", 0L);
      var jsm = nc.jetStreamManagement();
      List<String> streams = jsm.getStreamNames().stream().filter(n -> n.startsWith("SPREAD_FILLS")).sorted().toList();
      long total = 0, most = 0;
      for (String n : streams) { long c = jsm.getStreamInfo(n).getStreamState().getMsgCount(); total += c; most = Math.max(most, c); }
      assertThat(total).isEqualTo(36);
      assertThat(most).as("the most any one of %s holds", streams).isLessThanOrEqualTo(total / 2);
      for (int g = 0; g < 12; g++) {
        String gw = "gw-" + g;
        assertThat(read(nc, "SPREAD_FILLS_" + FillsRouter.shard(gw, shards), FillsRouter.subject(gw, shards)))
            .containsExactly("{\"id\":\"" + g + "-0\"}", "{\"id\":\"" + g + "-1\"}", "{\"id\":\"" + g + "-2\"}");
      }
    }
  }

  private static List<String> read(Connection nc, String subject) throws Exception {
    return read(nc, "TRADERX_FILLS", subject);
  }

  private static List<String> read(Connection nc, String stream, String subject) throws Exception {
    JetStreamSubscription sub = nc.jetStream().subscribe(subject, PullSubscribeOptions.builder().stream(stream).build());
    List<String> out = new ArrayList<>();
    for (Message m : sub.fetch(10, Duration.ofSeconds(2))) { out.add(new String(m.getData(), StandardCharsets.UTF_8)); m.ack(); }
    sub.unsubscribe();
    return out;
  }
}
