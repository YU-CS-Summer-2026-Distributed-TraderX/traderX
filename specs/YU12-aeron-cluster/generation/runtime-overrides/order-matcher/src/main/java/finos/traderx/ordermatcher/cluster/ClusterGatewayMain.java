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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Stateless-forward order gateway (ADR-047, first cut): terminates REST, screens nothing away
 * from the authoritative core (risk decides inside the cluster), forwards through the Aeron
 * Cluster client — which follows the leader natively — and answers each request from the
 * committed egress ack. One worker thread serializes requests, so ack correlation is FIFO by
 * construction: the first order-kind ack after an offer IS that order's create/reject response.
 *
 * Split readiness (ADR-045): {@code /ready} is 200 only while the cluster session is live.
 * Cluster order state survives this gateway dying (TD-AC01); counterparty FIX termination
 * lands on this same tier and is the open remainder of the workstream.
 */
public final class ClusterGatewayMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ACK_TIMEOUT_MS = 10_000;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer orderBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private final Map<String, Integer> idByTicker = new HashMap<>();

    private AeronCluster client;
    private volatile boolean connected;

    // Set by the egress listener between poll calls on the single worker thread.
    private long[] lastOrderAck;   // {appliedSeq, orderRef, kind, tradeSeq}
    private long[] lastSymbolAck;  // {appliedSeq, symbolId, requestId}
    private long nextSymbolRequestId = 1;

    public static void main(final String[] args) throws Exception {
        new ClusterGatewayMain().run();
    }

    private void run() throws Exception {
        final String ingressEndpoints = env("GATEWAY_INGRESS_ENDPOINTS", "0=localhost:21802");
        final int httpPort = Integer.parseInt(env("GATEWAY_HTTP_PORT", "18110"));
        final String aeronDir = env("GATEWAY_AERON_DIR", "/dev/shm/aeron-gateway");

        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));
        connect(ingressEndpoints, aeronDir);

        final HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 64);
        server.setExecutor(Executors.newSingleThreadExecutor()); // FIFO correlation by construction
        server.createContext("/orders", this::handleOrder);
        server.createContext("/ready", exchange ->
            respond(exchange, connected ? 200 : 503, "{\"connected\":" + connected + "}"));
        server.createContext("/health", exchange ->
            respond(exchange, 200, "{\"connected\":" + connected + "}"));
        server.start();
        System.out.println("GATEWAY up: http=" + httpPort + " ingress=" + ingressEndpoints);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            CloseHelper.quietCloseAll(client, driver);
        }));
        Thread.currentThread().join();
    }

    private void connect(final String ingressEndpoints, final String aeronDir) {
        CloseHelper.quietClose(client);
        client = AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(aeronDir)
            .ingressChannel("aeron:udp?term-length=64k")
            .ingressEndpoints(ingressEndpoints)
            .egressChannel("aeron:udp?term-length=64k|endpoint=" + env("GATEWAY_EGRESS_HOST", env("POD_IP", "localhost")) + ":" + env("GATEWAY_EGRESS_PORT", "0"))
            .egressListener(this::onEgress));
        connected = true;
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

    private void handleOrder(final HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"POST only\"}");
                return;
            }
            final JsonNode body = JSON.readTree(exchange.getRequestBody());
            final int securityId = resolveSecurityId(body);
            if (securityId < 0) {
                respond(exchange, 503, "{\"error\":\"symbol registration unavailable\"}");
                return;
            }
            event.type = InputEvent.TYPE_ORDER_NEW;
            event.side = "Sell".equalsIgnoreCase(body.path("side").asText("Buy"))
                ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
            event.orderRef = 0;
            event.accountId = body.path("accountId").asInt();
            event.securityId = securityId;
            event.qty = body.path("quantity").asInt();
            event.limitPx = Math.round(body.path("limitPrice").asDouble() * 1_000_000d);
            event.priceTicks = body.path("clientOrderKey").asLong(0L);
            event.eventTimeMillis = 0;
            codec.encodeInput(orderBuffer, 0, event, 0, 0, 0);

            lastOrderAck = null;
            if (!offerAndAwait(orderBuffer, AeronReplicationCodec.INPUT_BYTES, () -> lastOrderAck != null)) {
                respond(exchange, 504, "{\"error\":\"no committed ack\"}");
                return;
            }
            final long[] ack = lastOrderAck;
            final boolean accepted = ack[2] != OutputEvent.KIND_ORDER_REJECTED
                && ack[2] != OutputEvent.KIND_ORDER_NOT_FOUND;
            respond(exchange, accepted ? 200 : 422,
                "{\"orderRef\":" + ack[1] + ",\"kind\":" + ack[2] + ",\"appliedSeq\":" + ack[0] + "}");
        } catch (final Exception e) {
            connected = false;
            respond(exchange, 503, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
            try {
                connect(env("GATEWAY_INGRESS_ENDPOINTS", "0=localhost:21802"),
                    env("GATEWAY_AERON_DIR", "/dev/shm/aeron-gateway"));
            } catch (final Exception ignore) {
                // next request retries
            }
        }
    }

    /** ticker -> securityId via the sequenced registration path (matrix F2); cached forever. */
    private int resolveSecurityId(final JsonNode body) {
        if (body.hasNonNull("securityId")) {
            return body.get("securityId").asInt();
        }
        final String ticker = body.path("ticker").asText("");
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
