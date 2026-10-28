package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PurgeOptions;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import io.nats.client.api.KeyValueWatchOption;
import io.nats.client.api.KeyValueWatcher;
import io.nats.client.api.StorageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.MessageStore;
import quickfix.MessageStoreFactory;
import quickfix.SessionID;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ADR-086 (FR-FIX11): FIX session state in JetStream, so any gateway replica can resume a session.
 *
 * <p>Bucket {@value #BUCKET} holds, per session key {@code K}: {@code K.seq} = "sender target
 * createdMillis", moved only by compare-and-set on its revision, and {@code K.m.<n>} = outbound
 * message n. Bucket {@value #LEASE_BUCKET} holds {@code K} = the owning gateway while one holds the
 * session. Every write needs that lease; the seq revision fences a gateway that lost it.
 *
 * <p>QuickFIX/J stores a message before it sends it and does not send when the store throws
 * ({@code Session.sendRaw}), so an IOException here is how a refused write stays off the wire.
 */
final class FixSessionStore implements MessageStoreFactory {
    private static final Logger log = LoggerFactory.getLogger(FixSessionStore.class);
    static final String BUCKET = "FIX_SESSIONS";
    static final String LEASE_BUCKET = "FIX_SESSION_LEASE";
    static final Duration LEASE_TTL = Duration.ofSeconds(10);
    private static final long TICK_MILLIS = 1_000;   // renew/release cadence
    private static final long HOLD_MILLIS = 1_000;   // keep a lease this long after its last use
    /** A lease not renewed for this long is re-confirmed with the server before it is used: a gateway
     *  that was paused (GC, SIGSTOP, a frozen node) past the TTL may have lost it to another replica. */
    static final long FRESH_MILLIS = LEASE_TTL.toMillis() / 2;

    // Handed over by the first CONNECTED event (connectAsynchronously): a gateway may start before NATS does.
    private volatile Connection nats;
    // Set once the buckets exist (setUp).
    private volatile KeyValue kv;
    private volatile KeyValue leases;
    private final String owner;
    private final Map<SessionID, Store> stores = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "fix-session-lease");
        t.setDaemon(true);
        return t;
    });
    /** Called with the session's stored outbound messages each time this gateway takes its lease. */
    volatile BiConsumer<SessionID, List<String>> onAcquired = (s, m) -> { };
    /** Called when this gateway loses a lease it was using; the live session must be dropped. */
    volatile Consumer<SessionID> onLost = s -> { };
    /** True only while NATS is connected and the buckets are set up: no session is served without its store. */
    private volatile boolean available;

    FixSessionStore(final String natsUrl, final String owner) throws InterruptedException {
        this.owner = owner;
        // ADR-086 §7: fail closed. On a NATS disconnect every live session here is dropped (TCP close: a
        // Logout would take a sequence number the store cannot record) and Logons are refused until it is
        // back. A 5 s ping finds a silent partition in ~10 s instead of jnats' default 4 minutes. Connecting
        // never throws: a gateway that starts before NATS serves REST at once and FIX once NATS is up
        // (it once ran on with no FIX acceptor at all, the constructor's exception killing main).
        // connectAsynchronously(..., true), not connectReconnectOnConnect: the latter retries on the CALLER's
        // thread until it connects, which hung a gateway (and a test) started with NATS down.
        Nats.connectAsynchronously(new Options.Builder().server(natsUrl).maxReconnects(-1)
            .pingInterval(Duration.ofSeconds(5))
            .connectionListener((conn, event) -> {
                switch (event) {
                    case DISCONNECTED, CLOSED -> unavailable(event.toString());
                    case RECONNECTED, CONNECTED -> {
                        nats = conn;
                        ticker.execute(this::setUp);
                    }
                    default -> { }
                }
            }).build(), true);
        log.error("ALERT FIX session store unavailable until NATS {} is reachable: FIX Logons are refused", natsUrl);
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** On (re)connect: make sure both buckets exist, then serve. Retried by the tick until it succeeds. */
    private void setUp() {
        final Connection nats = this.nats;
        if (available || nats == null || nats.getStatus() != Connection.Status.CONNECTED) {
            return;
        }
        try {
            ensureBucket(BUCKET, KeyValueConfiguration.builder().name(BUCKET).maxHistoryPerKey(1)
                .ttl(Duration.ofDays(7)).maxBucketSize(1L << 30).storageType(StorageType.File).build());
            ensureBucket(LEASE_BUCKET, KeyValueConfiguration.builder().name(LEASE_BUCKET).maxHistoryPerKey(1)
                .ttl(LEASE_TTL).storageType(StorageType.File).build());
            kv = nats.keyValue(BUCKET);
            leases = nats.keyValue(LEASE_BUCKET);
            available = true;
            log.warn("FIX session store available (NATS connected, buckets {} and {}); Logons accepted",
                BUCKET, LEASE_BUCKET);
        } catch (final IOException | JetStreamApiException | RuntimeException e) {
            log.warn("FIX session store not ready yet: {}", e.toString());
        }
    }

    private void ensureBucket(final String name, final KeyValueConfiguration config)
        throws IOException, JetStreamApiException {
        if (!nats.keyValueManagement().getBucketNames().contains(name)) {
            nats.keyValueManagement().create(config);
        }
    }

    private void unavailable(final String why) {
        if (!available) {
            return;
        }
        available = false;
        log.error("ALERT FIX session store unavailable (NATS {}): live FIX sessions on this gateway are"
            + " disconnected and Logons refused until it returns", why);
        for (final Store s : stores.values()) {
            final boolean live;
            synchronized (s) {
                live = s.live;
                s.leaseRevision = 0;   // no claim survives an outage; tryAcquire takes back one still ours
                s.adoptPending = false;
            }
            if (live) {
                ticker.execute(() -> onLost.accept(s.sid));
            }
        }
    }

    boolean available() {
        return available;
    }

    /**
     * May this gateway act for {@code sid} right now? Held, and either renewed within
     * {@link #FRESH_MILLIS} or renewed successfully now. The acceptor asks before it submits anything,
     * so a gateway resumed after a pause past the TTL never puts an order into the cluster for a session
     * another replica has taken over.
     */
    boolean confirm(final SessionID sid) {
        final Store store = stores.get(sid);
        synchronized (store) {
            return store.fresh();
        }
    }

    @Override
    public MessageStore create(final SessionID sessionID) {
        return stores.computeIfAbsent(sessionID, Store::new);
    }

    /** Logon: take the session's lease, waiting up to {@code waitMillis} for its last owner to let go. */
    boolean acquireForLogon(final SessionID sid, final long waitMillis) throws InterruptedException {
        final Store store = stores.get(sid);
        final long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            final boolean got;
            synchronized (store) {
                got = store.tryAcquire();
            }
            if (got) {
                return adopted(store);
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(200);
        }
    }

    void live(final SessionID sid, final boolean live) {
        final Store store = stores.get(sid);
        synchronized (store) {
            store.live = live;
            store.lastUse = System.currentTimeMillis();
        }
    }

    /** A request from this session is being worked; its answer must still find the lease. */
    void requestStarted(final SessionID sid) {
        final Store store = stores.get(sid);
        synchronized (store) {
            store.inFlight++;
        }
    }

    void requestEnded(final SessionID sid) {
        final Store store = stores.get(sid);
        synchronized (store) {
            store.inFlight--;
            store.lastUse = System.currentTimeMillis();
        }
    }

    /** Take a free lease without waiting (ADR-084 fills for a session live nowhere); false if held elsewhere. */
    boolean acquire(final SessionID sid) {
        final Store store = stores.get(sid);
        final boolean got;
        synchronized (store) {
            got = store.tryAcquire();
        }
        return got && adopted(store);
    }

    /**
     * Hand a newly taken session's log to the acceptor. Outside the store's lock on purpose: the
     * acceptor takes its feed lock here, and its feed thread sends (store lock) while holding that one.
     */
    private boolean adopted(final Store store) {
        synchronized (store) {
            if (!store.adoptPending) {
                return true;
            }
            store.adoptPending = false;
        }
        try {
            onAcquired.accept(store.sid, store.scan());
            return true;
        } catch (final IOException | RuntimeException e) {
            log.warn("FIX session {}: reading its log after acquiring failed: {}", store.sid, e.toString());
            synchronized (store) {
                store.release();
            }
            return false;
        }
    }

    boolean held(final SessionID sid) {
        final Store store = stores.get(sid);
        synchronized (store) {
            return store.leaseRevision > 0;
        }
    }

    void close() {
        ticker.shutdownNow();
        for (final Store s : stores.values()) {
            synchronized (s) {
                s.release();
            }
        }
        try {
            if (nats != null) {
                nats.close();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Test hook: stop renewing, as a SIGSTOP'd JVM would (FixSessionPersistenceIT; the live proof SIGSTOPs). */
    volatile boolean renewalsStalled;

    /** Renew what is in use, release what is not. */
    private void tick() {
        if (renewalsStalled) {
            return;
        }
        if (!available) {
            setUp();
        }
        final long now = System.currentTimeMillis();
        for (final Store s : stores.values()) {
            boolean lost = false;
            synchronized (s) {
                if (s.leaseRevision == 0) {
                    continue;
                }
                if (s.live || s.inFlight > 0 || now - s.lastUse < HOLD_MILLIS) {
                    lost = !s.renew() && s.live;
                } else {
                    s.release();
                }
            }
            if (lost) {
                onLost.accept(s.sid);
            }
        }
    }

    /** A KV key for any SessionID: [A-Za-z0-9-] kept, every other char as _XX, so keys never collide. */
    static String keyOf(final SessionID sid) {
        final StringBuilder b = new StringBuilder();
        for (final char c : sid.toString().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-') {
                b.append(c);
            } else {
                b.append('_').append(String.format("%02X", (int) c));
            }
        }
        return b.toString();
    }

    private final class Store implements MessageStore {
        final SessionID sid;
        final String key;
        int sender = 1;
        int target = 1;
        long created = System.currentTimeMillis();
        long seqRevision;        // 0 = no K.seq record yet
        int persistedSender = 1;
        long leaseRevision;      // 0 = not held here
        long renewedAt;          // System.nanoTime() of the last take/renew: a wall-clock step cannot fake freshness
        boolean adoptPending;    // taken, but the acceptor has not seen the log yet
        boolean live;
        int inFlight;
        long lastUse;

        Store(final SessionID sid) {
            this.sid = sid;
            this.key = keyOf(sid);
            try {
                if (available) {
                    load();   // otherwise the first Logon's refresh loads it, once the store is up
                }
            } catch (final IOException e) {
                log.warn("FIX session {}: initial load failed, starting from 1: {}", sid, e.toString());
            }
        }

        /** Take the lease if it is free (or already ours) and reread the store; {@link #adopted} hands it over. */
        boolean tryAcquire() {
            if (leaseRevision > 0) {
                return true;
            }
            if (!available) {
                return false;
            }
            try {
                leaseRevision = leases.create(key, owner.getBytes(StandardCharsets.UTF_8));
                renewedAt = System.nanoTime();
            } catch (final JetStreamApiException taken) {
                return reclaim();
            } catch (final IOException e) {
                log.warn("FIX session {}: lease unavailable: {}", sid, e.toString());
                return false;
            }
            lastUse = System.currentTimeMillis();
            adoptPending = true;
            try {
                load();
            } catch (final IOException | RuntimeException e) {
                log.warn("FIX session {}: reload after acquiring failed: {}", sid, e.toString());
                release();
                return false;
            }
            log.info("FIX session {} acquired by {} at sender {} target {}", sid, owner, sender, target);
            return true;
        }

        /** The key exists: take it back only if it still names this gateway (claims dropped by an outage). */
        private boolean reclaim() {
            try {
                final KeyValueEntry e = leases.get(key);
                if (e == null || !owner.equals(e.getValueAsString())) {
                    return false;
                }
                leaseRevision = leases.update(key, owner, e.getRevision());
                renewedAt = System.nanoTime();
            } catch (final Exception e) {
                return false;
            }
            lastUse = System.currentTimeMillis();
            adoptPending = true;
            try {
                load();
            } catch (final IOException | RuntimeException e) {
                release();
                return false;
            }
            log.info("FIX session {} reclaimed by {} at sender {} target {}", sid, owner, sender, target);
            return true;
        }

        /** Renew now; on failure the lease is gone (lapsed, taken, or NATS unreachable). */
        boolean renew() {
            try {
                leaseRevision = leases.update(key, owner, leaseRevision);
                renewedAt = System.nanoTime();
                return true;
            } catch (final Exception e) {
                log.warn("FIX session {} lease lost: {}", sid, e.toString());
                leaseRevision = 0;
                adoptPending = false;
                return false;
            }
        }

        /** Held and known to be ours: renewed recently, or renewed successfully now. */
        boolean fresh() {
            if (leaseRevision == 0 || !available) {
                return false;
            }
            return System.nanoTime() - renewedAt < FRESH_MILLIS * 1_000_000L || renew();
        }

        void release() {
            if (leaseRevision == 0) {
                return;
            }
            try {
                leases.delete(key, leaseRevision);
            } catch (final Exception e) {
                // lapsed or taken: either way no longer ours
            }
            leaseRevision = 0;
            adoptPending = false;
            log.info("FIX session {} released by {}", sid, owner);
        }

        /**
         * May this replica write the shared state? Yes with the lease. Without it, a session that is
         * logged on here lost its lease and must not send; one that is not (a Logon being refused, a
         * rejected Logon's bookkeeping, another replica's midnight timer) writes TRANSIENTLY: its
         * cache moves, nothing is stored, and the next acquisition rereads the truth. That is what lets
         * QuickFIX/J send the refusal's Logout at all, since it stores every message before sending.
         */
        private boolean writable() throws IOException {
            if (fresh()) {
                lastUse = System.currentTimeMillis();
                return true;
            }
            if (live || leaseRevision > 0 || inFlight > 0) {
                // A session that is (or was a moment ago) served here and can no longer prove its lease, or
                // whose store is down: refuse, so QuickFIX/J does not send, and drop the client.
                final boolean wasLive = live;
                leaseRevision = 0;
                if (wasLive) {
                    ticker.execute(() -> onLost.accept(sid));
                }
                throw new IOException("FIX session " + sid + (available ? " lost its lease" : " store unavailable")
                    + "; not sending");
            }
            return false;
        }

        private void load() throws IOException {
            try {
                final KeyValueEntry e = kv.get(key + ".seq");
                if (e == null || e.getOperation() != KeyValueOperation.PUT) {
                    sender = 1;
                    target = 1;
                    created = System.currentTimeMillis();
                    seqRevision = e == null ? 0 : e.getRevision();
                } else {
                    final String[] f = e.getValueAsString().split(" ");
                    sender = Integer.parseInt(f[0]);
                    target = Integer.parseInt(f[1]);
                    created = Long.parseLong(f[2]);
                    seqRevision = e.getRevision();
                }
                persistedSender = sender;
            } catch (final JetStreamApiException e) {
                throw new IOException(e);
            }
        }

        /** The fencing write: move K.seq only from the revision this gateway last saw. */
        private void writeSeq(final int nextSender) throws IOException {
            final byte[] value = (nextSender + " " + target + " " + created).getBytes(StandardCharsets.UTF_8);
            try {
                seqRevision = seqRevision == 0 ? kv.create(key + ".seq", value)
                    : kv.update(key + ".seq", value, seqRevision);
                persistedSender = nextSender;
            } catch (final JetStreamApiException e) {
                // Someone else wrote this session since we read it: we are not its owner any more.
                final boolean wasLive = live;
                release();
                load();
                if (wasLive) {
                    // Off this thread: QuickFIX/J may hold its session lock while it waits on ours.
                    ticker.execute(() -> onLost.accept(sid));
                }
                throw new IOException("FIX session " + sid + " fenced: written by another gateway", e);
            } catch (final IOException e) {
                throw dropped(e);
            }
        }

        /** A store write failed (NATS timeout or gone): the message was not sent, so end the session
         *  rather than leave the client waiting on an answer it will never get. */
        private IOException dropped(final IOException e) {
            if (live) {
                ticker.execute(() -> onLost.accept(sid));
            }
            return e;
        }

        /** Today's stored outbound messages, oldest first. */
        private List<String> scan() throws IOException {
            final List<String> out = new ArrayList<>();
            final CountDownLatch done = new CountDownLatch(1);
            try {
                final var sub = kv.watch(key + ".m.*", new KeyValueWatcher() {
                    @Override
                    public void watch(final KeyValueEntry e) {
                        if (e.getOperation() == KeyValueOperation.PUT) {
                            out.add(e.getValueAsString());
                        }
                    }

                    @Override
                    public void endOfData() {
                        done.countDown();
                    }
                }, KeyValueWatchOption.IGNORE_DELETE);
                // ponytail: the whole day's log per acquisition; checkpoint order state if a session's
                // daily volume ever makes Logon slow.
                final boolean complete = done.await(30, TimeUnit.SECONDS);
                sub.unsubscribe();
                if (!complete) {
                    throw new IOException("FIX session " + sid + ": log scan timed out");
                }
            } catch (final JetStreamApiException e) {
                throw new IOException(e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return out;
        }

        @Override
        public synchronized boolean set(final int sequence, final String message) throws IOException {
            if (!writable()) {
                return true;
            }
            writeSeq(sequence + 1);   // claim the number first: a body without its claim would be unfenced
            try {
                kv.put(key + ".m." + sequence, message.getBytes(StandardCharsets.UTF_8));
            } catch (final JetStreamApiException e) {
                throw dropped(new IOException(e));
            } catch (final IOException e) {
                throw dropped(e);
            }
            return true;
        }

        @Override
        public synchronized void get(final int startSequence, final int endSequence,
                                     final Collection<String> messages) throws IOException {
            try {
                for (int n = startSequence; n <= endSequence; n++) {
                    final KeyValueEntry e = kv.get(key + ".m." + n);
                    if (e != null && e.getOperation() == KeyValueOperation.PUT) {
                        messages.add(e.getValueAsString());   // a missing body is gap-filled by QuickFIX/J
                    }
                }
            } catch (final JetStreamApiException e) {
                throw new IOException(e);
            }
        }

        @Override
        public synchronized int getNextSenderMsgSeqNum() {
            return sender;
        }

        @Override
        public synchronized int getNextTargetMsgSeqNum() {
            return target;
        }

        @Override
        public synchronized void setNextSenderMsgSeqNum(final int next) throws IOException {
            if (writable()) {   // transient (refusing a Logon here): the cache stays what the store says
                writeSeq(next);
                sender = next;
            }
        }

        @Override
        public synchronized void setNextTargetMsgSeqNum(final int next) throws IOException {
            if (!writable()) {
                return;   // a refused Logon must not move the number the next Logon is checked against
            }
            final int before = target;
            target = next;
            try {
                writeSeq(persistedSender);
            } catch (final IOException e) {
                target = before;
                throw e;
            }
        }

        @Override
        public synchronized void incrNextSenderMsgSeqNum() throws IOException {
            if (!writable()) {
                return;
            }
            if (sender + 1 != persistedSender) {   // set() persisted it for a stored message
                writeSeq(sender + 1);
            }
            sender++;
        }

        @Override
        public synchronized void incrNextTargetMsgSeqNum() throws IOException {
            setNextTargetMsgSeqNum(target + 1);
        }

        @Override
        public synchronized Date getCreationTime() {
            return new Date(created);
        }

        @Override
        public synchronized void reset() throws IOException {
            // The one write that takes a free lease on demand: a Logon after midnight resets before
            // fromAdmin runs, and with no live owner some replica must purge yesterday's messages.
            final long seen = created;
            if (!tryAcquire()) {
                if (live) {
                    throw new IOException("FIX session " + sid + " lost its lease; not resetting");
                }
                sender = 1;   // transient: the live owner (or the next one) resets the shared state
                target = 1;
                created = System.currentTimeMillis();
                return;
            }
            lastUse = System.currentTimeMillis();
            if (created > seen) {
                return;   // another replica reset it since we last read it (every timer resets at midnight)
            }
            try {
                nats.jetStreamManagement().purgeStream("KV_" + BUCKET,
                    PurgeOptions.subject("$KV." + BUCKET + "." + key + ".m.>"));
            } catch (final JetStreamApiException e) {
                throw new IOException(e);
            }
            created = System.currentTimeMillis();
            target = 1;
            writeSeq(1);
            sender = 1;
        }

        @Override
        public synchronized void refresh() throws IOException {
            if (!available) {
                return;   // QuickFIX/J refreshes on every Logon; with the store down that Logon is refused next
            }
            load();
        }
    }
}
