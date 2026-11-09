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
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * The trade store (ADR-097, the only projection since FR-THT-01 retired rows mode on 2026-11-06): one
 * {@link JetStreamFeed} pull thread per OWNED partition replays the capture stream from the committed cursor into
 * an in-memory {@link TradeView}, and a snapshot thread writes positions, the sides booking refused and the cursor
 * to SQL every snapshot-ms. Trades live in the history store (history-feed, ADR-102 cutover), not SQL.
 */
public class TradeFeedHandler extends NatsJSONSubscriber<TradeOrder> implements DisposableBean {
  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradeFeedHandler.class);

  private static final int BATCH_SIZE = 1000;

  private final AtomicLong applied = new AtomicLong();
  private final TradeSeqGaps gaps = new TradeSeqGaps();
  private final List<Thread> feedThreads = new ArrayList<>();
  private volatile boolean running = true;

  // Feed wiring (application.properties). Only jetstream + snapshot remain (FR-THT-01); anything else refuses to start.
  @Value("${traderx.feed.mode:jetstream}") String feedMode = "jetstream";
  @Value("${traderx.feed.partition-count:1}") int partitionCount = 1;
  @Value("${traderx.feed.owned:all}") String ownedPartitions = "all";
  @Value("${traderx.feed.stream:TRADERX_FEED}") String stream = "TRADERX_FEED";
  @Value("${traderx.feed.stream-layout:one}") String streamLayout = "one";
  @Value("${traderx.feed.stream-max-bytes:8589934592}") long streamMaxBytes = 8L << 30;
  @Value("${traderx.feed.stream-max-age-hours:24}") long streamMaxAgeHours = 24;
  // How long a pull waits for a FULL batch. Measured: at 10 ms the fetch returned partial batches
  // and the per-batch SQL costs (lock, prefetch, counter) amortized over ~200 rows instead of 1000,
  // tripling CPU per trade row. Latency under low load is bounded by this value.
  @Value("${traderx.feed.fetch-wait-ms:200}") long fetchWaitMillis = 200;
  @Value("${traderx.trade-projection.mode:snapshot}") String projectionMode = "snapshot";
  @Value("${traderx.trade-projection.snapshot-ms:5000}") long snapshotMillis = 5000;
  private final Map<Integer, TradeView> views = new ConcurrentHashMap<>();
  private final AtomicLong snapshots = new AtomicLong();
  private final AtomicLong snapshotRows = new AtomicLong();
  private final AtomicLong snapshotFailures = new AtomicLong();
  private final AtomicLong refused = new AtomicLong();   // snapshot mode: messages skipped as refused (FR-DRS-35)
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

  boolean jetstream() { return "jetstream".equalsIgnoreCase(feedMode); }
  boolean snapshotMode() { return "snapshot".equalsIgnoreCase(projectionMode); }

  @Override
  protected boolean autoSubscribe() { return false; }

  @Override
  public void afterPropertiesSet() throws Exception {
    if (!jetstream() || !snapshotMode()) {   // before connecting: a retired mode never touches the broker or SQL
      throw new IllegalStateException("trade feed: rows mode and the core feed were retired (FR-THT-01); traderx.feed.mode="
          + feedMode + ", traderx.trade-projection.mode=" + projectionMode + " must be jetstream and snapshot");
    }
    super.afterPropertiesSet();
    {
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
    }
  }

  private void startJetStream() throws Exception {
    JetStreamFeed.ensureStreams(connection(), stream, streamLayout, partitionCount, streamMaxBytes, Duration.ofHours(streamMaxAgeHours));
    if (writeLock != null) { writeLock.ensurePartitions(partitionCount); }
    startSnapshotMode();
  }

  /**
   * Snapshot mode: per owned partition, load the last snapshot (positions, cursor),
   * replay the stream from the cursor, and snapshot every {@link #snapshotMillis}. The consumer is
   * ephemeral: the cursor row IS the acknowledgement, so a crash before a snapshot replays from the
   * last one and books nothing twice (TradeView ignores seq <= cursor).
   */
  private void startSnapshotMode() throws Exception {
    final String scope = runRegistry == null ? RunRegistry.UNKNOWN : runRegistry.activeScope();
    snapshotScope = scope;
    List<Position> allPositions = snapshotStore.loadPositions(scope);
    List<String> unbound = new ArrayList<>();   // FR-DRS-36: every partition's attestation in one refusal
    for (int p : JetStreamFeed.owned(ownedPartitions, partitionCount)) {
      final int partition = p;
      TradeView view = new TradeView();
      TradeSnapshotStore.Cursor cursor = snapshotStore.loadCursor(scope, p);
      List<Position> mine = new ArrayList<>();
      for (Position pos : allPositions) { if (Math.floorMod(pos.getAccountId(), partitionCount) == p) { mine.add(pos); } }
      view.load(mine, cursor == null ? 0 : cursor.streamSeq(), cursor == null ? 0 : cursor.consensusSeq());
      view.loadDays(snapshotStore.loadDays(scope, p));
      view.loadAccounting(snapshotStore.loadAccounting(scope, p));
      try { requireRetained(p, view.streamSeq()); } catch (JetStreamFeed.Unbound u) { unbound.add(u.getMessage()); continue; }
      views.put(p, view);
      String subject = JetStreamFeed.subject("/trades", p, partitionCount);
      feedThreads.add(JetStreamFeed.startPipelined("trade-feed-p" + p, connection(), streamName(p), subject,
          JetStreamFeed.fromSequence(subject, view.streamSeq() + 1, BATCH_SIZE), BATCH_SIZE, Duration.ofMillis(fetchWaitMillis),
          msgs -> onSnapshotBatch(partition, msgs), view::idle));
      Thread snap = new Thread(() -> snapshotLoop(partition), "trade-snapshot-p" + p);
      snap.setDaemon(true);
      snap.start();
      feedThreads.add(snap);
      log.info("trade store: partition {} loaded {} positions, cursor stream_seq={}", p, mine.size(), view.streamSeq());
    }
    if (!unbound.isEmpty()) { throw new IllegalStateException(String.join("\n", unbound)); }
  }

  /**
   * Precondition, not a measurement: replay starts at {@code cursor + 1}, so if the stream no longer
   * holds that message the projection would silently skip whatever was discarded. Refuse to start
   * instead, naming both sequences. The remedy is retention sized to the outage (manifests) or a
   * rebuild from the archive; an idle partition does not trip this because its cursor follows the
   * stream head while idle ({@link TradeView#idle}). The cursor is bound to the stream it was taken on, and a recreated
   * stream is refused rather than resumed (FR-DRS-36).
   */
  private void requireRetained(int partition, long cursor) throws Exception {
    snapshotStore.bindStream(snapshotScope, partition, JetStreamFeed.requireRetained(connection(), streamName(partition),
        "trade store", snapshotScope, partition, cursor, snapshotStore.boundStream(snapshotScope, partition)));
  }

  public String snapshotScope() { return snapshotScope; }
  /** For the ledger recon: the stream, its partitioning and the connection the feed already holds. */
  public io.nats.client.Connection natsConnection() { return connection(); }
  public String streamName() { return stream; }
  /** The stream partition {@code p} is read from (FR-DRS-32). */
  public String streamName(int p) { return JetStreamFeed.streamFor(stream, streamLayout, p, Math.max(1, partitionCount)); }
  public int partitionCount() { return Math.max(1, partitionCount); }
  public boolean ledgerMode() { return true; }

  void onSnapshotBatch(int partition, List<Message> msgs) {
    TradeView view = views.get(partition);
    Map<String, Map<String, Object>> runs = new java.util.HashMap<>();
    Map<String, finos.traderx.tradeprocessor.model.InstrumentMetadata> bonds = new java.util.HashMap<>();
    long duplicatesBefore = view.accounting().duplicates();
    for (Message m : msgs) {
      List<byte[]> records = records(m.getData());          // FR-DRS-41: one message, one or more sides
      synchronized (view) {                                 // a cut never falls between one message's sides
        for (int i = 0; i < records.size(); i++) {
          try {
            var envelope = envelope(records.get(i));
            if (envelope == null) { continue; }
            TradeOrder t = envelope.getPayload();
            java.util.Date at = envelope.getDate();     // trade time on the ledger: the bridge's clock, as captured
            if (runRegistry != null) { runRegistry.validate(t, runs.computeIfAbsent(t.getProjectionScope(), runRegistry::scope)); }
            if (view.apply(t, m.metaData().streamSequence(), i, (o, p) -> tradeService.book(o, p, bonds, at))) { applied.incrementAndGet(); }
          } catch (Exception x) {
            // FR-DRS-35: a store failure is a barrier. The feed applies this batch again (the view ignores what it already
            // booked); booking the next side here would move the cursor, and the snapshot, past a valid one.
            if (JetStreamFeed.retryable(x)) { throw JetStreamFeed.barrier(x); }
            log.error("trade store: /trades message at stream sequence {} refused and skipped (refused={}): {}",
                m.metaData().streamSequence(), refused.incrementAndGet(), x.toString());
          }
        }
      }
    }
    long duplicates = view.accounting().duplicates() - duplicatesBefore;
    if (duplicates > 0) {
      log.error("ALERT trade store partition {}: {} trade side(s) arrived again with a tradeSeq at or below the last one booked"
          + " ({}); refused, not booked twice (total duplicates={})", partition, duplicates, view.accounting().maxSeq(),
          view.accounting().duplicates());
    }
    if (partitionCount <= 1) { for (Message m : msgs) { for (byte[] record : records(m.getData())) { try { TradeOrder t = decode(record); if (t != null) { gaps.observe(t.getId()); } } catch (Exception ignore) { } } } }
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
        log.error("trade store: snapshot of partition {} failed ({} positions, {} rejected); kept for the next one",
            partition, cut.positions().size(), cut.rejected().size(), x);
      }
    }
  }

  public long snapshots() { return snapshots.get(); }
  public long snapshotRows() { return snapshotRows.get(); }
  public long snapshotFailures() { return snapshotFailures.get(); }
  public long refused() { return refused.get(); }
  public Map<Integer, TradeView> views() { return views; }
  public String projectionMode() { return "snapshot"; }

  @Override
  public void destroy() {
    running = false;
    for (Thread t : feedThreads) { t.interrupt(); }
  }

  /** Never called: there is no core subscription ({@link #autoSubscribe} is false). */
  @Override
  public void onMessage(Envelope<?> envelope, TradeOrder order) { }

  public long dropped() { return 0; }   // nothing is queued, so nothing drops; the stream retains it
  /** Trade sides booked into the views, for a rate that does not need SQL. */
  public long applied() { return applied.get(); }
  public TradeSeqGaps gaps() { return gaps; }
  public int backlog() { return 0; }
  public String feedMode() { return "jetstream"; }

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
    /** Key prefix (under SCOPE) of the row holding the epoch's running missing count, so it survives a restart. */
    static final String MISSING = "missing:";
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
      try { store.accept(epoch, last); store.accept(MISSING + epoch, missing); } catch (RuntimeException x) { storeFailed(x); }
    }

    /** Sides that reached the projection but never committed (refused by SQL): missing, not applied. */
    synchronized void refused(int n) {
      if (n <= 0) { return; }
      missing += n;
      log.error("ALERT trade-feed REFUSED {} trade side(s): SQL refused them and they never committed (total missing={})",
          n, missing);
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
        Long savedMissing = null;
        if (saved != null) { try { savedMissing = load.apply(MISSING + epoch); } catch (RuntimeException x) { storeFailed(x); } }
        if (savedMissing != null) { missing += savedMissing; }   // the count before this start carries over
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

    /** The id's epoch prefix ({@code e1-<epoch>-}, or "" for a legacy id); call only when {@link #seqOf} > 0. */
    static String epochOf(String id) { return id.substring(0, start(id)); }

    public synchronized long missing() { return missing; }
    public synchronized long last() { return last; }
    public synchronized long firstSeen() { return firstSeen; }
  }
}
