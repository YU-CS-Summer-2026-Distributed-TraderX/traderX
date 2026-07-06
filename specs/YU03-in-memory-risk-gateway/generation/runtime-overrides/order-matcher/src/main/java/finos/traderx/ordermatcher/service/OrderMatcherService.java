package finos.traderx.ordermatcher.service;

import finos.traderx.ordermatcher.api.MarketTradeRequest;
import finos.traderx.ordermatcher.api.OpenCountResponse;
import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InMemoryOrderReadModel;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.OrderSnapshot;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.RiskRejectedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * State 009b: the 009 matcher service re-cast as the LMAX Gateway/Receptionist facade.
 *
 * The public surface (REST semantics, payload shapes, metric families, NATS subjects) is
 * parity-locked to 009 (FR-09B40). Internally, every state mutation is a sequenced input
 * event handled by the single-threaded BLP; every read is served from the output-event-fed
 * in-memory read model; the 009 polling tick, ReentrantLock, hot-path BigDecimal math, and
 * inline JPA/REST calls are gone (FR-09B02/B03, NFR-09B04).
 */
@Service
public class OrderMatcherService {
    private static final String APP_NAME = "traderx-order-matcher";
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^ord-013-(\\d{4,})$");
    // Batch ingress cap: a batch claims a contiguous run of input-ring slots, so it must stay
    // well under the ring size (default 65536) to avoid a producer that can never be satisfied.
    private static final int MAX_BATCH = 1024;

    private final LmaxEngine engine;
    private final InMemoryOrderReadModel readModel;
    private final GatewayReplicaStore replicas;
    private final boolean riskEnabled;
    private final String priceServiceUrl;
    private final String tradeServiceUrl;
    private final Instant startedAt = Instant.now();

    public OrderMatcherService(
        LmaxEngine engine,
        GatewayReplicaStore replicas,
        @Value("${risk.enabled:true}") boolean riskEnabled,
        @Value("${order.matcher.price-service-url:http://price-publisher:18100}") String priceServiceUrl,
        @Value("${order.matcher.trade-service-url:http://trade-service:18092/trade/}") String tradeServiceUrl
    ) {
        this.engine = engine;
        this.readModel = engine.readModel();
        this.replicas = replicas;
        this.riskEnabled = riskEnabled;
        this.priceServiceUrl = trimTrailingSlash(priceServiceUrl);
        this.tradeServiceUrl = tradeServiceUrl;
    }

    // ----- gateway ingress (price ticks become sequenced PRICE_TICK events, FR-09B06) -----

    public void onPriceTick(String ticker, BigDecimal marketPrice) {
        if (!StringUtils.hasText(ticker) || marketPrice == null) {
            return;
        }
        String normalizedTicker = ticker.trim().toUpperCase(Locale.ROOT);
        BigDecimal normalizedPrice = roundPrice(marketPrice);
        if (normalizedPrice == null) {
            return;
        }
        readModel.recordPrice(normalizedTicker, normalizedPrice);
        if (riskEnabled) {
            // Feed the Gateway replica's price-freshness state at the edge (FR-IMRG09); the BLP's
            // authoritative copy is fed by the sequenced PRICE_TICK below.
            replicas.recordPrice(normalizedTicker, Px.toTicks(normalizedPrice), System.currentTimeMillis());
        }
        engine.submitPriceTick(normalizedTicker, normalizedPrice);
    }

    // ----- Gateway preliminary screening (in-memory-risk-gateway, FR-IMRG06/07) --------------

    /** Screen against local replica state only; a pass is preliminary — the BLP repeats every
     *  mutable/aggregate check in sequence order and its decision wins (ADR-018/FR-IMRG19). */
    private void screen(Integer accountId, String ticker, Integer quantity, BigDecimal limitPrice,
                        boolean marketTrade, String clientOrderId) {
        if (!riskEnabled) {
            return;
        }
        long start = System.nanoTime();
        RiskReason preliminary = replicas.screen(accountId, ticker, quantity, limitPrice,
            marketTrade, System.currentTimeMillis());
        replicas.metrics().gatewayValidationLatency(System.nanoTime() - start);
        if (preliminary != RiskReason.ACCEPTED) {
            throw new RiskRejectedException(clientOrderId, preliminary, replicas.policyVersion(), -1L);
        }
    }

    /** The Gateway passed but the sequenced BLP rejected: the BLP wins; count the disagreement
     *  (bounded-cardinality telemetry, FR-IMRG19) and surface the stable rejection. */
    private RuntimeException blpRejection(String clientOrderId, RiskReason reason) {
        replicas.metrics().mismatch();
        long policyVersion = engine.riskState() == null
            ? replicas.policyVersion() : engine.riskState().policyVersion();
        return new RiskRejectedException(clientOrderId, reason, policyVersion, -1L);
    }

    // ----- command path (validate at the edge, sequence, await the BLP's response event) ---

    public OrderResponse createOrder(OrderCreateRequest request) {
        validateCreateRequest(request);
        String ticker = request.getSecurity().trim().toUpperCase(Locale.ROOT);
        screen(request.getAccountId(), ticker, request.getQuantity(), request.getLimitPrice(),
            false, request.getClientOrderId());
        long clientOrderKey = LmaxEngine.hashClientOrderId(request.getClientOrderId());
        int orderRef = engine.nextOrderRef();
        OrderSnapshot snapshot = run(() -> engine.executeNewOrder(
            orderRef, request.getAccountId(), ticker, request.getSide(),
            request.getQuantity(), roundPrice(request.getLimitPrice()), clientOrderKey));
        if (snapshot.riskReason != RiskReason.ACCEPTED) {
            throw blpRejection(request.getClientOrderId(), snapshot.riskReason);
        }
        return toResponse(snapshot);
    }

    /**
     * Batch create (throughput experiment, option 2): validate every order at the edge, then
     * sequence the whole batch in one call so a single gateway thread amortises the HTTP
     * round-trip and the ack-future block across all of them. Returns the per-order responses in
     * request order; all-or-nothing on a gateway timeout (mirrors the single-order semantics).
     */
    public List<OrderResponse> createOrderBatch(List<OrderCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "empty order batch");
        }
        if (requests.size() > MAX_BATCH) {
            throw new ResponseStatusException(BAD_REQUEST, "batch too large (max " + MAX_BATCH + ")");
        }
        List<LmaxEngine.NewOrderCommand> commands = new ArrayList<>(requests.size());
        for (OrderCreateRequest request : requests) {
            validateCreateRequest(request);
            String ticker = request.getSecurity().trim().toUpperCase(Locale.ROOT);
            screen(request.getAccountId(), ticker, request.getQuantity(), request.getLimitPrice(),
                false, request.getClientOrderId());
            int orderRef = engine.nextOrderRef();
            commands.add(new LmaxEngine.NewOrderCommand(orderRef, request.getAccountId(), ticker,
                request.getSide(), request.getQuantity(), roundPrice(request.getLimitPrice()),
                LmaxEngine.hashClientOrderId(request.getClientOrderId())));
        }
        List<OrderSnapshot> snapshots = run(() -> engine.executeNewOrderBatch(commands));
        List<OrderResponse> responses = new ArrayList<>(snapshots.size());
        for (OrderSnapshot snapshot : snapshots) {
            // Batch semantics: per-order BLP decisions are surfaced in the response payload
            // (REJECTED status + reason), not as an all-or-nothing HTTP failure.
            responses.add(toResponse(snapshot));
        }
        return responses;
    }

    public OrderResponse cancelOrder(String orderId) {
        OrderSnapshot snapshot = run(() -> engine.executeCancel(parseOrderRef(orderId)));
        return toResponse(snapshot);
    }

    public OrderResponse forceFillOrder(String orderId) {
        OrderSnapshot snapshot = run(() -> engine.executeForceFill(parseOrderRef(orderId)));
        return toResponse(snapshot);
    }

    /**
     * Market trade from the trade ticket (FR-09B08): trade-service has already validated the
     * ticker/account; here we sequence a TRADE_NEW event so the BLP books it and updates the
     * position (single writer). Fire-and-forget — 009's POST /trade/ booked asynchronously too —
     * so the request is echoed back without waiting on the read model.
     */
    public MarketTradeRequest bookMarketTrade(MarketTradeRequest request) {
        validateMarketTrade(request);
        String ticker = request.getSecurity().trim().toUpperCase(Locale.ROOT);
        screen(request.getAccountId(), ticker, request.getQuantity(), null, true,
            request.getClientOrderId());
        LmaxEngine.RiskDecision decision = engine.executeTradeNew(request.getAccountId(), ticker,
            request.getSide(), request.getQuantity(),
            LmaxEngine.hashClientOrderId(request.getClientOrderId()));
        if (decision.reason() != RiskReason.ACCEPTED) {
            throw blpRejection(request.getClientOrderId(), decision.reason());
        }
        return request;
    }

    /**
     * Retained for controller compatibility: in 009 the controller re-published command
     * responses onto the order subjects. In 009b every lifecycle transition is published by
     * the output-disruptor NATS bridge (exactly once), so this is intentionally a no-op.
     */
    public void publishOrderUpdate(OrderResponse order) {
        // no-op: the output ring's NATS bridge owns subject publication (FR-09B21)
    }

    // ----- read path (in-memory read model; no DB on the request path) ----------------------

    public List<OrderResponse> listOrders(String statusFilter, Integer accountIdFilter) {
        String normalizedStatus = StringUtils.hasText(statusFilter)
            ? statusFilter.trim().toLowerCase(Locale.ROOT) : "open";
        return readModel.all().stream()
            .filter(snapshot -> filterByStatus(snapshot, normalizedStatus))
            .filter(snapshot -> accountIdFilter == null || accountIdFilter.equals(snapshot.accountId))
            .sorted(Comparator.comparing((OrderSnapshot snapshot) -> snapshot.updatedAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    public OrderResponse getOrder(String orderId) {
        OrderSnapshot snapshot = readModel.get(parseOrderRef(orderId));
        if (snapshot == null) {
            throw new ResponseStatusException(NOT_FOUND, "order not found");
        }
        return toResponse(snapshot);
    }

    /** Net positions from the BLP's in-memory book (no DB) — the cutover read-side repoint. */
    public List<finos.traderx.ordermatcher.lmax.PositionUpdate> listPositions(Integer accountIdFilter) {
        return engine.listPositions(accountIdFilter);
    }

    public OpenCountResponse openCounts() {
        return new OpenCountResponse(readModel.countOpen(), readModel.countUnfilled());
    }

    public Map<String, Object> health() {
        OpenCountResponse openCount = openCounts();
        long lastEventMillis = engine.blp() == null ? 0 : engine.blp().lastEventTimeMillis();
        Instant lastEventAt = lastEventMillis > 0 ? Instant.ofEpochMilli(lastEventMillis) : null;

        Map<String, Object> matcher = new LinkedHashMap<>();
        matcher.put("engine", "lmax-disruptor");
        matcher.put("tickMs", 0);                       // event-driven: the 009 poll is gone
        matcher.put("ticks", engine.blp() == null ? 0 : engine.blp().eventsProcessed());
        matcher.put("lastTickAt", lastEventAt);
        matcher.put("autoFillAttempts", engine.blp() == null ? 0 : engine.blp().autoFillAttempts());
        matcher.put("autoFillSuccess", engine.blp() == null ? 0 : engine.blp().autoFillSuccess());
        matcher.put("tradeSubmitFailures", readModel.tradeSubmitFailures().sum());
        matcher.put("accountTradePublishFailures", readModel.accountTradePublishFailures().sum());
        matcher.put("positionPublishFailures", readModel.positionPublishFailures().sum());

        Map<String, Object> lmax = new LinkedHashMap<>();
        lmax.put("profile", engine.runtimeProfile());
        lmax.put("inputPublishedSeq", engine.inputPublishedSeq());
        lmax.put("journaledSeq", engine.journaledSeq());
        lmax.put("replicatedSeq", engine.replicatedSeq());
        lmax.put("blpSeq", engine.blp() == null ? -1 : engine.blp().blpSeq());
        lmax.put("marshalledSeq", engine.marshalledSeq());
        lmax.put("projectedSeq", engine.projectedSeq());
        lmax.put("inputRemainingCapacity", engine.inputRemainingCapacity());
        lmax.put("outputRemainingCapacity", engine.outputRemainingCapacity());
        lmax.put("riskEnabled", riskEnabled);
        lmax.put("riskReplicaReady", replicas.ready());
        lmax.put("riskControlVersion", replicas.sourceVersion());
        lmax.put("riskPolicyVersion", replicas.policyVersion());
        lmax.put("riskKillSwitch", replicas.killSwitch());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("service", APP_NAME);
        payload.put("uptimeSeconds", Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond()));
        payload.put("priceServiceUrl", priceServiceUrl);
        payload.put("tradeServiceUrl", tradeServiceUrl);
        payload.put("matcher", matcher);
        payload.put("lmax", lmax);
        payload.put("openOrders", openCount.getOpenOrders());
        payload.put("unfilledOrders", openCount.getUnfilledOrders());
        return payload;
    }

    public String prometheusMetrics() {
        StringBuilder sb = new StringBuilder(8192);
        long openOrders = readModel.countOpen();
        long unfilledOrders = readModel.countUnfilled();

        sb.append("# HELP traderx_orders_open_total Total open orders (NEW + PARTIALLY_FILLED).\n");
        sb.append("# TYPE traderx_orders_open_total gauge\n");
        sb.append("traderx_orders_open_total ").append(openOrders).append('\n');
        sb.append("# HELP traderx_orders_unfilled_total Total orders with remaining quantity > 0.\n");
        sb.append("# TYPE traderx_orders_unfilled_total gauge\n");
        sb.append("traderx_orders_unfilled_total ").append(unfilledOrders).append('\n');
        sb.append("# HELP traderx_orders_pending_by_side Pending orders grouped by side.\n");
        sb.append("# TYPE traderx_orders_pending_by_side gauge\n");
        sb.append("traderx_orders_pending_by_side{side=\"Buy\"} ").append(readModel.countPendingBySide(OrderSide.Buy)).append('\n');
        sb.append("traderx_orders_pending_by_side{side=\"Sell\"} ").append(readModel.countPendingBySide(OrderSide.Sell)).append('\n');
        sb.append("# HELP traderx_order_events_total Order lifecycle events.\n");
        sb.append("# TYPE traderx_order_events_total counter\n");
        for (String event : List.of("create", "partial_fill", "fill", "cancel", "reject", "force_fill")) {
            sb.append("traderx_order_events_total{event=\"").append(event).append("\"} ")
                .append(readModel.counterValue(event)).append('\n');
        }
        sb.append("# HELP traderx_order_matcher_ticks_total Sequenced input events processed by the BLP (event-driven; 009 tick parity name).\n");
        sb.append("# TYPE traderx_order_matcher_ticks_total counter\n");
        sb.append("traderx_order_matcher_ticks_total ").append(engine.blp() == null ? 0 : engine.blp().eventsProcessed()).append('\n');
        sb.append("# HELP traderx_order_autofill_attempts_total Auto-fill attempts.\n");
        sb.append("# TYPE traderx_order_autofill_attempts_total counter\n");
        sb.append("traderx_order_autofill_attempts_total ").append(engine.blp() == null ? 0 : engine.blp().autoFillAttempts()).append('\n');
        sb.append("# HELP traderx_order_autofill_success_total Auto-fill successful fills/partial-fills.\n");
        sb.append("# TYPE traderx_order_autofill_success_total counter\n");
        sb.append("traderx_order_autofill_success_total ").append(engine.blp() == null ? 0 : engine.blp().autoFillSuccess()).append('\n');
        sb.append("# HELP traderx_order_trade_submit_failures_total Trade submit failures on fill attempts.\n");
        sb.append("# TYPE traderx_order_trade_submit_failures_total counter\n");
        sb.append("traderx_order_trade_submit_failures_total ").append(readModel.tradeSubmitFailures().sum()).append('\n');
        sb.append("# HELP traderx_account_trade_publish_failures_total Direct account trade publish failures.\n");
        sb.append("# TYPE traderx_account_trade_publish_failures_total counter\n");
        sb.append("traderx_account_trade_publish_failures_total ").append(readModel.accountTradePublishFailures().sum()).append('\n');
        sb.append("# HELP traderx_position_publish_failures_total Direct position update publish failures.\n");
        sb.append("# TYPE traderx_position_publish_failures_total counter\n");
        sb.append("traderx_position_publish_failures_total ").append(readModel.positionPublishFailures().sum()).append('\n');

        HotPathMetrics metrics = engine.metrics();
        HotPathMetrics.renderHistogram(sb, "traderx_order_match_latency_seconds",
            "Order eligible-to-fill latency (ingress to fill emission); real measurement in 009b.",
            metrics.matchHistogram(), new double[]{0.01, 0.05, 0.1, 0.25, 0.5, 1});
        HotPathMetrics.renderHistogram(sb, "traderx_blp_event_latency_seconds",
            "BLP event handling latency (ingress to onEvent completion).",
            metrics.blpEventHistogram(), new double[]{0.0001, 0.001, 0.01, 0.1, 1});
        HotPathMetrics.renderHistogram(sb, "traderx_journal_write_latency_seconds",
            "Journaler append latency.",
            metrics.journalHistogram(), new double[]{0.0001, 0.001, 0.01, 0.1});
        HotPathMetrics.renderHistogram(sb, "traderx_output_publish_latency_seconds",
            "True end-to-end latency (ingress to output marshalling).",
            metrics.egressHistogram(), new double[]{0.0001, 0.001, 0.01, 0.1, 1});

        sb.append("# HELP traderx_disruptor_input_remaining_capacity Free input-ring slots (backpressure headroom).\n");
        sb.append("# TYPE traderx_disruptor_input_remaining_capacity gauge\n");
        sb.append("traderx_disruptor_input_remaining_capacity ").append(engine.inputRemainingCapacity()).append('\n');
        sb.append("# HELP traderx_input_published_seq Input ring publisher cursor (global sequence).\n");
        sb.append("# TYPE traderx_input_published_seq gauge\n");
        sb.append("traderx_input_published_seq ").append(engine.inputPublishedSeq()).append('\n');
        sb.append("# HELP traderx_input_gating_seq Min(journaled, replicated) sequence gating the BLP.\n");
        sb.append("# TYPE traderx_input_gating_seq gauge\n");
        sb.append("traderx_input_gating_seq ").append(engine.gatingSeq()).append('\n');
        sb.append("# HELP traderx_input_seq_lag Published minus BLP-consumed sequence.\n");
        sb.append("# TYPE traderx_input_seq_lag gauge\n");
        long blpSeq = engine.blp() == null ? -1 : engine.blp().blpSeq();
        sb.append("traderx_input_seq_lag ").append(Math.max(0, engine.inputPublishedSeq() - blpSeq)).append('\n');
        sb.append("# HELP traderx_input_backpressure_events_total Producer waits for a free input-ring slot.\n");
        sb.append("# TYPE traderx_input_backpressure_events_total counter\n");
        sb.append("traderx_input_backpressure_events_total ").append(engine.metrics().backpressureWaits()).append('\n');
        sb.append("# HELP traderx_input_events_total Sequenced input events by type.\n");
        sb.append("# TYPE traderx_input_events_total counter\n");
        sb.append("traderx_input_events_total{type=\"order_new\"} ").append(engine.blp() == null ? 0 : engine.blp().countOrdersNew()).append('\n');
        sb.append("traderx_input_events_total{type=\"order_cancel\"} ").append(engine.blp() == null ? 0 : engine.blp().countOrdersCancel()).append('\n');
        sb.append("traderx_input_events_total{type=\"force_fill\"} ").append(engine.blp() == null ? 0 : engine.blp().countForceFills()).append('\n');
        sb.append("traderx_input_events_total{type=\"price_tick\"} ").append(engine.blp() == null ? 0 : engine.blp().countPriceTicks()).append('\n');
        sb.append("traderx_input_events_total{type=\"trade_new\"} ").append(engine.blp() == null ? 0 : engine.blp().countTradesNew()).append('\n');
        sb.append("# HELP traderx_output_remaining_capacity Free output-ring slots (egress backpressure headroom).\n");
        sb.append("# TYPE traderx_output_remaining_capacity gauge\n");
        sb.append("traderx_output_remaining_capacity ").append(engine.outputRemainingCapacity()).append('\n');
        sb.append("# HELP traderx_output_events_total Output-ring events by kind.\n");
        sb.append("# TYPE traderx_output_events_total counter\n");
        sb.append("traderx_output_events_total{kind=\"order_update\"} ").append(engine.orderUpdatesOut()).append('\n');
        sb.append("traderx_output_events_total{kind=\"trade_booked\"} ").append(engine.tradesBookedOut()).append('\n');
        sb.append("traderx_output_events_total{kind=\"position_updated\"} ").append(engine.positionsUpdatedOut()).append('\n');
        sb.append("# HELP traderx_trades_per_second_peak Peak trades booked per second, measured in-process over 100ms event-time windows (independent of the scrape interval; resets on matcher restart).\n");
        sb.append("# TYPE traderx_trades_per_second_peak gauge\n");
        sb.append("traderx_trades_per_second_peak ").append(engine.peakTradesPerSecondOut()).append('\n');
        sb.append("# HELP traderx_output_nats_errors_total NATS bridge publish failures.\n");
        sb.append("# TYPE traderx_output_nats_errors_total counter\n");
        sb.append("traderx_output_nats_errors_total ").append(readModel.natsErrors().sum()).append('\n');
        sb.append("# HELP traderx_projector_lag_seq Output sequence minus last projected sequence.\n");
        sb.append("# TYPE traderx_projector_lag_seq gauge\n");
        sb.append("traderx_projector_lag_seq ").append(Math.max(0, engine.marshalledSeq() - engine.projectedSeq())).append('\n');
        sb.append("# HELP traderx_projector_pending_rows Read-model rows buffered awaiting projection.\n");
        sb.append("# TYPE traderx_projector_pending_rows gauge\n");
        sb.append("traderx_projector_pending_rows ").append(engine.projectorPendingRows()).append('\n');
        sb.append("# HELP traderx_projector_queue_depth Rows in the decoupled projector queue (DB staleness window, in rows).\n");
        sb.append("# TYPE traderx_projector_queue_depth gauge\n");
        sb.append("traderx_projector_queue_depth ").append(engine.projectorQueueDepth()).append('\n');
        sb.append("# HELP traderx_projector_queue_capacity Bounded capacity of the decoupled projector queue.\n");
        sb.append("# TYPE traderx_projector_queue_capacity gauge\n");
        sb.append("traderx_projector_queue_capacity ").append(engine.projectorQueueCapacity()).append('\n');
        sb.append("# HELP traderx_projector_enqueue_blocks_total Times the projector queue was full and the hot path had to block (backpressure fallback; should stay 0 until the DB is the hard limit).\n");
        sb.append("# TYPE traderx_projector_enqueue_blocks_total counter\n");
        sb.append("traderx_projector_enqueue_blocks_total ").append(engine.projectorEnqueueBlocks()).append('\n');
        sb.append("# HELP traderx_trades_persisted_total Trades committed to the database by the projector — the real (DB-bound) booking rate, unlike the marshaller-stage trade_booked counter which runs ahead via output-ring buffering.\n");
        sb.append("# TYPE traderx_trades_persisted_total counter\n");
        sb.append("traderx_trades_persisted_total ").append(engine.tradesPersistedOut()).append('\n');
        HotPathMetrics.renderCountHistogram(sb, "traderx_projector_batch_size",
            "Rows written per projector flush.",
            metrics.projectorBatchHistogram(), new long[]{1, 10, 50, 100, 500, 1000, 10000});
        sb.append("# HELP traderx_hotpath_alloc_bytes_total Bytes allocated by the BLP thread (should stay near zero in steady state).\n");
        sb.append("# TYPE traderx_hotpath_alloc_bytes_total counter\n");
        sb.append("traderx_hotpath_alloc_bytes_total{node=\"blp\"} ").append(engine.blpAllocatedBytes()).append('\n');

        // In-memory risk gateway (state YU03, FR-IMRG43): replica readiness/versions, Gateway
        // rejections, authoritative decisions, mismatches, decision latency, reserved exposure.
        if (riskEnabled) {
            replicas.metrics().render(sb, replicas.ready());
            HotPathMetrics.renderHistogram(sb, "traderx_risk_decision_latency_seconds",
                "Authoritative BLP risk decision + reservation latency (NFR-IMRG01).",
                metrics.riskDecisionHistogram(), new double[]{0.000001, 0.000005, 0.000025, 0.0001, 0.001});
            sb.append("# HELP traderx_risk_reserved_notional_total Live reserved exposure across all accounts (Px ticks).\n");
            sb.append("# TYPE traderx_risk_reserved_notional_total gauge\n");
            sb.append("traderx_risk_reserved_notional_total ").append(engine.totalReservedNotional()).append('\n');
            sb.append("# HELP traderx_risk_control_events_total Sequenced risk-control events applied by the BLP.\n");
            sb.append("# TYPE traderx_risk_control_events_total counter\n");
            sb.append("traderx_risk_control_events_total ").append(engine.blp() == null ? 0 : engine.blp().countControlEvents()).append('\n');
        }
        return sb.toString();
    }

    // ----- helpers --------------------------------------------------------------------------

    private <T> T run(java.util.function.Supplier<T> command) {
        try {
            return command.get();
        } catch (InMemoryOrderReadModel.OrderNotFoundException ex) {
            throw new ResponseStatusException(NOT_FOUND, "order not found");
        } catch (LmaxEngine.GatewayTimeoutException ex) {
            throw new ResponseStatusException(GATEWAY_TIMEOUT, "order command not acknowledged");
        }
    }

    private OrderResponse toResponse(OrderSnapshot snapshot) {
        return OrderResponse.from(snapshot.toRecord(), readModel.lastPrice(snapshot.security));
    }

    private int parseOrderRef(String orderId) {
        if (orderId != null) {
            Matcher matcher = ORDER_ID_PATTERN.matcher(orderId);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        throw new ResponseStatusException(NOT_FOUND, "order not found");
    }

    private boolean filterByStatus(OrderSnapshot snapshot, String statusFilter) {
        if ("open".equals(statusFilter)) {
            return snapshot.isOpen();
        }
        if ("all".equals(statusFilter)) {
            return true;
        }
        try {
            OrderStatus status = OrderStatus.valueOf(statusFilter.toUpperCase(Locale.ROOT));
            return snapshot.status == status;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void validateCreateRequest(OrderCreateRequest request) {
        if (request == null
            || request.getAccountId() == null || request.getAccountId() <= 0
            || !StringUtils.hasText(request.getSecurity())
            || request.getSide() == null
            || request.getQuantity() == null || request.getQuantity() <= 0
            || request.getLimitPrice() == null || request.getLimitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "invalid order payload");
        }
    }

    private void validateMarketTrade(MarketTradeRequest request) {
        if (request == null
            || request.getAccountId() == null || request.getAccountId() <= 0
            || !StringUtils.hasText(request.getSecurity())
            || request.getSide() == null
            || request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "invalid market trade payload");
        }
    }

    private BigDecimal roundPrice(BigDecimal input) {
        if (input == null) {
            return null;
        }
        return input.setScale(3, RoundingMode.HALF_UP);
    }

    private String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
