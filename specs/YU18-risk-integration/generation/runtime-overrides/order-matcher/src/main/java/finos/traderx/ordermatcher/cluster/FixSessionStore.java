package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PurgeOptions;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import io.nats.client.api.KeyValueWatchOption;
import io.nats.client.api.KeyValueWatcher;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
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
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>ADR-090 (FR-XOD-12, {@code FIX_EXACTLY_ONCE=jetstream}): an order record (FR-XOD-13: a ring slot in {@value #ORDER_BUCKET}) = this FIX day's
 * inbound NewOrderSingle {@code seq} for that account, stored (create-if-absent, under the lease) before it is
 * submitted, so a resend of its ClOrdID on any replica is resubmitted at the same sequence and the engine books it
 * once. The stream is the session key and its creation time, so the day reset starts new streams at 1.
 *
 * <p>ADR-090 amendment (FR-XOD-13): the records are a ring per account, as the ADR-086 message log is: order seq lives
 * at {@code K.<account>.<(seq-1) mod orderWindow>} in bucket {@value #ORDER_BUCKET}, so a session holds orderWindow keys per account however long its
 * FIX day. The heap holds the ring's ClOrdIDs and the requests the engine has not answered (all go-back-N can ask for).
 * A PossResend/PossDup whose ClOrdID is not in a ring that has lost records (wrapped, or discarded by a full bucket) is
 * {@link #AGED_OUT}: never booked as new.
 *
 * <p>QuickFIX/J stores a message before it sends it and does not send when the store throws
 * ({@code Session.sendRaw}), so an IOException here is how a refused write stays off the wire.
 */
final class FixSessionStore implements MessageStoreFactory {
    private static final Logger log = LoggerFactory.getLogger(FixSessionStore.class);
    static final String BUCKET = "FIX_SESSIONS";
    static final String LEASE_BUCKET = "FIX_SESSION_LEASE";
    /** ADR-090 (FR-XOD-13): {@code K.<account>.<slot>} = one order record of a session's per-account ring. */
    static final String ORDER_BUCKET = "FIX_ORDERS";
    static final long ORDER_STORE_BYTES =
        Long.parseLong(System.getenv().getOrDefault("FIX_ORDER_STORE_BYTES", Long.toString(1L << 30)));
    /**
     * ADR-086 §6b (FR-GFS-16): a dead gateway's sessions are free this long after its last renewal, so a client
     * resuming elsewhere waits about this long. It was 10 s (f15s/xof: 48 sessions unavailable ~12 s). 3 s is 6
     * renewals; a live owner stalled past it (gen-ZGC pauses are sub-millisecond, so a CPU-starved or NATS-starved
     * gateway) loses a session only to a client that has already logged on elsewhere, and retakes it otherwise.
     * Never two answering: every write is fenced by the K.seq revision, whatever the TTL.
     */
    static final Duration LEASE_TTL = Duration.ofMillis(Long.parseLong(
        System.getenv().getOrDefault("FIX_SESSION_LEASE_MS", "3000")));
    private static final long TICK_MILLIS = 500;   // renew/release cadence: six per TTL
    /** A Logon waits this long for a holder that has stopped renewing (dead) to lapse; a renewing one is refused at once. */
    static final long LOGON_WAIT_MILLIS = LEASE_TTL.toMillis() + 1_000;
    /** The lease value of a holder whose client has gone (finishing answers, or a fill for a session live nowhere). */
    private static final String DRAINING = " draining";
    private static final long HOLD_MILLIS = 1_000;   // keep a lease this long after its last use
    /** A lease not renewed for this long is re-confirmed with the server before it is used: a gateway
     *  that was paused (GC, SIGSTOP, a frozen node) past the TTL may have lost it to another replica. */
    static final long FRESH_MILLIS = LEASE_TTL.toMillis() / 2;
    /**
     * ADR-086 amendment (FR-GFS-14): outbound messages kept per session for ResendRequest. Message n is stored at
     * {@code K.m.<n mod RESEND_WINDOW>}, overwriting n - RESEND_WINDOW, so a session holds this many keys however
     * long its FIX day; older ones are gap-filled. f15s filled the 1 GiB bucket in 12 minutes at 20K/s with one key
     * per message.
     */
    static final int RESEND_WINDOW = Integer.parseInt(System.getenv().getOrDefault("FIX_RESEND_WINDOW", "2048"));
    private static final long CAPACITY_CHECK_TICKS = 60_000 / TICK_MILLIS;   // once a minute
    private final AtomicLong gapFilled = new AtomicLong();
    private long ticks;
    private boolean nearlyFull;

    // Handed over by the first CONNECTED event (connectAsynchronously): a gateway may start before NATS does.
    private volatile Connection nats;
    // Set once the buckets exist (setUp).
    private volatile KeyValue kv;
    private volatile KeyValue leases;
    /** FR-XOD-13: ADR-090's order rings, in a bucket of their own so they never evict the session records. */
    private volatile KeyValue orders;
    private final String owner;
    private final Map<SessionID, Store> stores = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "fix-session-lease");
        t.setDaemon(true);
        return t;
    });
    /** A session's stored outbound messages, oldest first, read one at a time (ADR-092: never the whole day at once). */
    interface SentLog {
        void forEach(Consumer<String> each) throws IOException;
    }

    /** Called with the session's stored outbound messages each time this gateway takes its lease. */
    volatile BiConsumer<SessionID, SentLog> onAcquired = (s, m) -> { };
    /** Called when this gateway loses a lease it was using; the live session must be dropped. */
    volatile Consumer<SessionID> onLost = s -> { };
    /** True only while NATS is connected and the buckets are set up: no session is served without its store. */
    private volatile boolean available;
    /** ADR-090 (FR-XOD-12): keep the inbound order records; set by the acceptor before any Logon. */
    volatile boolean sequenceOrders;
    /** FR-XOD-13: order records (and ClOrdIDs) a session keeps per account, store and heap alike. */
    volatile int orderWindow = Integer.parseInt(System.getenv().getOrDefault("FIX_EXACTLY_ONCE_WINDOW", "8192"));

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
            discardOldest(nats.jetStreamManagement());
            ensureBucket(LEASE_BUCKET, KeyValueConfiguration.builder().name(LEASE_BUCKET).maxHistoryPerKey(1)
                .ttl(LEASE_TTL).storageType(StorageType.File).build());
            leaseTtl(nats.jetStreamManagement());
            ensureBucket(ORDER_BUCKET, KeyValueConfiguration.builder().name(ORDER_BUCKET).maxHistoryPerKey(1)
                .ttl(Duration.ofDays(1)).maxBucketSize(ORDER_STORE_BYTES).storageType(StorageType.File).build());
            ordersDiscardOldest(nats.jetStreamManagement());
            kv = nats.keyValue(BUCKET);
            leases = nats.keyValue(LEASE_BUCKET);
            orders = nats.keyValue(ORDER_BUCKET);
            available = true;
            log.warn("FIX session store available (NATS connected, buckets {} and {}); Logons accepted",
                BUCKET, LEASE_BUCKET);
        } catch (final IOException | JetStreamApiException | RuntimeException e) {
            log.warn("FIX session store not ready yet: {}", e.toString());
        }
    }

    /**
     * FR-GFS-15: a full bucket drops its oldest entries instead of refusing the write. Discard-new (the KV default,
     * and this store's before the amendment) made a full bucket fail every session's next send, so every gateway
     * dropped every session at once. Applied to a bucket that already exists too: that is the migration.
     */
    private static void discardOldest(final JetStreamManagement jsm) throws IOException, JetStreamApiException {
        final StreamConfiguration sc = jsm.getStreamInfo("KV_" + BUCKET).getConfiguration();
        if (sc.getDiscardPolicy() != DiscardPolicy.Old) {
            jsm.updateStream(StreamConfiguration.builder(sc).discardPolicy(DiscardPolicy.Old).build());
            log.warn("FIX session store: {} now discards its oldest entries when full, instead of refusing writes"
                + " (ADR-086 amendment)", BUCKET);
        }
    }

    /**
     * FR-XOD-13: a full order bucket drops its oldest ring slots rather than refusing an order. A ring that loses a
     * slot this way has aged, so a resend that would have needed it is refused, never booked again.
     */
    private static void ordersDiscardOldest(final JetStreamManagement jsm) throws IOException, JetStreamApiException {
        final StreamConfiguration sc = jsm.getStreamInfo("KV_" + ORDER_BUCKET).getConfiguration();
        if (sc.getDiscardPolicy() != DiscardPolicy.Old) {
            jsm.updateStream(StreamConfiguration.builder(sc).discardPolicy(DiscardPolicy.Old).build());
        }
    }

    /**
     * FR-GFS-16: the lease bucket's max age IS the lease TTL, so an existing bucket (created at 10 s) is moved to
     * {@link #LEASE_TTL}, its duplicate window with it (the server refuses one longer than the max age).
     */
    private static void leaseTtl(final JetStreamManagement jsm) throws IOException, JetStreamApiException {
        final StreamConfiguration sc = jsm.getStreamInfo("KV_" + LEASE_BUCKET).getConfiguration();
        if (!LEASE_TTL.equals(sc.getMaxAge())) {
            final Duration dup = sc.getDuplicateWindow() == null || sc.getDuplicateWindow().compareTo(LEASE_TTL) > 0
                ? LEASE_TTL : sc.getDuplicateWindow();
            jsm.updateStream(StreamConfiguration.builder(sc).maxAge(LEASE_TTL).duplicateWindow(dup).build());
            log.warn("FIX session store: {} lease TTL {} -> {} (ADR-086 §6b)", LEASE_BUCKET, sc.getMaxAge(), LEASE_TTL);
        }
    }

    /** FR-GFS-15: once a minute, ALERT when the bucket nears its byte cap, where discarding the oldest begins. */
    private void checkCapacity() {
        try {
            final StreamInfo si = nats.jetStreamManagement().getStreamInfo("KV_" + BUCKET);
            final long max = si.getConfiguration().getMaxBytes();
            final boolean near = max > 0 && si.getStreamState().getByteCount() >= max * 9 / 10;
            if (near && !nearlyFull) {
                log.error("ALERT FIX session store {} at {} of {} bytes: its oldest entries are being discarded (an"
                    + " idle session's sequence record among them); sessions x FIX_RESEND_WINDOW ({}) outgrows it",
                    BUCKET, si.getStreamState().getByteCount(), max, RESEND_WINDOW);
            } else if (!near && nearlyFull) {
                log.warn("FIX session store {} back under 90% of its byte cap", BUCKET);
            }
            nearlyFull = near;
        } catch (final IOException | JetStreamApiException | RuntimeException e) {
            // the next minute tries again; availability is the connection listener's business
        }
    }

    /** Messages a ResendRequest asked for that were past the window, answered by GapFill (gateway /metrics). */
    long gapFilled() {
        return gapFilled.get();
    }

    /** Where message {@code n} of a session lives: one of RESEND_WINDOW keys, reused in turn. */
    static String messageKey(final String key, final int n) {
        return key + ".m." + Integer.remainderUnsigned(n, RESEND_WINDOW);
    }

    /** MsgSeqNum (34) of a stored message, or -1: a ring slot can hold a later message than the one asked for. */
    static int seqOf(final String message) {
        final int at = message.indexOf("\u000134=");
        if (at < 0) {
            return -1;
        }
        int n = 0;
        for (int i = at + 4; i < message.length() && Character.isDigit(message.charAt(i)); i++) {
            n = n * 10 + (message.charAt(i) - '0');
        }
        return n;
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

    /**
     * Logon: take the session's lease. FR-GFS-16: a holder seen renewing as live is serving the session, so the Logon
     * is refused at once (within one renewal, ~0.5 s; it waited 5 s). A holder that has stopped renewing is dead, or
     * draining a client that just left: wait up to {@code waitMillis} for it to lapse or let go, and take it then, so
     * a client resuming after a gateway died gets in on its first Logon, about one TTL after the last renewal.
     */
    boolean acquireForLogon(final SessionID sid, final long waitMillis) throws InterruptedException {
        final Store store = stores.get(sid);
        final long deadline = System.currentTimeMillis() + waitMillis;
        long seen = -1;
        while (true) {
            final boolean got;
            synchronized (store) {
                got = store.tryAcquire();
            }
            if (got) {
                return adopted(store);
            }
            final KeyValueEntry holder = store.holder();
            if (holder != null) {
                if (seen < 0) {
                    seen = holder.getRevision();
                } else if (holder.getRevision() != seen && !holder.getValueAsString().endsWith(DRAINING)) {
                    return false;   // renewed while we watched: live on another gateway
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(100);
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

    // ----- ADR-090 (FR-XOD-12): gateway-sequenced FIX orders -------------------------------------

    /** The engine stream this session's sequenced orders ride today (per account in the engine). */
    long orderStream(final SessionID sid) {
        final Store store = stores.get(sid);
        synchronized (store) {
            long h = 0xcbf29ce484222325L;
            for (final char c : (store.key + '/' + store.created).toCharArray()) {
                h = (h ^ c) * 0x100000001b3L;
            }
            return h;
        }
    }

    /** {@link #orderSeq}: a possible resend of a ClOrdID the ring may have lost. It is refused, never booked. */
    static final long AGED_OUT = -1;

    /**
     * The sequence {@code account}'s ClOrdID was stored at today; 0 if it is new; {@link #AGED_OUT} for a possible
     * resend (PossResend/PossDup) that is not in a ring that has lost records. FIX forbids reusing a ClOrdID without
     * those flags, so an unflagged miss is new.
     */
    long orderSeq(final SessionID sid, final int account, final String clOrdId, final boolean mayBeResend)
            throws IOException {
        final Store store = stores.get(sid);
        synchronized (store) {
            final long[] s = store.orderSeqs.get(clOrdId);
            if (s != null && s[0] == account) {
                return s[1];
            }
            final Ring ring = store.rings.get(account);
            return mayBeResend && ring != null && ring.aged ? AGED_OUT : 0;
        }
    }

    /** FR-XOD-13: the engine expects {@code floor} next on this stream; stored requests below it are dropped. */
    void answeredBelow(final SessionID sid, final int account, final long floor) {
        final Store store = stores.get(sid);
        synchronized (store) {
            final java.util.TreeMap<Long, String> raws = store.orderRaws.get(account);
            if (raws != null) {
                raws.headMap(floor).clear();
            }
        }
    }

    /** {ClOrdIDs, raw requests} this session holds in the heap (FR-XOD-13's bound). */
    int[] heldOrders(final SessionID sid) {
        final Store store = stores.get(sid);
        synchronized (store) {
            return new int[] { store.orderSeqs.size(),
                store.orderRaws.values().stream().mapToInt(Map::size).sum() };
        }
    }

    /** Store {@code raw} as {@code account}'s next sequenced order; an IOException means not stored: do not submit. */
    long recordOrder(final SessionID sid, final int account, final String clOrdId, final String raw) throws IOException {
        final Store store = stores.get(sid);
        synchronized (store) {
            return store.recordOrder(account, clOrdId, raw);
        }
    }

    /** The stored request at {@code (account, seq)}, or null. */
    String storedOrder(final SessionID sid, final int account, final long seq) {
        final Store store = stores.get(sid);
        synchronized (store) {
            final java.util.TreeMap<Long, String> raws = store.orderRaws.get(account);
            return raws == null ? null : raws.get(seq);
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
            onAcquired.accept(store.sid, each -> store.scan(store.key + ".m.*", each));
            return true;
        } catch (final RuntimeException e) {   // the acceptor rethrows a failed read as UncheckedIOException
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
        } else if (++ticks % CAPACITY_CHECK_TICKS == 0) {
            checkCapacity();
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

    /** The gateway named by a lease value ({@code <owner>} or {@code <owner> draining}). */
    static String ownerOf(final String leaseValue) {
        return leaseValue.endsWith(DRAINING) ? leaseValue.substring(0, leaseValue.length() - DRAINING.length()) : leaseValue;
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

    /** FR-XOD-13: one account's ring of order records; slot (seq-1) mod size holds seq, its ClOrdID and revision. */
    private static final class Ring {
        final String[] ids;
        final long[] seqs;
        final long[] revs;   // 0 = slot never written as far as this gateway knows
        boolean aged;        // a record of today is gone: overwritten by the ring, or discarded by a full bucket

        Ring(final int size) {
            ids = new String[size];
            seqs = new long[size];
            revs = new long[size];
        }

        int slot(final long seq) {
            return (int) ((seq - 1) % ids.length);
        }
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
        // ADR-090: today's order records, reloaded with the session. FR-XOD-13: the rings' ClOrdIDs and the requests
        // the engine has not answered.
        final Map<String, long[]> orderSeqs = new java.util.HashMap<>();
        final Map<Integer, Ring> rings = new java.util.HashMap<>();
        final Map<Integer, Long> nextOrderSeq = new java.util.HashMap<>();
        final Map<Integer, java.util.TreeMap<Long, String>> orderRaws = new java.util.HashMap<>();

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
                leaseRevision = leases.create(key, leaseValue().getBytes(StandardCharsets.UTF_8));
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
                if (e == null || !owner.equals(ownerOf(e.getValueAsString()))) {
                    return false;
                }
                leaseRevision = leases.update(key, leaseValue(), e.getRevision());
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

        /** Renew now; on failure the lease is gone (taken, or NATS unreachable), unless it only lapsed. */
        boolean renew() {
            try {
                leaseRevision = leases.update(key, leaseValue(), leaseRevision);
                renewedAt = System.nanoTime();
                return true;
            } catch (final Exception e) {
                if (retake()) {
                    return true;
                }
                log.warn("FIX session {} lease lost: {}", sid, e.toString());
                leaseRevision = 0;
                adoptPending = false;
                return false;
            }
        }

        /**
         * FR-GFS-16: this gateway was stalled past the TTL and its lease lapsed. If nobody has taken it since, take it
         * again and keep serving: a short TTL must not cost a live session its gateway for a pause nobody acted on.
         * Safe because writes are fenced by the K.seq revision: had another gateway served the session meanwhile,
         * this one's next write fails and drops it.
         */
        private boolean retake() {
            try {
                if (leases.get(key) != null) {
                    return false;   // someone else holds it
                }
                leaseRevision = leases.create(key, leaseValue().getBytes(StandardCharsets.UTF_8));
                renewedAt = System.nanoTime();
                log.warn("FIX session {}: lease lapsed while {} was stalled and nobody took it; retaken", sid, owner);
                return true;
            } catch (final Exception e) {
                return false;
            }
        }

        /** The lease's current holder, or null when it is free (lapsed or released). */
        KeyValueEntry holder() {
            try {
                return leases == null ? null : leases.get(key);
            } catch (final Exception e) {
                return null;
            }
        }

        /** Live while a client is logged on here; draining while answers or a fill finish without one. */
        private String leaseValue() {
            return live ? owner : owner + DRAINING;
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
            loadOrders();
        }

        /** ADR-090: this FIX day's order records, at most orderWindow per account (FR-XOD-13). */
        private void loadOrders() throws IOException {
            clearOrders();
            if (!sequenceOrders || orders == null) {
                return;
            }
            final Map<Integer, Long> held = new java.util.HashMap<>();
            scanEntries(orders, key + ".>", e -> {
                final String v = e.getValueAsString();
                final int nl = v.indexOf('\n');
                final String[] f = v.substring(0, nl).split(" ", 3);
                final int account = Integer.parseInt(f[0]);
                final long seq = Long.parseLong(f[1]);
                hold(ring(account), account, seq, f[2], v.substring(nl + 1), e.getRevision());
                nextOrderSeq.merge(account, seq + 1, Math::max);
                held.merge(account, 1L, Long::sum);
            });
            // every record of 1 .. next-1 present, or the ring has lost some: a resend may name one of them
            held.forEach((account, n) -> ring(account).aged |= n < nextOrderSeq.get(account) - 1);
        }

        private void clearOrders() {
            orderSeqs.clear();
            rings.clear();
            nextOrderSeq.clear();
            orderRaws.clear();
        }

        private Ring ring(final int account) {
            return rings.computeIfAbsent(account, a -> new Ring(orderWindow));
        }

        /** Hold seq in its slot, letting go of the record it overwrites (its ClOrdID and, ponytail, its request:
         *  go-back-N never reaches a whole ring back). */
        private void hold(final Ring ring, final int account, final long seq, final String clOrdId, final String raw,
                          final long revision) {
            final int slot = ring.slot(seq);
            final String old = ring.ids[slot];
            if (old != null && ring.seqs[slot] != seq) {
                final long[] s = orderSeqs.get(old);
                if (s != null && s[0] == account && s[1] == ring.seqs[slot]) {
                    orderSeqs.remove(old);
                }
                final java.util.TreeMap<Long, String> raws = orderRaws.get(account);
                if (raws != null) {
                    raws.remove(ring.seqs[slot]);
                }
                ring.aged = true;
            }
            ring.ids[slot] = clOrdId;
            ring.seqs[slot] = seq;
            ring.revs[slot] = revision;
            orderSeqs.put(clOrdId, new long[] { account, seq });
            orderRaws.computeIfAbsent(account, a -> new java.util.TreeMap<>()).put(seq, raw);
        }

        long recordOrder(final int account, final String clOrdId, final String raw) throws IOException {
            if (!writable()) {
                throw new IOException("FIX session " + sid + ": lease not held; order not stored");
            }
            final long seq = nextOrderSeq.getOrDefault(account, 1L);
            final Ring ring = ring(account);
            final int slot = ring.slot(seq);
            final String slotKey = key + "." + account + "." + slot;
            final byte[] value = (account + " " + seq + " " + clOrdId + "\n" + raw).getBytes(StandardCharsets.UTF_8);
            final long revision;
            try {
                // FR-XOD-13: overwrite the slot only from the revision this gateway wrote or read; another gateway's
                // write in between fences this one, as the create-if-absent record did.
                revision = ring.revs[slot] == 0 ? orders.create(slotKey, value)
                    : updateSlot(slotKey, value, ring.revs[slot]);
            } catch (final JetStreamApiException e) {
                // another gateway stored this sequence: it owns the session now (the seq record's fencing rule)
                final boolean wasLive = live;
                release();
                if (wasLive) {
                    ticker.execute(() -> onLost.accept(sid));
                }
                throw new IOException("FIX session " + sid + " fenced: order " + account + "." + seq
                    + " stored by another gateway", e);
            } catch (final IOException e) {
                throw dropped(e);
            }
            nextOrderSeq.put(account, seq + 1);
            hold(ring, account, seq, clOrdId, raw, revision);
            return seq;
        }

        /** A slot a full bucket discarded (FR-GFS-15) is created again; one another gateway rewrote is a fence. */
        private long updateSlot(final String slotKey, final byte[] value, final long revision)
                throws IOException, JetStreamApiException {
            try {
                return orders.update(slotKey, value, revision);
            } catch (final JetStreamApiException e) {
                final KeyValueEntry now = orders.get(slotKey);
                if (now == null || now.getOperation() != KeyValueOperation.PUT) {
                    return orders.create(slotKey, value);
                }
                throw e;
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

        /** Each stored value under {@code subject}, oldest first, handed over as it arrives. */
        private void scan(final String subject, final Consumer<String> each) throws IOException {
            scanEntries(kv, subject, e -> each.accept(e.getValueAsString()));
        }

        /** As {@link #scan}, with each entry's revision. */
        private void scanEntries(final KeyValue bucket, final String subject, final Consumer<KeyValueEntry> each)
                throws IOException {
            final CountDownLatch done = new CountDownLatch(1);
            try {
                final var sub = bucket.watch(subject, new KeyValueWatcher() {
                    @Override
                    public void watch(final KeyValueEntry e) {
                        if (e.getOperation() == KeyValueOperation.PUT) {
                            each.accept(e);
                        }
                    }

                    @Override
                    public void endOfData() {
                        done.countDown();
                    }
                }, KeyValueWatchOption.IGNORE_DELETE);
                // ponytail: the whole day's log is READ per acquisition (ADR-092 keeps only open orders of it);
                // checkpoint order state if a session's daily volume ever makes Logon slow.
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
        }

        @Override
        public synchronized boolean set(final int sequence, final String message) throws IOException {
            if (!writable()) {
                return true;
            }
            writeSeq(sequence + 1);   // claim the number first: a body without its claim would be unfenced
            try {
                kv.put(messageKey(key, sequence), message.getBytes(StandardCharsets.UTF_8));
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
            // FR-GFS-14: only the last RESEND_WINDOW are kept; a missing body is gap-filled by QuickFIX/J.
            final int from = Math.max(startSequence, endSequence - RESEND_WINDOW + 1);
            int missing = Math.max(0, from - startSequence);
            try {
                for (int n = from; n <= endSequence; n++) {
                    final KeyValueEntry e = kv.get(messageKey(key, n));
                    if (e != null && e.getOperation() == KeyValueOperation.PUT && seqOf(e.getValueAsString()) == n) {
                        messages.add(e.getValueAsString());
                    } else {
                        missing++;
                    }
                }
            } catch (final JetStreamApiException e) {
                throw new IOException(e);
            }
            if (missing > 0) {
                gapFilled.addAndGet(missing);
                log.error("ALERT FIX session {}: ResendRequest {}..{} reaches past what is kept (last {} messages);"
                    + " {} gap-filled, the client reconciles them by OrderStatusRequest", sid, startSequence, endSequence,
                    RESEND_WINDOW, missing);
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
                nats.jetStreamManagement().purgeStream("KV_" + ORDER_BUCKET,   // ADR-090: the day's order rings
                    PurgeOptions.subject("$KV." + ORDER_BUCKET + "." + key + ".>"));
            } catch (final JetStreamApiException e) {
                throw new IOException(e);
            }
            created = System.currentTimeMillis();
            target = 1;
            writeSeq(1);
            sender = 1;
            clearOrders();
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
