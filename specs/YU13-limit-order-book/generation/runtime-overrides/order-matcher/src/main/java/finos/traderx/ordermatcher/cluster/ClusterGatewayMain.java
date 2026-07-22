package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Stateless-forward order gateway (ADR-047): terminates REST and (optionally) FIX, screens
 * nothing away from the authoritative core (risk decides inside the cluster), and forwards
 * through the Aeron Cluster client — which follows the leader natively — answering each request
 * from the committed egress ack.
 *
 * The single-threaded Aeron Cluster client is owned by ONE loop thread; REST handler threads and
 * FIX session threads never touch it directly — they submit through {@link OrderSubmitter}, whose
 * work is serialized onto the owner thread (so ack correlation stays FIFO by construction, and
 * there is no data race on the client). Because every counterparty session lives on the front-end
 * side of that seam, a leader-change reconnect on the owner thread never disturbs a FIX session
 * (ADR-047 failover transparency; proven by {@code FixGatewaySurvivalTest}).
 *
 * Split readiness (ADR-045): {@code /ready} is 200 only while the cluster session is live.
 */
public final class ClusterGatewayMain implements OrderSubmitter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ACK_TIMEOUT_MS = 10_000;
    private static final long BATCH_FENCE_RETRY_MS = 5;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer orderBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private final Map<String, Integer> idByTicker = new HashMap<>();
    // Tasks that touch the cluster client; run ONLY on the owner thread.
    private final LinkedBlockingQueue<FutureTask<?>> tasks = new LinkedBlockingQueue<>();

    private String ingressEndpoints;
    private String aeronDir;
    private String[] endpointEntries;
    // Persists across reconnects so the single-endpoint fallback does not restart at the same
    // (possibly dead) endpoint every time — see connectCycling().
    private int connectRotation;
    private AeronCluster client;
    private volatile boolean connected;
    private volatile boolean running = true;

    // Owner-thread-only ack scratch (set by the egress listener between poll calls).
    private long[] lastOrderAck;   // {appliedSeq, orderRef, kind, tradeSeq}
    private long[] lastTradeAck;   // {kind, riskReason} — market-trade (/trades) committed decision
    private long[] lastSymbolAck;  // {appliedSeq, symbolId, requestId}
    private long nextSymbolRequestId = 1;
    // Pipelined-batch ack accounting (owner thread only; pollEgress runs on the owner thread).
    // Acks per session are FIFO in log order, so counting order-lifecycle acks matches offers.
    // YU13 (FR-LOB07): a crossing book interleaves counterparty RESTING-order updates on the
    // same egress stream — every ack now carries a resting-class byte and correlation counts
    // only direct (non-resting) order-lifecycle acks, so the count matches offers exactly.
    private boolean batchActive;
    private int batchOutstanding;
    private int batchAccepted;
    // A sequenced cancel of reserved orderRef=0 is offered after the final batch order. New-order
    // batches can never emit KIND_ORDER_NOT_FOUND for ref 0, so that ack is an unambiguous fence
    // whose appliedSeq is beyond every earlier order on this session. Repeated cancel fences are
    // side-effect-free and cover a dropped fence ack without touching reference or risk state.
    private boolean batchFenceAwaiting;
    private long batchFenceAppliedSeq = -1;
    private volatile long batchFenceOffers;
    private volatile long batchHighWaterCompletions;
    private volatile long batchHighWaterTimeouts;
    // Bench metrics: every committed fill-kind egress ack is a booked order (run-gke-bench.sh
    // reads traderx_order_events_total{event="fill"}). Written on the owner thread, read racily
    // by the /metrics handler — plain volatile longs.
    private volatile long fillEvents;
    private volatile long acceptedOrders;
    private volatile long canceledOrders;
    // Market-trade (/trades, the UI create-order path) outcome counters — the market-trade path
    // emits KIND_TRADE_BOOKED/REJECTED, neither of which the order-lifecycle metrics above count,
    // so without these a stage mis-seed books nothing with no visible signal. Owner-thread writes.
    // Gated on the ack's market-trade byte: YU13 crossing fills also emit KIND_TRADE_BOOKED, and
    // counting those here would drown the market-trade signal in ordinary order flow.
    private volatile long marketTradesBooked;
    private volatile long marketTradesRejected;

    public static void main(final String[] args) throws Exception {
        new ClusterGatewayMain().run();
    }

    private void run() throws Exception {
        ingressEndpoints = env("GATEWAY_INGRESS_ENDPOINTS", "0=localhost:21802");
        endpointEntries = ingressEndpoints.split(",");
        aeronDir = env("GATEWAY_AERON_DIR", "/dev/shm/aeron-gateway");
        final int httpPort = Integer.parseInt(env("GATEWAY_HTTP_PORT", "18110"));

        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));

        // Owner thread: it alone connects, offers, polls egress, and reconnects.
        final Thread owner = new Thread(this::ownerLoop, "cluster-client-owner");
        owner.setDaemon(true);
        owner.start();
        awaitConnected();

        final HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 64);
        // 64: under pipelined-batch load every in-flight batch parks one HTTP thread on its
        // owner-queue future (up to ~12s each); with only 8 threads the readiness probe starved
        // behind them and k8s pulled the gateway out of the Service mid-bench.
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.createContext("/orders/batch", this::handleBatch);
        server.createContext("/orders", this::handleOrder);
        // Deliberately NOT /orders/cancel. HttpServer routes by longest prefix, so during a rolling
        // gateway update an older replica has no /orders/cancel context and would hand the request
        // to /orders — measured: it sequenced the cancel body as a NEW order and returned an
        // orderRef. A cancel that silently books an order is the worst available failure mode; a
        // sibling path 404s on old replicas instead.
        server.createContext("/cancel", this::handleCancel);
        server.createContext("/trades", this::handleTrade);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/seed", this::handleSeed);
        server.createContext("/ready", exchange ->
            respond(exchange, connected ? 200 : 503, "{\"connected\":" + connected + "}"));
        server.createContext("/health", exchange ->
            respond(exchange, 200, "{\"connected\":" + connected + "}"));
        server.start();

        final String fixPortEnv = env("FIX_ACCEPTOR_PORT", "");
        if (!fixPortEnv.isEmpty()) {
            final List<String> compIds = Arrays.asList(env("FIX_SESSION_COMPIDS", "CLIENT1").split(","));
            final FixGatewayAcceptor fix = new FixGatewayAcceptor(this, Integer.parseInt(fixPortEnv),
                env("FIX_TARGET_COMP_ID", "TRADERX"),
                Integer.parseInt(env("FIX_DEFAULT_ACCOUNT", "11")), compIds);
            fix.start();
            Runtime.getRuntime().addShutdownHook(new Thread(fix::stop));
        }
        System.out.println("GATEWAY up: http=" + httpPort + " ingress=" + ingressEndpoints
            + (fixPortEnv.isEmpty() ? "" : " fix=" + fixPortEnv));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            server.stop(0);
            CloseHelper.quietCloseAll(client, driver);
        }));
        Thread.currentThread().join();
    }

    // ----- owner thread: sole cluster-client user --------------------------------------------

    private void ownerLoop() {
        connectCycling();
        long lastReconnect = 0;
        while (running) {
            try {
                final FutureTask<?> task = tasks.poll(50, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run(); // does its own offer + pollEgress; exceptions captured in the future
                } else if (client != null) {
                    client.pollEgress();
                }
                if (client != null && client.isClosed()) {
                    final long now = System.currentTimeMillis();
                    if (now - lastReconnect > 1000) {
                        lastReconnect = now;
                        connected = false;
                        connectCycling();
                    }
                }
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Exception e) {
                connected = false;
                connectCycling();
            }
        }
    }

    /**
     * Reconnect to the cluster (owner thread only).
     *
     * The first attempt hands Aeron the COMPLETE member list so the cluster client resolves the
     * leader itself. This used to cycle single endpoints starting at a local {@code attempt = 0}
     * — i.e. always endpoint 0 first — so whenever member 0 was the member that died, every
     * reconnect blocked on the dead endpoint's connect timeout before trying a live one. Measured
     * on GKE: killing member 0 cost a 1270ms gateway-session gap vs 41ms killing member 2, a 31x
     * penalty decided purely by WHICH pod died, and the sole cause of the bimodal failover
     * distribution (~85-180ms fast mode vs ~670-850ms slow mode). Single-endpoint cycling is kept
     * as the fallback, with a rotating start so a dead endpoint is not retried first every time.
     */
    private void connectCycling() {
        int attempt = 0;
        while (running) {
            final String entry = attempt == 0
                ? ingressEndpoints
                : endpointEntries[(connectRotation + attempt) % endpointEntries.length];
            attempt++;
            try {
                CloseHelper.quietClose(client);
                client = AeronCluster.connect(new AeronCluster.Context()
                    .aeronDirectoryName(aeronDir)
                    .ingressChannel("aeron:udp?term-length=1m")
                    .ingressEndpoints(entry)
                    .egressChannel("aeron:udp?term-length=1m|endpoint="
                        + env("GATEWAY_EGRESS_HOST", env("POD_IP", "localhost")) + ":"
                        + env("GATEWAY_EGRESS_PORT", "0"))
                    .egressListener(this::onEgress));
                connectRotation = (connectRotation + 1) % endpointEntries.length;
                connected = true;
                return;
            } catch (final Exception e) {
                connected = false;
            }
        }
    }

    private void awaitConnected() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        while (!connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private void onEgress(final long clusterSessionId, final long timestamp, final DirectBuffer buffer,
                          final int offset, final int length, final io.aeron.logbuffer.Header header) {
        final byte kind = buffer.getByte(offset + 12);
        if (kind == MatchingEngineClusteredService.KIND_SYMBOL_REGISTERED) {
            lastSymbolAck = new long[] {
                buffer.getLong(offset), buffer.getInt(offset + 8), buffer.getLong(offset + 13) };
        } else if (OutputEvent.isOrderLifecycleKind(kind) || kind == OutputEvent.KIND_ORDER_NOT_FOUND) {
            if (batchActive && batchFenceAwaiting && kind == OutputEvent.KIND_ORDER_NOT_FOUND
                    && buffer.getInt(offset + 8) == 0) {
                batchFenceAppliedSeq = Math.max(batchFenceAppliedSeq, buffer.getLong(offset));
                return;
            }
            // Resting-class byte (FR-LOB07): 1 = counterparty resting-order update from someone
            // else's cross — never the direct response to an offer, so it must not complete
            // lastOrderAck or decrement batch accounting.
            final boolean restingUpdate = buffer.getByte(offset + 21) != 0;
            if (!restingUpdate) {
                if (lastOrderAck == null) { // first DIRECT order-kind ack after the offer wins
                    lastOrderAck = new long[] {
                        buffer.getLong(offset), buffer.getInt(offset + 8), kind, buffer.getLong(offset + 13) };
                }
                if (batchActive && batchOutstanding > 0) {
                    batchOutstanding--;
                    if (kind != OutputEvent.KIND_ORDER_REJECTED && kind != OutputEvent.KIND_ORDER_NOT_FOUND) {
                        batchAccepted++;
                        acceptedOrders++;
                    }
                }
            }
        }
        // Booked-order metric: count every fill-kind ack — both sides of a cross count, direct
        // and resting alike; each is a booked trade (the engine's trade counter is the truth).
        if (kind == OutputEvent.KIND_ORDER_FILLED || kind == OutputEvent.KIND_ORDER_PARTIALLY_FILLED) {
            fillEvents++;
        }
        // Market-trade decision (/trades): KIND_TRADE_BOOKED (fresh accept) or KIND_TRADE_ACCEPTED
        // (idempotent replay) = booked; KIND_TRADE_REJECTED carries the RiskReason ordinal at 22.
        // Byte 23 gates this to outputs of a TYPE_TRADE_NEW apply — YU13's crossing book emits
        // KIND_TRADE_BOOKED for BOTH sides of every ordinary order match, so kind alone would let
        // a foreign fill answer (and inflate) the market-trade path. handleTrade offers with
        // lastTradeAck=null, so the first market-trade decision after its offer wins.
        if (buffer.getByte(offset + 23) != 0
                && (kind == OutputEvent.KIND_TRADE_BOOKED || kind == OutputEvent.KIND_TRADE_ACCEPTED
                    || kind == OutputEvent.KIND_TRADE_REJECTED)) {
            if (lastTradeAck == null) {
                lastTradeAck = new long[] {kind, buffer.getByte(offset + 22)};
            }
            if (kind == OutputEvent.KIND_TRADE_REJECTED) {
                marketTradesRejected++;
            } else {
                marketTradesBooked++;
            }
        }
    }

    /** Run a client-touching callable on the owner thread and wait for its result. */
    private <T> T onOwner(final java.util.concurrent.Callable<T> callable) throws Exception {
        return onOwner(callable, ACK_TIMEOUT_MS + 2_000);
    }

    private <T> T onOwner(final java.util.concurrent.Callable<T> callable, final long timeoutMs)
            throws Exception {
        final FutureTask<T> ft = new FutureTask<>(callable);
        tasks.add(ft);
        return ft.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ----- OrderSubmitter (called by REST + FIX threads) -------------------------------------

    @Override
    public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                  final char side, final int qty, final long limitPxTicks) {
        try {
            return onOwner(() -> {
                final int securityId = resolveSecurityId(ticker);
                if (securityId < 0) {
                    return null;
                }
                event.type = InputEvent.TYPE_ORDER_NEW;
                event.side = side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
                event.orderRef = 0;
                event.accountId = accountId;
                event.securityId = securityId;
                event.qty = qty;
                event.limitPx = limitPxTicks;
                event.priceTicks = 0L; // clientOrderKey slot; FIX ClOrdID dedup is deferred
                event.eventTimeMillis = 0;
                codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                lastOrderAck = null;
                if (!offerAndAwait(orderBuffer, AeronReplicationCodec.INPUT_BYTES, () -> lastOrderAck != null)) {
                    return null;
                }
                final long[] ack = lastOrderAck;
                final boolean accepted = ack[2] != OutputEvent.KIND_ORDER_REJECTED
                    && ack[2] != OutputEvent.KIND_ORDER_NOT_FOUND;
                if (accepted) {
                    acceptedOrders++;
                }
                return new ExecResult(accepted, (int) ack[1], (byte) ack[2]);
            });
        } catch (final Exception e) {
            return null; // ambiguous/timeout: caller must not claim rejection
        }
    }

    /**
     * Cancel ingress (FR-LOB09). No new SBE template and no engine change: {@code TYPE_ORDER_CANCEL}
     * already rides {@link InputEvent}'s {@code orderRef} slot — it is the very message this gateway
     * offers as the pipelined-batch high-water fence — and {@code MatchingEngine.onCancel} already
     * unlinks the resting order, releases its risk reservation exactly once, and emits either
     * {@code KIND_ORDER_CANCELED} or {@code KIND_ORDER_NOT_FOUND}. What was missing was only a
     * caller that supplies a real orderRef instead of the reserved 0.
     *
     * <p>Correlation safety: {@code emitOrderNotFound} hardcodes orderRef 0 in its ack, so a
     * cancel-of-unknown is correlated by the owner thread's FIFO ordering, not by ref. That is
     * sound here and load-bearing — {@code handleBatch} holds the owner thread for a whole batch,
     * so a client cancel can never interleave with the fence's own orderRef-0 NOT_FOUND ack.
     */
    @Override
    public ExecResult submitCancel(final int orderRef) {
        if (orderRef <= 0) {
            return new ExecResult(false, orderRef, OutputEvent.KIND_ORDER_NOT_FOUND); // 0 is the reserved fence ref
        }
        try {
            return onOwner(() -> {
                event.type = InputEvent.TYPE_ORDER_CANCEL;
                event.side = 0;
                event.orderRef = orderRef;
                event.accountId = 0;
                event.securityId = 0;
                event.qty = 0;
                event.limitPx = 0;
                event.priceTicks = 0L;
                event.eventTimeMillis = 0;
                codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                lastOrderAck = null;
                if (!offerAndAwait(orderBuffer, AeronReplicationCodec.INPUT_BYTES, () -> lastOrderAck != null)) {
                    return null;
                }
                final byte kind = (byte) lastOrderAck[2];
                // "Accepted" means the order is gone from the book. Cancel of an already-CANCELED
                // order also reports CANCELED — the engine re-publishes a terminal order unchanged
                // (009 parity), which makes a retried cancel idempotent rather than an error.
                if (kind == OutputEvent.KIND_ORDER_CANCELED) {
                    canceledOrders++;
                }
                return new ExecResult(kind == OutputEvent.KIND_ORDER_CANCELED, orderRef, kind);
            });
        } catch (final Exception e) {
            return null; // ambiguous/timeout: caller must not claim the order still rests
        }
    }

    // ----- REST -------------------------------------------------------------------------------

    /** POST /cancel {"orderRef":N} — 200 canceled, 409 already terminal, 404 unknown. */
    private void handleCancel(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            if (!body.hasNonNull("orderRef")) {
                respond(exchange, 400, "{\"error\":\"orderRef required\"}");
                return;
            }
            final int orderRef = body.get("orderRef").asInt();
            final ExecResult r = submitCancel(orderRef);
            if (r == null) {
                respond(exchange, 504, "{\"error\":\"no committed ack\"}");
                return;
            }
            final int code = r.accepted() ? 200
                : r.kind() == OutputEvent.KIND_ORDER_NOT_FOUND ? 404 : 409;
            respond(exchange, code, "{\"orderRef\":" + orderRef + ",\"kind\":" + r.kind()
                + ",\"canceled\":" + r.accepted() + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    private void handleOrder(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final String ticker = body.hasNonNull("securityId")
                ? "#" + body.get("securityId").asInt() : body.path("ticker").asText("");
            final char side = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
            final int qty = body.path("quantity").asInt();
            final long px = Math.round(body.path("limitPrice").asDouble() * 1_000_000d);
            final ExecResult r = submitOrder(body.path("clientOrderId").asText("rest"),
                body.path("accountId").asInt(), ticker, side, qty, px);
            if (r == null) {
                respond(exchange, 504, "{\"error\":\"no committed ack\"}");
                return;
            }
            respond(exchange, r.accepted() ? 200 : 422,
                "{\"orderRef\":" + r.orderRef() + ",\"kind\":" + r.kind() + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Bench load path: a JSON array of {accountId, security, side, quantity, limitPrice}.
     *  PIPELINED (NFR-AC02): the owner thread offers every order into the consensus log without
     *  per-order round trips and counts the acks as they stream back — the per-order committed-ack
     *  wait (~1.2ms each) was the ~1k/s ceiling; amortizing it is where YU11's 25k+/s came from. */
    private void handleBatch(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode arr = JSON.readTree(exchange.getRequestBody());
            final int total = arr.size();
            final int[] accounts = new int[total];
            final String[] tickers = new String[total];
            final char[] sides = new char[total];
            final int[] qtys = new int[total];
            final long[] pxs = new long[total];
            int i = 0;
            for (final JsonNode body : arr) {
                tickers[i] = body.hasNonNull("securityId")
                    ? "#" + body.get("securityId").asInt() : body.path("security").asText("");
                sides[i] = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
                accounts[i] = body.path("accountId").asInt();
                qtys[i] = body.path("quantity").asInt();
                pxs[i] = Math.round(body.path("limitPrice").asDouble() * 1_000_000d);
                i++;
            }
            final long batchBudgetMs = ACK_TIMEOUT_MS + total * 5L;
            final Integer batchResult = onOwner(() -> {
                final long deadline = System.currentTimeMillis() + batchBudgetMs;
                batchActive = true;
                batchOutstanding = 0;
                batchAccepted = 0;
                batchFenceAwaiting = false;
                batchFenceAppliedSeq = -1;
                try {
                    for (int n = 0; n < total; n++) {
                        final int securityId = resolveSecurityId(tickers[n]);
                        if (securityId < 0) {
                            continue; // unresolvable ticker: never offered, never acked
                        }
                        event.type = InputEvent.TYPE_ORDER_NEW;
                        event.side = sides[n] == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
                        event.orderRef = 0;
                        event.accountId = accounts[n];
                        event.securityId = securityId;
                        event.qty = qtys[n];
                        event.limitPx = pxs[n];
                        event.priceTicks = 0L;
                        event.eventTimeMillis = 0;
                        codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                        while (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
                            client.pollEgress(); // drains acks (frees ingress window) while backpressured
                            if (System.currentTimeMillis() > deadline) {
                                return batchAccepted;
                            }
                        }
                        batchOutstanding++;
                        client.pollEgress();
                    }
                    if (batchOutstanding > 0) {
                        event.type = InputEvent.TYPE_ORDER_CANCEL;
                        event.side = 0;
                        event.orderRef = 0;
                        event.accountId = 0;
                        event.securityId = 0;
                        event.qty = 0;
                        event.limitPx = 0;
                        event.priceTicks = 0;
                        event.eventTimeMillis = 0;
                        codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                        batchFenceAwaiting = true;
                        long nextFenceAt = 0;
                        while (System.currentTimeMillis() < deadline) {
                            client.pollEgress();
                            if (batchFenceAppliedSeq >= 0) {
                                batchHighWaterCompletions++;
                                return batchAccepted;
                            }
                            final long now = System.currentTimeMillis();
                            if (now >= nextFenceAt) {
                                if (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) > 0) {
                                    batchFenceOffers++;
                                    nextFenceAt = now + BATCH_FENCE_RETRY_MS;
                                }
                            }
                            Thread.yield();
                        }
                        batchHighWaterTimeouts++;
                        return batchAccepted;
                    }
                    while (batchOutstanding > 0 && System.currentTimeMillis() < deadline) {
                        client.pollEgress();
                    }
                    return batchAccepted;
                } finally {
                    batchFenceAwaiting = false;
                    batchActive = false;
                }
            }, batchBudgetMs + 2_000);
            final int accepted = batchResult == null ? 0 : batchResult;
            // 201: the inherited bench harness (batch-load.mjs) counts a batch as accepted
            // only on 201 Created.
            respond(exchange, 201, "{\"accepted\":" + accepted + ",\"total\":" + total + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Bench/ops seeding: POST {accountId, tickers:"JPM,GS,...", price} enables the account,
     *  registers+enables each ticker, and publishes a price — all through the sequenced
     *  ingress path (ADR-045: the consensus log is the only input). */
    /** Market trade from the trade ticket (FR-09B08), the path the web UI's create-order button
     *  takes: trade-service validates ticker + account, then POSTs the TradeOrder here. The engine
     *  books it at the security's last trade price (seeded by market-data ticks until the book
     *  first crosses — ADR-051) with no order and no matching, so the payload carries no price.
     *  SYNCHRONOUS by design: this path is the UI create-order button ONLY (one human click — the
     *  bench never touches it, it drives /orders + /orders/batch), so waiting for the committed
     *  decision costs no throughput that matters and lets us answer 200 booked / 422 + RiskReason
     *  on reject. Fire-and-forget here returned a green 200 on a risk-rejected trade and the order
     *  silently vanished — a reject leaves NO NATS/DB/UI trace (only a booked trade rides the
     *  /trades bridge), so the 200 was the only signal and it lied.
     *  422 and 504 are NOT interchangeable: 422 is a committed business rejection carrying its
     *  RiskReason; 504 means no committed decision arrived (a failover/timeout — ambiguous), and
     *  the trade may still commit. Never report an ambiguous outcome as a rejection. */
    private void handleTrade(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final int accountId = body.path("accountId").asInt();
            final String ticker = body.hasNonNull("securityId")
                ? "#" + body.get("securityId").asInt() : body.path("security").asText("");
            final char side = "Sell".equalsIgnoreCase(body.path("side").asText("Buy")) ? 'S' : 'B';
            final int qty = body.path("quantity").asInt();
            final long[] ack = onOwner(() -> {
                final int securityId = resolveSecurityId(ticker);
                if (securityId < 0) { // unresolvable ticker: never offered — answer as the risk gate would
                    return new long[] {OutputEvent.KIND_TRADE_REJECTED, RiskReason.UNKNOWN_SECURITY.ordinal()};
                }
                event.type = InputEvent.TYPE_TRADE_NEW;
                event.side = side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
                event.orderRef = 0;
                event.accountId = accountId;
                event.securityId = securityId;
                event.qty = qty;
                event.limitPx = 0L;   // market trade: the engine stamps the BLP's last trade price
                event.priceTicks = 0L; // clientOrderKey slot; ticket dedup is deferred
                event.eventTimeMillis = 0;
                codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
                lastTradeAck = null;
                if (!offerAndAwait(orderBuffer, AeronReplicationCodec.INPUT_BYTES, () -> lastTradeAck != null)) {
                    return null; // no committed decision within timeout: ambiguous, NOT a reject
                }
                return lastTradeAck;
            });
            if (ack == null) {
                respond(exchange, 504, "{\"error\":\"no committed decision\"}");
                return;
            }
            if (ack[0] != OutputEvent.KIND_TRADE_REJECTED) {
                respond(exchange, 200, "{\"booked\":true}");
            } else {
                respond(exchange, 422, "{\"booked\":false,\"reason\":\"" + RiskReason.values()[(int) ack[1]] + "\"}");
            }
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    private void handleSeed(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final int accountId = body.path("accountId").asInt();
            final String[] tickers = body.path("tickers").asText("").split(",");
            final long priceTicks = Math.round(body.path("price").asDouble(150) * 1_000_000d);
            final Boolean ok = onOwner(() -> {
                long version = System.currentTimeMillis(); // monotonic across re-seeds
                event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
                event.accountId = accountId;
                event.securityId = 0;
                event.setControlEnabled(true);
                event.setControlVersion(version++);
                offerBlocking();
                for (final String ticker : tickers) {
                    final int id = resolveSecurityId(ticker.trim());
                    if (id < 0) {
                        return false;
                    }
                    event.type = InputEvent.TYPE_SECURITY_CONTROL;
                    event.accountId = 0;
                    event.securityId = id;
                    event.setControlEnabled(true);
                    event.setControlVersion(version++);
                    offerBlocking();
                    event.type = InputEvent.TYPE_PRICE_TICK;
                    event.side = 0;
                    event.securityId = id;
                    event.priceTicks = priceTicks;
                    offerBlocking();
                }
                return true;
            });
            respond(exchange, Boolean.TRUE.equals(ok) ? 200 : 422, "{\"seeded\":" + ok + "}");
        } catch (final Exception e) {
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Offer the current {@code event} with backpressure retry (owner thread only). */
    private void offerBlocking() {
        codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);
        final long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        while (client.offer(orderBuffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
            client.pollEgress();
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("ingress offer timed out");
            }
            Thread.yield();
        }
    }

    /** Prometheus-text metrics the inherited bench harness reads (booked/s = delta of the fill
     *  counter / elapsed). Format matches the order-matcher's traderx_order_events_total family. */
    private void handleMetrics(final HttpExchange exchange) {
        final String body = "traderx_order_events_total{event=\"fill\"} " + fillEvents + "\n"
            + "traderx_order_events_total{event=\"accepted\"} " + acceptedOrders + "\n"
            + "traderx_order_events_total{event=\"canceled\"} " + canceledOrders + "\n"
            + "traderx_market_trades_total{outcome=\"booked\"} " + marketTradesBooked + "\n"
            + "traderx_market_trades_total{outcome=\"rejected\"} " + marketTradesRejected + "\n"
            + "traderx_gateway_batch_fences_total{state=\"offered\"} " + batchFenceOffers + "\n"
            + "traderx_gateway_batch_high_water_total{outcome=\"completed\"} "
                + batchHighWaterCompletions + "\n"
            + "traderx_gateway_batch_high_water_total{outcome=\"timeout\"} "
                + batchHighWaterTimeouts + "\n";
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final Exception ignore) {
            // scrape client went away
        }
    }

    // ----- symbol resolution (owner thread only) ---------------------------------------------

    /** ticker -> securityId via the sequenced registration path (matrix F2); cached forever.
     *  A "#<n>" pseudo-ticker is a pre-resolved securityId passthrough (REST securityId path). */
    private int resolveSecurityId(final String ticker) {
        if (ticker.startsWith("#")) {
            return Integer.parseInt(ticker.substring(1));
        }
        final Integer cached = idByTicker.get(ticker);
        if (cached != null) {
            return cached;
        }
        final long requestId = nextSymbolRequestId++;
        codec.encodeSymbolRegister(symbolBuffer, 0, requestId, ticker);
        lastSymbolAck = null;
        if (!offerAndAwait(symbolBuffer, AeronReplicationCodec.SYMBOL_BYTES,
            () -> lastSymbolAck != null && lastSymbolAck[2] == requestId)) {
            return -1;
        }
        final int id = (int) lastSymbolAck[1];
        if (id >= 0) {
            idByTicker.put(ticker, id);
        }
        return id;
    }

    private boolean offerAndAwait(final UnsafeBuffer buffer, final int length,
                                  final java.util.function.BooleanSupplier ackArrived) {
        final long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        boolean offered = false;
        while (System.currentTimeMillis() < deadline) {
            client.pollEgress();
            if (!offered && client.offer(buffer, 0, length) > 0) {
                offered = true;
            }
            if (offered && ackArrived.getAsBoolean()) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    private static void respond(final HttpExchange exchange, final int code, final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final Exception ignore) {
            // client went away
        }
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
