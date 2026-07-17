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
    private final boolean journalArchiveEnabled;   // YU09: rotate+archive the journal at snapshot boundaries
    private final String journalArchiveBucket;
    private final String journalArchiveGcsHmacKeyId;
    private final String journalArchiveGcsHmacSecret;
    private final int terminalRetain;        // bounded terminal-order retention cap (Tier 2-B)
    private final ApplicationEventPublisher applicationEventPublisher;
    private final boolean replicationEnabled;
    private final String podName;
    private final String natsAddress;
    private final ReplicationRole replicationRole;
    private final ReplicationTransport replicationTransport;
    private final ReplicationAckMode replicationAckMode;
    private final ReplicationFailurePolicy replicationFailurePolicy;
    private final String replicationStaticRole;
    private final boolean aeronShadowEnabled;
    private final String aeronClusterId;
    private final String aeronSecretFile;
    private final String aeronInlineSecret;
    private final int aeronLocalOrdinal;
    private final String aeronPeerId;
    private final String aeronDirectory;
    private final String aeronDataPublishChannel;
    private final String aeronDataArchiveDestination;
    private final String aeronDataLivePublishChannel;
    private final String aeronDataSubscribeChannel;
    private final String aeronAckPublishChannel;
    private final String aeronAckSubscribeChannel;
    private final String aeronControlPublishChannel;
    private final String aeronControlSubscribeChannel;
    private final int aeronDataStreamId;
    private final int aeronAckStreamId;
    private final int aeronControlStreamId;
    private final long aeronHeartbeatIntervalMs;
    private final long aeronPeerStaleMs;
    private final boolean fastWitnessEnabled;
    private final long fastWitnessTtlMs;
    private final long fastWitnessRenewMs;
    private final boolean aeronArchiveReplayEnabled;
    private final String aeronArchiveControlRequestChannel;
    private final String aeronArchiveControlResponseChannel;
    private final String aeronArchiveReplayDestination;
    private final String aeronArchiveRecordingChannelFragment;
    private final long aeronArchiveReplayTimeoutMs;
    private final int aeronMappingCapacity;
    private final long replicationAckTimeoutMs;
    private volatile long currentLeaderEpoch;
    private volatile boolean aeronProtocolFaulted;
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
    private final InMemoryOrderReadModel readModel;
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
    private io.aeron.Aeron aeronClient;
    private AeronReplicator aeronReplicator;
    private AeronReplicator aeronShadowReplicator;
    private AeronReplicationFollower aeronFollower;
    /** Lineage-continuous business sequencing (event.seq = inputSeqBase + ringSeq): the Disruptor
     *  ring restarts at -1 on every reboot, but the replicated stream's numbering must survive the
     *  whole leader lineage. Boot sets it from the journal's proven business tail; promotion
     *  recomputes it from the follower's replicated watermark (see onRoleChange). 0 for a fresh
     *  journal — identical to the legacy numbering. */
    private volatile long inputSeqBase;
    private AeronShadowFollower aeronShadowFollower;
    private ShadowSequenceMap shadowSequenceMap;
    private AeronPeerControlAgent aeronControlAgent;
    private LeaderElection leaderElection;
    private FastWitness fastWitness;
    private java.util.concurrent.ScheduledExecutorService fastFailoverScheduler;
    private final java.util.concurrent.atomic.AtomicBoolean failoverTransition =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long fastPeerStaleDetectedNs;
    private volatile long fastWitnessClaimedNs;
    private volatile long fastAdmissionOpenedNs;

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
        @Value("${journal.archive.enabled:false}") boolean journalArchiveEnabled,
        @Value("${journal.archive.bucket:}") String journalArchiveBucket,
        @Value("${journal.archive.gcs-hmac-key-id:}") String journalArchiveGcsHmacKeyId,
        @Value("${journal.archive.gcs-hmac-secret-access-key:}") String journalArchiveGcsHmacSecret,
        @Value("${blp.terminal.retain:262144}") int terminalRetain,
        @Value("${blp.replication.enabled:false}") boolean replicationEnabled,
        @Value("${blp.pod.name:order-matcher-0}") String podName,
        @Value("${nats.address:nats://localhost:4222}") String natsAddress,
        @Value("${blp.replication.transport:nats}") String replicationTransport,
        @Value("${blp.replication.ack-mode:onring}") String replicationAckMode,
        @Value("${blp.replication.failure-policy:degraded-solo}") String replicationFailurePolicy,
        @Value("${blp.replication.static-role:}") String replicationStaticRole,
        @Value("${blp.replication.aeron.shadow:false}") boolean aeronShadowEnabled,
        @Value("${blp.replication.aeron.schema-checksum:45a46b6dac82b4620569a8c02507f558d887ff96ab919d4eb7c5aac09f60074e}") String aeronSchemaChecksum,
        @Value("${blp.replication.cluster-id:traderx}") String aeronClusterId,
        @Value("${blp.replication.secret-file:}") String aeronSecretFile,
        @Value("${blp.replication.secret:}") String aeronInlineSecret,
        @Value("${blp.replication.local-ordinal:-1}") int aeronLocalOrdinal,
        @Value("${blp.replication.peer-id:}") String aeronPeerId,
        @Value("${blp.replication.aeron.directory:/dev/shm/aeron/driver}") String aeronDirectory,
        @Value("${blp.replication.aeron.data-publish-channel:aeron:udp?endpoint=127.0.0.1:40123}") String aeronDataPublishChannel,
        @Value("${blp.replication.aeron.data-archive-destination:}") String aeronDataArchiveDestination,
        @Value("${blp.replication.aeron.data-live-publish-channel:}") String aeronDataLivePublishChannel,
        @Value("${blp.replication.aeron.data-subscribe-channel:aeron:udp?endpoint=0.0.0.0:40123}") String aeronDataSubscribeChannel,
        @Value("${blp.replication.aeron.ack-publish-channel:aeron:udp?endpoint=127.0.0.1:40124}") String aeronAckPublishChannel,
        @Value("${blp.replication.aeron.ack-subscribe-channel:aeron:udp?endpoint=0.0.0.0:40124}") String aeronAckSubscribeChannel,
        @Value("${blp.replication.aeron.control-publish-channel:aeron:udp?endpoint=127.0.0.1:40125}") String aeronControlPublishChannel,
        @Value("${blp.replication.aeron.control-subscribe-channel:aeron:udp?endpoint=0.0.0.0:40125}") String aeronControlSubscribeChannel,
        @Value("${blp.replication.aeron.data-stream-id:1101}") int aeronDataStreamId,
        @Value("${blp.replication.aeron.ack-stream-id:1102}") int aeronAckStreamId,
        @Value("${blp.replication.aeron.control-stream-id:1103}") int aeronControlStreamId,
        @Value("${blp.replication.aeron.heartbeat-interval-ms:10}") long aeronHeartbeatIntervalMs,
        @Value("${blp.replication.aeron.peer-stale-ms:40}") long aeronPeerStaleMs,
        @Value("${blp.failover.mode:lease}") String failoverMode,
        @Value("${blp.failover.fast-witness.ttl-ms:50}") long fastWitnessTtlMs,
        @Value("${blp.failover.fast-witness.renew-ms:10}") long fastWitnessRenewMs,
        @Value("${blp.replication.aeron.archive-replay-enabled:true}") boolean aeronArchiveReplayEnabled,
        @Value("${blp.replication.aeron.archive-control-request-channel:auto}") String aeronArchiveControlRequestChannel,
        @Value("${blp.replication.aeron.archive-control-response-channel:auto}") String aeronArchiveControlResponseChannel,
        @Value("${blp.replication.aeron.archive-replay-destination:auto}") String aeronArchiveReplayDestination,
        @Value("${blp.replication.aeron.archive-recording-channel-fragment:alias=yu11-data}") String aeronArchiveRecordingChannelFragment,
        @Value("${blp.replication.aeron.archive-replay-timeout-ms:10000}") long aeronArchiveReplayTimeoutMs,
        @Value("${blp.replication.aeron.mapping-capacity:65536}") int aeronMappingCapacity,
        @Value("${blp.replication.ack-timeout-ms:500}") long replicationAckTimeoutMs,
        @Value("${blp.replication.leader-epoch:1}") long initialLeaderEpoch,
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
        this.journalArchiveEnabled = journalArchiveEnabled;
        this.journalArchiveBucket = journalArchiveBucket;
        this.journalArchiveGcsHmacKeyId = journalArchiveGcsHmacKeyId;
        this.journalArchiveGcsHmacSecret = journalArchiveGcsHmacSecret;
        this.terminalRetain = terminalRetain;
        this.readModel = new InMemoryOrderReadModel(terminalRetain);
        this.replicationEnabled = replicationEnabled;
        this.podName = podName;
        this.natsAddress = natsAddress;
        this.replicationTransport = ReplicationTransport.parse(replicationTransport);
        this.replicationAckMode = ReplicationAckMode.parse(replicationAckMode);
        this.replicationFailurePolicy = ReplicationFailurePolicy.parse(replicationFailurePolicy);
        this.replicationStaticRole = replicationStaticRole == null
            ? "" : replicationStaticRole.trim().toLowerCase(Locale.ROOT);
        if (!this.replicationStaticRole.isEmpty()
            && !"primary".equals(this.replicationStaticRole)
            && !"follower".equals(this.replicationStaticRole)) {
            throw new IllegalArgumentException("BLP_REPLICATION_STATIC_ROLE must be primary, follower, or empty");
        }
        ReplicationFailurePolicy.validate(this.replicationAckMode,
            this.replicationFailurePolicy, journalEnabled);
        this.aeronShadowEnabled = aeronShadowEnabled;
        if ((this.replicationTransport == ReplicationTransport.AERON || aeronShadowEnabled)
            && !AeronReplicationCodec.SCHEMA_CHECKSUM.equals(aeronSchemaChecksum)) {
            throw new IllegalArgumentException("BLP_SBE_SCHEMA_CHECKSUM mismatch: local="
                + AeronReplicationCodec.SCHEMA_CHECKSUM + " configured=" + aeronSchemaChecksum);
        }
        this.aeronClusterId = aeronClusterId == null ? "" : aeronClusterId.trim();
        this.aeronSecretFile = aeronSecretFile;
        this.aeronInlineSecret = aeronInlineSecret;
        boolean aeronActive = this.replicationTransport == ReplicationTransport.AERON || aeronShadowEnabled;
        this.aeronLocalOrdinal = aeronActive
            ? resolvePodOrdinal(aeronLocalOrdinal, podName) : aeronLocalOrdinal;
        this.aeronPeerId = aeronPeerId == null || aeronPeerId.isBlank()
            ? (aeronActive ? peerPodName(podName) : "") : aeronPeerId.trim();
        this.aeronDirectory = aeronDirectory;
        this.aeronDataPublishChannel = withAlias(
            resolveAeronChannel(aeronDataPublishChannel, podName, 40123, true), "yu11-data");
        this.aeronDataArchiveDestination = aeronDataArchiveDestination == null
            || aeronDataArchiveDestination.isBlank() ? ""
                : resolveLocalAeronChannel(aeronDataArchiveDestination, podName, 40127);
        this.aeronDataLivePublishChannel = aeronDataLivePublishChannel == null
            || aeronDataLivePublishChannel.isBlank() ? ""
                : resolveAeronChannel(aeronDataLivePublishChannel, podName, 40123, true);
        this.aeronDataSubscribeChannel = resolveAeronChannel(aeronDataSubscribeChannel, podName, 40123, false);
        this.aeronAckPublishChannel = resolveAeronChannel(aeronAckPublishChannel, podName, 40124, true);
        this.aeronAckSubscribeChannel = resolveAeronChannel(aeronAckSubscribeChannel, podName, 40124, false);
        this.aeronControlPublishChannel = resolveAeronChannel(aeronControlPublishChannel, podName, 40125, true);
        this.aeronControlSubscribeChannel = resolveAeronChannel(aeronControlSubscribeChannel, podName, 40125, false);
        this.aeronDataStreamId = aeronDataStreamId;
        this.aeronAckStreamId = aeronAckStreamId;
        this.aeronControlStreamId = aeronControlStreamId;
        this.aeronHeartbeatIntervalMs = aeronHeartbeatIntervalMs;
        this.aeronPeerStaleMs = aeronPeerStaleMs;
        String normalizedFailoverMode = failoverMode == null
            ? "lease" : failoverMode.trim().toLowerCase(Locale.ROOT);
        if (!"lease".equals(normalizedFailoverMode)
            && !"fast-witness".equals(normalizedFailoverMode)) {
            throw new IllegalArgumentException("BLP_FAILOVER_MODE must be lease or fast-witness");
        }
        this.fastWitnessEnabled = "fast-witness".equals(normalizedFailoverMode);
        this.fastWitnessTtlMs = fastWitnessTtlMs;
        this.fastWitnessRenewMs = fastWitnessRenewMs;
        this.aeronArchiveReplayEnabled = aeronArchiveReplayEnabled;
        this.aeronArchiveControlRequestChannel = resolveAeronChannel(
            aeronArchiveControlRequestChannel, podName, 8010, true);
        this.aeronArchiveControlResponseChannel = resolveLocalAeronChannel(
            aeronArchiveControlResponseChannel, podName, 8011);
        this.aeronArchiveReplayDestination = resolveLocalAeronChannel(
            aeronArchiveReplayDestination, podName, 40126);
        this.aeronArchiveRecordingChannelFragment = aeronArchiveRecordingChannelFragment;
        this.aeronArchiveReplayTimeoutMs = aeronArchiveReplayTimeoutMs;
        if (aeronActive && (aeronHeartbeatIntervalMs <= 0 || aeronPeerStaleMs <= aeronHeartbeatIntervalMs)) {
            throw new IllegalArgumentException("Aeron peer stale threshold must exceed heartbeat interval");
        }
        if (fastWitnessEnabled && (this.replicationTransport != ReplicationTransport.AERON
            || !this.replicationStaticRole.isEmpty())) {
            throw new IllegalArgumentException(
                "fast-witness failover requires Aeron transport and Lease-elected roles");
        }
        if (fastWitnessEnabled && (fastWitnessRenewMs <= 0L
            || fastWitnessRenewMs >= fastWitnessTtlMs
            || fastWitnessTtlMs < aeronPeerStaleMs)) {
            throw new IllegalArgumentException(
                "fast witness requires 0 < renew-ms < ttl-ms and ttl-ms >= peer-stale-ms");
        }
        this.aeronMappingCapacity = aeronMappingCapacity;
        this.replicationAckTimeoutMs = replicationAckTimeoutMs;
        if (initialLeaderEpoch < 0 || initialLeaderEpoch > 0xffff_ffffL) {
            throw new IllegalArgumentException("blp.replication.leader-epoch must fit uint32");
        }
        this.currentLeaderEpoch = initialLeaderEpoch;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private finos.traderx.ordermatcher.fix.FixExecutionReportHandler fixExecutionReportHandler;

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
        // Lineage base BEFORE transports: the primary's control heartbeat advertises
        // base + ring cursor from the moment it authenticates, and a bootstrapping follower may
        // read it before this pod's recovery completes. Recomputed after a follower bootstrap
        // truncation (see planFollowerBootstrap).
        initInputSeqBase();
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
        // YU10 FIX ingress: lifecycle ExecutionReport fan-out. Field-injected as an OPTIONAL bean
        // so plain-constructed engines (every existing test) and non-FIX contexts see null and
        // are byte-for-byte unaffected. The handler is enqueue-only on this ring thread
        // (see FixExecutionReportHandler).
        if (fixExecutionReportHandler != null) {
            outputHandlers.add(fixExecutionReportHandler);
        }
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
        JournalArchiver archiver = new JournalArchiver(journalArchiveEnabled, journalArchiveBucket,
            journalArchiveGcsHmacKeyId, journalArchiveGcsHmacSecret);
        journaler = new Journaler(journalEnabled, Path.of(journalPath), metrics, journalBatchRecords, archiver);
        inputDisruptor = new Disruptor<>(InputEvent::newInstance, normalizeRingSize(inputRingSize),
            DaemonThreadFactory.INSTANCE, ProducerType.MULTI, waitStrategy(inputWaitStrategy));
        EventHandler<InputEvent> initialDelegate = selectedPrimaryReplicator();
        delegatingReplicator = new DelegatingReplicator(initialDelegate);
        if (aeronShadowReplicator != null) {
            inputDisruptor.handleEventsWith(journaler, delegatingReplicator, aeronShadowReplicator)
                .then(matchingEngine);
        } else {
            inputDisruptor.handleEventsWith(journaler, delegatingReplicator).then(matchingEngine);
        }
        inputDisruptor.start();
        inputRing = inputDisruptor.getRingBuffer();
        if (replicationFollower != null) {
            replicationFollower.setInputRing(inputRing);
        }
        if (aeronFollower != null) {
            aeronFollower.setInputRing(inputRing);
            aeronFollower.setDurabilityWatermarks(journaler::journaledSeq,
                journaler::journalForceNanos, matchingEngine::blpSeq,
                Path.of(journalPath).resolve("aeron-follower.checkpoint"), this::followerFault);
        }
        startSnapshotScheduler();

        log.info("LMAX hot path live: profile={} role={} inputRing={} outputRing={} journal={} ({} orders warm)",
            runtimeProfile, replicationRole.get(), normalizeRingSize(inputRingSize), normalizeRingSize(outputRingSize),
            journalEnabled ? journalPath : "disabled", readModel.totalOrders());
        if (replicationEnabled && replicationRole.isFollower()
            && (replicationFollower != null || aeronFollower != null)) {
            recoveryStatus = "follower-catching-up";
            if (replicationFollower != null) replicationFollower.start();
            if (aeronFollower != null) {
                aeronFollower.start(this::followerReady, this::followerFault);
            }
            if (aeronShadowFollower != null) {
                aeronShadowFollower.start(this::shadowFollowerFault);
            }
            if (leaderElection != null) leaderElection.start();
        } else {
            recoveryReady = !aeronProtocolFaulted;
            recoveryStatus = aeronProtocolFaulted ? "aeron-peer-protocol-fault"
                : (journalRecovery ? "recovered-journal-tail" : "warm-started-from-postgres");
            publishReadiness(aeronProtocolFaulted
                ? ReadinessState.REFUSING_TRAFFIC : ReadinessState.ACCEPTING_TRAFFIC);
            if (leaderElection != null) leaderElection.start();
        }
        if (fastWitnessEnabled) startFastFailoverDetector();
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

    private EventHandler<InputEvent> selectedPrimaryReplicator() {
        if (!replicationEnabled || !replicationRole.isPrimary()) return new ReplicatorStub();
        if (replicationTransport == ReplicationTransport.AERON && aeronReplicator != null) {
            return aeronReplicator;
        }
        if (replicationTransport == ReplicationTransport.NATS && natsReplicator != null) {
            return natsReplicator;
        }
        return new ReplicatorStub();
    }

    /** Determine the Lease role, then initialize only the selected replication data/ACK leg. */
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

        if (!replicationStaticRole.isEmpty()) {
            if ("primary".equals(replicationStaticRole)) {
                replicationRole.set(ReplicationRole.Role.PRIMARY);
                initPrimaryTransport(false);
            } else {
                replicationRole.set(ReplicationRole.Role.FOLLOWER);
                initFollowerTransport(true);
            }
            log.info("BLP static role: {} (pod={} transport={} epoch={})",
                replicationRole.get(), podName, replicationTransport, currentLeaderEpoch);
            return;
        }

        String namespace = readK8sNamespace();
        leaderElection = new LeaderElection(podName, namespace, replicationRole,
            this::onElectionRoleChange, replicationConn);
        boolean isPrimary = leaderElection.tryAcquire();

        if (isPrimary) {
            replicationRole.set(ReplicationRole.Role.PRIMARY);
            initPrimaryTransport(false);
            log.info("BLP role: PRIMARY (pod={} transport={} epoch={})",
                podName, replicationTransport, currentLeaderEpoch);
        } else {
            replicationRole.set(ReplicationRole.Role.FOLLOWER);
            initFollowerTransport(true);
            log.info("BLP role: FOLLOWER (pod={} transport={} epoch={})",
                podName, replicationTransport, currentLeaderEpoch);
        }
        if (fastWitnessEnabled) initFastWitness();
    }

    private void initFastWitness() {
        fastWitness = new FastWitness(replicationConn, aeronClusterId, podName,
            AeronReplicationCodec.SCHEMA_CHECKSUM, fastWitnessTtlMs, fastWitnessRenewMs,
            this::fastWitnessLost);
        if (replicationRole.isPrimary() && !fastWitness.tryClaim(currentLeaderEpoch)) {
            throw new IllegalStateException("Lease primary could not acquire fast witness");
        }
        fastWitness.start();
    }

    private void ensureAeronClient() {
        if (aeronClient != null) return;
        io.aeron.Aeron.Context context = new io.aeron.Aeron.Context()
            .aeronDirectoryName(aeronDirectory)
            .clientName("traderx-blp-" + podName);
        aeronClient = io.aeron.Aeron.connect(context);
    }

    private void initAeronControl(int localRole) {
        if (aeronControlAgent != null) aeronControlAgent.close();
        int expectedRole = localRole == AeronPeerAuthenticator.ROLE_PRIMARY
            ? AeronPeerAuthenticator.ROLE_FOLLOWER : AeronPeerAuthenticator.ROLE_PRIMARY;
        AeronPeerAuthenticator.Identity identity = new AeronPeerAuthenticator.Identity(
            aeronClusterId, podName, aeronPeerId, currentLeaderEpoch, localRole, expectedRole,
            aeronLocalOrdinal, 1 - aeronLocalOrdinal);
        byte[] secret = AeronPeerAuthenticator.loadSecret(aeronSecretFile, aeronInlineSecret);
        // highestInputSeq is load-bearing (the follower's replay target and bootstrap fallback),
        // so it must be the BUSINESS sequence: base + ring cursor on a primary (base - 1 = the
        // proven journal tail before the ring starts), the replicated watermark on a follower.
        java.util.function.LongSupplier highestInputSeq =
            localRole == AeronPeerAuthenticator.ROLE_PRIMARY
                ? () -> inputRing == null ? inputSeqBase - 1L : inputSeqBase + inputRing.getCursor()
                : () -> {
                    AeronReplicationFollower follower = aeronFollower;
                    return follower == null ? -1L : follower.lastInputSeq();
                };
        aeronControlAgent = new AeronPeerControlAgent(aeronClient,
            aeronControlPublishChannel, aeronControlSubscribeChannel, aeronControlStreamId,
            identity, secret, aeronHeartbeatIntervalMs, aeronPeerStaleMs,
            highestInputSeq,
            () -> journaler == null ? -1L : journaler.journaledSeq(),
            () -> matchingEngine == null ? -1L : matchingEngine.blpSeq(),
            () -> aeronReplicator == null ? -1L : aeronReplicator.publicationPosition());
        aeronControlAgent.start(this::aeronProtocolFault);
    }

    private boolean aeronPeerAuthenticated() {
        AeronPeerControlAgent agent = aeronControlAgent;
        return agent != null && agent.sessionReady();
    }

    private void aeronProtocolFault() {
        aeronProtocolFaulted = true;
        recoveryReady = false;
        recoveryStatus = "aeron-peer-protocol-fault";
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
    }

    private void initPrimaryTransport(boolean promoted) {
        if (replicationTransport == ReplicationTransport.AERON || aeronShadowEnabled) {
            currentLeaderEpoch = LeaderEpochStore.claimNext(
                Path.of(journalPath).resolve("leader.epoch"), currentLeaderEpoch);
        } else if (promoted) {
            currentLeaderEpoch = Math.min(0xffff_ffffL, currentLeaderEpoch + 1L);
        }
        if (replicationTransport == ReplicationTransport.NATS) {
            if (NatsJournalReplicator.ensureStream(replicationConn)) {
                try {
                    natsReplicator = new NatsJournalReplicator(replicationConn.jetStream(),
                        NatsJournalReplicator.SUBJECT);
                    natsReplicator.startAckListener(replicationConn);
                } catch (Exception ex) {
                    log.warn("Could not create JetStream publisher — running degraded-solo: {}", ex.getMessage());
                }
            }
            if (aeronShadowEnabled) {
                try {
                    ensureAeronClient();
                    initAeronControl(AeronPeerAuthenticator.ROLE_PRIMARY);
                    aeronShadowReplicator = new AeronReplicator(aeronClient,
                        aeronDataPublishChannel, aeronDataStreamId,
                        aeronAckSubscribeChannel, aeronAckStreamId,
                        currentLeaderEpoch, ReplicationAckMode.ON_RING,
                        ReplicationFailurePolicy.DEGRADED_SOLO, replicationAckTimeoutMs, true,
                        this::aeronPeerAuthenticated, aeronDataArchiveDestination,
                        aeronDataLivePublishChannel);
                } catch (Exception ex) {
                    if (ex instanceof IllegalArgumentException invalidConfig) throw invalidConfig;
                    log.warn("Aeron shadow publisher unavailable: {}", ex.getMessage());
                }
            }
            return;
        }

        try {
            ensureAeronClient();
            initAeronControl(AeronPeerAuthenticator.ROLE_PRIMARY);
            aeronReplicator = new AeronReplicator(aeronClient,
                aeronDataPublishChannel, aeronDataStreamId,
                aeronAckSubscribeChannel, aeronAckStreamId,
                currentLeaderEpoch, replicationAckMode, replicationFailurePolicy,
                replicationAckTimeoutMs, false, this::aeronPeerAuthenticated,
                aeronDataArchiveDestination, aeronDataLivePublishChannel);
            boolean transportReady = aeronReplicator.awaitConnected(aeronArchiveReplayTimeoutMs);
            if (!transportReady) {
                throw new IllegalStateException(
                    "Aeron MDC Archive destination did not connect before startup timeout");
            }
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException invalidConfig) throw invalidConfig;
            if (replicationFailurePolicy == ReplicationFailurePolicy.STRICT) {
                throw new IllegalStateException("strict Aeron primary initialization failed", ex);
            }
            log.warn("Aeron primary unavailable — running degraded-solo: {}", ex.getMessage());
        }
    }

    private void initFollowerTransport(boolean bootPath) {
        if (replicationTransport == ReplicationTransport.NATS) {
            long startSeq = followerSnapshotJetStreamSeq();
            shadowSequenceMap = aeronShadowEnabled ? new ShadowSequenceMap(aeronMappingCapacity) : null;
            replicationFollower = new ReplicationFollower(replicationConn, podName, startSeq,
                readModel, this::followerReady, replicationAckMode,
                () -> journaler == null ? -1L : journaler.journaledSeq(), shadowSequenceMap);
            if (aeronShadowEnabled) {
                try {
                    ensureAeronClient();
                    initAeronControl(AeronPeerAuthenticator.ROLE_FOLLOWER);
                    aeronShadowFollower = new AeronShadowFollower(aeronClient,
                        aeronDataSubscribeChannel, aeronDataStreamId, currentLeaderEpoch,
                        shadowSequenceMap, replicationAckTimeoutMs);
                } catch (Exception ex) {
                    if (ex instanceof IllegalArgumentException invalidConfig) throw invalidConfig;
                    log.warn("Aeron shadow consumer unavailable: {}", ex.getMessage());
                }
            }
            return;
        }
        ensureAeronClient();
        initAeronControl(AeronPeerAuthenticator.ROLE_FOLLOWER);
        AeronFollowerCheckpointStore.Record checkpoint = readAeronFollowerCheckpoint();
        AeronArchiveReplayMerge.Config replayConfig = aeronArchiveReplayEnabled
            ? new AeronArchiveReplayMerge.Config(aeronArchiveControlRequestChannel,
                aeronArchiveControlResponseChannel, aeronArchiveReplayDestination,
                aeronDataSubscribeChannel, aeronArchiveRecordingChannelFragment,
                aeronDataStreamId, aeronArchiveReplayTimeoutMs)
            : null;
        long expectedFirstInputSeq = 0L;
        if (replayConfig != null) {
            if (bootPath) {
                BootstrapPlan plan = planFollowerBootstrap(checkpoint, replayConfig);
                checkpoint = plan.checkpoint();
                expectedFirstInputSeq = plan.expectedFirstInputSeq();
            } else {
                // Runtime demotion: the journal cut is only sound at boot (no live journaler
                // appending mid-cut, and recovery rebuilds state from the cut journal — neither
                // holds here). A demoted engine instead expects the stream to CONTINUE from its
                // own business watermark; if the new primary's stream forked (it promoted without
                // everything we accepted), the first fragment mismatches, the follower faults,
                // and the pod restart runs the sound boot-path bootstrap.
                checkpoint = null;
                long cursor = inputRing == null ? -1L : inputRing.getCursor();
                expectedFirstInputSeq = inputSeqBase + cursor + 1L;
                log.info("Demoted follower continuation: expecting stream to resume at inputSeq={}",
                    expectedFirstInputSeq);
            }
        }
        aeronFollower = new AeronReplicationFollower(aeronClient,
            aeronDataSubscribeChannel, aeronDataStreamId,
            aeronAckPublishChannel, aeronAckStreamId,
            () -> aeronControlAgent == null ? currentLeaderEpoch : aeronControlAgent.negotiatedEpoch(),
            replicationAckMode, aeronMappingCapacity, replicationAckTimeoutMs,
            this::aeronPeerAuthenticated, replayConfig, checkpoint);
        aeronFollower.setExpectedFirstInputSeq(expectedFirstInputSeq);
        aeronFollower.setPrimaryHighWatermark(() -> aeronControlAgent == null
            ? -1L : aeronControlAgent.peerHighestInputSeq());
        aeronFollower.setPrimaryRecordingPosition(() -> aeronControlAgent == null
            ? -1L : aeronControlAgent.peerRecordingPosition());
    }

    private record BootstrapPlan(AeronFollowerCheckpointStore.Record checkpoint,
                                 long expectedFirstInputSeq) { }

    /** Lineage base from the journal's proven business tail (0 for a fresh journal). */
    private void initInputSeqBase() {
        if (!journalEnabled) return;
        try {
            inputSeqBase = new JournalReader(Path.of(journalPath)).lastBusinessSeq() + 1L;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("cannot establish input-seq lineage base", ex);
        }
        if (inputSeqBase > 0) {
            log.info("Input-seq lineage base: {} (journal business tail {})",
                inputSeqBase, inputSeqBase - 1L);
        }
    }

    /**
     * Cross-epoch cold-follower bootstrap (the post-failover replacement path). A checkpoint from
     * an older leader epoch is unusable, and the new epoch's recording starts at a nonzero
     * business sequence S0 — so a checkpoint-less follower could never join (it demanded sequence
     * 0 and faulted, the one-shot-failover defect). The contract implemented here:
     *
     * <ol>
     *   <li>learn S0 from the primary's own recording (first fragment; heartbeat watermark + 1
     *       when the recording is still empty — degrades to 0 for a fresh pair);</li>
     *   <li>cut the local journal at exactly S0-1, discarding a divergent suffix the new leader
     *       lineage never saw (fail closed when local history cannot prove that boundary — the
     *       fell-behind / empty-PVC case needs the deferred snapshot transfer);</li>
     *   <li>recovery then rebuilds state to exactly S0-1, and the follower accepts S0 as its
     *       first fragment, replaying the new recording from its start via ReplayMerge.</li>
     * </ol>
     *
     * When the peer does not authenticate within the archive-replay timeout, boot proceeds with
     * the legacy stream-origin contract (a fresh pair, or a cold pair start where the election
     * has not yet produced a primary — the fail-closed FAULT_GAP still protects correctness).
     */
    private BootstrapPlan planFollowerBootstrap(AeronFollowerCheckpointStore.Record checkpoint,
                                                AeronArchiveReplayMerge.Config replayConfig) {
        AeronPeerControlAgent agent = aeronControlAgent;
        // Boot-only wait, 3x the archive timeout: a follower by definition saw a live Lease
        // holder, so the peer exists and authentication is pending, not absent — but under heavy
        // host load (50-190s Spring starts observed on kind) 10s expires before the control
        // session settles, and a needless legacy fallback after a real failover would restart-loop.
        long deadline = System.nanoTime() + 3L * aeronArchiveReplayTimeoutMs * 1_000_000L;
        while (agent != null && !agent.sessionReady() && !aeronProtocolFaulted
            && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        if (agent == null || !agent.sessionReady()) {
            log.info("Aeron follower bootstrap: peer not authenticated within {} ms — "
                + "starting with the legacy stream-origin contract", aeronArchiveReplayTimeoutMs);
            return new BootstrapPlan(checkpoint, 0L);
        }
        long epoch = agent.negotiatedEpoch();
        if (checkpoint != null && checkpoint.epoch() == epoch) {
            return new BootstrapPlan(checkpoint, 0L);   // same-epoch resume; boundary unused
        }
        long localTail;
        try {
            localTail = new JournalReader(Path.of(journalPath)).lastBusinessSeq();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("cannot read local journal tail for bootstrap", ex);
        }
        if (localTail < 0L) {
            // A fresh journal can only ever join a stream that begins at the origin — any nonzero
            // epoch start needs local history through S0-1, which this node cannot have. Probing
            // the recording adds nothing: either the recording starts at 0 (origin contract
            // succeeds) or the follower correctly fails closed on its first fragment (FAULT_GAP).
            log.info("Aeron follower bootstrap: fresh local journal — origin contract "
                + "(a nonzero epoch start will fail closed)");
            return new BootstrapPlan(null, 0L);
        }
        AeronArchiveReplayMerge.StreamStart start = AeronArchiveReplayMerge.probeStreamStart(
            aeronClient, replayConfig, agent.peerHighestInputSeq(), aeronArchiveReplayTimeoutMs);
        if (start.fromRecording() && start.leaderEpoch() != epoch) {
            throw new IllegalStateException("bootstrap probe decoded epoch " + start.leaderEpoch()
                + " but the negotiated epoch is " + epoch);
        }
        long s0 = start.firstInputSeq();
        log.info("Aeron follower bootstrap: negotiatedEpoch={} checkpointEpoch={} "
                + "epochStartInputSeq={} source={}",
            epoch, checkpoint == null ? -1L : checkpoint.epoch(), s0,
            start.fromRecording() ? "recording" : "peer-watermark");
        if (s0 <= 0L) {
            return new BootstrapPlan(null, 0L);   // stream begins at the origin — legacy contract
        }
        try {
            new JournalReader(Path.of(journalPath)).truncateAfterBusinessSeq(s0 - 1L);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("bootstrap journal cut failed", ex);
        }
        initInputSeqBase();   // the tail just changed; recompute the lineage base
        return new BootstrapPlan(null, s0);
    }

    private AeronFollowerCheckpointStore.Record readAeronFollowerCheckpoint() {
        Path path = Path.of(journalPath).resolve("aeron-follower.checkpoint");
        if (!java.nio.file.Files.exists(path)) return null;
        try (AeronFollowerCheckpointStore store = new AeronFollowerCheckpointStore(path)) {
            return store.read();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("cannot read Aeron follower checkpoint", ex);
        }
    }

    private long followerSnapshotJetStreamSeq() {
        if (snapshotStore == null) return -1L;
        try {
            SnapshotStore.Data snap = snapshotStore.read();
            if (snap != null && snap.jetsStreamSeq() > 0) {
                log.info("Follower snapshot covers JetStream seq={}", snap.jetsStreamSeq());
                return snap.jetsStreamSeq();
            }
        } catch (Exception ex) {
            log.warn("Could not read snapshot for follower start seq: {}", ex.getMessage());
        }
        return -1L;
    }

    /** Called when a FOLLOWER has caught up to the JetStream tail. Signal readiness. */
    private void followerReady() {
        if (aeronProtocolFaulted) return;
        recoveryReady = true;
        recoveryStatus = "follower-live";
        publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
        log.info("Follower caught up and ready (pod={})", podName);
    }

    private void followerFault() {
        recoveryReady = false;
        recoveryStatus = "follower-protocol-fault";
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
    }

    private void shadowFollowerFault() {
        // Shadow mode is diagnostic by contract: NATS remains authoritative and readiness is not
        // gated, but the mismatch remains sticky and operator-visible through logs/metrics.
        log.error("Aeron shadow validation fault (pod={} code={} compared={})", podName,
            aeronShadowFollower == null ? -1 : aeronShadowFollower.faultCode(),
            aeronShadowFollower == null ? -1 : aeronShadowFollower.comparedCount());
    }

    private void startFastFailoverDetector() {
        if (fastFailoverScheduler != null) return;
        fastFailoverScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "blp-fast-failover");
            thread.setDaemon(true);
            return thread;
        });
        fastFailoverScheduler.scheduleAtFixedRate(this::fastFailoverTick,
            1L, 1L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void fastFailoverTick() {
        if (!replicationRole.isFollower() || failoverTransition.get()) return;
        AeronPeerControlAgent control = aeronControlAgent;
        if (control == null || !control.sessionReady() || !control.peerStale()) return;
        if (fastPeerStaleDetectedNs == 0L) fastPeerStaleDetectedNs = System.nanoTime();
        long targetEpoch = Math.min(0xffff_ffffL,
            Math.max(currentLeaderEpoch, control.peerEpoch()) + 1L);
        promoteWithFastWitness(targetEpoch, true);
    }

    private void onElectionRoleChange(ReplicationRole.Role newRole) {
        if (!fastWitnessEnabled || newRole == ReplicationRole.Role.FOLLOWER) {
            onRoleChange(newRole);
            return;
        }
        long peerEpoch = aeronControlAgent == null ? -1L : aeronControlAgent.peerEpoch();
        long targetEpoch = Math.min(0xffff_ffffL,
            Math.max(currentLeaderEpoch, peerEpoch) + 1L);
        if (!promoteWithFastWitness(targetEpoch, false)) {
            replicationRole.set(ReplicationRole.Role.FOLLOWER);
            recoveryReady = false;
            recoveryStatus = "fast-witness-unavailable";
            publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        }
    }

    private boolean promoteWithFastWitness(long targetEpoch, boolean reconcileLease) {
        FastWitness witness = fastWitness;
        if (witness == null || !failoverTransition.compareAndSet(false, true)) return false;
        try {
            if (!witness.tryClaim(targetEpoch)) return false;
            fastWitnessClaimedNs = System.nanoTime();
            replicationRole.set(ReplicationRole.Role.PRIMARY);
            onRoleChange(ReplicationRole.Role.PRIMARY);
            if (!witness.isHeld()
                || (witness.epoch() != currentLeaderEpoch
                && !witness.tryClaim(currentLeaderEpoch))) {
                replicationRole.set(ReplicationRole.Role.FOLLOWER);
                onRoleChange(ReplicationRole.Role.FOLLOWER);
                return false;
            }
            recoveryReady = true;
            recoveryStatus = "ready";
            publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
            fastAdmissionOpenedNs = System.nanoTime();
            if (reconcileLease && leaderElection != null) leaderElection.witnessPromoted();
            return true;
        } finally {
            failoverTransition.set(false);
        }
    }

    private void fastWitnessLost() {
        if (!replicationRole.isPrimary() || !failoverTransition.compareAndSet(false, true)) return;
        try {
            recoveryReady = false;
            recoveryStatus = "fast-witness-lost";
            publishReadiness(ReadinessState.REFUSING_TRAFFIC);
            replicationRole.set(ReplicationRole.Role.FOLLOWER);
            onRoleChange(ReplicationRole.Role.FOLLOWER);
        } finally {
            failoverTransition.set(false);
        }
    }

    /** Called by LeaderElection when role changes (e.g. follower promoted, primary demoted). */
    private synchronized void onRoleChange(ReplicationRole.Role newRole) {
        log.info("BLP role transition → {} (pod={})", newRole, podName);
        aeronProtocolFaulted = false;
        if (newRole == ReplicationRole.Role.PRIMARY) {
            if (aeronControlAgent != null && aeronControlAgent.peerEpoch() > currentLeaderEpoch) {
                currentLeaderEpoch = aeronControlAgent.peerEpoch();
            }
            // Lineage base for the new epoch, captured before the follower transport closes:
            // the next ring sequence (cursor+1) must publish as lastReplicatedInputSeq+1 so the
            // business numbering continues across the promotion. With nothing replicated yet the
            // boot-time base (journal tail + 1) already holds.
            AeronReplicationFollower promotedFrom = aeronFollower;
            if (promotedFrom != null && promotedFrom.lastInputSeq() >= 0 && inputRing != null) {
                inputSeqBase = promotedFrom.lastInputSeq() - inputRing.getCursor();
                log.info("Promotion lineage base: lastReplicatedInputSeq={} ringCursor={} base={}",
                    promotedFrom.lastInputSeq(), inputRing.getCursor(), inputSeqBase);
            }
            closeFollowerTransport();
            closePrimaryTransport();
            try {
                initPrimaryTransport(true);
                if (delegatingReplicator != null) {
                    delegatingReplicator.swapDelegate(selectedPrimaryReplicator());
                }
            } catch (Exception ex) {
                log.warn("Post-promotion replicator init failed — continuing with loopback: {}", ex.getMessage());
            }
            boolean admissionFenceReady = !fastWitnessEnabled
                || (fastWitness != null
                    && fastWitness.isHeld()
                    && fastWitness.epoch() == currentLeaderEpoch);
            recoveryReady = admissionFenceReady;
            recoveryStatus = admissionFenceReady ? "ready" : "fast-witness-transition";
            publishReadiness(admissionFenceReady
                ? ReadinessState.ACCEPTING_TRAFFIC
                : ReadinessState.REFUSING_TRAFFIC);
        } else {
            if (fastWitness != null) fastWitness.relinquish();
            closePrimaryTransport();
            if (delegatingReplicator != null) {
                delegatingReplicator.swapDelegate(new ReplicatorStub());
            }
            currentLeaderEpoch = Math.min(0xffff_ffffL, currentLeaderEpoch + 1L);
            try {
                initFollowerTransport(false);
                if (inputRing != null) {
                    if (replicationFollower != null) {
                        replicationFollower.setInputRing(inputRing);
                        replicationFollower.start();
                    }
                    if (aeronFollower != null) {
                        aeronFollower.setInputRing(inputRing);
                        aeronFollower.setDurabilityWatermarks(journaler::journaledSeq,
                            journaler::journalForceNanos, matchingEngine::blpSeq,
                            Path.of(journalPath).resolve("aeron-follower.checkpoint"), this::followerFault);
                        aeronFollower.start(this::followerReady, this::followerFault);
                    }
                    if (aeronShadowFollower != null) {
                        aeronShadowFollower.start(this::shadowFollowerFault);
                    }
                }
            } catch (Exception ex) {
                log.error("Follower transport initialization failed", ex);
            }
            publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        }
    }

    private void closePrimaryTransport() {
        if (natsReplicator != null) {
            natsReplicator.stopAckListener();
            natsReplicator = null;
        }
        if (aeronReplicator != null) {
            aeronReplicator.close();
            aeronReplicator = null;
        }
        if (aeronShadowReplicator != null) {
            aeronShadowReplicator.close();
            aeronShadowReplicator = null;
        }
        closeAeronControl();
    }

    private void closeFollowerTransport() {
        if (replicationFollower != null) {
            replicationFollower.stop();
            replicationFollower = null;
        }
        if (aeronFollower != null) {
            aeronFollower.close();
            aeronFollower = null;
        }
        if (aeronShadowFollower != null) {
            aeronShadowFollower.close();
            aeronShadowFollower = null;
        }
        shadowSequenceMap = null;
        closeAeronControl();
    }

    private void closeAeronControl() {
        if (aeronControlAgent != null) {
            aeronControlAgent.close();
            aeronControlAgent = null;
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

    private static String resolveAeronChannel(String configured, String podName,
                                              int port, boolean publish) {
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            return configured;
        }
        if (!publish) return resolveLocalAeronChannel("auto", podName, port);
        String peer;
        if (podName != null && podName.endsWith("-0")) {
            peer = podName.substring(0, podName.length() - 1) + "1";
        } else if (podName != null && podName.endsWith("-1")) {
            peer = podName.substring(0, podName.length() - 1) + "0";
        } else {
            throw new IllegalArgumentException(
                "automatic Aeron peer channel requires StatefulSet pod ordinal 0 or 1: " + podName);
        }
        return "aeron:udp?endpoint=" + peer + ".order-matcher-headless."
            + readK8sNamespace() + ".svc.cluster.local:" + port;
    }

    private static String withAlias(String channel, String alias) {
        if (channel == null || channel.contains("alias=")) return channel;
        return channel + (channel.indexOf('?') >= 0 ? "|" : "?") + "alias=" + alias;
    }

    private static String resolveLocalAeronChannel(String configured, String podName, int port) {
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            return configured;
        }
        if (podName == null || (!podName.endsWith("-0") && !podName.endsWith("-1"))) {
            throw new IllegalArgumentException(
                "automatic local Aeron channel requires StatefulSet pod ordinal 0 or 1: " + podName);
        }
        return "aeron:udp?endpoint=" + podName + ".order-matcher-headless."
            + readK8sNamespace() + ".svc.cluster.local:" + port;
    }

    private static int resolvePodOrdinal(int configured, String podName) {
        if (configured == 0 || configured == 1) return configured;
        if (podName != null && podName.endsWith("-0")) return 0;
        if (podName != null && podName.endsWith("-1")) return 1;
        throw new IllegalArgumentException(
            "Aeron replication requires StatefulSet ordinal 0/1 or BLP_REPLICATION_LOCAL_ORDINAL");
    }

    private static String peerPodName(String podName) {
        if (podName != null && podName.endsWith("-0")) {
            return podName.substring(0, podName.length() - 1) + "1";
        }
        if (podName != null && podName.endsWith("-1")) {
            return podName.substring(0, podName.length() - 1) + "0";
        }
        throw new IllegalArgumentException(
            "Aeron replication requires BLP_REPLICATION_PEER_ID outside a StatefulSet ordinal");
    }

    @Override
    public void destroy() {
        recoveryReady = false;
        recoveryStatus = "stopped";
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        closeFollowerTransport();
        closePrimaryTransport();
        if (fastFailoverScheduler != null) {
            fastFailoverScheduler.shutdownNow();
            fastFailoverScheduler = null;
        }
        if (fastWitness != null) {
            fastWitness.close();
            fastWitness = null;
        }
        if (leaderElection != null) leaderElection.stop();
        if (aeronClient != null) {
            try { aeronClient.close(); } catch (Exception ex) {
                log.warn("Replication Aeron close error: {}", ex.getMessage());
            }
            aeronClient = null;
        }
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
        guardPrimaryAdmission();
        long hi = claimInputSlots(n);
        long lo = hi - (n - 1);
        @SuppressWarnings("unchecked")
        CompletableFuture<OrderSnapshot>[] acks = new CompletableFuture[n];
        long ingressNanos = System.nanoTime();
        long eventTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            long seq = lo + i;
            NewOrderCommand c = commands.get(i);
            acks[i] = readModel.registerAck(inputSeqBase + seq);
            InputEvent e = inputRing.get(seq);
            e.seq = inputSeqBase + seq;
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
            e.seq = inputSeqBase + seq;
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
        guardPrimaryAdmission();
        int securityId = symbols.idFor(ticker);
        long seq = claimInputSlot();
        CompletableFuture<RiskReason> ack = readModel.registerTradeAck(inputSeqBase + seq);
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = inputSeqBase + seq;
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
            e.seq = inputSeqBase + seq;
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
        int canceled = 0;
        for (OrderSnapshot snapshot : readModel.all()) {
            if (snapshot.isOpen() && ticker.equalsIgnoreCase(snapshot.security)) {
                executeCancel(snapshot.orderRef);
                canceled++;
            }
        }
        return canceled;
    }

    /** Pure companion used by the restriction contract test: selects the same open orders while
     * letting the caller provide the sequenced-cancel sink. */
    static int cancelOpenOrdersForSecurity(String ticker, java.util.List<OrderSnapshot> orders,
                                           java.util.function.IntConsumer cancelSink) {
        int canceled = 0;
        for (OrderSnapshot snapshot : orders) {
            if (snapshot.isOpen() && ticker.equalsIgnoreCase(snapshot.security)) {
                cancelSink.accept(snapshot.orderRef);
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
        guardPrimaryAdmission();
        long seq = claimInputSlot();
        CompletableFuture<OrderSnapshot> ack = readModel.registerAck(inputSeqBase + seq);
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = inputSeqBase + seq;
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

    /** Thrown when a gateway admission is attempted on a pod that is not a fenced primary. */
    public static final class NotPrimaryException extends RuntimeException {
        public NotPrimaryException() { super("order-matcher is not the serving primary"); }
    }

    /**
     * Preserve the YU02 synchronous admission fence in this full-file YU11 override. Static roles
     * exist only for the isolated compose proof; real HA must have a recently confirmed Lease.
     */
    private void guardPrimaryAdmission() {
        if (!replicationEnabled) return;
        if (aeronProtocolFaulted) throw new NotPrimaryException();
        if (!replicationStaticRole.isEmpty()) {
            if (!replicationRole.isPrimary()) throw new NotPrimaryException();
            return;
        }
        if (fastWitnessEnabled) {
            FastWitness witness = fastWitness;
            boolean witnessed = replicationRole.isPrimary()
                && witness != null
                && witness.isHeld()
                && witness.epoch() == currentLeaderEpoch;
            if (!witnessed) throw new NotPrimaryException();
            return;
        }
        LeaderElection election = leaderElection;
        boolean ok = replicationRole.isPrimary()
            && election != null
            && (System.nanoTime() - election.lastSuccessfulRenewNs()) < election.renewDeadlineNanos();
        if (!ok) throw new NotPrimaryException();
    }

    public LeaderElection leaderElection() { return leaderElection; }

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
        DbWarmupCounters counters = new DbWarmupCounters();
        DbWarmupReader.Result streamed = new DbWarmupReader(jdbcTemplate).stream(
            record -> bootstrapOrderFromDb(record, counters),
            this::bootstrapPositionFromDb);
        nextOrderRef.set(counters.maxRef + 1);
        matchingEngine.bootstrapTradeCounter(streamed.maxTradeSeq());

        // Counter parity with 009's refreshCountersFromDatabase().
        readModel.setCounter("create", counters.created);
        readModel.setCounter("partial_fill", counters.count(OrderStatus.PARTIALLY_FILLED));
        readModel.setCounter("fill", counters.count(OrderStatus.FILLED));
        readModel.setCounter("cancel", counters.count(OrderStatus.CANCELED));
        readModel.setCounter("reject", counters.count(OrderStatus.REJECTED));
        readModel.setCounter("force_fill", 0);
        log.info("DB warm-up streamed {} orders, {} positions, and {} trade ids; retained {} orders",
            streamed.orderRows(), streamed.positionRows(), streamed.tradeRows(), readModel.totalOrders());
    }

    private void bootstrapOrderFromDb(OrderRecord record, DbWarmupCounters counters) {
        Matcher matcher = ORDER_ID_PATTERN.matcher(record.getOrderId() == null ? "" : record.getOrderId());
        if (!matcher.matches()) {
            log.warn("Skipping order with unrecognized id format: {}", record.getOrderId());
            return;
        }
        int ref = Integer.parseInt(matcher.group(1));
        counters.record(ref, record.getStatus());

        int securityId = symbols.idFor(record.getSecurity());
        OrderSnapshot snapshot = OrderSnapshot.fromRecord(ref, record);
        if (!readModel.bootstrapNewestFirst(snapshot)) {
            return;
        }
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

    private void bootstrapPositionFromDb(Position position) {
        if (position.getAccountId() == null || position.getSecurity() == null) {
            return;
        }
        int securityId = symbols.idFor(position.getSecurity());
        matchingEngine.bootstrapPosition(position.getAccountId(), securityId,
            position.getQuantity() == null ? 0 : position.getQuantity(),
            Px.toTicks(position.getAverageCostBasis()));
    }

    private static final class DbWarmupCounters {
        private final long[] byStatus = new long[OrderStatus.values().length];
        private int maxRef;
        private long created;

        void record(int ref, OrderStatus status) {
            maxRef = Math.max(maxRef, ref);
            created++;
            if (status != null) {
                byStatus[status.ordinal()]++;
            }
        }

        long count(OrderStatus status) {
            return byStatus[status.ordinal()];
        }
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
            e.seq = inputSeqBase + seq;
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

    public boolean replicationEnabled() { return replicationEnabled; }
    public String replicationTransportName() { return replicationTransport.name().toLowerCase(Locale.ROOT); }
    public String replicationAckModeName() { return replicationAckMode.name().toLowerCase(Locale.ROOT); }
    public String replicationFailurePolicyName() {
        return replicationFailurePolicy.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
    public String failoverModeName() { return fastWitnessEnabled ? "fast-witness" : "lease"; }
    public boolean replicationPrimary() { return replicationRole.isPrimary(); }
    public long leaderEpoch() { return currentLeaderEpoch; }
    public boolean replicationDegraded() {
        return delegatingReplicator != null && delegatingReplicator.degraded();
    }
    public boolean replicationConnected() {
        if (!replicationEnabled) return true;
        if (replicationTransport == ReplicationTransport.NATS) {
            return replicationConn != null
                && replicationConn.getStatus() == io.nats.client.Connection.Status.CONNECTED;
        }
        AeronReplicator primary = aeronReplicator;
        if (replicationRole.isPrimary()) return primary != null && primary.connected();
        AeronPeerControlAgent control = aeronControlAgent;
        return control != null && control.sessionReady();
    }
    public boolean replicationHealthy() {
        if (!replicationEnabled) return true;
        if (aeronProtocolFaulted || !recoveryReady) return false;
        FastWitness witness = fastWitness;
        return !fastWitnessEnabled || !replicationRole.isPrimary()
            || (witness != null && witness.isHeld() && witness.epoch() == currentLeaderEpoch);
    }
    public long followerAckedInputSeq() {
        AeronReplicator primary = aeronReplicator;
        return primary == null ? -1L : primary.followerAckedSeq();
    }
    public long followerReceivedInputSeq() {
        AeronReplicationFollower follower = aeronFollower;
        return follower == null ? -1L : follower.lastInputSeq();
    }
    public long followerDurableAckedInputSeq() {
        AeronReplicationFollower follower = aeronFollower;
        return follower == null ? -1L : follower.durableAckedInputSeq();
    }
    public long replicationOfferFailureCount() {
        AeronReplicator primary = aeronReplicator;
        return primary == null ? 0L : primary.offerFailureCount();
    }
    public long replicationInvalidFrameCount() {
        AeronReplicationFollower follower = aeronFollower;
        return follower == null ? 0L : follower.invalidFrameCount();
    }
    public boolean controlSessionReady() {
        AeronPeerControlAgent control = aeronControlAgent;
        return control != null && control.sessionReady();
    }
    public boolean peerStale() {
        AeronPeerControlAgent control = aeronControlAgent;
        return control != null && control.peerStale();
    }
    public long peerHeartbeatAgeMillis() {
        AeronPeerControlAgent control = aeronControlAgent;
        return control == null ? -1L : control.peerHeartbeatAgeMillis();
    }
    public boolean archiveReplayActive() {
        AeronReplicationFollower follower = aeronFollower;
        return follower != null && follower.archiveReplaying();
    }
    public boolean archiveReplayMerged() {
        AeronReplicationFollower follower = aeronFollower;
        return follower != null && follower.archiveMerged();
    }
    public long shadowMismatchCount() {
        AeronShadowFollower follower = aeronShadowFollower;
        return follower == null ? 0L : follower.mismatchCount();
    }
    public boolean witnessHeld() {
        FastWitness witness = fastWitness;
        return witness != null && witness.isHeld();
    }
    public long witnessRevision() {
        FastWitness witness = fastWitness;
        return witness == null ? 0L : witness.revision();
    }
    public long witnessEpoch() {
        FastWitness witness = fastWitness;
        return witness == null ? -1L : witness.epoch();
    }
    public long witnessClaimAttemptCount() {
        FastWitness witness = fastWitness;
        return witness == null ? 0L : witness.claimAttemptCount();
    }
    public long witnessClaimConflictCount() {
        FastWitness witness = fastWitness;
        return witness == null ? 0L : witness.claimConflictCount();
    }
    public long witnessAmbiguousOperationCount() {
        FastWitness witness = fastWitness;
        return witness == null ? 0L : witness.ambiguousOperationCount();
    }
    public long witnessLostClaimCount() {
        FastWitness witness = fastWitness;
        return witness == null ? 0L : witness.lostClaimCount();
    }
    public double failoverDetectionToClaimMillis() {
        return phaseMillis(fastPeerStaleDetectedNs, fastWitnessClaimedNs);
    }
    public double failoverClaimToAdmissionMillis() {
        return phaseMillis(fastWitnessClaimedNs, fastAdmissionOpenedNs);
    }
    public double failoverTotalMillis() {
        return phaseMillis(fastPeerStaleDetectedNs, fastAdmissionOpenedNs);
    }

    private static double phaseMillis(long startNs, long endNs) {
        return startNs == 0L || endNs < startNs ? 0.0 : (endNs - startNs) / 1_000_000.0;
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
