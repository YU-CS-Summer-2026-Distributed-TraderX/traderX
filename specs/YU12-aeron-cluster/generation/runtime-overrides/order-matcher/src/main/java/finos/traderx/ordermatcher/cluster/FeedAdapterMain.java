package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feed adapter (ADR-045): consumes the inherited NATS pricing subjects and sequences them into
 * the consensus log as the ONLY market-data path into the deterministic core. Ticks are
 * conflated per ticker (latest wins) and flushed every {@code FEED_FLUSH_MS}, bounding log
 * volume regardless of upstream rate. Unknown tickers are first registered through the
 * sequenced {@code SymbolRegisterMessage} (matrix finding F2): the cluster assigns the id in
 * committed-log order and the adapter caches the mapping from the egress ack.
 *
 * The parent state's follower-side tick injection defect class cannot recur: no replica ever
 * consumes NATS directly.
 */
public final class FeedAdapterMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private final Map<String, Long> latestTicks = new ConcurrentHashMap<>();
    private final Map<String, Integer> idByTicker = new ConcurrentHashMap<>();
    private final Map<Long, String> pendingRegistrations = new ConcurrentHashMap<>();
    private long nextRequestId = 1;

    private AeronCluster client;

    public static void main(final String[] args) throws Exception {
        new FeedAdapterMain().run();
    }

    private void run() throws Exception {
        final String natsUrl = env("NATS_URL", "nats://localhost:4222");
        final String ingressEndpoints = env("FEED_INGRESS_ENDPOINTS", "0=localhost:21802");
        final long flushMs = Long.parseLong(env("FEED_FLUSH_MS", "50"));
        final String aeronDir = env("FEED_AERON_DIR", "/dev/shm/aeron-feed");

        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));
        client = AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(aeronDir)
            .ingressChannel("aeron:udp?term-length=64k")
            .ingressEndpoints(ingressEndpoints)
            .egressChannel("aeron:udp?term-length=64k|endpoint=" + env("FEED_EGRESS_HOST", env("POD_IP", "localhost")) + ":" + env("FEED_EGRESS_PORT", "0"))
            .egressListener((sessionId, timestamp, egress, offset, length, header) -> {
                if (egress.getByte(offset + 12) == MatchingEngineClusteredService.KIND_SYMBOL_REGISTERED) {
                    final int id = egress.getInt(offset + 8);
                    final String ticker = pendingRegistrations.remove(egress.getLong(offset + 13));
                    if (ticker != null && id >= 0) {
                        idByTicker.put(ticker, id);
                        System.out.println("SYMBOL " + ticker + "=" + id);
                    }
                }
            }));

        final Connection nats = Nats.connect(natsUrl);
        final Dispatcher dispatcher = nats.createDispatcher(message -> {
            try {
                final String ticker = message.getSubject().substring("pricing.".length());
                final JsonNode node = JSON.readTree(message.getData());
                final double price = node.get("price").asDouble();
                latestTicks.put(ticker, Math.round(price * 1_000_000d));
            } catch (final Exception ignore) {
                // malformed tick: conflation map untouched
            }
        });
        dispatcher.subscribe("pricing.>");
        System.out.println("FEED ADAPTER up: nats=" + natsUrl + " flushMs=" + flushMs);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CloseHelper.quietCloseAll(client, driver);
        }));

        while (true) {
            client.pollEgress();
            for (final Map.Entry<String, Long> tick : latestTicks.entrySet()) {
                final Integer id = idByTicker.get(tick.getKey());
                if (id == null) {
                    register(tick.getKey());
                    continue; // sequenced next flush once the ack lands
                }
                final Long px = latestTicks.remove(tick.getKey());
                if (px != null) {
                    offerTick(id, px);
                }
            }
            Thread.sleep(flushMs);
        }
    }

    private void register(final String ticker) {
        if (pendingRegistrations.containsValue(ticker)) {
            return; // one in-flight registration per ticker
        }
        final long requestId = nextRequestId++;
        pendingRegistrations.put(requestId, ticker);
        codec.encodeSymbolRegister(symbolBuffer, 0, requestId, ticker);
        while (client.offer(symbolBuffer, 0, AeronReplicationCodec.SYMBOL_BYTES) < 0) {
            client.pollEgress();
            Thread.yield();
        }
    }

    private void offerTick(final int securityId, final long priceTicks) {
        event.type = InputEvent.TYPE_PRICE_TICK;
        event.side = 0;
        event.orderRef = 0;
        event.accountId = 0;
        event.securityId = securityId;
        event.qty = 0;
        event.limitPx = 0;
        event.priceTicks = priceTicks;
        event.eventTimeMillis = 0; // stamped with cluster time at apply
        codec.encodeInput(buffer, 0, event, 0, 0, 0);
        while (client.offer(buffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
            client.pollEgress();
            Thread.yield();
        }
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
