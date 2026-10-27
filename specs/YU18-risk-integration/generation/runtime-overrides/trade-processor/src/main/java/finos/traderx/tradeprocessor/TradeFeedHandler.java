package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.service.ProjectionWriteLock;
import finos.traderx.tradeprocessor.service.TradeService;
import io.nats.client.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Consumes booked trades off NATS and hands them to {@link TradeService} in batches rather than
 * one DB transaction per trade. Under load, each trade previously cost its own round trip to
 * MariaDB (SELECT position + INSERT trade + UPDATE position); batching collapses that to a
 * handful of round trips per batch via {@code saveAll()} + Hibernate JDBC batching (see
 * {@code spring.jpa.properties.hibernate.jdbc.batch_size} in application.properties).
 *
 * <p>{@code onMessage} is called on the single NATS dispatcher thread, so it never blocks on the
 * queue — it just offers and returns. A dedicated flush thread drains the queue: eagerly once
 * {@link #BATCH_SIZE} trades are queued, or at worst every {@link #MAX_LATENCY_MILLIS} so trades
 * never sit indefinitely under low load.
 *
 * <p><b>YU18 downstream redesign, {@code traderx.feed.mode=jetstream}:</b> no core subscription and
 * no in-process queue. One {@link JetStreamFeed} pull thread per OWNED partition fetches batches
 * from the capture stream's durable consumer and acks them after the SQL commit, so a slow or
 * restarting projection lags and catches up instead of dropping. See JetStreamFeed.
 */
public class TradeFeedHandler extends NatsJSONSubscriber<TradeOrder> implements DisposableBean {
  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradeFeedHandler.class);

  // YU18: 1000 (was 100) - with the per-row registry reads gone, a batch costs a fixed handful of
  // round trips, so the batch size is the throughput lever. Matches hibernate.jdbc.batch_size.
  private static final int BATCH_SIZE = 1000;
  private static final long MAX_LATENCY_MILLIS = 10;

  /**
   * YU18: BOUNDED. The unbounded queue turned a slow database into an OutOfMemoryError (measured:
   * a 256 MB heap died 11 s into a 100K msg/s flood) and the restart lost the whole backlog. Core
   * NATS has no replay either way, so a full queue drops - but it drops COUNTED, the process stays
   * up, and the gap counter below names how many trade sides never reached the projection.
   * The jetstream feed mode is the fix that turns that loss into lag.
   */
  static final int QUEUE_CAPACITY = Integer.getInteger("traderx.trade-feed.queue-capacity", 20_000);
  // 20K = 20 batches of slack. Sized against the DEFAULT heap (25% of the 1 GiB pod limit = 256 MB):
  // two 200K queues of parsed events filled it and the process still died of OOM (measured).

  private final BlockingQueue<TradeOrder> pending = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong applied = new AtomicLong();
  private final TradeSeqGaps gaps = new TradeSeqGaps();
  private volatile Thread flushThread;
  private final List<Thread> feedThreads = new ArrayList<>();
  private volatile boolean running = true;

  // Feed wiring (application.properties); Java defaults keep the plain-constructed test path on core.
  @Value("${traderx.feed.mode:core}") String feedMode = "core";
  @Value("${traderx.feed.partition-count:1}") int partitionCount = 1;
  @Value("${traderx.feed.owned:all}") String ownedPartitions = "all";
  @Value("${traderx.feed.stream:TRADERX_FEED}") String stream = "TRADERX_FEED";
  @Value("${traderx.feed.stream-max-bytes:8589934592}") long streamMaxBytes = 8L << 30;
  @Value("${traderx.feed.stream-max-age-hours:24}") long streamMaxAgeHours = 24;
  // How long a pull waits for a FULL batch. Measured: at 10 ms the fetch returned partial batches
  // and the per-batch SQL costs (lock, prefetch, counter) amortized over ~200 rows instead of 1000,
  // tripling CPU per trade row. Latency under low load is bounded by this value.
  @Value("${traderx.feed.fetch-wait-ms:200}") long fetchWaitMillis = 200;

  public TradeFeedHandler() {
    super(TradeOrder.class);
  }

  @Autowired
  private TradeService tradeService;
  @Autowired(required = false)
  private ProjectionWriteLock writeLock;

  boolean jetstream() { return "jetstream".equalsIgnoreCase(feedMode); }

  @Override
  protected boolean autoSubscribe() { return !jetstream(); }

  @Override
  public void afterPropertiesSet() throws Exception {
    super.afterPropertiesSet();
    if (jetstream()) {
      startJetStream();
      return;
    }
    flushThread = new Thread(this::flushLoop, "trade-feed-batch-flush");
    flushThread.setDaemon(true);
    flushThread.start();
  }

  private void startJetStream() throws Exception {
    JetStreamFeed.ensureStream(connection(), stream, streamMaxBytes, Duration.ofHours(streamMaxAgeHours));
    if (writeLock != null) { writeLock.ensurePartitions(partitionCount); }
    for (int p : JetStreamFeed.owned(ownedPartitions, partitionCount)) {
      String subject = JetStreamFeed.subject("/trades", p, partitionCount);
      feedThreads.add(JetStreamFeed.start("trade-feed-p" + p, connection(), stream, subject,
          JetStreamFeed.durable("tp-trades-p" + p, subject), BATCH_SIZE, Duration.ofMillis(fetchWaitMillis),
          msgs -> onJetStreamBatch(p, msgs)));
    }
    log.info("trade feed: jetstream mode, {} of {} partitions owned", feedThreads.size(), partitionCount);
  }

  /** One fetched batch: decode on this partition's own thread, project, count. */
  void onJetStreamBatch(int partition, List<Message> msgs) {
    ProjectionWriteLock.partition(partition);
    List<TradeOrder> batch = new ArrayList<>(msgs.size());
    for (Message m : msgs) {
      try {
        TradeOrder t = decode(m.getData());
        if (t != null) { batch.add(t); }
      } catch (Exception x) {
        log.error("undecodable /trades message skipped: {}", x.toString());
      }
    }
    if (batch.isEmpty()) { return; }
    // The gap counter needs every tradeSeq in order; with partitions each thread sees a subsequence.
    if (partitionCount <= 1) { for (TradeOrder order : batch) { gaps.observe(order.getId()); } }
    processBatch(batch);
  }

  @Override
  public void destroy() {
    running = false;
    if (flushThread != null) {
      flushThread.interrupt();
    }
    for (Thread t : feedThreads) { t.interrupt(); }
  }

  @Override
  public void onMessage(Envelope<?> envelope, TradeOrder order) {
    if (!pending.offer(order)) {
      long n = dropped.incrementAndGet();
      if (n == 1 || n % 10_000 == 0) {
        log.warn("trade-feed queue full ({}): dropped={} - the projection is behind the cluster and these trade sides are lost", QUEUE_CAPACITY, n);
      }
    }
  }

  public long dropped() { return dropped.get(); }
  /** Trade sides handed to the projection (both modes), for a rate that does not need SQL. */
  public long applied() { return applied.get(); }
  public TradeSeqGaps gaps() { return gaps; }
  public int backlog() { return pending.size(); }
  public String feedMode() { return jetstream() ? "jetstream" : "core"; }

  private void flushLoop() {
    List<TradeOrder> batch = new ArrayList<>(BATCH_SIZE);
    while (running) {
      try {
        TradeOrder first = pending.poll(MAX_LATENCY_MILLIS, TimeUnit.MILLISECONDS);
        if (first == null) {
          continue;
        }
        batch.add(first);
        pending.drainTo(batch, BATCH_SIZE - 1);
        for (TradeOrder order : batch) { gaps.observe(order.getId()); }
        processBatch(batch);
        batch.clear();
      } catch (InterruptedException x) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void processBatch(List<TradeOrder> batch) {
    try {
      tradeService.processTrades(batch);
      applied.addAndGet(batch.size());
    } catch (Exception x) {
      log.error("Batch trade processing failed for {} trades; retrying individually", batch.size(), x);
      // Fall back to per-trade processing so one bad order doesn't silently drop the whole batch.
      for (TradeOrder order : batch) {
        try {
          tradeService.processTrade(order);
          applied.incrementAndGet();
        } catch (Exception individualFailure) {
          log.error("Error processing trade order {}", order, individualFailure);
        }
      }
    }
  }

  /**
   * Contiguity of the engine's per-leg trade counter, as it reaches the projection. Every booked
   * leg takes the next {@code tradeSeq} ({@code ++tradeCounter}) and the leader's bridge publishes
   * them in that order, so a jump forward is exactly the number of trade sides lost upstream of
   * the flush thread: bridge queue drops, NATS slow-consumer cuts, a failover's unpublished tail,
   * this process's own queue drops. Ids are {@code [e1-<epoch>-]<tradeSeq>-B|S}.
   * ponytail: in memory since process start - a gap that spans a restart shows as a fresh baseline;
   * persist the watermark in the batch transaction to close that.
   */
  public static final class TradeSeqGaps {
    private long last = -1;
    private long missing;
    private long firstSeen = -1;

    synchronized void observe(String id) {
      long seq = seqOf(id);
      if (seq <= 0) { return; }
      if (last < 0) { firstSeen = seq; last = seq; return; }
      if (seq > last + 1) {
        missing += seq - last - 1;
        log.warn("trade-feed GAP tradeSeq {} -> {}: {} trade sides never reached the projection (total missing={})",
            last, seq, seq - last - 1, missing);
      }
      if (seq > last) { last = seq; }
    }

    static long seqOf(String id) {
      if (id == null || id.length() < 3 || id.charAt(id.length() - 2) != '-') { return -1; }
      int start = id.lastIndexOf('-', id.length() - 3) + 1;
      try { return Long.parseLong(id.substring(start, id.length() - 2)); } catch (NumberFormatException x) { return -1; }
    }

    public synchronized long missing() { return missing; }
    public synchronized long last() { return last; }
    public synchronized long firstSeen() { return firstSeen; }
  }
}
