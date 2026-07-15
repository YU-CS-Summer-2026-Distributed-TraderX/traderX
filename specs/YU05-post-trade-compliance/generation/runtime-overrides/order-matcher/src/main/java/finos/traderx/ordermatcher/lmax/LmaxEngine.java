package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.repository.OrderRepository;
import finos.traderx.ordermatcher.repository.PositionRepository;
import finos.traderx.ordermatcher.repository.TradeRepository;
import finos.traderx.ordermatcher.reporting.AuditLogHandler;
import finos.traderx.ordermatcher.reporting.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
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
    private final int blpPinCpu;
    private final long snapshotIntervalMs;
    private final String recoverySource;        // "db" (warm-start) or "journal" (snapshot+replay)
    private final boolean projectorDbEnabled;   // false => no DB writes at all (cutover)
    private final long ackTimeoutMs;
    private final String runtimeProfile;
    private final int journalBatchRecords;   // journal write-coalescing buffer depth (Tier 3-D)
    private final int terminalRetain;        // bounded terminal-order retention cap (Tier 2-B)
    private final ApplicationEventPublisher applicationEventPublisher;
    private final boolean replicationEnabled;
    private final String podName;
    private final String natsAddress;
    private final ReplicationRole replicationRole;
    // In-memory risk gateway (state YU03): the Gateway replica feeding preliminary screening and
    // the config for the BLP's authoritative risk state (SEC 15c3-5 baseline controls).
    private final GatewayReplicaStore gatewayReplicas;
    // Post-trade compliance (state YU05, ADR-022): replay-safe trade blotter feeding
    // reconciliation; rebuilt during recovery replay (see TradeBlotterHandler doc).
    private final TradeBlotter tradeBlotter;
    // Post-trade compliance (state YU05, FR-PTC10): the most recent on-demand full-journal
    // reindex, if one has ever been run (see reindexFullHistory()). Null until first triggered.
    private volatile TradeBlotter fullHistoryIndex;
    // Post-trade compliance (state YU05, FR-PTC02): passed through to ProjectorHandler, which
    // stamps each booked trade's initial settlementDate (the Projector is the live TRADES writer,
    // not trade-processor — see research.md's corrected write-path finding).
    private final int settlementTPlusDays;
    private final int riskMaxAccounts;
    private final int riskIdempotencyCapacity;
    private final long riskCreditLimitTicks;
    private final int riskMaxOrderQuantity;
    private final long riskMaxOrderNotionalTicks;
    private final long riskPriceMaxAgeMillis;
    private final int riskMaxPositionQuantity;
    private final long riskMaxConcentrationNotionalTicks;
    private final boolean riskEnabled;
    private BlpRiskState riskState;

    private final SymbolTable symbols;
    private final HotPathMetrics metrics = new HotPathMetrics();
    private final InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
    private final AtomicInteger nextOrderRef = new AtomicInteger(1);
    private volatile boolean recoveryReady;
    private volatile String recoveryStatus = "starting";
    private volatile String recoveryMode = "not-started";
    private volatile String recoveryError;

    private Disruptor<InputEvent> inputDisruptor;
    private Disruptor<OutputEvent> outputDisruptor;
    private RingBuffer<InputEvent> inputRing;
    private RingBuffer<OutputEvent> outputRing;
    private MatchingEngine matchingEngine;
    private Journaler journaler;
    private DelegatingReplicator delegatingReplicator;   // always in the ring; delegates to stub or NATS
    private NatsJournalReplicator natsReplicator;
    private MarshallerHandler marshaller;
    private ProjectorHandler projector;
    private SnapshotStore snapshotStore;
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;
    private io.nats.client.Connection replicationConn;
    private ReplicationFollower replicationFollower;
    private LeaderElection leaderElection;

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
        @Value("${blp.pin.cpu:-1}") int blpPinCpu,
        @Value("${snapshot.interval.ms:0}") long snapshotIntervalMs,
        @Value("${recovery.source:db}") String recoverySource,
        @Value("${output.projector.db.enabled:true}") boolean projectorDbEnabled,
        @Value("${blp.gateway.ack-timeout-ms:5000}") long ackTimeoutMs,
        @Value("${runtime.profile:demo}") String runtimeProfile,
        @Value("${journal.batch.records:1024}") int journalBatchRecords,
        @Value("${blp.terminal.retain:262144}") int terminalRetain,
        @Value("${blp.replication.enabled:false}") boolean replicationEnabled,
        @Value("${blp.pod.name:order-matcher-0}") String podName,
        @Value("${nats.address:nats://localhost:4222}") String natsAddress,
        @Value("${risk.enabled:true}") boolean riskEnabled,
        @Value("${risk.max-accounts:4096}") int riskMaxAccounts,
        @Value("${risk.idempotency.capacity:65536}") int riskIdempotencyCapacity,
        @Value("${risk.credit-limit-ticks:5000000000000000}") long riskCreditLimitTicks,
        @Value("${risk.max-order-quantity:1000000}") int riskMaxOrderQuantity,
        @Value("${risk.max-order-notional-ticks:1000000000000000}") long riskMaxOrderNotionalTicks,
        @Value("${risk.price.max-age-ms:30000}") long riskPriceMaxAgeMillis,
        @Value("${risk.max-position-quantity:1000000}") int riskMaxPositionQuantity,
        @Value("${risk.max-concentration-notional-ticks:5000000000000000}") long riskMaxConcentrationNotionalTicks,
        GatewayReplicaStore gatewayReplicas,
        TradeBlotter tradeBlotter,
        @Value("${settlement.t-plus-days:1}") int settlementTPlusDays,
        ApplicationEventPublisher applicationEventPublisher,
        ReplicationRole replicationRole
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
        this.blpPinCpu = blpPinCpu;
        this.snapshotIntervalMs = snapshotIntervalMs;
        this.recoverySource = recoverySource;
        this.projectorDbEnabled = projectorDbEnabled;
        this.ackTimeoutMs = ackTimeoutMs;
        this.runtimeProfile = runtimeProfile;
        this.journalBatchRecords = journalBatchRecords;
        this.terminalRetain = terminalRetain;
        this.replicationEnabled = replicationEnabled;
        this.podName = podName;
        this.natsAddress = natsAddress;
        this.riskEnabled = riskEnabled;
        this.riskMaxAccounts = riskMaxAccounts;
        this.riskIdempotencyCapacity = riskIdempotencyCapacity;
        this.riskCreditLimitTicks = riskCreditLimitTicks;
        this.riskMaxOrderQuantity = riskMaxOrderQuantity;
        this.riskMaxOrderNotionalTicks = riskMaxOrderNotionalTicks;
        this.riskPriceMaxAgeMillis = riskPriceMaxAgeMillis;
        this.riskMaxPositionQuantity = riskMaxPositionQuantity;
        this.riskMaxConcentrationNotionalTicks = riskMaxConcentrationNotionalTicks;
        this.gatewayReplicas = gatewayReplicas;
        this.tradeBlotter = tradeBlotter;
        this.settlementTPlusDays = settlementTPlusDays;
        this.applicationEventPublisher = applicationEventPublisher;
        this.replicationRole = replicationRole;
        this.symbols = new SymbolTable(maxSecurities);
    }

    @Override
    public void afterPropertiesSet() {
        recoveryReady = false;
        recoveryStatus = "starting";
        recoveryMode = recoverySource == null ? "db" : recoverySource.toLowerCase(Locale.ROOT);
        recoveryError = null;
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        try {
        // Restore the durable ticker->securityId mapping FIRST (before any idFor): the journal stores
        // ids, so replay must resolve them to the same tickers the original run used (FR-09B05 recovery).
        if (journalEnabled) {
            symbols.enablePersistence(Path.of(journalPath).resolve("symbols.tab"));
            snapshotStore = new SnapshotStore(Path.of(journalPath));
        }
        boolean journalRecovery = "journal".equalsIgnoreCase(recoverySource);
        if (!journalRecovery && projectorDbEnabled) {
            seedReadModelIfEmpty();   // DB is the source of truth: seed it
        }

        // Output ring first: the BLP needs its publisher before it can run.
        marshaller = new MarshallerHandler(readModel, symbols, metrics);
        if (projectorDbEnabled) {
            projector = new ProjectorHandler(jdbcTemplate, symbols,
                projectorBatchSize, projectorQueueCapacity, metrics, settlementTPlusDays);
        }
        // Replication: determine role before wiring handlers (role gates their output).
        if (replicationEnabled) {
            initReplication();
        } else {
            replicationRole.set(ReplicationRole.Role.PRIMARY);
        }

        NatsBridgeHandler natsBridge = new NatsBridgeHandler(orderPublisher, symbols, readModel, replicationRole);
        AccountTradeHandler accountTrade = new AccountTradeHandler(accountTradePublisher, symbols, readModel, replicationRole);
        PositionUpdateHandler positionUpdate = new PositionUpdateHandler(positionPublisher, symbols, readModel, replicationRole);

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
        // Post-trade compliance (YU05): no isReplaying()/follower guard — must capture during
        // recovery replay too, unlike the NATS/DB bridges above (see TradeBlotterHandler doc).
        outputHandlers.add(new TradeBlotterHandler(tradeBlotter, symbols));
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

        // In-memory risk gateway: align the replica's security ids to the (possibly
        // journal-restored) SymbolTable, then build the BLP's authoritative risk state from the
        // replica's seeded initial condition (deterministic; sequenced control events layer on top).
        if (riskEnabled) {
            gatewayReplicas.alignSecurityIds(symbols::idFor);
            riskState = newRiskState(gatewayReplicas.metrics());
            riskState.bootstrap(gatewayReplicas.snapshot());
            riskState.putLimits(riskMaxPositionQuantity, riskMaxConcentrationNotionalTicks);
            // YU04 (ADR-019/FR-IMRG05): readiness is no longer granted here unconditionally on
            // seeds+aligned-ids alone — ReplicaBootstrap now grants it only once BOTH durable
            // control feeds (account, security) have installed a valid snapshot and caught up to
            // their observed high watermark. Until then the replica stays not-ready (fail-closed,
            // CONTROL_STATE_STALE) even though the seed image + aligned ids are already installed.
        }

        matchingEngine = new MatchingEngine(new OutputPublisher(outputRing),
            metrics, maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity, terminalRetain,
            riskState);
        matchingEngine.setPinCpu(blpPinCpu);   // perf profile: pin the BLP thread on ring start

        // Recovery: DB warm-start (+ shadow verify) OR snapshot+journal authoritative (no DB).
        if (journalRecovery) {
            recoverLiveFromJournal();
        } else {
            bootstrapFromReadModel();
            verifyJournalReplay();   // prove the journal alone reconstructs the same state (verify-only)
        }

        // Set the snapshot trigger only AFTER recovery, so SNAPSHOT markers replayed from the journal
        // tail are no-ops and do not overwrite snapshot.dat with mid-recovery state.
        if (snapshotStore != null) {
            matchingEngine.setSnapshotTrigger(this::writeSnapshot);
        }

        // Input ring: journaler + replicator run in parallel; the BLP is gated behind both
        // (sequence barrier), so every event it acts on is already durable and replicated.
        // DelegatingReplicator is always wired; its inner delegate is hot-swapped on role change
        // without restarting the Disruptor (see onRoleChange).
        journaler = new Journaler(journalEnabled, Path.of(journalPath), metrics, journalBatchRecords);
        inputDisruptor = new Disruptor<>(InputEvent::newInstance, normalizeRingSize(inputRingSize),
            DaemonThreadFactory.INSTANCE, ProducerType.MULTI, waitStrategy(inputWaitStrategy));
        EventHandler<InputEvent> initialDelegate = (replicationEnabled && replicationRole.isPrimary() && natsReplicator != null)
            ? natsReplicator
            : new ReplicatorStub();
        delegatingReplicator = new DelegatingReplicator(initialDelegate);
        inputDisruptor.handleEventsWith(journaler, delegatingReplicator).then(matchingEngine);
        inputDisruptor.start();
        inputRing = inputDisruptor.getRingBuffer();
        if (replicationFollower != null) {
            replicationFollower.setInputRing(inputRing);
        }
        startSnapshotScheduler();

        log.info("LMAX hot path live: profile={} role={} inputRing={} outputRing={} journal={} ({} orders warm)",
            runtimeProfile, replicationRole.get(), normalizeRingSize(inputRingSize), normalizeRingSize(outputRingSize),
            journalEnabled ? journalPath : "disabled", readModel.totalOrders());
        if (replicationEnabled && replicationRole.isFollower() && replicationFollower != null) {
            // Follower: start JetStream subscription (blocks until caught up, then signals readiness).
            recoveryStatus = "follower-catching-up";
            replicationFollower.start();
        } else {
            recoveryReady = true;
            recoveryStatus = journalRecovery ? "recovered-journal-tail" : "warm-started-from-postgres";
            publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
            if (leaderElection != null) leaderElection.start();
        }
        if (riskEnabled) {
            // Recovery-boundary alignment: the journal/snapshot-restored BLP policy state (e.g. an
            // armed kill switch) is authoritative; push it back to the edge replica so the Gateway
            // fails fast instead of relying only on the BLP re-check (ADR-018 still defends).
            gatewayReplicas.overridePolicyFromAuthority(riskState.policyVersion(), riskState.killSwitch());
        }
        } catch (RuntimeException ex) {
            recoveryError = ex.toString();
            recoveryStatus = "startup-failed";
            publishReadiness(ReadinessState.REFUSING_TRAFFIC);
            throw ex;
        } catch (Exception ex) {
            recoveryError = ex.toString();
            recoveryStatus = "startup-failed";
            publishReadiness(ReadinessState.REFUSING_TRAFFIC);
            throw new IllegalStateException("LMAX engine startup failed", ex);
        }
    }

    // ----- replication -----------------------------------------------------------------------

    /**
     * Determine PRIMARY/FOLLOWER role via Kubernetes Lease, then wire the appropriate NATS
     * connection: primary gets a publisher (NatsJournalReplicator), follower gets a subscriber
     * (ReplicationFollower). Called before output/input ring construction.
     */
    private void initReplication() {
        try {
            replicationConn = io.nats.client.Nats.connect(
                io.nats.client.Options.builder().server(natsAddress)
                    .connectionTimeout(java.time.Duration.ofSeconds(10))
                    .build());
        } catch (Exception ex) {
            log.warn("Replication NATS connect failed: {} — falling back to single-node primary", ex.getMessage());
            replicationRole.set(ReplicationRole.Role.PRIMARY);
            return;
        }

        String namespace = readK8sNamespace();
        leaderElection = new LeaderElection(podName, namespace, replicationRole, this::onRoleChange, replicationConn);
        boolean isPrimary = leaderElection.tryAcquire();

        if (isPrimary) {
            replicationRole.set(ReplicationRole.Role.PRIMARY);
            boolean streamReady = NatsJournalReplicator.ensureStream(replicationConn);
            if (streamReady) {
                try {
                    natsReplicator = new NatsJournalReplicator(replicationConn.jetStream(),
                        NatsJournalReplicator.SUBJECT);
                    natsReplicator.startAckListener(replicationConn);
                } catch (Exception ex) {
                    log.warn("Could not create JetStream publisher — running without NATS replication: {}", ex.getMessage());
                }
            }
            leaderElection.start();
            log.info("BLP role: PRIMARY (pod={} natsReplication={})", podName, natsReplicator != null);
        } else {
            replicationRole.set(ReplicationRole.Role.FOLLOWER);
            // Follower: will recover from snapshot+JetStream after ring is started.
            // Determine start JetStream sequence from snapshot (if available).
            long startSeq = -1L;
            if (snapshotStore != null) {
                try {
                    SnapshotStore.Data snap = snapshotStore.read();
                    if (snap != null && snap.jetsStreamSeq() > 0) {
                        startSeq = snap.jetsStreamSeq();
                        log.info("Follower snapshot covers JetStream seq={}", startSeq);
                    }
                } catch (Exception ex) {
                    log.warn("Could not read snapshot for follower start seq: {}", ex.getMessage());
                }
            }
            replicationFollower = new ReplicationFollower(replicationConn, podName, startSeq,
                readModel, this::followerReady);
            leaderElection.start();
            log.info("BLP role: FOLLOWER (pod={} startSeq={})", podName, startSeq);
        }
    }

    /** Called when a FOLLOWER has caught up to the JetStream tail. Signal readiness. */
    private void followerReady() {
        recoveryReady = true;
        recoveryStatus = "follower-live";
        publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
        log.info("Follower caught up and ready (pod={})", podName);
    }

    /** Called by LeaderElection when role changes (e.g. follower promoted, primary demoted). */
    private void onRoleChange(ReplicationRole.Role newRole) {
        log.info("BLP role transition → {} (pod={})", newRole, podName);
        if (newRole == ReplicationRole.Role.PRIMARY) {
            // Stop follower subscription.
            if (replicationFollower != null) {
                replicationFollower.stop();
                replicationFollower = null;
            }
            // Stop ACK listener from the old replicator (if any).
            if (natsReplicator != null) natsReplicator.stopAckListener();

            // Create a fresh NatsJournalReplicator and hot-swap it into the running Disruptor
            // via DelegatingReplicator — no ring restart needed.
            try {
                NatsJournalReplicator.ensureStream(replicationConn);
                natsReplicator = new NatsJournalReplicator(replicationConn.jetStream(),
                    NatsJournalReplicator.SUBJECT);
                natsReplicator.startAckListener(replicationConn);
                if (delegatingReplicator != null) {
                    delegatingReplicator.swapDelegate(natsReplicator);
                }
            } catch (Exception ex) {
                log.warn("Post-promotion replicator init failed — continuing with loopback: {}", ex.getMessage());
            }
            recoveryReady = true;
            publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
        } else {
            // Transitioning to FOLLOWER: stop ACK listener, swap to loopback stub.
            if (natsReplicator != null) {
                natsReplicator.stopAckListener();
                natsReplicator = null;
            }
            if (delegatingReplicator != null) {
                delegatingReplicator.swapDelegate(new ReplicatorStub());
            }
            publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        }
    }

    private static String readK8sNamespace() {
        try {
            return java.nio.file.Files.readString(
                java.nio.file.Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace")).strip();
        } catch (Exception ex) {
            return "traderx";
        }
    }

    @Override
    public void destroy() {
        recoveryReady = false;
        recoveryStatus = "stopped";
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        if (replicationFollower != null) replicationFollower.stop();
        if (leaderElection != null) leaderElection.stop();
        if (replicationConn != null) {
            try { replicationConn.close(); } catch (Exception ex) {
                log.warn("Replication NATS close error: {}", ex.getMessage());
            }
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
    }

    // ----- gateway commands (the Receptionist edge: validate, convert, sequence) ----------

    public int nextOrderRef() {
        return nextOrderRef.getAndIncrement();
    }

    public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                         int quantity, BigDecimal limitPrice) {
        return executeNewOrder(orderRef, accountId, ticker, side, quantity, limitPrice, 0L);
    }

    /** New order with an idempotency key (FR-IMRG14); key 0 = key-less (no retry mapping). The key
     *  rides the priceTicks slot, unused by ORDER_NEW (see InputEvent's payload-slot contract). */
    public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                         int quantity, BigDecimal limitPrice, long clientOrderKey) {
        int securityId = symbols.idFor(ticker);
        long limitPx = Px.toTicks(limitPrice);
        return execute(InputEvent.TYPE_ORDER_NEW, orderRef, accountId, securityId,
            (byte) side.ordinal(), quantity, limitPx, clientOrderKey);
    }

    /** A validated new-order command ready for batch sequencing (throughput experiment, option 2). */
    public record NewOrderCommand(int orderRef, int accountId, String ticker, OrderSide side,
                                  int quantity, BigDecimal limitPrice, long clientOrderKey) {
        public NewOrderCommand(int orderRef, int accountId, String ticker, OrderSide side,
                               int quantity, BigDecimal limitPrice) {
            this(orderRef, accountId, ticker, side, quantity, limitPrice, 0L);
        }
    }

    /** BLP-authoritative market-trade decision (FR-IMRG15/20): reason + the global sequence. */
    public record RiskDecision(RiskReason reason, long commandSequence) {}

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
            e.priceTicks = c.clientOrderKey();   // idempotency key rides the ORDER_NEW payload slot
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
        submitPriceTick(ticker, Px.toTicks(price));
    }

    /** Same as {@link #submitPriceTick(String, BigDecimal)} but for a caller that already has
     * the price in fixed-point ticks (e.g. the binary NATS tick subscriber) — skips the
     * BigDecimal parse/round entirely on the ingestion path. */
    public void submitPriceTick(String ticker, long priceTicks) {
        int securityId = symbols.idFor(ticker);
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
     * Market trade from the trade ticket (FR-09B08 + FR-IMRG20): sequence a TRADE_NEW event so
     * booking + position-keeping run on the single-writer BLP, then block for the BLP's
     * authoritative risk decision. 009's fire-and-forget contract is intentionally tightened —
     * success is reported only after the sequenced accept; a rejection surfaces as a stable 4xx
     * at the edge instead of a silent drop (the state's one admission-contract delta).
     */
    public RiskDecision executeTradeNew(int accountId, String ticker, OrderSide side, int quantity,
                                        long clientOrderKey) {
        int securityId = symbols.idFor(ticker);
        long seq = claimInputSlot();
        CompletableFuture<RiskReason> ack = readModel.registerTradeAck(seq);
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
            e.priceTicks = clientOrderKey;   // idempotency key rides the TRADE_NEW payload slot
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            inputRing.publish(seq);
        }
        try {
            return new RiskDecision(ack.get(ackTimeoutMs, TimeUnit.MILLISECONDS), seq);
        } catch (TimeoutException ex) {
            readModel.abandonTradeAck(seq);
            throw new GatewayTimeoutException("no trade acknowledgement for input seq " + seq);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            readModel.abandonTradeAck(seq);
            throw new GatewayTimeoutException("interrupted awaiting trade input seq " + seq);
        }
    }

    // ----- risk control plane (state YU03): versioned control events into the journaled ring -----
    // Sequenced with commands and prices (FR-IMRG11 / ADR-020) so replay reproduces the exact
    // original decisions. Payload slots per InputEvent's type-discriminated contract.

    public void submitAccountControl(int accountId, boolean enabled, long version) {
        submitControl(InputEvent.TYPE_ACCOUNT_CONTROL, accountId, 0, enabled, version, 0, 0L);
    }

    public void submitSecurityControl(String ticker, boolean enabled, long version) {
        submitControl(InputEvent.TYPE_SECURITY_CONTROL, 0, symbols.idFor(ticker), enabled, version, 0, 0L);
    }

    public void submitPolicyControl(boolean killSwitch, long policyVersion) {
        submitControl(InputEvent.TYPE_POLICY_CONTROL, 0, 0, killSwitch, policyVersion, 0, 0L);
    }

    public void submitPolicyControl(boolean killSwitch, long policyVersion, int maxPositionQuantity,
                                    long maxConcentrationNotionalTicks) {
        submitControl(InputEvent.TYPE_POLICY_CONTROL, 0, 0, killSwitch, policyVersion,
            maxPositionQuantity, maxConcentrationNotionalTicks);
    }

    public void submitRestrictionControl(String ticker, boolean restricted, long version) {
        submitControl(InputEvent.TYPE_RESTRICTION_CONTROL, 0, symbols.idFor(ticker), restricted,
            version, 0, 0L);
    }

    private void submitControl(byte type, int accountId, int securityId, boolean enabled,
                               long version, int policyMaxPositionQty, long policyMaxConcentrationTicks) {
        long seq = claimInputSlot();
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = type;
            e.orderRef = 0;
            e.accountId = accountId;
            e.securityId = securityId;
            e.setControlEnabled(enabled);
            e.qty = policyMaxPositionQty;
            e.limitPx = policyMaxConcentrationTicks;
            e.setControlVersion(version);
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            inputRing.publish(seq);
        }
    }

    /**
     * Restriction follow-up (FR-IMRG24): resting orders on a newly restricted security are
     * cancelled through explicit sequenced CANCEL events — never silently deleted or mutated.
     */
    public int cancelOpenOrdersForSecurity(String ticker) {
        return cancelOpenOrdersForSecurity(ticker, readModel.all(), this::executeCancel);
    }

    /** Package-private deterministic seam proving the restriction path uses the sequenced
     * cancellation command for each matching resting order (FR-IMRG24). */
    static int cancelOpenOrdersForSecurity(String ticker, Iterable<OrderSnapshot> snapshots,
                                           java.util.function.IntConsumer sequencedCancel) {
        int canceled = 0;
        for (OrderSnapshot snapshot : snapshots) {
            if (snapshot.isOpen() && ticker.equalsIgnoreCase(snapshot.security)) {
                sequencedCancel.accept(snapshot.orderRef);
                canceled++;
            }
        }
        return canceled;
    }

    /** Stable FNV-1a hash of a client order id / principal; 0 is reserved for "absent". */
    public static long hashClientOrderId(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
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
            // The shadow must mirror the live risk configuration: journaled control events and
            // risk-gated rejections replay through it, so a risk-less shadow would diverge.
            BlpRiskState shadowRisk = null;
            if (riskEnabled) {
                shadowRisk = newRiskState(new RiskMetrics());
                shadowRisk.bootstrap(gatewayReplicas.snapshot());
                shadowRisk.putLimits(riskMaxPositionQuantity, riskMaxConcentrationNotionalTicks);
            }
            MatchingEngine shadow = new MatchingEngine(new OutputPublisher(shadowOut.getRingBuffer()),
                new HotPathMetrics(), maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity,
                terminalRetain, shadowRisk);
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

    /**
     * YU05 (post-trade-compliance, FR-PTC10): on-demand, admin-triggered full journal replay for
     * full-history reconciliation. Unlike {@link #verifyJournalReplay()} (a lightweight digest-only
     * check that discards output), this captures every historical trade into an unbounded {@link
     * TradeBlotter} so trade-processor's reconciliation can detect {@code ORPHAN_IN_PROJECTION}
     * rows (a MariaDB row with no corresponding journal fill) with full confidence, not just the
     * forward window the live bounded blotter covers.
     *
     * <p>Expensive (replays the ENTIRE journal from empty-seed, same as verify's "no snapshot"
     * path) and synchronized to prevent overlapping runs — never on the hot path, never scheduled,
     * always explicitly triggered via {@code POST /recon/full-history/reindex}.
     */
    public synchronized TradeBlotter reindexFullHistory() throws java.io.IOException {
        if (!journalEnabled) {
            throw new IllegalStateException("journal disabled; full-history reindex unavailable");
        }
        JournalReader reader = new JournalReader(Path.of(journalPath));
        TradeBlotter fullHistory = new TradeBlotter(Integer.MAX_VALUE);
        Disruptor<OutputEvent> shadowOut = new Disruptor<>(OutputEvent::newInstance, 16384,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new BlockingWaitStrategy());
        shadowOut.handleEventsWith(new TradeBlotterHandler(fullHistory, symbols));
        shadowOut.start();
        long t0 = System.nanoTime();
        long replayed = 0;
        try {
            BlpRiskState shadowRisk = null;
            if (riskEnabled) {
                shadowRisk = newRiskState(new RiskMetrics());
                shadowRisk.bootstrap(gatewayReplicas.snapshot());
                shadowRisk.putLimits(riskMaxPositionQuantity, riskMaxConcentrationNotionalTicks);
            }
            MatchingEngine shadow = new MatchingEngine(new OutputPublisher(shadowOut.getRingBuffer()),
                new HotPathMetrics(), maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity,
                terminalRetain, shadowRisk);
            seedShadow(shadow);
            replayed = reader.replay(e -> shadow.onEvent(e, e.seq, true));
        } finally {
            try {
                shadowOut.shutdown(30, TimeUnit.SECONDS);
            } catch (com.lmax.disruptor.TimeoutException ex) {
                shadowOut.halt();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        log.info("FULL-HISTORY REINDEX: replayed {} journal events in {} ms; {} historical trades indexed",
            replayed, ms, fullHistory.size());
        this.fullHistoryIndex = fullHistory;
        return fullHistory;
    }

    /** Result of the most recent {@link #reindexFullHistory()} run, or {@code null} if never run. */
    public TradeBlotter fullHistoryIndex() {
        return fullHistoryIndex;
    }

    /**
     * YU05 (post-trade-compliance, ADR-023, FR-PTC20/21): journal-sourced audit-trail report over
     * an input-sequence range {@code [fromSeq, toSeq]} ({@code toSeq <= 0} means "to the end").
     * Reproducible byte-for-byte: same journal + same range always produces the same records,
     * because it is a pure replay, never a query against the mutable MariaDB projection.
     * Synchronized with {@link #reindexFullHistory()} (both replay the whole journal into a shadow
     * engine) so the two admin operations cannot run concurrently and thrash the disk/CPU.
     */
    public synchronized List<AuditRecord> generateRegulatoryReport(long fromSeq, long toSeq) throws java.io.IOException {
        if (!journalEnabled) {
            throw new IllegalStateException("journal disabled; regulatory report unavailable");
        }
        JournalReader reader = new JournalReader(Path.of(journalPath));
        List<AuditRecord> records = new ArrayList<>();
        Disruptor<OutputEvent> shadowOut = new Disruptor<>(OutputEvent::newInstance, 16384,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new BlockingWaitStrategy());
        shadowOut.handleEventsWith(new AuditLogHandler(records, symbols, fromSeq, toSeq));
        shadowOut.start();
        long t0 = System.nanoTime();
        long replayed;
        try {
            BlpRiskState shadowRisk = null;
            if (riskEnabled) {
                shadowRisk = newRiskState(new RiskMetrics());
                shadowRisk.bootstrap(gatewayReplicas.snapshot());
                shadowRisk.putLimits(riskMaxPositionQuantity, riskMaxConcentrationNotionalTicks);
            }
            MatchingEngine shadow = new MatchingEngine(new OutputPublisher(shadowOut.getRingBuffer()),
                new HotPathMetrics(), maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity,
                terminalRetain, shadowRisk);
            seedShadow(shadow);
            replayed = reader.replay(e -> shadow.onEvent(e, e.seq, true));
        } finally {
            try {
                shadowOut.shutdown(30, TimeUnit.SECONDS);
            } catch (com.lmax.disruptor.TimeoutException ex) {
                shadowOut.halt();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        log.info("REGULATORY REPORT: replayed {} journal events in {} ms; {} records in range [{}, {}]",
            replayed, ms, records.size(), fromSeq, toSeq <= 0 ? "end" : toSeq);
        return records;
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
            long jsSeq = (replicationFollower != null) ? replicationFollower.lastJetsStreamSeq() : -1L;
            BlpRiskState risk = matchingEngine.riskState();
            snapshotStore.write(new SnapshotStore.Data(offset, nextOrderRef.get(),
                matchingEngine.tradeCounter(), matchingEngine.priceTuples(),
                matchingEngine.positionTuples(), matchingEngine.allOrderTuples(), jsSeq,
                risk == null ? null : risk.policyTuple(),
                risk == null ? null : risk.accountTuples(),
                risk == null ? null : risk.securityTuples(),
                risk == null ? null : risk.idempotencyTuples()));
        } catch (Exception ex) {
            log.warn("Snapshot write failed (continuing): {}", ex.toString());
        }
    }

    /** Restore engine state (orders + positions + prices + trade counter + risk) from a snapshot.
     *  Risk control/idempotency sections load first; each open order then re-accumulates its live
     *  reservation into the risk aggregates as it is bootstrapped (FR-IMRG21/22). */
    private void loadSnapshotInto(MatchingEngine engine, SnapshotStore.Data data) {
        BlpRiskState risk = engine.riskState();
        if (risk != null && data.riskPolicy() != null) {
            risk.bootstrapPolicy(data.riskPolicy());
            for (long[] a : data.riskAccounts()) {
                risk.bootstrapAccount((int) a[0], a[1] != 0, a[2]);
            }
            for (long[] s : data.riskSecurities()) {
                risk.bootstrapSecurity((int) s[0], s[1] != 0, s[2] != 0, s[3], s[4]);
            }
            for (long[] k : data.riskIdempotency()) {
                risk.bootstrapIdempotency(k[0], (int) k[1], (byte) k[2]);
            }
        }
        for (long[] o : data.orders()) {
            engine.bootstrapOrder((int) o[0], (int) o[1], (int) o[2], (byte) o[3], (int) o[4], (int) o[5],
                o[6], (byte) o[7], (byte) o[12], o[8], (int) o[9], o[10], o[11], o[13], (int) o[14]);
        }
        for (long[] p : data.positions()) {
            engine.bootstrapPosition((int) p[0], (int) p[1], (int) p[2], p[3]);
        }
        for (long[] pr : data.prices()) {
            engine.bootstrapPrice((int) pr[0], pr[1]);
        }
        engine.bootstrapTradeCounter(data.tradeCounter());
    }

    private BlpRiskState newRiskState(RiskMetrics riskMetrics) {
        return new BlpRiskState(riskMaxAccounts, maxSecurities, bookPoolSize, riskIdempotencyCapacity,
            riskCreditLimitTicks, riskMaxOrderQuantity, riskMaxOrderNotionalTicks,
            riskPriceMaxAgeMillis, riskMetrics);
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
            replayed = reader.replayFrom(offset, e -> matchingEngine.onEvent(e, e.seq, true));
            drainOutputRing();   // let the marshaller rebuild the read model from the replayed tail
            recoveryMode = mode;
        } catch (Exception ex) {
            recoveryError = ex.toString();
            recoveryStatus = "journal-recovery-failed";
            log.error("Journal recovery failed: {}", ex.toString(), ex);
            throw new IllegalStateException("Journal recovery failed", ex);
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

    /** Total live reserved exposure across all accounts (gauge; racy-but-safe edge read). */
    public long totalReservedNotional() {
        return riskState == null ? 0L : riskState.totalReservedNotional();
    }

    public BlpRiskState riskState() {
        return riskState;
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
        return delegatingReplicator != null ? delegatingReplicator.replicatedSeq() : -1;
    }

    public long gatingSeq() {
        return Math.min(journaledSeq(), replicatedSeq());
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

    public boolean recoveryReady() {
        return recoveryReady;
    }

    public String recoveryStatus() {
        return recoveryStatus;
    }

    public String recoveryMode() {
        return recoveryMode;
    }

    public String recoveryError() {
        return recoveryError;
    }

    public String journalPath() {
        return journalPath;
    }

    public boolean projectorDbEnabled() {
        return projectorDbEnabled;
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

    private void publishReadiness(ReadinessState state) {
        if (applicationEventPublisher != null) {
            AvailabilityChangeEvent.publish(applicationEventPublisher, this, state);
        }
    }
}
