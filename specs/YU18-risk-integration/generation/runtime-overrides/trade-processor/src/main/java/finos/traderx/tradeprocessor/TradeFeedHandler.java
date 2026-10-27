package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.service.ProjectionWriteLock;
import finos.traderx.tradeprocessor.service.RunRegistry;
import finos.traderx.tradeprocessor.service.TradeSnapshotStore;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
  // YU18 trade store (docs/engineering/downstream-at-400k.md §7): "snapshot" books trades in memory
  // (TradeView) and writes SQL every snapshotMillis; "rows" is the row-per-trade path.
  @Value("${traderx.trade-projection.mode:rows}") String projectionMode = "rows";
  @Value("${traderx.trade-projection.snapshot-ms:5000}") long snapshotMillis = 5000;
  @Value("${traderx.trade-projection.blotter-depth:100}") int blotterDepth = 100;
  private final Map<Integer, TradeView> views = new ConcurrentHashMap<>();
  private final AtomicLong snapshots = new AtomicLong();
  private final AtomicLong snapshotRows = new AtomicLong();
  private final AtomicLong snapshotFailures = new AtomicLong();
  // Row projection on the jetstream feed: per owned partition, the latest instant at which every side the
  // stream held had committed to SQL. The recon needs it after an outage, while the replay is still running.
  private final Map<Integer, Long> caughtUpAt = new ConcurrentHashMap<>();
  private volatile String snapshotScope;

  public TradeFeedHandler() {
    super(TradeOrder.class);
  }

  @Autowired
  private TradeService tradeService;
  @Autowired(required = false)
  private ProjectionWriteLock writeLock;
  @Autowired(required = false)
  private TradeSnapshotStore snapshotStore;
  @Autowired(required = false)
  private RunRegistry runRegistry;
  @Autowired(required = false)
  private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired(required = false)
  private org.springframework.transaction.support.TransactionTemplate transactions;

  boolean jetstream() { return "jetstream".equalsIgnoreCase(feedMode); }
  boolean snapshotMode() { return "snapshot".equalsIgnoreCase(projectionMode); }

  @Override
  protected boolean autoSubscribe() { return !jetstream(); }

  @Override
  public void afterPropertiesSet() throws Exception {
    super.afterPropertiesSet();
    // Row projection: the gap counter's watermark survives a restart (TradeSeqGaps). The snapshot
    // projection replays from its own stream cursor instead.
    if (jdbc != null && !snapshotMode() && partitionCount <= 1) {
      gaps.persistIn(
          epoch -> jdbc.query("SELECT stream_seq FROM projection_cursor WHERE projection_scope=? AND partition_id=0",
              (rs, i) -> rs.getLong(1), TradeSeqGaps.SCOPE + epoch).stream().findFirst().orElse(null),
          (epoch, seq) -> jdbc.update("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq)"
              + " VALUES (?,0,?,0) ON DUPLICATE KEY UPDATE stream_seq=GREATEST(stream_seq,VALUES(stream_seq))",
              TradeSeqGaps.SCOPE + epoch, seq));
    }
    if (jetstream()) {
      try {
        startJetStream();
      } catch (Exception refused) {
        // A refusal (RETENTION_GAP, STREAM_RESET) must stop the process. Measured: after the context fails,
        // the other beans' NATS connections (non-daemon threads) keep the JVM up as a never-ready zombie.
        // Exit from a daemon thread once Spring has finished failing (System.exit inside refresh can deadlock).
        Thread exit = new Thread(() -> {
          try { Thread.sleep(5000); } catch (InterruptedException ignore) { }
          log.error("trade feed refused to start ({}); exiting", refused.getMessage());
          System.exit(3);
        }, "trade-feed-refusal-exit");
        exit.setDaemon(true);
        exit.start();
        throw refused;
      }
      return;
    }
    flushThread = new Thread(this::flushLoop, "trade-feed-batch-flush");
    flushThread.setDaemon(true);
    flushThread.start();
  }

  private void startJetStream() throws Exception {
    JetStreamFeed.ensureStream(connection(), stream, streamMaxBytes, Duration.ofHours(streamMaxAgeHours));
    if (writeLock != null) { writeLock.ensurePartitions(partitionCount); }
    if (snapshotMode()) { startSnapshotMode(); return; }
    for (int p : JetStreamFeed.owned(ownedPartitions, partitionCount)) {
      String subject = JetStreamFeed.subject("/trades", p, partitionCount);
      caughtUpAt.put(p, 0L);
      feedThreads.add(JetStreamFeed.start("trade-feed-p" + p, connection(), stream, subject,
          JetStreamFeed.durable("tp-trades-p" + p, subject), BATCH_SIZE, Duration.ofMillis(fetchWaitMillis),
          msgs -> onJetStreamBatch(p, msgs), head -> caughtUpAt.put(p, System.currentTimeMillis())));
    }
    log.info("trade feed: jetstream mode, {} of {} partitions owned", feedThreads.size(), partitionCount);
  }

  /**
   * Snapshot mode: per owned partition, load the last snapshot (positions, blotter windows, cursor),
   * replay the stream from the cursor, and snapshot every {@link #snapshotMillis}. The consumer is
   * ephemeral: the cursor row IS the acknowledgement, so a crash before a snapshot replays from the
   * last one and books nothing twice (TradeView ignores seq <= cursor).
   */
  private void startSnapshotMode() throws Exception {
    final String scope = runRegistry == null ? RunRegistry.UNKNOWN : runRegistry.activeScope();
    snapshotScope = scope;
    List<Position> allPositions = snapshotStore.loadPositions(scope);
    List<Trade> allTrades = snapshotStore.loadBlotters(scope);
    for (int p : JetStreamFeed.owned(ownedPartitions, partitionCount)) {
      final int partition = p;
      TradeView view = new TradeView(blotterDepth);
      TradeSnapshotStore.Cursor cursor = snapshotStore.loadCursor(scope, p);
      List<Position> mine = new ArrayList<>();
      for (Position pos : allPositions) { if (Math.floorMod(pos.getAccountId(), partitionCount) == p) { mine.add(pos); } }
      List<Trade> myTrades = new ArrayList<>();
      for (Trade t : allTrades) { if (Math.floorMod(t.getAccountId(), partitionCount) == p) { myTrades.add(t); } }
      view.load(mine, myTrades, cursor == null ? 0 : cursor.streamSeq(), cursor == null ? 0 : cursor.consensusSeq());
      view.loadDays(snapshotStore.loadDays(scope, p));
      requireRetained(p, view.streamSeq());
      views.put(p, view);
      String subject = JetStreamFeed.subject("/trades", p, partitionCount);
      feedThreads.add(JetStreamFeed.start("trade-feed-p" + p, connection(), stream, subject,
          JetStreamFeed.fromSequence(subject, view.streamSeq() + 1), BATCH_SIZE, Duration.ofMillis(fetchWaitMillis),
          msgs -> onSnapshotBatch(partition, msgs), view::idle));
      Thread snap = new Thread(() -> snapshotLoop(partition), "trade-snapshot-p" + p);
      snap.setDaemon(true);
      snap.start();
      feedThreads.add(snap);
      log.info("trade store: partition {} loaded {} positions, {} blotter rows, cursor stream_seq={}",
          p, mine.size(), myTrades.size(), view.streamSeq());
    }
  }

  /**
   * Precondition, not a measurement: replay starts at {@code cursor + 1}, so if the stream no longer
   * holds that message the projection would silently skip whatever was discarded. Refuse to start
   * instead, naming both sequences. The remedy is retention sized to the outage (manifests) or a
   * rebuild from the archive; an idle partition does not trip this because its cursor follows the
   * stream head while idle ({@link TradeView#idle}).
   */
  private void requireRetained(int partition, long cursor) throws Exception {
    var state = connection().jetStreamManagement().getStreamInfo(stream).getStreamState();
    long first = state.getFirstSequence();
    if (state.getLastSequence() < cursor) {
      // Sequences never go backwards in one stream: this is a different (recreated) stream, e.g. NATS
      // restarted on an emptyDir. Replaying it would ignore every seq <= cursor, i.e. drop it silently.
      throw new IllegalStateException("STREAM_RESET trade store partition " + partition + ": the snapshot cursor is at"
          + " stream sequence " + cursor + " but " + stream + " ends at " + state.getLastSequence()
          + "; the stream was recreated, so it is not the ledger this snapshot was taken from");
    }
    if (first > cursor + 1) {
      throw new IllegalStateException("RETENTION_GAP trade store partition " + partition + ": the snapshot cursor is at"
          + " stream sequence " + cursor + " but the oldest message " + stream + " retains is " + first + "; sequences "
          + (cursor + 1) + ".." + (first - 1) + " were discarded before this partition applied them");
    }
  }

  public String snapshotScope() { return snapshotScope; }
  /** For the ledger recon: the stream, its partitioning and the connection the feed already holds. */
  public io.nats.client.Connection natsConnection() { return connection(); }
  public String streamName() { return stream; }
  public int partitionCount() { return Math.max(1, partitionCount); }
  public boolean ledgerMode() { return jetstream() && snapshotMode(); }

  void onSnapshotBatch(int partition, List<Message> msgs) {
    TradeView view = views.get(partition);
    Map<String, Map<String, Object>> runs = new java.util.HashMap<>();
    Map<String, finos.traderx.tradeprocessor.model.InstrumentMetadata> bonds = new java.util.HashMap<>();
    for (Message m : msgs) {
      try {
        var envelope = envelope(m.getData());
        if (envelope == null) { continue; }
        TradeOrder t = envelope.getPayload();
        java.util.Date at = envelope.getDate();     // trade time on the ledger: the bridge's clock, as captured
        if (runRegistry != null) { runRegistry.validate(t, runs.computeIfAbsent(t.getProjectionScope(), runRegistry::scope)); }
        if (view.apply(t, m.metaData().streamSequence(), (o, p) -> tradeService.book(o, p, bonds, at))) { applied.incrementAndGet(); }
      } catch (Exception x) {
        log.error("trade store: /trades message skipped: {}", x.toString());
      }
    }
    if (partitionCount <= 1) { for (Message m : msgs) { try { TradeOrder t = decode(m.getData()); if (t != null) { gaps.observe(t.getId()); } } catch (Exception ignore) { } } }
  }

  private void snapshotLoop(int partition) {
    TradeView view = views.get(partition);
    long written = view.streamSeq();
    while (running) {
      try { Thread.sleep(snapshotMillis); } catch (InterruptedException x) { return; }
      TradeView.Cut cut = view.cut();
      if (cut.isEmpty() && cut.streamSeq() == written) { continue; }   // an idle advance still moves the cursor
      try {
        snapshotRows.addAndGet(snapshotStore.write(snapshotScope, partition, cut));
        snapshots.incrementAndGet();
        written = cut.streamSeq();
      } catch (Exception x) {
        snapshotFailures.incrementAndGet();
        view.remark(cut);
        log.error("trade store: snapshot of partition {} failed ({} positions, {} accounts); kept for the next one",
            partition, cut.positions().size(), cut.blotters().size(), x);
      }
    }
  }

  public long snapshots() { return snapshots.get(); }
  public long snapshotRows() { return snapshotRows.get(); }
  public long snapshotFailures() { return snapshotFailures.get(); }
  public Map<Integer, TradeView> views() { return views; }
  public String projectionMode() { return snapshotMode() ? "snapshot" : "rows"; }

  /**
   * Row projection on the jetstream feed: the instant (epoch ms) by which every side the stream held had
   * committed to SQL, on every owned partition; 0 until each has caught up once since start.
   * ponytail: min over OWNED partitions; a replica owning a subset never vouches for the others.
   */
  public long caughtUpAt() {
    return caughtUpAt.values().stream().mapToLong(Long::longValue).min().orElse(0L);
  }
  public boolean jetstreamRows() { return jetstream() && !snapshotMode(); }

  /** One fetched batch: decode on this partition's own thread, project, count. */
  void onJetStreamBatch(int partition, List<Message> msgs) {
    // A short batch drained the consumer: everything stored before this fetch began is in it or before it.
    long drainedAt = msgs.size() < BATCH_SIZE ? System.currentTimeMillis() - fetchWaitMillis : -1;
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
    if (drainedAt > 0) { caughtUpAt.merge(partition, drainedAt, Math::max); }
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
      if (transactions != null && gaps.persisted()) {
        // The watermark commits with the batch: a restart counts exactly the sides that never committed.
        transactions.executeWithoutResult(s -> { tradeService.processTrades(batch); gaps.save(); });
      } else {
        tradeService.processTrades(batch);
      }
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
      gaps.save();   // these sides reached the projection; a failed one is logged above, not lost upstream
    }
  }

  /**
   * Contiguity of the engine's per-leg trade counter, as it reaches the projection. Every booked
   * leg takes the next {@code tradeSeq} ({@code ++tradeCounter}) and the leader's bridge publishes
   * them in that order, so a jump forward is exactly the number of trade sides lost upstream of
   * the flush thread: bridge queue drops, NATS slow-consumer cuts, a failover's unpublished tail,
   * this process's own queue drops. Ids are {@code [e1-<epoch>-]<tradeSeq>-B|S}.
   *
   * <p>With {@link #persistIn} the watermark (last applied tradeSeq of the id's epoch) is saved with
   * each batch, and the first side after a start is measured against it, so sides booked while the
   * projection was down count as missing instead of becoming a fresh baseline. A new epoch restarts
   * tradeSeq, so it has no watermark and starts one.
   */
  public static final class TradeSeqGaps {
    /** projection_cursor row (partition 0) holding an epoch's watermark in stream_seq. */
    static final String SCOPE = "trade-seq:";
    private long last = -1;
    private long missing;
    private long firstSeen = -1;
    private String epoch = "";
    private java.util.function.Function<String, Long> load = e -> null;
    private java.util.function.BiConsumer<String, Long> store;

    synchronized void persistIn(java.util.function.Function<String, Long> load, java.util.function.BiConsumer<String, Long> store) {
      this.load = load;
      this.store = store;
    }

    boolean persisted() { return store != null; }

    /** Save the watermark; called inside the batch's transaction. A failure never fails the batch. */
    synchronized void save() {
      if (store == null || last <= 0) { return; }
      try { store.accept(epoch, last); } catch (RuntimeException x) { storeFailed(x); }
    }

    private boolean warned;
    private void storeFailed(RuntimeException x) {
      if (!warned) { warned = true; log.error("trade-feed tradeSeq watermark unavailable; a restart gap will not be counted", x); }
    }

    synchronized void observe(String id) {
      long seq = seqOf(id);
      if (seq <= 0) { return; }
      boolean first = last < 0;
      if (first) {
        firstSeen = seq;
        epoch = id.substring(0, start(id));
        Long saved = null;
        try { saved = load.apply(epoch); } catch (RuntimeException x) { storeFailed(x); }
        last = saved == null ? seq - 1 : saved;   // nothing saved: this side is the baseline
      }
      if (seq > last + 1) {
        missing += seq - last - 1;
        if (first) {
          log.error("ALERT trade-feed restart GAP tradeSeq {} (last applied before this start) -> {}: {} trade sides were"
              + " booked while the projection was down and never reached it (total missing={})", last, seq, seq - last - 1, missing);
        } else {
          log.warn("trade-feed GAP tradeSeq {} -> {}: {} trade sides never reached the projection (total missing={})",
              last, seq, seq - last - 1, missing);
        }
      }
      if (seq > last) { last = seq; }
    }

    static long seqOf(String id) {
      if (id == null || id.length() < 3 || id.charAt(id.length() - 2) != '-') { return -1; }
      try { return Long.parseLong(id.substring(start(id), id.length() - 2)); } catch (NumberFormatException x) { return -1; }
    }

    private static int start(String id) { return id.lastIndexOf('-', id.length() - 3) + 1; }

    public synchronized long missing() { return missing; }
    public synchronized long last() { return last; }
    public synchronized long firstSeen() { return firstSeen; }
  }
}
