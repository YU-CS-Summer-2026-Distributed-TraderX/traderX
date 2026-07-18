package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
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
    private AeronCluster client;
    private volatile boolean connected;
    private volatile boolean running = true;

    // Owner-thread-only ack scratch (set by the egress listener between poll calls).
    private long[] lastOrderAck;   // {appliedSeq, orderRef, kind, tradeSeq}
    private long[] lastSymbolAck;  // {appliedSeq, symbolId, requestId}
    private long nextSymbolRequestId = 1;

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
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/orders", this::handleOrder);
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

    /** Cycle single endpoints until the leader accepts a session (owner thread only). */
    private void connectCycling() {
        int attempt = 0;
        while (running) {
            final String entry = endpointEntries[attempt++ % endpointEntries.length];
            try {
                CloseHelper.quietClose(client);
                client = AeronCluster.connect(new AeronCluster.Context()
                    .aeronDirectoryName(aeronDir)
                    .ingressChannel("aeron:udp?term-length=64k")
                    .ingressEndpoints(entry)
                    .egressChannel("aeron:udp?term-length=64k|endpoint="
                        + env("GATEWAY_EGRESS_HOST", env("POD_IP", "localhost")) + ":"
                        + env("GATEWAY_EGRESS_PORT", "0"))
                    .egressListener(this::onEgress));
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
            if (lastOrderAck == null) { // first order-kind ack after the offer wins (create/reject)
                lastOrderAck = new long[] {
                    buffer.getLong(offset), buffer.getInt(offset + 8), kind, buffer.getLong(offset + 13) };
            }
        }
    }

    /** Run a client-touching callable on the owner thread and wait for its result. */
    private <T> T onOwner(final java.util.concurrent.Callable<T> callable) throws Exception {
        final FutureTask<T> ft = new FutureTask<>(callable);
        tasks.add(ft);
        return ft.get(ACK_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
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
                return new ExecResult(accepted, (int) ack[1], (byte) ack[2]);
            });
        } catch (final Exception e) {
            return null; // ambiguous/timeout: caller must not claim rejection
        }
    }

    // ----- REST -------------------------------------------------------------------------------

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
