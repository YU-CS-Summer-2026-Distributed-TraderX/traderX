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

  private static List<String> read(Connection nc, String subject) throws Exception {
    JetStreamSubscription sub = nc.jetStream().subscribe(subject, PullSubscribeOptions.builder().stream("TRADERX_FILLS").build());
    List<String> out = new ArrayList<>();
    for (Message m : sub.fetch(10, Duration.ofSeconds(2))) { out.add(new String(m.getData(), StandardCharsets.UTF_8)); m.ack(); }
    sub.unsubscribe();
    return out;
  }
}
