package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.Subscription;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-33: a cumulative ack (AckPolicy.All, ack the last of each batch) must never retire a message this process
 * was delivered but did not apply. The server redelivers such a message only after ackWait (60 s); the next batch's
 * ack covers it first. Two ways to get there, both on a real JetStream:
 * <ul>
 *   <li>kill -9: lifetime A is delivered a batch and dies before applying it; lifetime B resumes the durable;</li>
 *   <li>a delivery that never reaches the feed (a pull answered over a dead connection), the feed still running.</li>
 * </ul>
 * Each must end with every stored message applied.
 */
@Tag("integration")
@Testcontainers
@Timeout(120)
class JetStreamFeedAckGapIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withCommand("-js")
      .withExposedPorts(4222);
  private static final String SUBJECT = "/trades.0";

  private static Connection connect() throws Exception {
    return Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
  }

  private static void stream(Connection nc, String name) throws Exception {
    for (String old : nc.jetStreamManagement().getStreamNames()) { nc.jetStreamManagement().deleteStream(old); }
    nc.jetStreamManagement().addStream(StreamConfiguration.builder().name(name).subjects(SUBJECT)
        .storageType(StorageType.File).build());
  }

  private static void publish(Connection nc, int from, int to) throws Exception {
    for (int i = from; i <= to; i++) { nc.jetStream().publish(SUBJECT, ("m" + i).getBytes(StandardCharsets.UTF_8)); }
  }

  private static Set<Long> range(long from, long to) {
    Set<Long> s = new java.util.TreeSet<>();
    LongStream.rangeClosed(from, to).forEach(s::add);
    return s;
  }

  private static void await(BooleanSupplier done, Duration max) throws InterruptedException {
    long end = System.nanoTime() + max.toNanos();
    while (!done.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(50); }
  }

  /** Delivers up to {@code n} messages of {@code consumer} to a reply subject nobody applies: a lost pull response. */
  private static int steal(Connection nc, String stream, String consumer, int n) throws Exception {
    Subscription inbox = nc.subscribe(nc.createInbox());
    nc.publish("$JS.API.CONSUMER.MSG.NEXT." + stream + "." + consumer, inbox.getSubject(),
        ("{\"batch\":" + n + ",\"expires\":5000000000}").getBytes(StandardCharsets.UTF_8));
    int got = 0;
    for (Message m; got < n && (m = inbox.nextMessage(Duration.ofSeconds(6))) != null; ) {
      if (m.getData().length > 0) { got++; }
    }
    inbox.unsubscribe();
    return got;
  }

  @Test void aBatchDeliveredToAKilledProcessIsAppliedByTheNextOne() throws Exception {
    try (Connection nc = connect()) {
      stream(nc, "KILL");
      publish(nc, 1, 150);
      // Lifetime A: applies and acks 1..50, is delivered 51..100, dies (no ack, no unsubscribe).
      Connection a = connect();
      JetStreamSubscription sub = a.jetStream().subscribe(SUBJECT, PullSubscribeOptions.builder().stream("KILL")
          .configuration(JetStreamFeed.durable("tp-trades-p0", SUBJECT)).build());
      List<Message> first = sub.fetch(50, Duration.ofSeconds(2));
      first.get(first.size() - 1).ack();
      assertThat(sub.fetch(50, Duration.ofSeconds(2))).hasSize(50);
      a.flush(Duration.ofSeconds(2));
      a.close();
      // Lifetime B resumes the durable.
      Set<Long> applied = new ConcurrentSkipListSet<>();
      Thread feed = JetStreamFeed.start("t", nc, "KILL", SUBJECT, JetStreamFeed.durable("tp-trades-p0", SUBJECT), 50,
          Duration.ofMillis(200), msgs -> msgs.forEach(m -> applied.add(m.metaData().streamSequence())));
      await(() -> applied.containsAll(range(51, 150)), Duration.ofSeconds(15));
      feed.interrupt();
      assertThat(applied).as("lifetime B applies everything lifetime A did not").containsAll(range(51, 150));
    }
  }

  @Test void aLostDeliveryIsAppliedByTheRunningDurableFeed() throws Exception {
    lostDelivery("DURABLE", false);
  }

  @Test void aLostDeliveryIsAppliedByTheRunningPipelinedFeed() throws Exception {
    lostDelivery("PIPE", true);
  }

  private void lostDelivery(String stream, boolean pipelined) throws Exception {
    try (Connection nc = connect(); Connection thief = connect()) {
      stream(nc, stream);
      Set<Long> applied = new ConcurrentSkipListSet<>();
      var consumer = pipelined ? JetStreamFeed.fromSequence(SUBJECT, 1, 10) : JetStreamFeed.durable("tp-trades-p0", SUBJECT);
      java.util.function.Consumer<List<Message>> apply = msgs -> msgs.forEach(m -> applied.add(m.metaData().streamSequence()));
      Thread feed = pipelined
          ? JetStreamFeed.startPipelined("t", nc, stream, SUBJECT, consumer, 10, Duration.ofMillis(200), apply, head -> { })
          : JetStreamFeed.start("t", nc, stream, SUBJECT, consumer, 10, Duration.ofMillis(200), apply);
      await(() -> names(nc, stream).size() == 1, Duration.ofSeconds(10));
      String name = names(nc, stream).get(0);
      publish(nc, 1, 50);
      await(() -> applied.size() == 50, Duration.ofSeconds(10));
      // 20 of the next 50 go to a pull whose response never reaches the feed; 100 more make the feed ack past them.
      Thread stealer = new Thread(() -> { try { steal(thief, stream, name, 20); } catch (Exception x) { throw new IllegalStateException(x); } });
      stealer.start();
      Thread.sleep(300);
      publish(nc, 51, 100);
      stealer.join();
      publish(nc, 101, 200);
      await(() -> applied.containsAll(range(1, 200)), Duration.ofSeconds(15));
      feed.interrupt();
      assertThat(applied).as("every stored message is applied").containsAll(range(1, 200));
    }
  }

  /**
   * FR-DRS-35: a batch whose apply fails with a store failure is a barrier in the pipelined feed. It is applied again
   * until it succeeds, and nothing after it is applied first. The failure outlasts the ephemeral consumer's
   * inactive threshold (the fetch thread is held while the apply thread retries), so the feed must also come back
   * on a consumer the server deleted. The handler applies like the trade store: a seq at or below its cursor is a replay.
   */
  @Test void aStoreFailureIsABarrierInThePipelinedFeed() throws Exception {
    try (Connection nc = connect()) {
      stream(nc, "BARRIER");
      List<Long> applied = new java.util.concurrent.CopyOnWriteArrayList<>();
      long[] cursor = {0};
      long failUntil = System.currentTimeMillis() + 4000;
      var consumer = io.nats.client.api.ConsumerConfiguration.builder(JetStreamFeed.fromSequence(SUBJECT, 1, 10))
          .inactiveThreshold(Duration.ofSeconds(1)).build();
      Thread feed = JetStreamFeed.startPipelined("t", nc, "BARRIER", SUBJECT, consumer, 10, Duration.ofMillis(200), msgs -> {
        for (Message m : msgs) {
          long seq = m.metaData().streamSequence();
          if (seq <= cursor[0]) { continue; }
          if (seq == 25 && System.currentTimeMillis() < failUntil) {
            throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
          }
          applied.add(seq);
          cursor[0] = seq;
        }
      }, head -> { });
      publish(nc, 1, 100);
      await(() -> applied.size() >= 100, Duration.ofSeconds(30));
      publish(nc, 101, 150);
      await(() -> applied.size() >= 150, Duration.ofSeconds(30));
      feed.interrupt();
      assertThat(applied).as("every message applied, in order, none skipped").containsExactlyElementsOf(range(1, 150));
    }
  }

  private static List<String> names(Connection nc, String stream) {
    try { return nc.jetStreamManagement().getConsumerNames(stream); } catch (Exception x) { return List.of(); }
  }
}
