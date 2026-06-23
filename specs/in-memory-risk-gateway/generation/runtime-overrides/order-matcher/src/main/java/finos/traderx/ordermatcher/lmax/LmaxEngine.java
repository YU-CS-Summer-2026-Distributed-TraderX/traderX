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
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.BlpRiskSnapshotCodec;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
    public record RiskDecision(RiskReason reason, long commandSequence) {}

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
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
    private final int projectorBatchSize;
    private final int maxSecurities;
    private final int bookPoolSize;
    private final int positionCapacity;
    private final long ackTimeoutMs;
    private final String runtimeProfile;
    private final GatewayReplicaStore gatewayReplicas;
    private final int riskMaxAccounts;
    private final int riskIdempotencyCapacity;
    private final long riskCreditLimitTicks;
    private final int riskMaxOrderQuantity;
    private final long riskMaxOrderNotionalTicks;
    private final long riskPriceMaxAgeMillis;
    private final int riskMaxPositionQuantity;
    private final long riskMaxConcentrationNotionalTicks;
    private final boolean riskSnapshotEnabled;
    private final String riskSnapshotPath;
    private final long riskOrderExpiryMillis;

    private final SymbolTable symbols;
    private final HotPathMetrics metrics = new HotPathMetrics();
    private final InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
    private final AtomicInteger nextOrderRef = new AtomicInteger(1);

    private Disruptor<InputEvent> inputDisruptor;
    private Disruptor<OutputEvent> outputDisruptor;
    private RingBuffer<InputEvent> inputRing;
    private RingBuffer<OutputEvent> outputRing;
    private MatchingEngine matchingEngine;
    private BlpRiskState riskState;
    private Journaler journaler;
    private ReplicatorStub replicator;
    private MarshallerHandler marshaller;
    private ProjectorHandler projector;

    public LmaxEngine(
        OrderRepository orderRepository,
        TradeRepository tradeRepository,
        PositionRepository positionRepository,
        Publisher<OrderResponse> orderPublisher,
        Publisher<TradeOrder> tradePublisher,
        Publisher<AccountTrade> accountTradePublisher,
        Publisher<PositionUpdate> positionPublisher,
        GatewayReplicaStore gatewayReplicas,
        @Value("${output.legacy-trades.enabled:false}") boolean legacyTradeSubmitEnabled,
        @Value("${order.matcher.seed-enabled:true}") boolean seedEnabled,
        @Value("${order.matcher.fill-full-threshold:1000}") int fillFullThreshold,
        @Value("${disruptor.input.ring-size:65536}") int inputRingSize,
        @Value("${disruptor.input.wait-strategy:blocking}") String inputWaitStrategy,
        @Value("${disruptor.output.ring-size:65536}") int outputRingSize,
        @Value("${disruptor.output.wait-strategy:blocking}") String outputWaitStrategy,
        @Value("${journal.enabled:true}") boolean journalEnabled,
        @Value("${journal.path:./data/journal}") String journalPath,
        @Value("${output.projector.batch-size:500}") int projectorBatchSize,
        @Value("${blp.books.max-securities:4096}") int maxSecurities,
        @Value("${blp.book.pool-size:65536}") int bookPoolSize,
        @Value("${blp.positions.capacity:8192}") int positionCapacity,
        @Value("${blp.gateway.ack-timeout-ms:5000}") long ackTimeoutMs,
        @Value("${runtime.profile:demo}") String runtimeProfile,
        @Value("${risk.max-accounts:4096}") int riskMaxAccounts,
        @Value("${risk.idempotency.capacity:65536}") int riskIdempotencyCapacity,
        @Value("${risk.credit-limit-ticks:5000000000000000}") long riskCreditLimitTicks,
        @Value("${risk.max-order-quantity:1000000}") int riskMaxOrderQuantity,
        @Value("${risk.max-order-notional-ticks:1000000000000000}") long riskMaxOrderNotionalTicks,
        @Value("${risk.price.max-age-ms:30000}") long riskPriceMaxAgeMillis,
        @Value("${risk.max-position-quantity:1000000}") int riskMaxPositionQuantity,
        @Value("${risk.max-concentration-notional-ticks:5000000000000000}") long riskMaxConcentrationNotionalTicks,
        @Value("${risk.snapshot.enabled:true}") boolean riskSnapshotEnabled,
        @Value("${risk.snapshot.path:./data/risk/blp-risk.snapshot}") String riskSnapshotPath,
        @Value("${risk.order-expiry-ms:300000}") long riskOrderExpiryMillis
    ) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.orderPublisher = orderPublisher;
        this.tradePublisher = tradePublisher;
        this.accountTradePublisher = accountTradePublisher;
        this.positionPublisher = positionPublisher;
        this.gatewayReplicas = gatewayReplicas;
        this.legacyTradeSubmitEnabled = legacyTradeSubmitEnabled;
        this.seedEnabled = seedEnabled;
        this.fillFullThreshold = fillFullThreshold;
        this.inputRingSize = inputRingSize;
        this.inputWaitStrategy = inputWaitStrategy;
        this.outputRingSize = outputRingSize;
        this.outputWaitStrategy = outputWaitStrategy;
        this.journalEnabled = journalEnabled;
        this.journalPath = journalPath;
        this.projectorBatchSize = projectorBatchSize;
        this.maxSecurities = maxSecurities;
        this.bookPoolSize = bookPoolSize;
        this.positionCapacity = positionCapacity;
        this.ackTimeoutMs = ackTimeoutMs;
        this.runtimeProfile = runtimeProfile;
        this.riskMaxAccounts = riskMaxAccounts;
        this.riskIdempotencyCapacity = riskIdempotencyCapacity;
        this.riskCreditLimitTicks = riskCreditLimitTicks;
        this.riskMaxOrderQuantity = riskMaxOrderQuantity;
        this.riskMaxOrderNotionalTicks = riskMaxOrderNotionalTicks;
        this.riskPriceMaxAgeMillis = riskPriceMaxAgeMillis;
        this.riskMaxPositionQuantity = riskMaxPositionQuantity;
        this.riskMaxConcentrationNotionalTicks = riskMaxConcentrationNotionalTicks;
        this.riskSnapshotEnabled = riskSnapshotEnabled;
        this.riskSnapshotPath = riskSnapshotPath;
        this.riskOrderExpiryMillis = riskOrderExpiryMillis;
        this.symbols = new SymbolTable(maxSecurities);
    }

    @Override
    public void afterPropertiesSet() {
        seedReadModelIfEmpty();

        // Output ring first: the BLP needs its publisher before it can run.
        marshaller = new MarshallerHandler(readModel, symbols, metrics);
        projector = new ProjectorHandler(orderRepository, tradeRepository, positionRepository, symbols,
            projectorBatchSize, metrics);
        NatsBridgeHandler natsBridge = new NatsBridgeHandler(orderPublisher, symbols, readModel);
        AccountTradeHandler accountTrade = new AccountTradeHandler(accountTradePublisher, symbols, readModel);
        PositionUpdateHandler positionUpdate = new PositionUpdateHandler(positionPublisher, symbols, readModel);

        // Booking + position-keeping are fused into the BLP (FR-09B08/B10). Each optimized
        // output handler consumes independently so NATS and projection remain parallel. The
        // output ring itself provides bounded backpressure when any consumer falls behind.
        outputDisruptor = new Disruptor<>(OutputEvent::newInstance, normalizeRingSize(outputRingSize),
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, waitStrategy(outputWaitStrategy));
        if (legacyTradeSubmitEnabled) {
            TradeSubmitHandler tradeSubmit = new TradeSubmitHandler(tradePublisher, symbols, readModel);
            outputDisruptor.handleEventsWith(marshaller, natsBridge, accountTrade, positionUpdate,
                tradeSubmit, projector);
        } else {
            outputDisruptor.handleEventsWith(marshaller, natsBridge, accountTrade, positionUpdate, projector);
        }
        outputDisruptor.start();
        outputRing = outputDisruptor.getRingBuffer();

        GatewayReplicaStore.Snapshot riskSnapshot = gatewayReplicas.snapshot();
        for (GatewayReplicaStore.SecurityRecord security : riskSnapshot.securities()) {
            symbols.registerAuthoritative(security.securityId(), security.ticker());
        }
        BlpRiskState blpRisk = new BlpRiskState(riskMaxAccounts, maxSecurities, bookPoolSize,
            riskIdempotencyCapacity, riskCreditLimitTicks, riskMaxOrderQuantity,
            riskMaxOrderNotionalTicks, riskPriceMaxAgeMillis, gatewayReplicas.metrics());
        blpRisk.bootstrap(riskSnapshot);
        blpRisk.putLimits(riskMaxPositionQuantity, riskMaxConcentrationNotionalTicks);
        for (GatewayReplicaStore.EntitlementRecord entitlement : gatewayReplicas.entitlementSnapshot()) {
            blpRisk.putEntitlement(hashClientOrderId(entitlement.principal()), entitlement.accountId(),
                entitlement.enabled());
        }
        restoreRiskSnapshot(blpRisk);
        riskState = blpRisk;
        matchingEngine = new MatchingEngine(new OutputPublisher(outputRing),
            metrics, maxSecurities, fillFullThreshold, bookPoolSize, positionCapacity, blpRisk,
            riskOrderExpiryMillis);

        bootstrapFromReadModel();

        // Input ring: journaler + replicator run in parallel; the BLP is gated behind both
        // (sequence barrier), so every event it acts on is already durable and replicated.
        journaler = new Journaler(journalEnabled, Path.of(journalPath), metrics);
        replicator = new ReplicatorStub();
        inputDisruptor = new Disruptor<>(InputEvent::newInstance, normalizeRingSize(inputRingSize),
            DaemonThreadFactory.INSTANCE, ProducerType.MULTI, waitStrategy(inputWaitStrategy));
        inputDisruptor.handleEventsWith(journaler, replicator).then(matchingEngine);
        inputDisruptor.start();
        inputRing = inputDisruptor.getRingBuffer();

        log.info("LMAX hot path live: profile={} inputRing={} outputRing={} journal={} ({} orders warm)",
            runtimeProfile, normalizeRingSize(inputRingSize), normalizeRingSize(outputRingSize),
            journalEnabled ? journalPath : "disabled", readModel.totalOrders());
    }

    @Override
    public void destroy() {
        try {
            if (inputDisruptor != null) {
                inputDisruptor.shutdown(5, TimeUnit.SECONDS);
            }
        } catch (com.lmax.disruptor.TimeoutException ex) {
            inputDisruptor.halt();
        }
        persistRiskSnapshot();
        try {
            if (outputDisruptor != null) {
                outputDisruptor.shutdown(5, TimeUnit.SECONDS);
            }
        } catch (com.lmax.disruptor.TimeoutException ex) {
            outputDisruptor.halt();
        }
        if (journaler != null) {
            journaler.close();
        }
    }

    private void restoreRiskSnapshot(BlpRiskState state) {
        if (!riskSnapshotEnabled) return;
        Path path = Path.of(riskSnapshotPath);
        if (!Files.exists(path)) return;
        try {
            BlpRiskSnapshotCodec.restore(path, state);
            log.info("Restored BLP risk snapshot from {}", path.toAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to restore BLP risk snapshot " + path, ex);
        }
    }

    private void persistRiskSnapshot() {
        if (!riskSnapshotEnabled || riskState == null) return;
        Path path = Path.of(riskSnapshotPath);
        try {
            BlpRiskSnapshotCodec.write(path, riskState);
            log.info("Persisted BLP risk snapshot to {}", path.toAbsolutePath());
        } catch (IOException ex) {
            log.error("Unable to persist BLP risk snapshot to {}", path, ex);
        }
    }

    // ----- gateway commands (the Receptionist edge: validate, convert, sequence) ----------

    public int nextOrderRef() {
        return nextOrderRef.getAndIncrement();
    }

    public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                         int quantity, BigDecimal limitPrice) {
        return executeNewOrder(orderRef, accountId, ticker, side, quantity, limitPrice,
            "order-ref-" + orderRef, "*");
    }

    public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                         int quantity, BigDecimal limitPrice, String clientOrderId) {
        return executeNewOrder(orderRef, accountId, ticker, side, quantity, limitPrice, clientOrderId, "*");
    }

    public OrderSnapshot executeNewOrder(int orderRef, int accountId, String ticker, OrderSide side,
                                         int quantity, BigDecimal limitPrice, String clientOrderId,
                                         String principal) {
        int securityId = symbols.idForExisting(ticker);
        long limitPx = Px.toTicks(limitPrice);
        return execute(InputEvent.TYPE_ORDER_NEW, orderRef, accountId, securityId,
            (byte) side.ordinal(), quantity, limitPx, 0L, hashClientOrderId(clientOrderId),
            hashClientOrderId(principal));
    }

    public OrderSnapshot executeCancel(int orderRef) {
        return execute(InputEvent.TYPE_ORDER_CANCEL, orderRef, 0, 0, (byte) 0, 0, 0L, 0L, 0L, 0L);
    }

    public OrderSnapshot executeForceFill(int orderRef) {
        return execute(InputEvent.TYPE_FORCE_FILL, orderRef, 0, 0, (byte) 0, 0, 0L, 0L, 0L, 0L);
    }

    public void submitPriceTick(String ticker, BigDecimal price) {
        int securityId = symbols.idForExisting(ticker);
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
            e.clientOrderKey = 0L;
            e.principalKey = 0L;
            e.controlVersion = 0L;
            e.controlEnabled = false;
        } finally {
            inputRing.publish(seq);
        }
    }

    /**
     * Market trade from the trade ticket (FR-09B08): sequence a TRADE_NEW event so booking +
     * position-keeping run on the single-writer BLP. Fire-and-forget, matching 009's POST
     * /trade/ contract (booking is async; the gateway does not block on the read-model).
     */
    public RiskDecision executeTradeNew(int accountId, String ticker, OrderSide side, int quantity) {
        return executeTradeNew(accountId, ticker, side, quantity,
            "trade-" + accountId + '-' + System.nanoTime(), "*");
    }

    public RiskDecision executeTradeNew(int accountId, String ticker, OrderSide side, int quantity,
                                        String clientOrderId) {
        return executeTradeNew(accountId, ticker, side, quantity, clientOrderId, "*");
    }

    public RiskDecision executeTradeNew(int accountId, String ticker, OrderSide side, int quantity,
                                        String clientOrderId, String principal) {
        int securityId = symbols.idForExisting(ticker);
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
            e.priceTicks = 0L;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
            e.clientOrderKey = hashClientOrderId(clientOrderId);
            e.principalKey = hashClientOrderId(principal);
            e.controlVersion = 0L;
            e.controlEnabled = false;
        } finally {
            inputRing.publish(seq);
        }
        try {
            return new RiskDecision(ack.get(ackTimeoutMs, TimeUnit.MILLISECONDS), seq);
        } catch (TimeoutException ex) {
            readModel.abandonTradeAck(seq);
            throw new GatewayTimeoutException("no trade acknowledgement for input seq " + seq);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            readModel.abandonTradeAck(seq);
            throw new GatewayTimeoutException("interrupted awaiting trade input seq " + seq);
        }
    }

    public void submitAccountControl(int accountId, boolean enabled, long version) {
        submitControl(InputEvent.TYPE_ACCOUNT_CONTROL, accountId, 0, 0L, enabled, version);
    }

    public void submitSecurityControl(String ticker, boolean enabled, long version) {
        int securityId = gatewayReplicas.securityId(ticker);
        if (securityId < 0) {
            throw new IllegalArgumentException("unknown authoritative security: " + ticker);
        }
        if (symbols.tickerFor(securityId) == null) {
            symbols.registerAuthoritative(securityId, ticker);
        }
        submitControl(InputEvent.TYPE_SECURITY_CONTROL, 0, securityId, 0L, enabled, version);
    }

    public void submitPolicyControl(boolean killSwitch, long version) {
        submitControl(InputEvent.TYPE_POLICY_CONTROL, 0, 0, 0L, killSwitch, version);
    }

    public void submitEntitlementControl(String principal, int accountId, boolean enabled, long version) {
        submitControl(InputEvent.TYPE_ENTITLEMENT_CONTROL, accountId, 0,
            hashClientOrderId(principal), enabled, version);
    }

    public void submitRestrictionControl(String ticker, boolean restricted, long version) {
        int securityId = gatewayReplicas.securityId(ticker);
        if (securityId < 0) throw new IllegalArgumentException("unknown authoritative security: " + ticker);
        submitControl(InputEvent.TYPE_RESTRICTION_CONTROL, 0, securityId, 0L, restricted, version);
    }

    /** Policy edge expands cancel-all into explicit sequenced cancel commands. */
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

    private void submitControl(byte type, int accountId, int securityId, long principalKey,
                               boolean enabled, long version) {
        long seq = claimInputSlot();
        try {
            InputEvent e = inputRing.get(seq);
            e.seq = seq;
            e.type = type;
            e.orderRef = 0;
            e.accountId = accountId;
            e.securityId = securityId;
            e.side = 0;
            e.qty = 0;
            e.limitPx = 0L;
            e.priceTicks = 0L;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
            e.clientOrderKey = 0L;
            e.principalKey = principalKey;
            e.controlVersion = version;
            e.controlEnabled = enabled;
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

    /** FNV-1a edge hash; collision-safe external ids remain in the HTTP/audit layer. */
    public static long hashClientOrderId(String value) {
        long hash = 0xcbf29ce484222325L;
        String input = value == null ? "" : value;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == Long.MIN_VALUE || hash == Long.MIN_VALUE + 1L ? Long.MIN_VALUE + 2L : hash;
    }

    private OrderSnapshot execute(byte type, int orderRef, int accountId, int securityId, byte side,
                                  int quantity, long limitPx, long priceTicks, long clientOrderKey,
                                  long principalKey) {
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
            e.clientOrderKey = clientOrderKey;
            e.principalKey = principalKey;
            e.controlVersion = 0L;
            e.controlEnabled = false;
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
        List<OrderRecord> seed = List.of(
            seedOrder("ord-013-0001", 22214, "IBM", OrderSide.Buy, 1800, 1800, new BigDecimal("187.250"), OrderStatus.NEW),
            seedOrder("ord-013-0002", 22214, "MSFT", OrderSide.Sell, 900, 650, new BigDecimal("412.000"), OrderStatus.PARTIALLY_FILLED),
            seedOrder("ord-013-0003", 44044, "JPM", OrderSide.Buy, 1200, 1200, new BigDecimal("191.500"), OrderStatus.NEW),
            seedOrder("ord-013-0004", 52355, "GS", OrderSide.Sell, 300, 0, new BigDecimal("498.000"), OrderStatus.FILLED),
            seedOrder("ord-013-0005", 10031, "NVDA", OrderSide.Buy, 450, 450, new BigDecimal("905.125"), OrderStatus.NEW),
            seedOrder("ord-013-0006", 10031, "C", OrderSide.Sell, 1000, 1000, new BigDecimal("61.500"), OrderStatus.NEW),
            seedOrder("ord-013-0007", 62654, "META", OrderSide.Sell, 500, 500, new BigDecimal("507.880"), OrderStatus.NEW)
        );
        orderRepository.saveAll(seed);
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

            int securityId;
            try {
                securityId = symbols.idForExisting(record.getSecurity());
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping order with security absent from authoritative reference data: {}", record.getSecurity());
                continue;
            }
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
            int securityId;
            try {
                securityId = symbols.idForExisting(position.getSecurity());
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping position with security absent from authoritative reference data: {}", position.getSecurity());
                continue;
            }
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

    public HotPathMetrics metrics() {
        return metrics;
    }

    public MatchingEngine blp() {
        return matchingEngine;
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
        return replicator == null ? -1 : replicator.replicatedSeq();
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

    public long positionsUpdatedOut() {
        return marshaller == null ? 0 : marshaller.positionsUpdated();
    }

    public long projectedSeq() {
        return projector == null ? -1 : projector.projectedSeq();
    }

    public long projectorPendingRows() {
        return projector == null ? 0 : projector.pendingRows();
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
