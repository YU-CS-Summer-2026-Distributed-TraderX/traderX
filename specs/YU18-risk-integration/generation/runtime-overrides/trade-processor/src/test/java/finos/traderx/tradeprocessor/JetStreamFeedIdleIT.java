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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-58's guard on the feed side: the idle advance should never pass a message the feed is handed afterwards (a
 * cursor-acknowledged view used to ignore such a message as a replay). GKE 2026-11-12 lost three consecutive trade
 * messages of partition 6 that way, or by a delivery the server never made: which was not established, and this test
 * did NOT reproduce it on one NATS 2.14 server (three runs, bursts of 20 to 33,000 messages; a direct probe read the
 * stream's stored count and then the consumer's pending 51,550 times under a publish flood and never found pending
 * behind). It stays as the check that the idle rule holds here; TradeViewTest holds what happens when it does not.
 *
 * <p>The stream carries two subjects, as a feed partition does (/trades.p and /orders.p), and is published in bursts
 * with silences between them, so empty fetches and their idle checks land on the edges of bursts. The view here is
 * the cursor rule alone: a message at or below the cursor is counted as passed, never applied.
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class JetStreamFeedIdleIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
      .withExposedPorts(4222);

  @Test
  void anIdleAdvanceNeverPassesAMessageDeliveredAfterIt() throws Exception {
    Duration every = JetStreamFeed.IDLE_EVERY;
    JetStreamFeed.IDLE_EVERY = Duration.ZERO;   // an idle check after every empty fetch: the window, as often as possible
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
         Connection pub = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("FEED_IDLE").subjects("/trades.0", "/orders.0")
          .storageType(StorageType.File).build());
      AtomicLong cursor = new AtomicLong(), applied = new AtomicLong(), idles = new AtomicLong(), advances = new AtomicLong();
      ConcurrentLinkedQueue<String> passed = new ConcurrentLinkedQueue<>();
      // Both run on the pipelined feed's apply thread, in fetch order.
      Thread feed = JetStreamFeed.startPipelined("idle-it", nc, "FEED_IDLE", "/trades.0",
          JetStreamFeed.fromSequence("/trades.0", 1, 1000), 1000, Duration.ofMillis(20),
          (List<Message> msgs) -> {
            for (Message m : msgs) {
              long seq = m.metaData().streamSequence();
              if (seq <= cursor.get()) { passed.add(seq + " (cursor " + cursor.get() + ")"); continue; }
              cursor.set(seq);
              applied.incrementAndGet();
            }
          },
          head -> { idles.incrementAndGet(); if (head > cursor.get()) { cursor.set(head); advances.incrementAndGet(); } });
      long trades = 0;
      byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
      java.util.Random r = new java.util.Random(7);
      long end = System.nanoTime() + Duration.ofSeconds(Long.getLong("idleItSeconds", 20)).toNanos();
      while (System.nanoTime() < end && passed.isEmpty()) {
        int burst = Integer.getInteger("idleItBurst", 200) / 10 + r.nextInt(Integer.getInteger("idleItBurst", 200));
        for (int i = 0; i < burst; i++) {   // core publishes, as the bridge's: stored asynchronously, as fast as they go
          boolean trade = r.nextInt(3) == 0;
          pub.publish(trade ? "/trades.0" : "/orders.0", body);
          if (trade) { trades++; }
        }
        pub.flush(Duration.ofSeconds(2));
        Thread.sleep(15 + r.nextInt(60));   // around the 20 ms fetch wait: empty fetches at the edges of bursts
      }
      long want = trades;
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (applied.get() + passed.size() < want && System.nanoTime() < deadline) { Thread.sleep(50); }
      feed.interrupt();
      assertThat(passed).as("messages delivered at or below an idle-advanced cursor (%d trade messages published, %d applied, %d idle checks, %d advances)", want, applied.get(), idles.get(), advances.get()).isEmpty();
      assertThat(advances.get()).as("the idle check moved the cursor (of %d checks): else this proves nothing", idles.get()).isGreaterThan(50);
      assertThat(applied.get()).as("every trade message applied").isEqualTo(want);
    } finally {
      JetStreamFeed.IDLE_EVERY = every;
    }
  }
}
