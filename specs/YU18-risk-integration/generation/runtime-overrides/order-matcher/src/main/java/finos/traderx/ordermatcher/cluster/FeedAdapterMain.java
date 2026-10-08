package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.exceptions.AeronException;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Latest-value feed conflation with bounded recovery of this adapter's established Aeron session.
 * Transport availability is independent of quote activity and is not quorum or freshness evidence. */
public final class FeedAdapterMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent event = new InputEvent();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private final Map<String, Long> latestTicks = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong(), dropped = new AtomicLong();
    private final Settings settings;
    private final Connector connector;
    private final Timing timing;
    private final Resources resources;
    private final Consumer<String> log;
    private volatile Session active;
    private volatile Connection nats;
    private long generation, nextRequestId = 1, sequenced, lastReportMs;
    private long attempts, failures, recoveries, successfulConnections;
    private int episodeAttempts;
    private String state = "STARTING", lastFailure = "none";

    interface Connector { AeronCluster connect(AeronCluster.Context context) throws Exception; }
    interface Timing {
        long nanoTime(); long currentTimeMillis(); void sleepNanos(long nanos) throws InterruptedException;
    }
    interface Resources {
        MediaDriver driver(Settings settings); Connection nats(Settings settings) throws Exception;
    }
    record Retry(long budgetMs, long initialBackoffMs, long maxBackoffMs, long attemptMs) {
        Retry {
            for (long value : new long[] {budgetMs, initialBackoffMs, maxBackoffMs, attemptMs}) {
                if (value <= 0 || value > Long.MAX_VALUE / 1_000_000L)
                    throw new IllegalArgumentException("feed reconnect durations must be positive safe milliseconds");
            }
            if (maxBackoffMs < initialBackoffMs) throw new IllegalArgumentException("feed reconnect maximum backoff is below initial backoff");
        }
    }
    record Settings(String natsUrl, String ingress, String aeronDir, String egress, long flushMs, Retry retry) {
        Settings { if (flushMs <= 0) throw new IllegalArgumentException("FEED_FLUSH_MS must be positive"); }
        static Settings environment() {
            return new Settings(env("NATS_URL", "nats://localhost:4222"), env("FEED_INGRESS_ENDPOINTS", "0=localhost:21802"),
                env("FEED_AERON_DIR", "/dev/shm/aeron-feed"),
                "aeron:udp?term-length=64k|endpoint=" + env("FEED_EGRESS_HOST", env("POD_IP", "localhost")) + ":" + env("FEED_EGRESS_PORT", "0"),
                Long.parseLong(env("FEED_FLUSH_MS", "50")),
                new Retry(Long.parseLong(env("FEED_RECONNECT_BUDGET_MS", "10000")),
                    Long.parseLong(env("FEED_RECONNECT_BACKOFF_MS", "100")),
                    Long.parseLong(env("FEED_RECONNECT_MAX_BACKOFF_MS", "1000")),
                    Long.parseLong(env("FEED_RECONNECT_ATTEMPT_MS", "1000"))));
        }
    }
    FeedAdapterMain() {
        this(Settings.environment(), FeedAdapterMain::nativeConnect, new Timing() {
            public long nanoTime() { return System.nanoTime(); }
            public long currentTimeMillis() { return System.currentTimeMillis(); }
            public void sleepNanos(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
        }, new Resources() {
            public MediaDriver driver(Settings s) {
                return MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(s.aeronDir())
                    .threadingMode(ThreadingMode.SHARED).termBufferSparseFile(true).dirDeleteOnStart(true));
            }
            public Connection nats(Settings s) throws Exception { return Nats.connect(s.natsUrl()); }
        }, System.out::println);
    }
    FeedAdapterMain(Settings settings, Connector connector, Timing timing, Resources resources, Consumer<String> log) {
        this.settings = settings; this.connector = connector; this.timing = timing; this.resources = resources; this.log = log;
    }
    public static void main(String[] args) {
        try { new FeedAdapterMain().run(); }
        catch (Throwable fatal) { fatal.printStackTrace(); }
        finally { System.exit(1); } // visible failure; never leave client threads alive without the flush loop
    }
    private static AeronCluster nativeConnect(AeronCluster.Context context) throws Exception {
        try (AeronCluster.AsyncConnect pending = AeronCluster.asyncConnect(context)) {
            // DONE transfers ownership; AsyncConnect.close does not close the returned client.
            AeronCluster result;
            while (true) { interrupted(); result = pending.poll(); if (result != null) return result; context.idleStrategy().idle(); }
        } catch (Exception error) {
            try { context.close(); } catch (RuntimeException cleanup) { cleanup.addSuppressed(error); throw new CleanupFailure(cleanup); }
            if (error.getSuppressed().length > 0) throw new CleanupFailure(error);
            throw error;
        }
    }
    void run() throws Exception {
        final Thread owner = Thread.currentThread();
        final Thread hook = new Thread(owner::interrupt, "feed-stop");
        boolean interrupted = false;
        Runtime.getRuntime().addShutdownHook(hook);
        transition("STARTING", "none");
        try (MediaDriver driver = resources.driver(settings)) {
            try {
                try { attempts++; connect(0); successfulConnections++; }
                catch (Exception error) { failures++; transition("FAILED_START", cause(error)); throw error; }
                try (Connection connection = resources.nats(settings)) {
                    nats = connection;
                    Dispatcher dispatcher = connection.createDispatcher(message -> onPricing(message.getSubject(), message.getData()));
                    dispatcher.subscribe("pricing.>");
                    transition("CONNECTED", "none");
                    try { loop(); }
                    catch (InterruptedException error) { Thread.interrupted(); interrupted = true; throw error; }
                }
            } catch (InterruptedException error) { Thread.interrupted(); interrupted = true; throw error; }
            finally { closeActive(); }
        } catch (InterruptedException error) { transition("STOPPED", "InterruptedException"); throw error; }
        catch (Exception error) {
            if (!state.equals("FAILED_START") && !state.equals("EXHAUSTED")) transition("FAILED", cause(error));
            throw error;
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shutdown) { /* JVM is already stopping */ }
            if (interrupted) owner.interrupt();
        }
    }
    private void connect(long timeoutNanos) throws Exception {
        interrupted();
        final Session session = new Session(++generation);
        active = session; // callbacks capture only this generation's own maps
        final AeronCluster.Context context = new AeronCluster.Context().aeronDirectoryName(settings.aeronDir())
            .ingressChannel("aeron:udp?term-length=64k").ingressEndpoints(settings.ingress())
            .egressChannel(settings.egress()).egressListener(session);
        if (timeoutNanos > 0) context.messageTimeoutNs(timeoutNanos);
        try {
            session.client = connector.connect(context);
            interrupted(); check(session);
        } catch (Exception error) { closeActive(); throw error; }
    }
    private void loop() throws Exception {
        long nextFlush = timing.currentTimeMillis() + settings.flushMs();
        while (true) {
            interrupted();
            try {
                final Session session = active;
                poll(session);
                final long now = timing.currentTimeMillis();
                if (now - session.lastKeepAliveMs >= 1_000L) {
                    session.lastKeepAliveMs = now; session.client.sendKeepAlive(); check(session);
                }
                if (now >= nextFlush) { nextFlush = now + settings.flushMs(); flush(session); report(); }
            } catch (SessionLost | AeronException lost) {
                recover(lost); nextFlush = timing.currentTimeMillis() + settings.flushMs();
            }
            timing.sleepNanos(TimeUnit.MILLISECONDS.toNanos(Math.min(100L, settings.flushMs())));
        }
    }
    private void recover(Exception lost) throws Exception {
        final long began = timing.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(settings.retry().budgetMs());
        long delay = TimeUnit.MILLISECONDS.toNanos(settings.retry().initialBackoffMs());
        final long maxDelay = TimeUnit.MILLISECONDS.toNanos(settings.retry().maxBackoffMs());
        episodeAttempts = 0; transition("RECONNECTING", cause(lost)); closeActive();
        while (true) {
            interrupted();
            long remaining = budget - (timing.nanoTime() - began);
            if (remaining <= 0) { transition("EXHAUSTED", lastFailure); throw new SessionLost("reconnect budget exhausted"); }
            attempts++; episodeAttempts++;
            try {
                connect(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(settings.retry().attemptMs())));
                if (timing.nanoTime() - began >= budget) { failures++; closeActive(); transition("EXHAUSTED", "late_connect"); throw new SessionLost("late reconnect exceeds budget"); }
                successfulConnections++; recoveries++; transition("CONNECTED", "none"); return;
            } catch (InterruptedException stop) { throw stop; }
            catch (CleanupFailure fatal) { throw fatal; }
            catch (Exception error) {
                if (state.equals("EXHAUSTED")) throw error;
                failures++; transition("RECONNECTING", cause(error));
            }
            remaining = budget - (timing.nanoTime() - began);
            if (remaining > 0) timing.sleepNanos(Math.min(delay, remaining));
            delay = delay >= maxDelay / 2 ? maxDelay : Math.min(maxDelay, delay * 2);
        }
    }
    private void closeActive() {
        final Session previous = active; active = null;
        if (previous != null && previous.client != null) {
            try { previous.client.close(); } catch (RuntimeException error) { throw new CleanupFailure(error); }
        }
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("feed owner stopped");
    }
    private void check(Session session) {
        if (session == null || session != active || session.lost || session.client == null || session.client.isClosed()
            || !session.client.ingressPublication().isConnected()) throw new SessionLost("session_unavailable");
    }
    private void poll(Session session) { check(session); session.client.pollEgress(); check(session); }
    private final class Session implements EgressListener {
        final long generation;
        final Map<String,Integer> ids = new ConcurrentHashMap<>();
        final Map<Long,String> pending = new ConcurrentHashMap<>();
        volatile AeronCluster client; volatile boolean lost; long lastKeepAliveMs;
        Session(long generation) { this.generation = generation; }
        public void onMessage(long sessionId, long timestamp, DirectBuffer egress, int offset, int length, io.aeron.logbuffer.Header header) {
            if (active != this || lost || client == null || sessionId != client.clusterSessionId() || length < 21) return;
            if (egress.getByte(offset + 12) == MatchingEngineClusteredService.KIND_SYMBOL_REGISTERED) {
                final int id = egress.getInt(offset + 8);
                final String ticker = pending.remove(egress.getLong(offset + 13));
                if (ticker != null && id >= 0) { ids.put(ticker,id); log.accept("SYMBOL " + ticker + "=" + id); }
            }
        }
        public void onSessionEvent(long correlationId, long sessionId, long leadershipTermId, int leaderMemberId, EventCode code, String detail) {
            if (active == this && (client == null || sessionId == client.clusterSessionId())
                && (code == EventCode.CLOSED || code == EventCode.ERROR)) lost = true;
        }
    }
    void onPricing(String subject, byte[] data) {
        final String ticker = subject.substring("pricing.".length());
        final long px = parsePriceTicks(data);
        if (px == NO_PRICE) {
            if (dropped.getAndIncrement() == 0) log.accept("FEED DROP first unparseable tick on " + subject
                + ": " + new String(data,0,Math.min(160,data.length)));
            return;
        }
        received.incrementAndGet(); latestTicks.put(ticker,px);
    }
    private void flush(Session session) throws InterruptedException {
        for (final Map.Entry<String,Long> tick : latestTicks.entrySet()) {
            final Integer id = session.ids.get(tick.getKey());
            if (id == null) { register(session,tick.getKey()); continue; }
            final Long px = latestTicks.get(tick.getKey());
            if (px != null) {
                offerTick(session,id,px);
                latestTicks.remove(tick.getKey(),px); // retain a newer arrival or a locally unaccepted tick
                sequenced++; // existing counter: publication offers, not committed price ACKs
            }
        }
    }
    private void register(Session session, String ticker) throws InterruptedException {
        if (session.pending.containsValue(ticker)) return;
        final long request = nextRequestId++;
        session.pending.put(request,ticker); codec.encodeSymbolRegister(symbolBuffer,0,request,ticker);
        offer(session,symbolBuffer,AeronReplicationCodec.SYMBOL_BYTES);
    }
    private void offer(Session session, UnsafeBuffer bytes, int length) throws InterruptedException {
        long result;
        while (true) {
            interrupted(); check(session);
            result = session.client.offer(bytes,0,length);
            if (result >= 0) return;
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED || result == Publication.MAX_POSITION_EXCEEDED)
                throw new SessionLost("offer=" + result);
            poll(session); Thread.yield(); // retain original backpressure retry semantics
        }
    }
    private void offerTick(Session session, int securityId, long priceTicks) throws InterruptedException {
        event.type=InputEvent.TYPE_PRICE_TICK; event.side=0; event.orderRef=0; event.accountId=0; event.securityId=securityId;
        event.qty=0; event.limitPx=0; event.priceTicks=priceTicks; event.eventTimeMillis=0;
        codec.encodeInput(buffer,0,event,0,0,0); offer(session,buffer,AeronReplicationCodec.INPUT_BYTES);
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


    private void report() {
        final long now=timing.currentTimeMillis(); if(now-lastReportMs<60_000L)return;lastReportMs=now;
        final Session session=active;
        log.accept("FEED received="+received.get()+" dropped="+dropped.get()+" sequenced="+sequenced
            +" symbols="+(session==null?0:session.ids.size())+" pendingRegistrations="+(session==null?0:session.pending.size()));
        log.accept("FEED_RECOVERY " + recoveryJson());
    }
    String recoveryJson() {
        final Map<String,Object> values=new LinkedHashMap<>();final Session session=active;
        values.put("state",state);values.put("observedAtEpochMs",timing.currentTimeMillis());values.put("sessionGeneration",generation);values.put("attempts",attempts);
        values.put("episodeAttempts",episodeAttempts);values.put("failures",failures);values.put("recoveries",recoveries);
        values.put("successfulConnections",successfulConnections);values.put("lastFailure",lastFailure);
        values.put("received",received.get()); values.put("dropped",dropped.get()); values.put("publicationOffers",sequenced);
        values.put("symbols",session==null?0:session.ids.size());values.put("pendingRegistrations",session==null?0:session.pending.size());
        values.put("conflatedTickers",latestTicks.size());values.put("budgetMs",settings.retry().budgetMs());
        values.put("initialBackoffMs",settings.retry().initialBackoffMs());values.put("maxBackoffMs",settings.retry().maxBackoffMs());
        values.put("attemptCapMs",settings.retry().attemptMs());
        values.put("nativeSessionId",session==null||session.client==null?null:session.client.clusterSessionId());
        values.put("natsState",nats==null||nats.getStatus()==null?"unavailable":nats.getStatus().name());
        values.put("scope","adapter_transport_not_quorum_or_price_freshness");
        try{return JSON.writeValueAsString(values);}catch(Exception error){throw new IllegalStateException(error);}
    }
    private void transition(String state,String failure){this.state=state;this.lastFailure=failure;log.accept("FEED_RECOVERY "+recoveryJson());}
    private static String cause(Throwable error){if(error instanceof CleanupFailure)return "client_cleanup_failed";if(error instanceof SessionLost)return error.getMessage();while(error.getCause()!=null)error=error.getCause();return error.getClass().getSimpleName();}
    private static final class CleanupFailure extends RuntimeException {CleanupFailure(Throwable error){super("feed client cleanup failed",error);}}
    private static final class SessionLost extends RuntimeException {SessionLost(String message){super(message);}}
    private static String env(String name,String fallback){final String value=System.getenv(name);return value==null||value.isEmpty()?fallback:value;}
}
