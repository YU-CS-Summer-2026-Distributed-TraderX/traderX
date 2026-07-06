package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.repository.OrderRepository;
import finos.traderx.ordermatcher.repository.PositionRepository;
import finos.traderx.ordermatcher.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LMAX hot-path wiring for state 009b (LMAX-SEQUENCER-ARCHITECTURE.md §4):
 *
 *   gateway commands / price ticks
 *      -> input disruptor (multi-producer; the ring sequence IS the global sequence)
 *           -> journaler + replicator in parallel, BLP gated behind both
 *      -> single-threaded MatchingEngine (in-memory book; match + emit on one thread)
 *           -> output disruptor (single producer)
 *                -> marshaller | order NATS bridge | account trade | position update
 *                   | optional legacy trade submit | read-model projector
 *
 * Demo profile defaults: BlockingWaitStrategy, loopback replicator, file journal, no core
 * pinning — container-safe per NFR-09B06. Recovery: read-model warm-start + journal
 * (the persisted read-model acts as the snapshot; the journal captures the event stream).
 *
 * Warm standby (FR-09B30..B32): with {@code blp.role=standby} this engine boots as a FOLLOWER —
 * it recovers from snapshot+journal, then a {@link JournalFollower} keeps applying the leader's
 * journal so book/positions/read-model stay warm (NATS bridges gated the whole time via the
 * replaying flag). A {@link PrimaryWatchdog} probes the leader and calls {@link #promote} when it
 * dies; promotion must win the {@link LeaderLock} on the shared journal volume (split-brain
 * fence), drains the journal tail, then starts the normal live input path. A node configured
 * primary that finds the lock held (restart after a failover) demotes itself to follower.
 */
@Component
public class LmaxEngine implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(LmaxEngine.class);
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^ord-013-(\\d{4,})$");

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Publisher<OrderResponse> orderPublisher;
    private final Publisher<TradeOrder> tradePublisher;
    private final Publisher<AccountTrade> accountTradePublisher;
    private final Publisher<PositionUpdate> positionPublisher;
    private final boolean legacyTradeSubmitEnabled;
    private final boolean seedEnabled;
    private final int fillFullThreshold;
    private final int inputRingSize;
    private final String inputWaitStrategy;
    private final int outputRingSize;
    private final String outputWaitStrategy;
    private final boolean journalEnabled;
    private final String journalPath;
    private final boolean replayVerifyEnabled;
    private final int projectorBatchSize;
    private final int projectorQueueCapacity;
    private final int maxSecurities;
    private final int bookPoolSize;
    private final int positionCapacity;
    private final int maxRetainedTerminal;
    private final int blpPinCpu;
    private final long snapshotIntervalMs;
    private final String recoverySource;        // "db" (warm-start) or "journal" (snapshot+replay)
    private final boolean projectorDbEnabled;   // false => no DB writes at all (cutover)
    private final long ackTimeoutMs;
    private final String runtimeProfile;
    private final String configuredRole;        // "primary" (default) or "standby" — a preference, not a verdict
    private final String failoverWatchUrl;      // standby: leader health URL the watchdog probes
    private final long probeIntervalMs;
    private final int probeFailures;
    private final boolean autoPromote;
    private final long followerPollMs;
    private final long leaderAcquireTimeoutMs;

    private final SymbolTable symbols;
    private final HotPathMetrics metrics = new HotPathMetrics();
    private final InMemoryOrderReadModel readModel;
    private final AtomicInteger nextOrderRef = new AtomicInteger(1);

    private Disruptor<InputEvent> inputDisruptor;
    private Disruptor<OutputEvent> outputDisruptor;
    private RingBuffer<InputEvent> inputRing;
    private RingBuffer<OutputEvent> outputRing;
    private MatchingEngine matchingEngine;
    private Journaler journaler;
    private ReplicatorStub replicator;
    private MarshallerHandler marshaller;
    private ProjectorHandler projector;
    private SnapshotStore snapshotStore;
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;

    // Failover state (FR-09B30..B32). `live` is the single gate the gateways check: false means
    // follower (reads OK, writes 503, price ticks dropped — they arrive via the journal instead).
    private volatile boolean live;
    private volatile String role = "primary";
    private LeaderLock leaderLock;
    private volatile JournalFollower follower;
    private volatile PrimaryWatchdog watchdog;
    private final java.util.concurrent.atomic.AtomicLong promotions = new java.util.concurrent.atomic.AtomicLong();

    public LmaxEngine(
        OrderRepository orderRepository,
        TradeRepository tradeRepository,
        PositionRepository positionRepository,
        JdbcTemplate jdbcTemplate,
        Publisher<OrderResponse> orderPublisher,
        Publisher<TradeOrder> tradePublisher,
        Publisher<AccountTrade> accountTradePublisher,
        Publisher<PositionUpdate> positionPublisher,
        @Value("${output.legacy-trades.enabled:false}") boolean legacyTradeSubmitEnabled,
        @Value("${order.matcher.seed-enabled:true}") boolean seedEnabled,
        @Value("${order.matcher.fill-full-threshold:1000}") int fillFullThreshold,
        @Value("${disruptor.input.ring-size:65536}") int inputRingSize,
        @Value("${disruptor.input.wait-strategy:blocking}") String inputWaitStrategy,
        @Value("${disruptor.output.ring-size:65536}") int outputRingSize,
        @Value("${disruptor.output.wait-strategy:blocking}") String outputWaitStrategy,
        @Value("${journal.enabled:true}") boolean journalEnabled,
        @Value("${journal.path:./data/journal}") String journalPath,
        @Value("${journal.replay.verify:true}") boolean replayVerifyEnabled,
        @Value("${output.projector.batch-size:500}") int projectorBatchSize,
        @Value("${output.projector.queue-capacity:1000000}") int projectorQueueCapacity,
        @Value("${blp.books.max-securities:4096}") int maxSecurities,
        @Value("${blp.book.pool-size:65536}") int bookPoolSize,
        @Value("${blp.positions.capacity:8192}") int positionCapacity,
        @Value("${blp.orders.max-retained:262144}") int maxRetainedTerminal,
        @Value("${blp.pin.cpu:-1}") int blpPinCpu,
        @Value("${snapshot.interval.ms:0}") long snapshotIntervalMs,
        @Value("${recovery.source:db}") String recoverySource,
        @Value("${output.projector.db.enabled:true}") boolean projectorDbEnabled,
        @Value("${blp.gateway.ack-timeout-ms:5000}") long ackTimeoutMs,
        @Value("${runtime.profile:demo}") String runtimeProfile,
        @Value("${blp.role:primary}") String configuredRole,
        @Value("${failover.watch-url:}") String failoverWatchUrl,
        @Value("${failover.probe-interval-ms:1000}") long probeIntervalMs,
        @Value("${failover.probe-failures:3}") int probeFailures,
        @Value("${failover.auto-promote:true}") boolean autoPromote,
        @Value("${failover.follower-poll-ms:10}") long followerPollMs,
        @Value("${blp.leader.acquire-timeout-ms:10000}") long leaderAcquireTimeoutMs
    ) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.orderPublisher = orderPublisher;
        this.tradePublisher = tradePublisher;
        this.accountTradePublisher = accountTradePublisher;
        this.positionPublisher = positionPublisher;
        this.legacyTradeSubmitEnabled = legacyTradeSubmitEnabled;
        this.seedEnabled = seedEnabled;
        this.fillFullThreshold = fillFullThreshold;
        this.inputRingSize = inputRingSize;
        this.inputWaitStrategy = inputWaitStrategy;
        this.outputRingSize = outputRingSize;
        this.outputWaitStrategy = outputWaitStrategy;
        this.journalEnabled = journalEnabled;
        this.journalPath = journalPath;
        this.replayVerifyEnabled = replayVerifyEnabled;
        this.projectorBatchSize = projectorBatchSize;
        this.projectorQueueCapacity = projectorQueueCapacity;
        this.maxSecurities = maxSecurities;
        this.bookPoolSize = bookPoolSize;
        this.positionCapacity = positionCapacity;
        this.maxRetainedTerminal = maxRetainedTerminal;
        this.blpPinCpu = blpPinCpu;
        this.snapshotIntervalMs = snapshotIntervalMs;
        this.recoverySource = recoverySource;
        this.projectorDbEnabled = projectorDbEnabled;
        this.ackTimeoutMs = ackTimeoutMs;
        this.runtimeProfile = runtimeProfile;
        this.configuredRole = configuredRole == null ? "primary" : configuredRole.trim().toLowerCase(Locale.ROOT);
        this.failoverWatchUrl = failoverWatchUrl == null ? "" : failoverWatchUrl.trim();
        this.probeIntervalMs = probeIntervalMs;
        this.probeFailures = probeFailures;
        this.autoPromote = autoPromote;
        this.followerPollMs = followerPollMs;
        this.leaderAcquireTimeoutMs = leaderAcquireTimeoutMs;
        this.symbols = new SymbolTable(maxSecurities);
        this.readModel = new InMemoryOrderReadModel(maxRetainedTerminal);
    }

    @Override
    public void afterPropertiesSet() {
        boolean standbyRequested = "standby".equals(configuredRole);
        // Restore the durable ticker->securityId mapping FIRST (before any idFor): the journal stores
        // ids, so replay must resolve them to the same tickers the original run used (FR-09B05 recovery).
        // Loaded read-only here; the node that WINS leadership turns appends on (beginPersisting).
        if (journalEnabled) {
            symbols.enableReadOnly(Path.of(journalPath).resolve("symbols.tab"));
            snapshotStore = new SnapshotStore(Path.of(journalPath));
            leaderLock = new LeaderLock(Path.of(journalPath).resolve("leader.lock"));
        } else if (standbyRequested) {
            throw new IllegalStateException(
                "blp.role=standby requires journal.enabled=true (the journal is the replication stream)");
        }

        // Decide leadership BEFORE wiring the output side: a follower must not carry the DB
        // projector (it would shadow-write the database while replaying the leader's journal).
        // The lock, not the config, is the verdict — a primary restarted after a failover finds
        // the promoted standby holding it and must rejoin as a follower, not split-brain.
        boolean lead = !standbyRequested;
        if (lead && leaderLock != null && !leaderLock.acquireWithin(leaderAcquireTimeoutMs, "boot:" + configuredRole)) {
            log.warn("Leader lock is held by another node; joining as FOLLOWER instead of primary");
            lead = false;
        }

        buildOutputSide(!lead);

        matchingEngine = new MatchingEngine(new OutputPublisher(outputRing),
            metrics, maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity, maxRetainedTerminal);
        matchingEngine.setPinCpu(blpPinCpu);   // perf profile: pin the BLP thread on ring start

        if (lead) {
            goLiveFromBoot();
        } else {
            goStandby();
        }
    }

    /** Output ring + handlers; identical for leader and follower (the follower's read model is
     *  rebuilt through the same marshaller, with NATS gated by the replaying flag). The DB
     *  projector is never attached on a follower — it must not shadow-write the database while
     *  replaying the leader's journal, and post-promotion the journal is authoritative. */
    private void buildOutputSide(boolean follower) {
        marshaller = new MarshallerHandler(readModel, symbols, metrics);
        if (projectorDbEnabled && !follower) {
            projector = new ProjectorHandler(jdbcTemplate, symbols,
                projectorBatchSize, projectorQueueCapacity, metrics);
        }
        NatsBridgeHandler natsBridge = new NatsBridgeHandler(orderPublisher, symbols, readModel);
        AccountTradeHandler accountTrade = new AccountTradeHandler(accountTradePublisher, symbols, readModel);
        PositionUpdateHandler positionUpdate = new PositionUpdateHandler(positionPublisher, symbols, readModel);

        // Output ring fans out to the marshaller (rebuilds the read model), the NATS bridges, the
        // optional legacy `/trades`, and the optional async DB projector. With the DB dropped
        // (output.projector.db-enabled=false) the projector is simply absent — no DB writes at all.
        outputDisruptor = new Disruptor<>(OutputEvent::newInstance, normalizeRingSize(outputRingSize),
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, waitStrategy(outputWaitStrategy));
        java.util.List<EventHandler<OutputEvent>> outputHandlers = new ArrayList<>();
        outputHandlers.add(marshaller);
        outputHandlers.add(natsBridge);
        outputHandlers.add(accountTrade);
        outputHandlers.add(positionUpdate);
        if (legacyTradeSubmitEnabled) {
            outputHandlers.add(new TradeSubmitHandler(tradePublisher, symbols, readModel));
        }
        if (projector != null) {
            outputHandlers.add(projector);
        }
        outputDisruptor.handleEventsWith(outputHandlers.toArray(new EventHandler[0]));
        if (projector != null) {
            projector.start();   // drain thread up before the ring feeds it (decoupled async writes)
        }
        outputDisruptor.start();
        outputRing = outputDisruptor.getRingBuffer();
    }

    /** Normal leader boot: recover, then start the live input path (this node owns the journal). */
    private void goLiveFromBoot() {
        // Persistence on BEFORE the seed registration, so a fresh volume's symbols.tab starts with
        // the seed ids — every later node then restores them from the file instead of re-deriving.
        symbols.beginPersisting();
        registerSeedSymbols();
        // Recovery: DB warm-start (+ shadow verify) OR snapshot+journal authoritative (no DB).
        boolean journalRecovery = "journal".equalsIgnoreCase(recoverySource);
        if (journalRecovery) {
            recoverLiveFromJournal();
        } else {
            if (projectorDbEnabled) {
                seedReadModelIfEmpty();   // DB is the source of truth: seed it
            }
            bootstrapFromReadModel();
            verifyJournalReplay();   // prove the journal alone reconstructs the same state (verify-only)
        }
        startLiveInputPath();
        role = "primary";
        log.info("LMAX hot path live: profile={} role=primary inputRing={} outputRing={} journal={} ({} orders warm)",
            runtimeProfile, normalizeRingSize(inputRingSize), normalizeRingSize(outputRingSize),
            journalEnabled ? journalPath : "disabled", readModel.totalOrders());
    }

    /** The live-node input path: journaler + replicator ahead of the BLP, snapshots on a timer.
     *  Shared by leader boot and standby promotion (recovery/drain must be complete first). */
    private void startLiveInputPath() {
        // Set the snapshot trigger only AFTER recovery, so SNAPSHOT markers replayed from the journal
        // tail are no-ops and do not overwrite snapshot.dat with mid-recovery state.
        if (snapshotStore != null) {
            matchingEngine.setSnapshotTrigger(this::writeSnapshot);
        }
        // Input ring: journaler + replicator run in parallel; the BLP is gated behind both
        // (sequence barrier), so every event it acts on is already durable and replicated.
        journaler = new Journaler(journalEnabled, Path.of(journalPath), metrics);
        replicator = new ReplicatorStub();
        inputDisruptor = new Disruptor<>(InputEvent::newInstance, normalizeRingSize(inputRingSize),
            DaemonThreadFactory.INSTANCE, ProducerType.MULTI, waitStrategy(inputWaitStrategy));
        inputDisruptor.handleEventsWith(journaler, replicator).then(matchingEngine);
        inputDisruptor.start();
        inputRing = inputDisruptor.getRingBuffer();
        startSnapshotScheduler();
        live = true;
    }

    // ----- warm standby: follow the leader's journal; promote on its death (FR-09B30..B32) -----

    /** Boot (or demote) into follower mode: recover from snapshot+journal, then tail the journal
     *  continuously. Outputs stay gated (replaying=true) — the LEADER publishes to NATS, not us —
     *  but the read model is fully warm, so reads can be served the whole time. */
    private void goStandby() {
        role = "standby";
        readModel.setReplaying(true);
        // Pin the seed ids (registration only — never persisted from a follower). If the leader
        // already wrote symbols.tab this is a no-op (the file was loaded first and wins); if we
        // raced a fresh leader, both derive the identical fixed assignment from an empty table.
        registerSeedSymbols();
        long startOffset = 0;
        SnapshotStore.Data snap = null;
        try {
            snap = snapshotStore == null ? null : snapshotStore.read();
        } catch (Exception ex) {
            log.warn("Snapshot read failed on standby; following from the full journal: {}", ex.toString());
        }
        if (snap != null) {
            loadSnapshotInto(matchingEngine, snap);
            for (long[] o : snap.orders()) {
                readModel.bootstrap(snapshotOrderToReadModel(o));
            }
            nextOrderRef.set(snap.nextOrderRef());
            startOffset = snap.coveredOffset();
        } else {
            // Same fixed initial condition the leader seeds; the journal replays on top of it.
            seedLiveInitialCondition();
        }
        follower = new JournalFollower(Path.of(journalPath), symbols, this::applyFollowedEvent,
            startOffset, followerPollMs);
        follower.start();
        if (autoPromote && !failoverWatchUrl.isEmpty()) {
            watchdog = new PrimaryWatchdog(failoverWatchUrl, probeIntervalMs, probeFailures, this::promote);
            watchdog.start();
        }
        log.info("LMAX standby FOLLOWER: tailing journal from byte {} ({} orders warm from {}); watchdog={}",
            startOffset, readModel.totalOrders(), snap != null ? "snapshot" : "seed",
            watchdog != null ? failoverWatchUrl : "disabled (promote via POST /admin/promote)");
    }

    /** Apply one journaled event to this node's BLP (follower tail, and the promotion drain). */
    private void applyFollowedEvent(InputEvent e) {
        if (e.type == InputEvent.TYPE_ORDER_NEW) {
            bumpNextOrderRef(e.orderRef);
        }
        matchingEngine.onEvent(e, e.seq, true);
        if (e.type == InputEvent.TYPE_PRICE_TICK) {
            // Keep the gateway price cache warm too (follower thread; not a no-GC path).
            String ticker = symbols.tickerFor(e.securityId);
            if (ticker != null) {
                readModel.recordPrice(ticker, Px.toBigDecimal(e.priceTicks));
            }
        }
    }

    private void bumpNextOrderRef(int seenRef) {
        int next = seenRef + 1;
        while (true) {
            int current = nextOrderRef.get();
            if (current >= next || nextOrderRef.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * Standby -> live cutover. Idempotent; called by the watchdog or POST /admin/promote. Order
     * matters: (1) WIN THE LEADER LOCK first — it fences out a primary that is alive-but-slow (the
     * kernel only releases it when the holder process dies), and once held nobody can append to the
     * journal; (2) stop the follower; (3) drain the remaining journal tail, after which this node
     * holds every event the old leader ever acked (acks are gated behind the journaler, so acked
     * implies journaled); (4) un-gate outputs and start the normal live input path.
     */
    public synchronized boolean promote() {
        if (live) {
            return true;
        }
        if (follower == null) {
            log.warn("promote() called but this node is not following a journal");
            return false;
        }
        if (leaderLock == null || !leaderLock.acquireWithin(3000, "promoted-standby")) {
            log.warn("Promotion refused: leader lock still held — the primary process is alive");
            return false;
        }
        follower.stopAndJoin();
        // stopAndJoin's interrupt etiquette (and a watchdog self-close) can leave this thread's
        // interrupt flag set — which would close every NIO channel we open next (the tail drain,
        // the journaler). Clear it: nothing here is waiting to be interrupted anymore.
        Thread.interrupted();
        long tailFrom = follower.appliedOffset();
        long drained;
        try {
            drained = new JournalReader(Path.of(journalPath)).replayFrom(tailFrom, this::applyFollowedEvent);
        } catch (Exception ex) {
            // Going live without the tail would silently drop acked events. Abort: give the lock
            // back and resume following from where we stopped; the watchdog will retry.
            log.error("Promotion ABORTED: journal tail drain failed at byte {}: {}", tailFrom, ex.toString(), ex);
            leaderLock.close();
            follower = new JournalFollower(Path.of(journalPath), symbols, this::applyFollowedEvent,
                tailFrom, followerPollMs);
            follower.start();
            return false;
        }
        if (watchdog != null) {
            watchdog.close();   // self-close safe: it never interrupts its own thread
            watchdog = null;
        }
        drainOutputRing();               // read model catches up before acks/reads go live
        readModel.setReplaying(false);   // from here on, output events publish to NATS again
        symbols.beginPersisting();
        refreshCounters();
        startLiveInputPath();
        if (journalEnabled && !journaler.isWriting()) {
            // The whole point of promotion is to own the durable stream. Demo profile keeps
            // serving (availability over durability, as the Journaler documents) but this must
            // never pass silently — the verify harness asserts journalWriting on the new leader.
            log.error("PROMOTED LEADER IS NOT JOURNALING (append unavailable at {}) — acks are not durable", journalPath);
        }
        follower = null;
        role = "promoted";
        promotions.incrementAndGet();
        log.warn("PROMOTED to live BLP: drained {} tail events from byte {}; {} orders warm, nextRef {}, tradeCounter {}",
            drained, tailFrom, readModel.totalOrders(), nextOrderRef.get(), matchingEngine.tradeCounter());
        return true;
    }

    @Override
    public void destroy() {
        if (watchdog != null) {
            watchdog.close();
        }
        if (follower != null) {
            follower.stopAndJoin();
        }
        if (snapshotScheduler != null) {
            snapshotScheduler.shutdownNow();   // stop emitting snapshot markers before the rings stop
        }
        try {
            if (inputDisruptor != null) {
                inputDisruptor.shutdown(5, TimeUnit.SECONDS);
            }
        } catch (com.lmax.disruptor.TimeoutException ex) {
            inputDisruptor.halt();
        }
        try {
            if (outputDisruptor != null) {
                outputDisruptor.shutdown(5, TimeUnit.SECONDS);
            }
        } catch (com.lmax.disruptor.TimeoutException ex) {
            outputDisruptor.halt();
        }
        if (projector != null) {
            projector.stop();   // drain the queue to the DB after the ring has stopped feeding it
        }
        if (journaler != null) {
            journaler.close();
        }
        if (leaderLock != null) {
            leaderLock.close();   // last: nothing of ours appends to the journal after this point
        }
    }

    // ----- gateway commands (the Receptionist edge: validate, convert, sequence) ----------

    public int nextOrderRef() {
        return nextOrderRef.getAndIncrement();
    }

    public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                         int quantity, BigDecimal limitPrice) {
        int securityId = symbols.idFor(ticker);
        long limitPx = Px.toTicks(limitPrice);
        return execute(InputEvent.TYPE_ORDER_NEW, orderRef, accountId, securityId,
            (byte) side.ordinal(), quantity, limitPx, 0L);
    }

    /** A validated new-order command ready for batch sequencing (throughput experiment, option 2). */
    public record NewOrderCommand(int orderRef, int accountId, String ticker, OrderSide side,
                                  int quantity, BigDecimal limitPrice) {}

    /**
     * Batch ingress (throughput experiment): sequence N new-order commands in one shot and block
     * the calling gateway thread once for all N acks. The thread claims a contiguous run of input
     * slots, registers every ack, fills every event, then publishes the whole run with a single
     * cursor advance — so the per-order HTTP round-trip AND the per-order park/unpark on the ack
     * future are amortised across the batch (the single-order {@link #execute} path pays both per
     * order). Ordering within the batch is preserved (lo..hi); other producers (price ticks,
     * concurrent batches) interleave between blocks safely on the multi-producer ring. Nothing is
     * publishable until the final {@code publish(lo, hi)}, so no ack can complete before it is
     * registered.
     */
    public List<OrderSnapshot> executeNewOrderBatch(List<NewOrderCommand> commands) {
        int n = commands.size();
        if (n == 0) {
            return List.of();
        }
        long hi = claimInputSlots(n);
        long lo = hi - (n - 1);
        @SuppressWarnings("unchecked")
        CompletableFuture<OrderSnapshot>[] acks = new CompletableFuture[n];
        long ingressNanos = System.nanoTime();
        long eventTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            long seq = lo + i;
            NewOrderCommand c = commands.get(i);
            acks[i] = readModel.registerAck(seq);
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = InputEvent.TYPE_ORDER_NEW;
            e.orderRef = c.orderRef();
            e.accountId = c.accountId();
            e.securityId = symbols.idFor(c.ticker());
            e.side = (byte) c.side().ordinal();
            e.qty = c.quantity();
            e.limitPx = Px.toTicks(c.limitPrice());
            e.priceTicks = 0L;
            e.ingressNanos = ingressNanos;
            e.eventTimeMillis = eventTimeMillis;
        }
        inputRing.publish(lo, hi);

        List<OrderSnapshot> out = new ArrayList<>(n);
        long deadlineNanos = System.nanoTime() + ackTimeoutMs * 1_000_000L;
        for (int i = 0; i < n; i++) {
            long remaining = deadlineNanos - System.nanoTime();
            try {
                out.add(acks[i].get(Math.max(1L, remaining), TimeUnit.NANOSECONDS));
            } catch (TimeoutException ex) {
                for (int j = i; j < n; j++) {
                    readModel.abandonAck(lo + j);
                }
                throw new GatewayTimeoutException("no acknowledgement for input seq " + (lo + i));
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                for (int j = i; j < n; j++) {
                    readModel.abandonAck(lo + j);
                }
                throw new GatewayTimeoutException("interrupted awaiting input seq " + (lo + i));
            }
        }
        return out;
    }

    public OrderSnapshot executeCancel(int orderRef) {
        return execute(InputEvent.TYPE_ORDER_CANCEL, orderRef, 0, 0, (byte) 0, 0, 0L, 0L);
    }

    public OrderSnapshot executeForceFill(int orderRef) {
        return execute(InputEvent.TYPE_FORCE_FILL, orderRef, 0, 0, (byte) 0, 0, 0L, 0L);
    }

    public void submitPriceTick(String ticker, BigDecimal price) {
        int securityId = symbols.idFor(ticker);
        long priceTicks = Px.toTicks(price);
        long seq = claimInputSlot();
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = InputEvent.TYPE_PRICE_TICK;
            e.orderRef = 0;
            e.accountId = 0;
            e.securityId = securityId;
            e.side = 0;
            e.qty = 0;
            e.limitPx = 0L;
            e.priceTicks = priceTicks;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            inputRing.publish(seq);
        }
    }

    /**
     * Market trade from the trade ticket (FR-09B08): sequence a TRADE_NEW event so booking +
     * position-keeping run on the single-writer BLP. Fire-and-forget, matching 009's POST
     * /trade/ contract (booking is async; the gateway does not block on the read-model).
     */
    public void executeTradeNew(int accountId, String ticker, OrderSide side, int quantity) {
        int securityId = symbols.idFor(ticker);
        long seq = claimInputSlot();
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = InputEvent.TYPE_TRADE_NEW;
            e.orderRef = 0;
            e.accountId = accountId;
            e.securityId = securityId;
            e.side = (byte) side.ordinal();
            e.qty = quantity;
            e.limitPx = 0L;
            e.priceTicks = 0L;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            inputRing.publish(seq);
        }
    }

    /**
     * Claim the next input-ring slot. The fast path is the lock-free tryNext claim; only
     * when the ring is full (a lagging consumer still owns the slot) does the producer
     * fall back to the waiting claim — counted so bounded backpressure is observable
     * (FR-09B07, traderx_input_backpressure_events_total).
     */
    private long claimInputSlot() {
        try {
            return inputRing.tryNext();
        } catch (InsufficientCapacityException ex) {
            metrics.recordBackpressureWait();
            return inputRing.next();
        }
    }

    /**
     * Claim a contiguous run of {@code n} input-ring slots (batch ingress). Same lock-free
     * fast path / counted-backpressure fallback as {@link #claimInputSlot}; returns the
     * sequence of the LAST slot (the run is {@code [returned - n + 1 .. returned]}).
     */
    private long claimInputSlots(int n) {
        try {
            return inputRing.tryNext(n);
        } catch (InsufficientCapacityException ex) {
            metrics.recordBackpressureWait();
            return inputRing.next(n);
        }
    }

    private OrderSnapshot execute(byte type, int orderRef, int accountId, int securityId, byte side,
                                  int quantity, long limitPx, long priceTicks) {
        long seq = claimInputSlot();
        CompletableFuture<OrderSnapshot> ack = readModel.registerAck(seq);
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = type;
            e.orderRef = orderRef;
            e.accountId = accountId;
            e.securityId = securityId;
            e.side = side;
            e.qty = quantity;
            e.limitPx = limitPx;
            e.priceTicks = priceTicks;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            inputRing.publish(seq);
        }
        try {
            return ack.get(ackTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            readModel.abandonAck(seq);
            throw new GatewayTimeoutException("no acknowledgement for input seq " + seq);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            readModel.abandonAck(seq);
            throw new GatewayTimeoutException("interrupted awaiting input seq " + seq);
        }
    }

    public static final class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) {
            super(message);
        }
    }

    // ----- bootstrap ------------------------------------------------------------------------

    private void seedReadModelIfEmpty() {
        if (!seedEnabled || orderRepository.count() != 0) {
            return;
        }
        orderRepository.saveAll(seedOrders());
    }

    /**
     * Assign the seed tickers' securityIds FIRST, in one fixed order, on every node. The DB
     * warm-start path otherwise assigns ids in DB iteration order (updatedAt DESC, ties arbitrary),
     * which can differ between a leader and a standby seeding independently — and they MUST agree,
     * because the journal speaks securityIds (FR-09B05). Mappings already restored from symbols.tab
     * win (it is loaded before this); fresh nodes converge on this deterministic assignment.
     */
    private void registerSeedSymbols() {
        if (!seedEnabled) {
            return;
        }
        for (OrderRecord r : seedOrders()) {
            symbols.idFor(r.getSecurity());
        }
        symbols.idFor("MS");    // the seed positions (initialSchema.sql) reference these two
        symbols.idFor("BAC");   // beyond the seed order book
    }

    /** The fixed initial book (state 009b). Single source for both the DB seed and the journal-replay
     *  verification's shadow seed, so both reconstruct from the identical initial condition. */
    private List<OrderRecord> seedOrders() {
        return List.of(
            seedOrder("ord-013-0001", 22214, "IBM", OrderSide.Buy, 1800, 1800, new BigDecimal("187.250"), OrderStatus.NEW),
            seedOrder("ord-013-0002", 22214, "MSFT", OrderSide.Sell, 900, 650, new BigDecimal("412.000"), OrderStatus.PARTIALLY_FILLED),
            seedOrder("ord-013-0003", 44044, "JPM", OrderSide.Buy, 1200, 1200, new BigDecimal("191.500"), OrderStatus.NEW),
            seedOrder("ord-013-0004", 52355, "GS", OrderSide.Sell, 300, 0, new BigDecimal("498.000"), OrderStatus.FILLED),
            seedOrder("ord-013-0005", 10031, "NVDA", OrderSide.Buy, 450, 450, new BigDecimal("905.125"), OrderStatus.NEW),
            seedOrder("ord-013-0006", 10031, "C", OrderSide.Sell, 1000, 1000, new BigDecimal("61.500"), OrderStatus.NEW),
            seedOrder("ord-013-0007", 62654, "META", OrderSide.Sell, 500, 500, new BigDecimal("507.880"), OrderStatus.NEW)
        );
    }

    private OrderRecord seedOrder(String orderId, int accountId, String security, OrderSide side,
                                  int quantity, int remaining, BigDecimal limitPrice, OrderStatus status) {
        Instant now = Instant.now();
        OrderRecord order = new OrderRecord();
        order.setOrderId(orderId);
        order.setAccountId(accountId);
        order.setSecurity(security);
        order.setSide(side);
        order.setQuantity(quantity);
        order.setRemainingQuantity(remaining);
        order.setLimitPrice(limitPrice);
        order.setStatus(status);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    /** Warm-start: the persisted read-model is the snapshot; load it into memory (BLP + reads). */
    private void bootstrapFromReadModel() {
        int maxRef = 0;
        long counterCreate = 0;
        for (OrderRecord record : orderRepository.findAllByOrderByUpdatedAtDesc()) {
            Matcher matcher = ORDER_ID_PATTERN.matcher(record.getOrderId() == null ? "" : record.getOrderId());
            if (!matcher.matches()) {
                log.warn("Skipping order with unrecognized id format: {}", record.getOrderId());
                continue;
            }
            int ref = Integer.parseInt(matcher.group(1));
            maxRef = Math.max(maxRef, ref);
            counterCreate++;

            int securityId = symbols.idFor(record.getSecurity());
            readModel.bootstrap(OrderSnapshot.fromRecord(ref, record));
            matchingEngine.bootstrapOrder(
                ref,
                record.getAccountId(),
                securityId,
                (byte) record.getSide().ordinal(),
                record.getQuantity(),
                record.getRemainingQuantity() == null ? 0 : record.getRemainingQuantity(),
                Px.toTicks(record.getLimitPrice()),
                (byte) record.getStatus().ordinal(),
                record.getLastExecutionPrice() == null ? Px.NONE : Px.toTicks(record.getLastExecutionPrice()),
                record.getLastFillQuantity() == null ? 0 : record.getLastFillQuantity(),
                record.getCreatedAt() == null ? 0 : record.getCreatedAt().toEpochMilli(),
                record.getUpdatedAt() == null ? 0 : record.getUpdatedAt().toEpochMilli()
            );
        }
        nextOrderRef.set(maxRef + 1);

        // Warm the BLP's net positions from the persisted POSITIONS read-model (FR-09B16 recovery):
        // the BLP is the single position writer, so it must resume from the durable state on restart.
        for (Position position : positionRepository.findAll()) {
            if (position.getAccountId() == null || position.getSecurity() == null) {
                continue;
            }
            int securityId = symbols.idFor(position.getSecurity());
            matchingEngine.bootstrapPosition(position.getAccountId(), securityId,
                position.getQuantity() == null ? 0 : position.getQuantity(),
                Px.toTicks(position.getAverageCostBasis()));
        }

        // Resume the global trade counter above the max persisted trade id so ids never collide
        // across restarts (mirrors nextOrderRef; foreign-format ids — e.g. 009 seed — are ignored).
        long maxTradeSeq = 0;
        for (Trade trade : tradeRepository.findAll()) {
            maxTradeSeq = Math.max(maxTradeSeq, OrderSnapshot.tradeSeqFromId(trade.getId()));
        }
        matchingEngine.bootstrapTradeCounter(maxTradeSeq);

        // Counter parity with 009's refreshCountersFromDatabase().
        readModel.setCounter("create", counterCreate);
        readModel.setCounter("partial_fill", readModel.countByStatus(OrderStatus.PARTIALLY_FILLED));
        readModel.setCounter("fill", readModel.countByStatus(OrderStatus.FILLED));
        readModel.setCounter("cancel", readModel.countByStatus(OrderStatus.CANCELED));
        readModel.setCounter("reject", readModel.countByStatus(OrderStatus.REJECTED));
        readModel.setCounter("force_fill", 0);
    }

    /**
     * Step 1 of "drop the DB": prove that replaying the input journal reconstructs the SAME
     * recoverable state the DB warm-start produced. The live BLP stays DB-loaded (unchanged); this
     * builds an ISOLATED shadow engine with its own discarding output ring, seeds it identically,
     * replays the journal into it, and diffs the digests. Verify-only — zero effect on the running
     * system. Precondition for a clean compare: the DB and journal were empty together at first start
     * (so the journal covers exactly the history the DB accumulated). Gate off with
     * journal.replay.verify=false once trusted.
     */
    private void verifyJournalReplay() {
        if (!journalEnabled || !replayVerifyEnabled) {
            return;
        }
        JournalReader reader = new JournalReader(Path.of(journalPath));
        Disruptor<OutputEvent> shadowOut = new Disruptor<>(OutputEvent::newInstance, 16384,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new BlockingWaitStrategy());
        shadowOut.handleEventsWith((EventHandler<OutputEvent>) (ev, seq, endOfBatch) -> { /* discard */ });
        shadowOut.start();
        try {
            MatchingEngine shadow = new MatchingEngine(new OutputPublisher(shadowOut.getRingBuffer()),
                new HotPathMetrics(), maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity,
                maxRetainedTerminal);
            SnapshotStore.Data snap = snapshotStore != null ? snapshotStore.read() : null;
            long t0 = System.nanoTime();
            long replayed;
            String mode;
            if (snap != null) {
                // Snapshot+tail recovery (step a): load the checkpoint, then replay only the journal tail.
                loadSnapshotInto(shadow, snap);
                replayed = reader.replayFrom(snap.coveredOffset(), e -> shadow.onEvent(e, e.seq, true));
                mode = "snapshot(@" + snap.coveredOffset() + ")+tail";
            } else {
                // No snapshot yet: seed the initial condition and replay the whole journal.
                seedShadow(shadow);
                replayed = reader.replay(e -> shadow.onEvent(e, e.seq, true));
                mode = "full-journal";
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            MatchingEngine.RecoveryDigest live = matchingEngine.recoveryDigest();
            MatchingEngine.RecoveryDigest replay = shadow.recoveryDigest();
            boolean ordersMatch = live.openOrders() == replay.openOrders()
                && live.orderHash() == replay.orderHash();
            boolean positionsMatch = live.positions() == replay.positions()
                && live.positionHash() == replay.positionHash();
            boolean tradesMatch = live.tradeCounter() == replay.tradeCounter();

            if (ordersMatch && positionsMatch && tradesMatch) {
                log.info("JOURNAL-REPLAY VERIFY: PASS [{}] - replayed {} tail events in {} ms; replay == DB "
                    + "warm-start (openOrders={}, positions={}, tradeCounter={}); replay additionally recovered "
                    + "{} security prices the DB cannot. Durability via the journal is sound.",
                    mode, replayed, ms, live.openOrders(), live.positions(), live.tradeCounter(),
                    replay.pricedSecurities());
            } else {
                log.warn("JOURNAL-REPLAY VERIFY: MISMATCH - replayed {} events in {} ms (DB+journal must start "
                    + "empty together for a clean compare)\n  DB-live: openOrders={} (h={}) positions={} (h={}) "
                    + "tradeCounter={}\n  replay : openOrders={} (h={}) positions={} (h={}) tradeCounter={}",
                    replayed, ms,
                    live.openOrders(), live.orderHash(), live.positions(), live.positionHash(), live.tradeCounter(),
                    replay.openOrders(), replay.orderHash(), replay.positions(), replay.positionHash(),
                    replay.tradeCounter());
                logStateDiff(matchingEngine, shadow);
            }
        } catch (Exception ex) {
            log.warn("JOURNAL-REPLAY VERIFY: error during verification (non-fatal): {}", ex.toString(), ex);
        } finally {
            try {
                shadowOut.shutdown(2, TimeUnit.SECONDS);
            } catch (com.lmax.disruptor.TimeoutException ex) {
                shadowOut.halt();
            }
        }
    }

    /** Seed the shadow engine with the same fixed initial condition the DB is seeded with — the
     *  seed order book AND the seed positions — so a clean (empty-start) journal replay reconstructs
     *  the identical state. These seeds are pre-loaded state, not produced by any journaled event, so
     *  replay alone cannot recreate them; the comparison is replay+seed vs DB(seed+accumulated). */
    private void seedShadow(MatchingEngine shadow) {
        if (!seedEnabled) {
            return;
        }
        for (OrderRecord r : seedOrders()) {
            Matcher matcher = ORDER_ID_PATTERN.matcher(r.getOrderId() == null ? "" : r.getOrderId());
            if (!matcher.matches()) {
                continue;
            }
            int ref = Integer.parseInt(matcher.group(1));
            int securityId = symbols.idFor(r.getSecurity());
            shadow.bootstrapOrder(ref, r.getAccountId(), securityId, (byte) r.getSide().ordinal(),
                r.getQuantity(), r.getRemainingQuantity() == null ? 0 : r.getRemainingQuantity(),
                Px.toTicks(r.getLimitPrice()), (byte) r.getStatus().ordinal(),
                r.getLastExecutionPrice() == null ? Px.NONE : Px.toTicks(r.getLastExecutionPrice()),
                r.getLastFillQuantity() == null ? 0 : r.getLastFillQuantity(),
                r.getCreatedAt() == null ? 0 : r.getCreatedAt().toEpochMilli(),
                r.getUpdatedAt() == null ? 0 : r.getUpdatedAt().toEpochMilli());
        }
        // Seed positions mirror database/initialSchema.sql: pre-loaded holdings for accounts 22214
        // and 52355 that no journaled event produces, so the shadow must start from them as well.
        seedShadowPosition(shadow, 22214, "MS", 1000, "95.125");
        seedShadowPosition(shadow, 22214, "IBM", -100, "136.250");
        seedShadowPosition(shadow, 22214, "C", -2000, "57.500");
        seedShadowPosition(shadow, 52355, "BAC", -2400, "41.125");
    }

    private void seedShadowPosition(MatchingEngine shadow, int accountId, String security, int quantity,
                                    String averageCostBasis) {
        shadow.bootstrapPosition(accountId, symbols.idFor(security), quantity,
            Px.toTicks(new BigDecimal(averageCostBasis)));
    }

    /** Diagnostic: log the open orders / positions that differ between the DB-warm-started live engine
     *  and the journal-replay shadow, to pinpoint where (and why) recovery diverges. */
    private void logStateDiff(MatchingEngine live, MatchingEngine shadow) {
        java.util.Map<Long, long[]> liveO = new java.util.HashMap<>();
        for (long[] t : live.openOrderTuples()) {
            liveO.put(t[0], t);
        }
        java.util.Map<Long, long[]> repO = new java.util.HashMap<>();
        for (long[] t : shadow.openOrderTuples()) {
            repO.put(t[0], t);
        }
        java.util.TreeSet<Long> refs = new java.util.TreeSet<>();
        refs.addAll(liveO.keySet());
        refs.addAll(repO.keySet());
        int shown = 0;
        for (Long ref : refs) {
            long[] a = liveO.get(ref);
            long[] b = repO.get(ref);
            if (a == null || b == null || a[1] != b[1] || a[2] != b[2] || a[3] != b[3]) {
                log.warn("  OPEN-ORDER DIFF ref={} sec={} live=[{}] replay=[{}]", ref,
                    a != null ? sym((int) a[5]) : (b != null ? sym((int) b[5]) : "?"),
                    a == null ? "<absent>" : ("st=" + a[1] + " rem=" + a[2] + " lim=" + a[3] + " acct=" + a[4]),
                    b == null ? "<absent>" : ("st=" + b[1] + " rem=" + b[2] + " lim=" + b[3] + " acct=" + b[4]));
                if (++shown >= 12) { log.warn("  ... (open-order diffs truncated)"); break; }
            }
        }
        java.util.Map<Long, long[]> liveP = new java.util.HashMap<>();
        for (long[] t : live.positionTuples()) {
            liveP.put((t[0] << 32) | (t[1] & 0xFFFFFFFFL), t);
        }
        java.util.Map<Long, long[]> repP = new java.util.HashMap<>();
        for (long[] t : shadow.positionTuples()) {
            repP.put((t[0] << 32) | (t[1] & 0xFFFFFFFFL), t);
        }
        java.util.TreeSet<Long> pks = new java.util.TreeSet<>();
        pks.addAll(liveP.keySet());
        pks.addAll(repP.keySet());
        shown = 0;
        for (Long k : pks) {
            long[] a = liveP.get(k);
            long[] b = repP.get(k);
            if (a == null || b == null || a[2] != b[2] || a[3] != b[3]) {
                long[] any = a != null ? a : b;
                log.warn("  POSITION DIFF acct={} sec={} live=[{}] replay=[{}]", any[0], sym((int) any[1]),
                    a == null ? "<absent>" : ("qty=" + a[2] + " avg=" + a[3]),
                    b == null ? "<absent>" : ("qty=" + b[2] + " avg=" + b[3]));
                if (++shown >= 12) { log.warn("  ... (position diffs truncated)"); break; }
            }
        }
    }

    private String sym(int securityId) {
        try {
            return symbols.tickerFor(securityId);
        } catch (RuntimeException ex) {
            return "sec#" + securityId;
        }
    }

    // ----- periodic snapshot (step a: bound journal replay) ------------------------------------

    private void startSnapshotScheduler() {
        if (snapshotStore == null || snapshotIntervalMs <= 0) {
            return;
        }
        snapshotScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-scheduler");
            t.setDaemon(true);
            return t;
        });
        snapshotScheduler.scheduleAtFixedRate(this::submitSnapshot, snapshotIntervalMs, snapshotIntervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("Periodic state snapshot every {} ms -> {}/snapshot.dat", snapshotIntervalMs, journalPath);
    }

    /** Sequence a SNAPSHOT marker so the BLP checkpoints its state at a consistent point in the stream. */
    public void submitSnapshot() {
        if (inputRing == null) {
            return;
        }
        long seq = claimInputSlot();
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = InputEvent.TYPE_SNAPSHOT;
            e.orderRef = 0;
            e.accountId = 0;
            e.securityId = 0;
            e.side = 0;
            e.qty = 0;
            e.limitPx = 0L;
            e.priceTicks = 0L;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            inputRing.publish(seq);
        }
    }

    /** Runs on the BLP thread at a SNAPSHOT marker: write a consistent full-state checkpoint plus the
     *  journal tail boundary it covers, so recovery loads it and replays only the tail (bounded). */
    private void writeSnapshot() {
        try {
            long offset = journaler == null ? 0L : journaler.lastSnapshotOffset();
            snapshotStore.write(new SnapshotStore.Data(offset, nextOrderRef.get(),
                matchingEngine.tradeCounter(), matchingEngine.priceTuples(),
                matchingEngine.positionTuples(), matchingEngine.allOrderTuples()));
        } catch (Exception ex) {
            log.warn("Snapshot write failed (continuing): {}", ex.toString());
        }
    }

    /** Restore engine state (orders + positions + prices + trade counter) from a loaded snapshot. */
    private void loadSnapshotInto(MatchingEngine engine, SnapshotStore.Data data) {
        for (long[] o : data.orders()) {
            engine.bootstrapOrder((int) o[0], (int) o[1], (int) o[2], (byte) o[3], (int) o[4], (int) o[5],
                o[6], (byte) o[7], o[8], (int) o[9], o[10], o[11]);
        }
        for (long[] p : data.positions()) {
            engine.bootstrapPosition((int) p[0], (int) p[1], (int) p[2], p[3]);
        }
        for (long[] pr : data.prices()) {
            engine.bootstrapPrice((int) pr[0], pr[1]);
        }
        engine.bootstrapTradeCounter(data.tradeCounter());
    }

    // ----- step b: snapshot+journal recovery is AUTHORITATIVE (no DB) --------------------------

    /** Reconstruct the LIVE engine and read model from snapshot+journal (or seed+journal when fresh),
     *  with the NATS bridges gated so recovery does not re-broadcast history. Replaces the DB warm-start
     *  when recovery.source=journal — the matcher then needs no database to recover. */
    private void recoverLiveFromJournal() {
        JournalReader reader = new JournalReader(Path.of(journalPath));
        SnapshotStore.Data snap = null;
        try {
            snap = snapshotStore != null ? snapshotStore.read() : null;
        } catch (Exception ex) {
            log.warn("Snapshot read failed; recovering from the full journal: {}", ex.toString());
        }
        readModel.setReplaying(true);   // gate NATS handlers; the marshaller still rebuilds the read model
        long replayed = 0;
        String mode = "unknown";
        try {
            long offset;
            if (snap != null) {
                loadSnapshotInto(matchingEngine, snap);
                for (long[] o : snap.orders()) {
                    readModel.bootstrap(snapshotOrderToReadModel(o));
                }
                nextOrderRef.set(snap.nextOrderRef());
                offset = snap.coveredOffset();
                mode = "snapshot(@" + offset + ")+tail";
            } else {
                seedLiveInitialCondition();
                offset = 0;
                mode = "seed+full-journal";
            }
            // applyFollowedEvent also advances nextOrderRef past every replayed ORDER_NEW: orders
            // created after the snapshot boundary must not have their refs re-issued (id collisions).
            replayed = reader.replayFrom(offset, this::applyFollowedEvent);
            drainOutputRing();   // let the marshaller rebuild the read model from the replayed tail
        } catch (Exception ex) {
            log.error("Journal recovery failed: {}", ex.toString(), ex);
        } finally {
            readModel.setReplaying(false);
        }
        refreshCounters();
        log.info("LIVE RECOVERY [journal]: {} - replayed {} tail events; {} orders warm, nextRef {}, tradeCounter {}",
            mode, replayed, readModel.totalOrders(), nextOrderRef.get(), matchingEngine.tradeCounter());
    }

    /** Seed the fresh initial condition (the DB's seed orders + seed positions) directly into the live
     *  engine and read model — the journal-only equivalent of seedReadModelIfEmpty + initialSchema.sql. */
    private void seedLiveInitialCondition() {
        if (!seedEnabled) {
            return;
        }
        for (OrderRecord r : seedOrders()) {
            Matcher matcher = ORDER_ID_PATTERN.matcher(r.getOrderId() == null ? "" : r.getOrderId());
            if (!matcher.matches()) {
                continue;
            }
            int ref = Integer.parseInt(matcher.group(1));
            int securityId = symbols.idFor(r.getSecurity());
            matchingEngine.bootstrapOrder(ref, r.getAccountId(), securityId, (byte) r.getSide().ordinal(),
                r.getQuantity(), r.getRemainingQuantity() == null ? 0 : r.getRemainingQuantity(),
                Px.toTicks(r.getLimitPrice()), (byte) r.getStatus().ordinal(),
                r.getLastExecutionPrice() == null ? Px.NONE : Px.toTicks(r.getLastExecutionPrice()),
                r.getLastFillQuantity() == null ? 0 : r.getLastFillQuantity(),
                r.getCreatedAt() == null ? 0 : r.getCreatedAt().toEpochMilli(),
                r.getUpdatedAt() == null ? 0 : r.getUpdatedAt().toEpochMilli());
            readModel.bootstrap(OrderSnapshot.fromRecord(ref, r));
            nextOrderRef.set(Math.max(nextOrderRef.get(), ref + 1));
        }
        seedLivePosition(22214, "MS", 1000, "95.125");
        seedLivePosition(22214, "IBM", -100, "136.250");
        seedLivePosition(22214, "C", -2000, "57.500");
        seedLivePosition(52355, "BAC", -2400, "41.125");
    }

    private void seedLivePosition(int accountId, String security, int quantity, String averageCostBasis) {
        matchingEngine.bootstrapPosition(accountId, symbols.idFor(security), quantity,
            Px.toTicks(new BigDecimal(averageCostBasis)));
    }

    /** Build a read-model entry from a snapshot order tuple (so the read model is restored alongside
     *  the engine when loading a snapshot, before the tail rebuilds the rest via the marshaller). */
    private OrderSnapshot snapshotOrderToReadModel(long[] o) {
        OrderRecord r = new OrderRecord();
        int ref = (int) o[0];
        r.setOrderId(String.format(Locale.ROOT, "ord-013-%04d", ref));
        r.setAccountId((int) o[1]);
        r.setSecurity(symbols.tickerFor((int) o[2]));
        r.setSide(OrderSide.values()[(int) o[3]]);
        r.setQuantity((int) o[4]);
        r.setRemainingQuantity((int) o[5]);
        r.setLimitPrice(Px.toDecimalOrZero(o[6]));
        r.setStatus(OrderStatus.values()[(int) o[7]]);
        r.setLastExecutionPrice(o[8] == Px.NONE ? null : Px.toDecimalOrZero(o[8]));
        r.setLastFillQuantity((int) o[9]);
        r.setCreatedAt(Instant.ofEpochMilli(o[10]));
        r.setUpdatedAt(Instant.ofEpochMilli(o[11]));
        return OrderSnapshot.fromRecord(ref, r);
    }

    /** Wait for the marshaller to catch up to the output cursor so the read model reflects the replay. */
    private void drainOutputRing() {
        if (marshaller == null || outputRing == null) {
            return;
        }
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (marshaller.marshalledSeq() < outputRing.getCursor() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void refreshCounters() {
        readModel.setCounter("create", readModel.totalOrders());
        readModel.setCounter("partial_fill", readModel.countByStatus(OrderStatus.PARTIALLY_FILLED));
        readModel.setCounter("fill", readModel.countByStatus(OrderStatus.FILLED));
        readModel.setCounter("cancel", readModel.countByStatus(OrderStatus.CANCELED));
        readModel.setCounter("reject", readModel.countByStatus(OrderStatus.REJECTED));
        readModel.setCounter("force_fill", 0);
    }

    // ----- wiring helpers ----------------------------------------------------------------------

    private static int normalizeRingSize(int requested) {
        int size = Math.max(1024, Integer.highestOneBit(requested));
        return size == requested ? requested : size * (requested > size ? 2 : 1);
    }

    private static WaitStrategy waitStrategy(String name) {
        return switch (name == null ? "blocking" : name.trim().toLowerCase(Locale.ROOT)) {
            case "busyspin" -> new BusySpinWaitStrategy();
            case "yielding" -> new YieldingWaitStrategy();
            case "sleeping" -> new SleepingWaitStrategy();
            default -> new BlockingWaitStrategy();
        };
    }

    // ----- telemetry accessors for the service facade ------------------------------------------

    public InMemoryOrderReadModel readModel() {
        return readModel;
    }

    public SymbolTable symbols() {
        return symbols;
    }

    /** Net positions straight from the BLP's in-memory PositionBook (no DB) — the read-side repoint for
     *  the cutover. Flat positions are omitted; filter by account when given. */
    public List<PositionUpdate> listPositions(Integer accountIdFilter) {
        List<PositionUpdate> out = new ArrayList<>();
        if (matchingEngine == null) {
            return out;
        }
        for (long[] p : matchingEngine.positionTuples()) {
            int accountId = (int) p[0];
            if (accountIdFilter != null && accountIdFilter != accountId) {
                continue;
            }
            if (p[2] == 0) {
                continue;   // flat
            }
            PositionUpdate pos = new PositionUpdate();
            pos.setAccountId(accountId);
            pos.setSecurity(symbols.tickerFor((int) p[1]));
            pos.setQuantity((int) p[2]);
            pos.setAverageCostBasis(p[3] == Px.NONE ? null : Px.toBigDecimal(p[3]));
            out.add(pos);
        }
        return out;
    }

    public HotPathMetrics metrics() {
        return metrics;
    }

    public MatchingEngine blp() {
        return matchingEngine;
    }

    public long inputPublishedSeq() {
        return inputRing == null ? -1 : inputRing.getCursor();
    }

    public long inputRemainingCapacity() {
        return inputRing == null ? 0 : inputRing.remainingCapacity();
    }

    public long outputRemainingCapacity() {
        return outputRing == null ? 0 : outputRing.remainingCapacity();
    }

    public long journaledSeq() {
        return journaler == null ? -1 : journaler.journaledSeq();
    }

    public long replicatedSeq() {
        return replicator == null ? -1 : replicator.replicatedSeq();
    }

    public long gatingSeq() {
        return Math.min(journaledSeq(), replicatedSeq());
    }

    // ----- failover telemetry (FR-09B30..B32) ---------------------------------------------------

    /** True once this node is the leader: accepting commands, journaling, publishing outputs. */
    public boolean isLive() {
        return live;
    }

    /** "primary" | "standby" | "promoted" (a standby that took over). */
    public String role() {
        return role;
    }

    public long promotions() {
        return promotions.get();
    }

    public boolean leaderLockHeld() {
        LeaderLock lock = leaderLock;
        return lock != null && lock.held();
    }

    /** True when this node's appends are actually reaching the journal (leaders only). */
    public boolean journalWriting() {
        Journaler j = journaler;
        return j != null && j.isWriting();
    }

    /** Follower staleness bound: journal bytes the leader wrote that we have not applied yet. */
    public long followerLagBytes() {
        JournalFollower f = follower;
        return f == null ? 0 : f.lagBytes();
    }

    public long followerAppliedEvents() {
        JournalFollower f = follower;
        return f == null ? 0 : f.appliedEvents();
    }

    public long marshalledSeq() {
        return marshaller == null ? -1 : marshaller.marshalledSeq();
    }

    public long orderUpdatesOut() {
        return marshaller == null ? 0 : marshaller.orderUpdates();
    }

    public long tradesBookedOut() {
        return marshaller == null ? 0 : marshaller.tradesBooked();
    }

    public long peakTradesPerSecondOut() {
        return marshaller == null ? 0 : marshaller.peakTradesPerSecond();
    }

    public long positionsUpdatedOut() {
        return marshaller == null ? 0 : marshaller.positionsUpdated();
    }

    public long projectedSeq() {
        return projector == null ? -1 : projector.projectedSeq();
    }

    public long projectorPendingRows() {
        return projector == null ? 0 : projector.pendingRows();
    }

    public long tradesPersistedOut() {
        return projector == null ? 0 : projector.tradesPersisted();
    }

    public long projectorQueueDepth() {
        return projector == null ? 0 : projector.queueDepth();
    }

    public long projectorQueueCapacity() {
        return projector == null ? 0 : projector.queueCapacity();
    }

    public long projectorEnqueueBlocks() {
        return projector == null ? 0 : projector.enqueueBlocks();
    }

    public String runtimeProfile() {
        return runtimeProfile;
    }

    public long blpAllocatedBytes() {
        long threadId = matchingEngine == null ? 0 : matchingEngine.blpThreadId();
        if (threadId == 0) {
            return 0;
        }
        var threadMx = ManagementFactory.getThreadMXBean();
        if (threadMx instanceof com.sun.management.ThreadMXBean sunThreadMx
            && sunThreadMx.isThreadAllocatedMemorySupported()) {
            long allocated = sunThreadMx.getThreadAllocatedBytes(threadId);
            return Math.max(0, allocated);
        }
        return 0;
    }
}
