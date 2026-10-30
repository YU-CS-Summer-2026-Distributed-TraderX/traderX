package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;

/**
 * FR-DRS-36, a running feed: its stream is deleted and recreated under the same name (NATS storage lost) and grows past
 * where the feed was. Resuming at the old sequence + 1 skips the new stream's first messages. A cursor-acknowledged
 * feed (the trade store, the order view snapshot) must apply none of the new stream; a durable (the row projections,
 * idempotent) must read it from its first message. On NATS 2.10 and 2.14 (production). codeX review 2026-10-30 §6.
 */
@Tag("integration")
@Timeout(120)
class StreamRecreateIT {
  static final String SUBJECT = "/trades.0";

  static GenericContainer<?> nats(String image) {
    GenericContainer<?> c = new GenericContainer<>(image).withCommand("-js").withExposedPorts(4222);
    c.start();
    return c;
  }

  static StreamConfiguration config(String name) {
    return StreamConfiguration.builder().name(name).subjects(SUBJECT).storageType(StorageType.File).build();
  }

  static void publish(Connection nc, String prefix, int n) throws Exception {
    for (int i = 1; i <= n; i++) { nc.jetStream().publish(SUBJECT, (prefix + i).getBytes(StandardCharsets.UTF_8)); }
  }

  static void await(BooleanSupplier done, Duration max) throws InterruptedException {
    long end = System.nanoTime() + max.toNanos();
    while (!done.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(50); }
  }

  /** Runs the feed over old1..old5, recreates the stream with new1..new8, and returns what the handler was given. */
  static List<String> recreateUnder(Connection nc, boolean durable) throws Exception {
    var jsm = nc.jetStreamManagement();
    jsm.addStream(config("RECREATE"));
    List<String> got = new CopyOnWriteArrayList<>();
    java.util.function.Consumer<List<Message>> apply =
        msgs -> msgs.forEach(m -> got.add(new String(m.getData(), StandardCharsets.UTF_8)));
    Thread feed = durable
        ? JetStreamFeed.start("t", nc, "RECREATE", SUBJECT, JetStreamFeed.durable("tp-trades-p0", SUBJECT), 10,
            Duration.ofMillis(200), apply)
        : JetStreamFeed.startPipelined("t", nc, "RECREATE", SUBJECT, JetStreamFeed.fromSequence(SUBJECT, 1, 10), 10,
            Duration.ofMillis(200), apply, head -> { });
    publish(nc, "old", 5);
    await(() -> got.size() >= 5, Duration.ofSeconds(10));
    assertThat(got).as("the feed applied the original stream").containsExactly("old1", "old2", "old3", "old4", "old5");
    jsm.deleteStream("RECREATE");
    jsm.addStream(config("RECREATE"));
    publish(nc, "new", 8);
    await(() -> got.size() >= 13, Duration.ofSeconds(8));
    Thread.sleep(1000);
    feed.interrupt();
    return got;
  }

  @ParameterizedTest @ValueSource(strings = {"nats:2.10-alpine", "nats:2.14-alpine"})
  void aCursorFeedAppliesNothingOfARecreatedStream(String image) throws Exception {
    try (GenericContainer<?> c = nats(image);
         Connection nc = Nats.connect("nats://" + c.getHost() + ":" + c.getMappedPort(4222))) {
      List<String> got = recreateUnder(nc, false);
      assertThat(got).as("resuming at sequence 6 of another stream would apply new6..new8 and skip new1..new5")
          .noneMatch(s -> s.startsWith("new"));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"nats:2.10-alpine", "nats:2.14-alpine"})
  void aDurableFeedReadsARecreatedStreamFromItsStart(String image) throws Exception {
    try (GenericContainer<?> c = nats(image);
         Connection nc = Nats.connect("nats://" + c.getHost() + ":" + c.getMappedPort(4222))) {
      List<String> got = recreateUnder(nc, true);
      assertThat(got.subList(5, got.size())).as("every message of the new stream, from its first")
          .containsExactly("new1", "new2", "new3", "new4", "new5", "new6", "new7", "new8");
    }
  }
}
