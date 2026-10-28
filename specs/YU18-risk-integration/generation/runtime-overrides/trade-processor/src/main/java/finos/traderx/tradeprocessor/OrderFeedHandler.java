package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import finos.traderx.tradeprocessor.service.ProjectionWriteLock;
import io.nats.client.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Consumes order-lifecycle updates off the cluster's leader-side {@code /orders} bridge (YU13) and
 * upserts the {@code orderbook} read-model projection — the order-state sibling of
 * {@link TradeFeedHandler}. {@code onMessage} runs on the single NATS dispatcher thread, so it only
 * enqueues; a flush thread drains and persists, keeping the dispatcher unblocked exactly as the
 * trade path does.
 *
 * <p>Upsert by epoch-qualified id: an order goes NEW → PARTIALLY_FILLED → FILLED/CANCELED as updates
 * arrive, and {@code save()} on the fixed primary key overwrites the row to the latest state. NATS
 * preserves publish order, so the last write is the current state. At-least-once delivery is
 * idempotent — a replayed update rewrites the same row to the same value.
 *
 * <p><b>Rejection signal (brief 05 item 3):</b> this write path is not allowed to drop silently. A
 * row that fails to persist — the four known cases are VARCHAR truncation, a risk-gated /
 * synthetic account with no FK parent, and epoch-id collision — is counted in {@link #rejected} and
 * logged at WARN with the offending id, so a cluster-accepts / read-model-rejects divergence is
 * visible instead of a phantom missing order.
 *
 * <p><b>YU18 downstream redesign.</b> Every update is also applied to the in-memory {@link OrderView}.
 * With {@code traderx.order-feed.persist=false} the SQL {@code orderbook} rows are not written at
 * all and {@code GET /accounts/{id}/orders} answers from the view (direction a: no row per order
 * event); with {@code traderx.feed.mode=jetstream} the updates are pulled from the capture stream,
 * one thread per owned partition (direction c). A memory-only view re-fills itself on restart from
 * the last {@code traderx.order-view.replay-seconds} of the stream.
 */
public class OrderFeedHandler extends NatsJSONSubscriber<OrderUpdate> implements DisposableBean {
  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderFeedHandler.class);

  private static final long MAX_LATENCY_MILLIS = 10;
  /** YU18: updates per transaction (was 1). See OrderProjectionService.persistAll. */
  private static final int BATCH_SIZE = 1000;
  /** YU18: bounded, same reasoning as TradeFeedHandler.QUEUE_CAPACITY. */
  static final int QUEUE_CAPACITY = Integer.getInteger("traderx.order-feed.queue-capacity", 20_000);
  // 20K = 20 batches of slack. Sized against the DEFAULT heap (25% of the 1 GiB pod limit = 256 MB):
  // two 200K queues of parsed events filled it and the process still died of OOM (measured).

  private final BlockingQueue<OrderUpdate> pending = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private volatile Thread flushThread;
  private final List<Thread> feedThreads = new ArrayList<>();
  private volatile boolean running = true;

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
  /** false = the view is the read model and no orderbook row is written. */
  @Value("${traderx.order-feed.persist:true}") boolean persist = true;
  // Measured: a retained OrderUpdate costs ~850 B (strings, BigDecimals); 200K of them filled the
  // 256 MB default heap and the trade path thrashed in full GC. 50K = ~42 MB.
  @Value("${traderx.order-view.terminal-capacity:50000}") int terminalCapacity = 50_000;
  @Value("${traderx.order-view.replay-seconds:300}") long replaySeconds = 300;

  private volatile OrderView view = new OrderView(50_000);

  public OrderFeedHandler() {
    super(OrderUpdate.class);
  }

  @Autowired
  private OrderRepository orderRepository;
  @Autowired
  private finos.traderx.tradeprocessor.service.OrderProjectionService projectionService;
  @Autowired(required = false)
  private ProjectionWriteLock writeLock;

  boolean jetstream() { return "jetstream".equalsIgnoreCase(feedMode); }

  @Override
  protected boolean autoSubscribe() { return !jetstream(); }

  @Override
  public void afterPropertiesSet() throws Exception {
    view = new OrderView(terminalCapacity);
    super.afterPropertiesSet();
    if (jetstream()) {
      startJetStream();
      return;
    }
    flushThread = new Thread(this::flushLoop, "order-feed-flush");
    flushThread.setDaemon(true);
    flushThread.start();
  }

  private void startJetStream() throws Exception {
    JetStreamFeed.ensureStream(connection(), stream, streamMaxBytes, Duration.ofHours(streamMaxAgeHours));
    if (persist && writeLock != null) { writeLock.ensurePartitions(partitionCount); }
    for (int p : JetStreamFeed.owned(ownedPartitions, partitionCount)) {
      String subject = JetStreamFeed.subject("/orders", p, partitionCount);
      // A persisted projection resumes at its durable cursor; a memory-only view has nothing to
      // resume from, so it replays the recent window and rebuilds itself.
      var consumer = persist
          ? JetStreamFeed.durable("tp-orders-p" + p, subject)
          : JetStreamFeed.replayWindow(subject, Duration.ofSeconds(replaySeconds));
      feedThreads.add(JetStreamFeed.start("order-feed-p" + p, connection(), stream, subject, consumer,
          BATCH_SIZE, Duration.ofMillis(fetchWaitMillis), msgs -> onJetStreamBatch(p, msgs)));
    }
    log.info("order feed: jetstream mode, {} of {} partitions owned, persist={}", feedThreads.size(), partitionCount, persist);
  }

  void onJetStreamBatch(int partition, List<Message> msgs) {
    ProjectionWriteLock.partition(partition);
    List<OrderUpdate> batch = new ArrayList<>(msgs.size());
    for (Message m : msgs) {
      try {
        OrderUpdate u = decode(m.getData());
        if (u != null) { batch.add(u); }
      } catch (Exception x) {
        log.error("undecodable /orders message skipped: {}", x.toString());
      }
    }
    if (!batch.isEmpty()) { persistBatch(batch); }
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
  public void onMessage(Envelope<?> envelope, OrderUpdate update) {
    if (!pending.offer(update)) {
      long n = dropped.incrementAndGet();
      if (n == 1 || n % 10_000 == 0) {
        log.warn("order-feed queue full ({}): dropped={} - the orderbook read model is behind and these updates are lost", QUEUE_CAPACITY, n);
      }
    }
  }

  public long dropped() { return dropped.get(); }
  public int backlog() { return pending.size(); }
  public OrderView view() { return view; }
  public boolean persists() { return persist; }
  public String feedMode() { return jetstream() ? "jetstream" : "core"; }

  public long rejected() {
    return rejected.get();
  }

  private void flushLoop() {
    java.util.List<OrderUpdate> batch = new java.util.ArrayList<>(BATCH_SIZE);
    while (running) {
      try {
        OrderUpdate update = pending.poll(MAX_LATENCY_MILLIS, TimeUnit.MILLISECONDS);
        if (update == null) {
          continue;
        }
        batch.add(update);
        pending.drainTo(batch, BATCH_SIZE - 1);
        persistBatch(batch);
        batch.clear();
      } catch (InterruptedException x) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * The view first (it is what a reader sees when persistence is off), then one transaction for
   * the batch; if any update in it is refused, the batch rolls back and every update is retried
   * on its own, so a single conflict still costs only that update (and is still counted in
   * {@link #rejected}).
   */
  void persistBatch(java.util.List<OrderUpdate> batch) {
    for (OrderUpdate u : batch) { view.apply(u); }
    if (!persist) { return; }
    if (projectionService != null && batch.size() > 1) {
      try {
        projectionService.persistAll(batch);
        return;
      } catch (Exception x) {
        log.warn("orderbook batch of {} refused ({}); retrying individually", batch.size(), x.getMessage());
      }
    }
    for (OrderUpdate u : batch) { persist(u); }
  }

  /** Package-private so the rejection signal is unit-testable with a throwing repository. */
  void persist(OrderUpdate update) {
    try {
      if (projectionService!=null) {projectionService.persist(update);}
      else if ("legacy-unknown".equals(update.getProjectionScope())) {
        orderRepository.save(preserveTraceId(toRow(update))); // Standalone legacy test seam.
      } else {throw new IllegalStateException("managed order requires projection service");}
    } catch (Exception x) {
      long n = rejected.incrementAndGet();
      log.warn("orderbook write rejected for order {} (rejected={}): {}",
          update.getId(), n, x.getMessage());
    }
  }

  public static OrderRow toRow(OrderUpdate u) {
    OrderRow row = new OrderRow();
    row.setId(u.getId());
    row.setProjectionScope(u.getProjectionScope());row.setClusterEpoch(u.getClusterEpoch());
    row.setEventIdScheme(u.getEventIdScheme());row.setRunDescriptorHash(u.getRunDescriptorHash());
    row.setConsensusSequence(u.getConsensusSequence());row.setOutputOrdinal(u.getOutputOrdinal());
    row.setAccountId(u.getAccountId());
    row.setSecurity(u.getSecurity());
    row.setSide(u.getSide());
    row.setQuantity(u.getQuantity());
    row.setRemainingQuantity(u.getRemainingQuantity());
    row.setLimitPrice(u.getLimitPrice());
    row.setStatus(u.getStatus());
    row.setLastExecutionPrice(u.getLastExecutionPrice());
    row.setLastFillQuantity(u.getLastFillQuantity());
    row.setFilledQuantity(u.getFilledQuantity());
    row.setTraceId(u.getTraceId());
    row.setClientOrderKey(u.getClientOrderKey());
    // YU18 (FR-OT33): an untyped order is read as what the engine inferred from its price —
    // LIMIT GTC above zero, MARKET IOC otherwise — so every row names a type and a TIF.
    final boolean typed = u.getOrderType() != null;
    final boolean priced = u.getLimitPrice() != null && u.getLimitPrice().signum() > 0;
    row.setOrderType(typed ? u.getOrderType() : priced ? "LIMIT" : "MARKET");
    row.setTimeInForce(typed ? u.getTimeInForce() : priced ? "GTC" : "IOC");
    row.setStopPrice(u.getStopPrice());
    row.setDisplayQuantity(u.getDisplayQuantity());
    row.setPegReference(u.getPegReference());
    row.setPegOffset(u.getPegOffset());
    row.setPegCap(u.getPegCap());
    row.setTrailAmount(u.getTrailAmount());
    row.setTrailPercentBps(u.getTrailPercentBps());
    row.setTriggered(u.getTriggered());
    row.setSessionDate(u.getSessionDate());
    row.setSuspendReason(u.getSuspendReason());
    row.setReason(u.getReason());
    row.setCreatedAt(u.getCreatedAt() == null ? new Date() : new Date(u.getCreatedAt()));
    row.setUpdatedAt(u.getUpdatedAt() == null ? new Date() : new Date(u.getUpdatedAt()));
    return row;
  }

  /**
   * Keep the trace id a previous update already persisted.
   *
   * <p>{@link #toRow} builds a FRESH row from each update and {@code save()} writes it whole on the
   * fixed primary key, so without this the very next status update after NEW — a partial fill, a
   * cancel — silently overwrites traceid with null, and the feature undoes itself minutes after it
   * lands. The bridge stamps the id exactly once, on the order's own NEW, precisely so that a later
   * update cannot stamp a DIFFERENT order's trace here; the cost of that is that the read model,
   * not the wire, owns keeping it.
   *
   * <p>Only ever fills a blank: an incoming id always wins, so a corrected or re-published NEW is
   * not blocked by a stale row.
   */
  OrderRow preserveTraceId(OrderRow row) {
    if ((row.getTraceId() == null || row.getClientOrderKey() == null) && row.getId() != null) {
      orderRepository.findById(row.getId()).ifPresent(old -> {
        if (row.getTraceId() == null) { row.setTraceId(old.getTraceId()); }
        if (row.getClientOrderKey() == null) { row.setClientOrderKey(old.getClientOrderKey()); }   // ADR-093, same rule
      });
    }
    return row;
  }
}
