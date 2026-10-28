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

    private final Connection nats;
    private final KeyValue kv;
    private final KeyValue leases;
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

    FixSessionStore(final String natsUrl, final String owner) throws IOException, InterruptedException {
        this.owner = owner;
        this.nats = Nats.connect(new Options.Builder().server(natsUrl).maxReconnects(-1).build());
        try {
            ensureBucket(BUCKET, KeyValueConfiguration.builder().name(BUCKET).maxHistoryPerKey(1)
                .ttl(Duration.ofDays(7)).maxBucketSize(1L << 30).storageType(StorageType.File).build());
            ensureBucket(LEASE_BUCKET, KeyValueConfiguration.builder().name(LEASE_BUCKET).maxHistoryPerKey(1)
                .ttl(LEASE_TTL).storageType(StorageType.File).build());
        } catch (final JetStreamApiException e) {
            throw new IOException("FIX session buckets: " + e.getMessage(), e);
        }
        this.kv = nats.keyValue(BUCKET);
        this.leases = nats.keyValue(LEASE_BUCKET);
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void ensureBucket(final String name, final KeyValueConfiguration config)
        throws IOException, JetStreamApiException {
        if (!nats.keyValueManagement().getBucketNames().contains(name)) {
            nats.keyValueManagement().create(config);
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
            nats.close();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Renew what is in use, release what is not. */
    private void tick() {
        final long now = System.currentTimeMillis();
        for (final Store s : stores.values()) {
            boolean lost = false;
            synchronized (s) {
                if (s.leaseRevision == 0) {
                    continue;
                }
                if (s.live || s.inFlight > 0 || now - s.lastUse < HOLD_MILLIS) {
                    try {
                        s.leaseRevision = leases.update(s.key, owner, s.leaseRevision);
                    } catch (final Exception e) {
                        log.warn("FIX session {} lease lost: {}", s.sid, e.toString());
                        s.leaseRevision = 0;
                        lost = s.live;
                    }
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
        boolean adoptPending;    // taken, but the acceptor has not seen the log yet
        boolean live;
        int inFlight;
        long lastUse;

        Store(final SessionID sid) {
            this.sid = sid;
            this.key = keyOf(sid);
            try {
                load();
            } catch (final IOException e) {
                log.warn("FIX session {}: initial load failed, starting from 1: {}", sid, e.toString());
            }
        }

        /** Take the lease if it is free (or already ours) and reread the store; {@link #adopted} hands it over. */
        boolean tryAcquire() {
            if (leaseRevision > 0) {
                return true;
            }
            try {
                leaseRevision = leases.create(key, owner.getBytes(StandardCharsets.UTF_8));
            } catch (final JetStreamApiException taken) {
                return false;
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
            if (leaseRevision > 0) {
                lastUse = System.currentTimeMillis();
                return true;
            }
            if (live) {
                throw new IOException("FIX session " + sid + " lost its lease; not sending");
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
            }
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
                throw new IOException(e);
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
            if (writable()) {
                writeSeq(next);
            }
            sender = next;
        }

        @Override
        public synchronized void setNextTargetMsgSeqNum(final int next) throws IOException {
            final int before = target;
            target = next;
            try {
                if (writable()) {
                    writeSeq(persistedSender);
                }
            } catch (final IOException e) {
                target = before;
                throw e;
            }
        }

        @Override
        public synchronized void incrNextSenderMsgSeqNum() throws IOException {
            if (writable() && sender + 1 != persistedSender) {   // set() persisted it for a stored message
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
            load();
        }
    }
}
