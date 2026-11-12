package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.TradeFeedHandler;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-56 (450K acceptance run 6, 2026-11-11): the ledger recon classifies EVERY side at run 6's peak side rate
 * (158,190 sides/s over 30 s). It classified one blotter page (1,000 sides) per sweep whatever was traded, and the
 * engine's live window (500,000 sides) rolled past the rest, so 58,022 of ~125M sides were ever looked at and the
 * 0 missing / 0 mismatched it reported described those alone.
 *
 * <p>The engine here is the member's own contract: a bounded window served strictly-greater-than {@code sinceSeq}, a
 * page of {@code RECON_PAGE_SIZE} unless the caller asks for more with {@code limit}. Partition 1 trades nothing, as in
 * the acceptance load. The assertion is per side, not a rate read off a clock: every side driven must end MATCHED with
 * none skipped. A recon slower than the drive loses sides to the window and fails on the count.
 *
 * <p>The suite drives 160K sides/s. The 2x measurement is {@code RECON_IT_RATE=350000}: all 3.65M sides classified in
 * 12 of 12 runs on a quiet host, and sides dropped (and counted) when another lane's kind cluster had the Mac at a load
 * average of 11. One replica's ceiling here is 330-370K sides/s, so that rate is a measurement, not a gate.
 *
 * <p>Other arms, by environment: {@code RECON_IT_SECONDS}, {@code RECON_IT_INTERVAL_MS} (the scheduler's fixed delay),
 * {@code RECON_IT_SERVER_PAGE} (the member's RECON_PAGE_SIZE), {@code RECON_IT_WINDOW}, {@code RECON_IT_PAGE_LIMIT},
 * {@code RECON_IT_MAX_RATE}. Each run prints one {@code RECON-THROUGHPUT} line.
 */
@Tag("integration")
@Testcontainers
class ReconThroughputIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withCommand("-js")
      .withExposedPorts(4222);

  private static final int PARTITIONS = 8, BATCH = 64;   // BRIDGE_BATCH_MAX's default
  /** A cold JVM is not the claim: the load starts at 50K sides/s for 3 s (run 6 ramped too), and those sides count. */
  private static final long WARM_RATE = 50_000, WARM_SECONDS = 3;
  private static final int[] ACCOUNTS = {9900002, 9900003, 9900004, 9900005, 9900006, 9900007, 9900008};

  private static long env(String name, long fallback) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? fallback : Long.parseLong(v);
  }

  private final long intervalMs = env("RECON_IT_INTERVAL_MS", 1_000);
  private final int serverPage = (int) env("RECON_IT_SERVER_PAGE", 1_000);

  private final ConcurrentSkipListMap<Long, byte[]> engine = new ConcurrentSkipListMap<>();
  private final AtomicLong fetchNanos = new AtomicLong(), fetches = new AtomicLong(), served = new AtomicLong();
  /** The projection's committed cursors are ahead of every side while this is set; at 0 for every partition while not. */
  private final AtomicBoolean projected = new AtomicBoolean(true);

  @Test void everySideIsClassifiedAtRunSixPeak() throws Exception {
    int rate = (int) env("RECON_IT_RATE", 160_000), window = (int) env("RECON_IT_WINDOW", 500_000);
    Run r = run(rate, (int) env("RECON_IT_SECONDS", 10), window, WARM_RATE * WARM_SECONDS, 0, total -> { });
    assertThat(r.driven).as("the drive reached its rate").isGreaterThan(rate * 0.98);
    assertThat(r.status.missingInProjection() + r.status.fieldMismatch()).as("no false verdict").isZero();
    assertThat(r.status.unverified()).as("sides the engine's window dropped before the recon read them").isZero();
    assertThat(r.status.matched()).as("every side driven at %d/s through a %d-side window is classified", rate, window)
        .isEqualTo(r.total);
  }

  /**
   * A side waits for its partition's snapshot (every 5 s in production) while the engine's window (3.2 s of run 6's
   * peak) rolls past it. The recon holds what it has read, so the wait costs nothing: here the projection commits
   * nothing for the first half of the load, nearly two windows' worth, and every side still ends MATCHED.
   */
  @Test void sidesWaitingForTheirSnapshotOutliveTheEnginesWindow() throws Exception {
    projected.set(false);
    // 15K sides/s into a 40K-side window: a 1 s interval's trading fits the window, five seconds' holding does not.
    Run r = run(15_000, 10, 40_000, 0, 0, total -> projected.set(true));
    assertThat(r.maxHeld).as("sides held while the projection was behind: more than the engine's window").isGreaterThan(40_000);
    assertThat(r.status.unverified()).isZero();
    assertThat(r.status.missingInProjection() + r.status.fieldMismatch()).isZero();
    assertThat(r.status.matched()).isEqualTo(r.total);
  }

  /** The holding is bounded. Past the bound the recon stops reading, the window rolls on, and the loss is counted. */
  @Test void pastItsBoundTheReconStopsReadingAndCountsWhatTheWindowDropped() throws Exception {
    projected.set(false);
    Run r = run(20_000, 6, 20_000, 0, 10_000, total -> projected.set(true));
    assertThat(r.maxHeld).as("held sides: the bound, and the page that crossed it").isLessThanOrEqualTo(10_000 + 10_000);
    assertThat(r.status.unverified()).as("sides the window dropped while the recon could not hold more").isPositive();
    assertThat(r.status.missingInProjection() + r.status.fieldMismatch()).as("a dropped side is not a verdict").isZero();
    assertThat(r.status.matched() + r.status.unverified()).as("every side is either classified or counted unverified")
        .isEqualTo(r.total);
  }

  private record Run(long total, double driven, ReconciliationService.StatusSnapshot status, long maxHeld) { }

  /** Drive {@code warm + rate x seconds} sides and sweep at the interval; {@code atHalf} runs when half are driven. */
  private Run run(int rate, int seconds, int window, long warm, int bufferMax, java.util.function.LongConsumer atHalf) throws Exception {
    engine.clear();
    HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    http.setExecutor(Executors.newFixedThreadPool(2));
    http.createContext("/recon/trades/blotter", ex -> {
      long t0 = System.nanoTime();
      Map<String, String> q = new HashMap<>();
      for (String kv : ex.getRequestURI().getQuery().split("&")) { q.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1)); }
      // The member's rule: its page size unless the caller asks, and never more than 50 pages' worth.
      int limit = q.containsKey("limit") ? Math.min(Integer.parseInt(q.get("limit")), 50 * serverPage) : serverPage;
      ByteArrayOutputStream body = new ByteArrayOutputStream(limit * 160);
      body.write('[');
      int n = 0;
      for (byte[] e : engine.tailMap(Long.parseLong(q.get("sinceSeq")), false).values()) {
        if (n == limit) break;
        if (n++ > 0) body.write(',');
        body.write(e, 0, e.length);
      }
      body.write(']');
      ex.sendResponseHeaders(200, body.size());
      body.writeTo(ex.getResponseBody());
      ex.close();
      served.addAndGet(n); fetches.incrementAndGet(); fetchNanos.addAndGet(System.nanoTime() - t0);
    });
    http.start();
    try (Connection nc = Nats.connect(new io.nats.client.Options.Builder()   // the feed's connection, as NatsJSONSubscriber builds it
        .server("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))
        .errorListener(finos.traderx.messaging.nats.NatsJSONSubscriber.quietNoWaitPulls()).build())) {
      String[] streams = new String[PARTITIONS];
      for (int p = 0; p < PARTITIONS; p++) {
        streams[p] = "TRADERX_FEED_P" + p;
        if (nc.jetStreamManagement().getStreamNames().contains(streams[p])) { nc.jetStreamManagement().deleteStream(streams[p]); }
        nc.jetStreamManagement().addStream(StreamConfiguration.builder().name(streams[p]).subjects("/trades." + p)
            .storageType(StorageType.Memory).maxBytes(192L << 20).discardPolicy(DiscardPolicy.Old).build());
      }
      TradeFeedHandler feed = mock(TradeFeedHandler.class);
      when(feed.ledgerMode()).thenReturn(true);
      when(feed.partitionCount()).thenReturn(PARTITIONS);
      when(feed.natsConnection()).thenReturn(nc);
      when(feed.streamName(anyInt())).thenAnswer(i -> streams[i.getArgument(0, Integer.class)]);
      Map<Integer, Long> ahead = new HashMap<>();
      for (int p = 0; p < PARTITIONS; p++) { ahead.put(p, Long.MAX_VALUE); }
      TradeSnapshotStore store = mock(TradeSnapshotStore.class);
      when(store.cursors(any())).thenAnswer(i -> projected.get() ? ahead : Map.of());

      ReconciliationService recon = new ReconciliationService(mock(TradeRepository.class),
          "http://localhost:" + http.getAddress().getPort(), "dev-recon-control", new SimpleMeterRegistry());
      recon.subscribers(List.of(feed));
      recon.snapshotStore(store);
      recon.pageLimit((int) env("RECON_IT_PAGE_LIMIT", 10_000));
      recon.maxSidesPerSecond((int) env("RECON_IT_MAX_RATE", 500_000));
      recon.sweepBudgetMs(env("RECON_IT_BUDGET_MS", 30_000));
      recon.bufferMaxSides(bufferMax);

      long total = warm + (long) rate * seconds;
      AtomicBoolean driving = new AtomicBoolean(true);
      AtomicLong driveNanos = new AtomicLong(), maxHeld = new AtomicLong();
      Thread driver = new Thread(() -> drive(nc, rate, window, warm, total, driveNanos, atHalf), "recon-it-driver");
      // The scheduler's fixed delay: a sweep, then the interval.
      StringBuilder perSweep = new StringBuilder();
      AtomicLong sweeps = new AtomicLong(), sweepNanos = new AtomicLong();
      Thread sweeper = new Thread(() -> {
        long last = 0;
        while (driving.get()) {
          long t0 = System.nanoTime();
          recon.sweep();
          sweepNanos.addAndGet(System.nanoTime() - t0);
          var now = recon.status();
          maxHeld.accumulateAndGet(now.held(), Math::max);
          if (sweeps.incrementAndGet() <= 12) { perSweep.append(now.matched() - last).append(' '); }
          last = now.matched();
          LockSupport.parkNanos(intervalMs * 1_000_000L);
        }
      }, "recon-it-sweeper");
      driver.start(); sweeper.start();
      driver.join();
      long matchedAtDriveEnd = recon.status().matched();
      double driveSeconds = driveNanos.get() / 1e9, driven = (total - warm) / driveSeconds;
      // What is still in flight when the drive stops may take one more interval and one more read.
      long deadline = System.nanoTime() + (intervalMs + 5_000) * 1_000_000L;
      while (recon.status().matched() + recon.status().unverified() < total && System.nanoTime() < deadline) { Thread.sleep(50); }
      driving.set(false); sweeper.join();

      var s = recon.status();
      System.out.printf("RECON-THROUGHPUT rate=%d seconds=%d intervalMs=%d serverPage=%d window=%d | drove %d sides, the last %d at %.0f/s"
              + " | classified %d by drive end | final matched=%d missing=%d mismatch=%d cursor=%d unverified=%d unclassified=%d maxHeld=%d"
              + " | sweeps=%d busy=%.2fs first sweeps' matched: %s| fetches=%d served=%d fetchTime=%.2fs%n",
          rate, seconds, intervalMs, serverPage, window, total, total - warm, driven, matchedAtDriveEnd, s.matched(), s.missingInProjection(), s.fieldMismatch(), s.cursor(), s.unverified(),
          total - s.matched() - s.missingInProjection() - s.fieldMismatch(), maxHeld.get(), sweeps.get(), sweepNanos.get() / 1e9, perSweep,
          fetches.get(), served.get(), fetchNanos.get() / 1e9);
      return new Run(total, driven, s, maxHeld.get());
    } finally {
      http.stop(0);
    }
  }

  /** The engine and its bridges: each side enters the bounded window and its partition's stream, 64 to a message. */
  private void drive(Connection nc, int rate, int window, long warm, long total, AtomicLong driveNanos, java.util.function.LongConsumer atHalf) {
    StringBuilder[] batch = new StringBuilder[PARTITIONS];
    int[] held = new int[PARTITIONS];
    for (int p = 0; p < PARTITIONS; p++) { batch[p] = new StringBuilder(BATCH * 280); }
    long start = System.nanoTime(), rateStart = start;
    for (long seq = 1; seq <= total; seq++) {
      int account = ACCOUNTS[(int) (seq % ACCOUNTS.length)];
      int p = account % PARTITIONS;
      String id = "1-" + seq + "-B";
      String fields = "\"accountId\":" + account + ",\"security\":\"AAPL\",\"side\":\"Buy\",\"quantity\":100,\"price\":241.30";
      if (held[p]++ > 0) { batch[p].append('\n'); }
      // The trade bridge's record, field for field (TradeNatsPublisher), about 260 B.
      batch[p].append("{\"topic\":\"/trades.").append(p).append("\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":")
          .append(1794452000000L + seq / 350).append(",\"payload\":{\"id\":\"").append(id).append("\",\"state\":\"New\",")
          .append(fields).append(",\"bookingSource\":\"MATCH\",\"sourceOrderId\":\"1-").append(4294967297L + seq).append("\"}}");
      if (held[p] == BATCH) { nc.publish("/trades." + p, batch[p].toString().getBytes(StandardCharsets.UTF_8)); batch[p].setLength(0); held[p] = 0; }
      engine.put(seq, ("{\"id\":\"" + id + "\",\"tradeSeq\":" + seq + "," + fields + ",\"execTimeMillis\":"
          + System.currentTimeMillis() + "}").getBytes(StandardCharsets.UTF_8));
      if (engine.size() > window) { engine.pollFirstEntry(); }
      if (seq == warm) { rateStart = System.nanoTime(); }
      if (seq == total / 2) { atHalf.accept(total); }
      if ((seq & 1023) == 0) {   // pace in 1,024-side steps
        long due = seq <= warm ? start + seq * 1_000_000_000L / WARM_RATE : rateStart + (seq - warm) * 1_000_000_000L / rate;
        long wait = due - System.nanoTime();
        if (wait > 0) { LockSupport.parkNanos(wait); }
      }
    }
    for (int p = 0; p < PARTITIONS; p++) {
      if (held[p] > 0) { nc.publish("/trades." + p, batch[p].toString().getBytes(StandardCharsets.UTF_8)); }
    }
    driveNanos.set(System.nanoTime() - rateStart);
  }
}
