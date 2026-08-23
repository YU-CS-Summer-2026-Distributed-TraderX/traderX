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
import java.util.concurrent.atomic.AtomicLong;

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
    // Counted on the NATS dispatcher thread, read on the flush loop. A handler that swallows every
    // message it cannot parse is how this class ran for five states without sequencing one tick
    // (issues/open/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md): nothing it
    // printed and nothing on the members distinguished "no feed" from "feed dropped on the floor".
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private long sequenced;
    private long lastReportMs;

    private AeronCluster client;

    public static void main(final String[] args) {
        try {
            new FeedAdapterMain().run();
        } catch (final Throwable fatal) {
            fatal.printStackTrace();
        } finally {
            // run() never returns on the happy path, so reaching here means failure. The exit is
            // load-bearing: the NATS and Aeron client threads are non-daemon, so a throw out of
            // main leaves the JVM alive with NO flush loop -- 1/1 Running, sequencing nothing.
            // Measured 2026-08-24: the session-lost throw during a member roll did exactly that,
            // and the pod sat wedged for two hours. Die visibly; the kubelet restarts a clean
            // connect (verified: a fresh pod re-registers and resumes within one flush).
            System.exit(1);
        }
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
            final String ticker = message.getSubject().substring("pricing.".length());
            final long px = parsePriceTicks(message.getData());
            if (px == NO_PRICE) {
                // Malformed: conflation map untouched. Say so ONCE with the bytes, so the next
                // shape mismatch is diagnosed from this line rather than from a flat counter.
                if (dropped.getAndIncrement() == 0) {
                    System.out.println("FEED DROP first unparseable tick on " + message.getSubject()
                        + ": " + new String(message.getData(), 0, Math.min(160, message.getData().length)));
                }
                return;
            }
            received.incrementAndGet();
            latestTicks.put(ticker, px);
        });
        dispatcher.subscribe("pricing.>");
        System.out.println("FEED ADAPTER up: nats=" + natsUrl + " flushMs=" + flushMs);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CloseHelper.quietCloseAll(client, driver);
        }));

        // The flush interval is NOT the loop interval. This used to sleep flushMs between passes,
        // which at the 50ms default was invisible and at 15s was fatal: the consensus module expires
        // an idle session (the gateway's keepalive comment records the same finding), offer() then
        // answers CLOSED forever, and register()'s retry spun on it with no log line -- measured
        // 2026-08-23 as received=0 dropped=0 sequenced=0 reported once and never again. Poll egress
        // and keep the session alive every second; flush on the interval; treat a lost session as
        // fatal so the kubelet restarts a fresh connect rather than leaving a quiet wedge Running.
        long nextFlushMs = System.currentTimeMillis() + flushMs;
        long lastKeepAliveMs = 0L;
        while (true) {
            client.pollEgress();
            final long now = System.currentTimeMillis();
            if (now - lastKeepAliveMs >= 1_000L) {
                lastKeepAliveMs = now;
                client.sendKeepAlive();
            }
            if (now >= nextFlushMs) {
                nextFlushMs = now + flushMs;
                flush();
                report();
            }
            Thread.sleep(Math.min(100L, flushMs));
        }
    }

    private void flush() {
        for (final Map.Entry<String, Long> tick : latestTicks.entrySet()) {
            final Integer id = idByTicker.get(tick.getKey());
            if (id == null) {
                register(tick.getKey());
                continue; // sequenced next flush once the ack lands
            }
            final Long px = latestTicks.remove(tick.getKey());
            if (px != null) {
                offerTick(id, px);
                sequenced++;
            }
        }
    }

    /** Retry back-pressure; die on a session that is gone. A closed session never comes back on
     *  its own and this process holds nothing worth preserving across a restart. */
    private void offerOrDie(final UnsafeBuffer buf, final int length) {
        long result;
        while ((result = client.offer(buf, 0, length)) < 0) {
            if (result == io.aeron.Publication.CLOSED || result == io.aeron.Publication.NOT_CONNECTED
                || result == io.aeron.Publication.MAX_POSITION_EXCEEDED || client.isClosed()) {
                throw new IllegalStateException("cluster session lost (offer=" + result + "); exiting for a clean reconnect");
            }
            client.pollEgress();
            Thread.yield();
        }
    }

    static final long NO_PRICE = Long.MIN_VALUE;

    /**
     * Price ticks (1e6 per unit) from one {@code pricing.<ticker>} message, or {@link #NO_PRICE}.
     * price-publisher wraps every quote in the house envelope
     * {@code {topic, payload:{ticker, price, ...}, date, from, type}}, so the price is at
     * {@code payload.price}; a bare {@code {price}} is accepted too so a publisher that drops the
     * envelope does not put this class back where it was. Both of the publisher's wire scales
     * (equities 3dp, treasuries 6dp fraction of par) land on 1e6 ticks under this rounding.
     */
    static long parsePriceTicks(final byte[] data) {
        try {
            final JsonNode node = JSON.readTree(data);
            final JsonNode body = node.has("payload") ? node.get("payload") : node;
            final JsonNode price = body.get("price");
            if (price == null || !price.isNumber()) {
                return NO_PRICE;
            }
            final long px = Math.round(price.asDouble() * 1_000_000d);
            return px > 0 ? px : NO_PRICE;
        } catch (final Exception malformed) {
            return NO_PRICE;
        }
    }

    /** One line a minute: the three counts whose relationship says whether the feed
     *  is flowing. received ~ dropped means the envelope changed; sequenced 0 with received > 0
     *  means registrations are not being acked; all three 0 means NATS has nothing on the subject. */
    private void report() {
        final long now = System.currentTimeMillis();
        if (now - lastReportMs < 60_000L) {
            return;
        }
        lastReportMs = now;
        System.out.println("FEED received=" + received.get() + " dropped=" + dropped.get()
            + " sequenced=" + sequenced + " symbols=" + idByTicker.size()
            + " pendingRegistrations=" + pendingRegistrations.size());
    }

    private void register(final String ticker) {
        if (pendingRegistrations.containsValue(ticker)) {
            return; // one in-flight registration per ticker
        }
        final long requestId = nextRequestId++;
        pendingRegistrations.put(requestId, ticker);
        codec.encodeSymbolRegister(symbolBuffer, 0, requestId, ticker);
        offerOrDie(symbolBuffer, AeronReplicationCodec.SYMBOL_BYTES);
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
        offerOrDie(buffer, AeronReplicationCodec.INPUT_BYTES);
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
