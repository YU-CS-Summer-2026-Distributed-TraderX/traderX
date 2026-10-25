package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.service.TradeService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

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
   * ponytail: in-process bound only; a replayable feed (archive replay / JetStream) is the fix
   * that turns loss into lag.
   */
  static final int QUEUE_CAPACITY = Integer.getInteger("traderx.trade-feed.queue-capacity", 20_000);
  // 20K = 20 batches of slack. Sized against the DEFAULT heap (25% of the 1 GiB pod limit = 256 MB):
  // two 200K queues of parsed events filled it and the process still died of OOM (measured).

  private final BlockingQueue<TradeOrder> pending = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
  private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
  private final TradeSeqGaps gaps = new TradeSeqGaps();
  private volatile Thread flushThread;
  private volatile boolean running = true;

  public TradeFeedHandler() {
    super(TradeOrder.class);
  }

  @Autowired
  private TradeService tradeService;

  @Override
  public void afterPropertiesSet() throws Exception {
    super.afterPropertiesSet();
    flushThread = new Thread(this::flushLoop, "trade-feed-batch-flush");
    flushThread.setDaemon(true);
    flushThread.start();
  }

  @Override
  public void destroy() {
    running = false;
    if (flushThread != null) {
      flushThread.interrupt();
    }
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
  public TradeSeqGaps gaps() { return gaps; }
  public int backlog() { return pending.size(); }

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
    } catch (Exception x) {
      log.error("Batch trade processing failed for {} trades; retrying individually", batch.size(), x);
      // Fall back to per-trade processing so one bad order doesn't silently drop the whole batch.
      for (TradeOrder order : batch) {
        try {
          tradeService.processTrade(order);
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
