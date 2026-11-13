package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.ThreadedSocketAcceptor;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.LastRptRequested;
import quickfix.field.MassStatusReqID;
import quickfix.field.OrigClOrdID;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderMassStatusRequest;
import quickfix.fix44.OrderStatusRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FIX 4.4 acceptor that terminates the counterparty session ON THE GATEWAY (ADR-047) and
 * forwards each NewOrderSingle through the {@link OrderSubmitter} seam into the cluster. The
 * session — its TCP connection, sequence numbers, logon state — lives entirely here, wholly
 * independent of the cluster client the submitter owns. That is the failover-transparency
 * property: when the cluster leader dies and the submitter reconnects to the new leader, this
 * acceptor and its live sessions are never touched, so the counterparty stays logged on.
 *
 * Session state is in-memory (TD-AC01) unless {@code FIX_SESSION_STORE=jetstream}: then ADR-086's
 * {@link FixSessionStore} keeps sequence numbers and sent messages in JetStream, a lease keeps each
 * session on one gateway at a time, and a counterparty can resume, and ResendRequest, on any replica.
 * Deferred
 * (not needed to prove session survival): the amortized submit-batch of the parent state's
 * in-process acceptor, and JWT entitlement (risk/entitlement is decided inside the cluster, not at
 * this tier).
 *
 * <p>YU13 adds OrderCancelRequest (F), answered with an ExecutionReport carrying
 * {@code OrdStatus=Canceled} or an OrderCancelReject (9). The cancel verdict itself is made inside
 * the replicated state machine, so every member reaches it identically; this tier only resolves
 * <em>which</em> orderRef the counterparty means.
 *
 * <p>YU13 also adds OrderStatusRequest (H) and OrderMassStatusRequest (AF), answered with
 * ExecutionReport(s) carrying {@code ExecType=OrderStatus}. These read the {@link OrderStatusSource}
 * — the same {@code orderbook} read model the REST blotter serves — so a FIX status answer and a
 * {@code GET /accounts/{id}/orders} answer are the same data. No cluster round-trip: status is
 * off-consensus.
 *
 * <p>YU18 (FR-FIX05, ADR-084): a fill after the direct ack — the resting side of someone else's
 * cross, or the aggressor's own fills in the apply that accepted it — is reported unsolicited as an
 * ExecutionReport {@code ExecType=F (Trade)}, read from the leader's {@code /orders} feed
 * ({@link #onOrderUpdate}). The cluster egress cannot carry it: its 32-byte record has no fill
 * quantity or price, and it is addressed to the AGGRESSOR's cluster session, so a resting order
 * owned by another gateway's session is never on this gateway's egress at all.
 */
public final class FixGatewayAcceptor {
    private static final Logger log = LoggerFactory.getLogger(FixGatewayAcceptor.class);

    private final OrderSubmitter submitter;
    private final OrderStatusSource statusSource;
    private final int port;
    private final String serverCompId;
    private final int defaultAccountId;
    private final List<String> compIds;
    private final AtomicLong execSeq = new AtomicLong();
    /** ADR-086: ExecIDs stay unique when a session moves between replicas or a gateway restarts. */
    private static final String EXEC_ID_PREFIX = Long.toString(System.currentTimeMillis(), 36)
        + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1296), 36) + "-";
    private FixSessionStore sessionStore;
    /** ADR-104 ({@code FIX_EXACTLY_ONCE=engine}): each pipelined NEW carries its session tail to the engine's ring. */
    private volatile boolean sessionKeyed;

    /**
     * ClOrdID → orderRef, so a counterparty can cancel by OrigClOrdID (41) alone, as FIX 4.4
     * requires. Bounded LRU: an unbounded map here would be the {@code ordersByRef} trap again —
     * that one stayed invisible until 33M orders. Eviction only costs the OrigClOrdID convenience;
     * OrderID (37) still resolves, because this gateway mints it as {@code ord-<orderRef>}.
     *
     * <p>With persistent sessions (ADR-086) a gateway rebuilds a session's entries from its stored
     * reports when it takes the session's lease, so OrigClOrdID resolves on any replica for today's
     * orders. In-memory sessions keep it gateway-local: after a move, cancel by OrderID.
     */
    private static final int CLORDID_MAP_CAPACITY = 65_536;
    private final Map<String, Long> refByClOrdId = Collections.synchronizedMap(
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Long> eldest) {
                return size() > CLORDID_MAP_CAPACITY;
            }
        });

    /**
     * FR-FIX05: orderRef → the FIX order a feed fill belongs to. An entry leaves on its terminal update (filled,
     * canceled, rejected). FR-FFR-19: a working order stays until then; the bound only guards the heap. Its size is
     * time: every order acked while the routed feed is late is working here, so 65,536 was ~4 s of one gateway at 300K
     * orders/s, and run 4 lost every fill that the read model's fills router delivered later than that. 524,288
     * (~215 B each measured, ~113 MB of the 768 MB heap) rides ~30 s of feed lag at 300K, ~20 s at 450K. Past it the oldest
     * working order is evicted, counted and ALERTed: its later fills age out of the early buffer counted lost.
     */
    static final int TRACKED_CAPACITY = Integer.parseInt(System.getenv().getOrDefault("FIX_TRACKED_ORDERS", "524288"));
    int trackedCapacity = TRACKED_CAPACITY;   // not final: tests shorten it
    private final AtomicLong trackedEvicted = new AtomicLong();
    /** FR-XOC-08: NEWs asked again as PossDup after no answer with no leader change. */
    private final AtomicLong unansweredResubmits = new AtomicLong();
    private final Map<Long, Tracked> tracked = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, Tracked> eldest) {
            if (size() <= trackedCapacity) {
                return false;
            }
            if (trackedEvicted.getAndIncrement() == 0) {
                log.error("ALERT FIX {} orders tracked: ord-{} evicted while working, its later fills and end go unreported"
                    + " (traderx_fix_tracked_evicted_total counts every one; the fill feed is late or losing ends)",
                    trackedCapacity, eldest.getKey());
            }
            final List<byte[]> held = eldest.getValue().pending;
            if (held != null && !held.isEmpty() && ownFillsOnly) {
                fillsLost.addAndGet(held.size());   // FR-FFR-18: evicted before its New was sent
            }
            return true;
        }
    };

    /**
     * FR-FFR-15: the feed beats the ack. The leader publishes the fill and the ack from one apply, and a pipelined
     * order (FR-BSP-02) is tracked when its ack reaches {@link #acks} (FR-FFR-18; when its answer ran on the session's
     * report thread, seconds later when the session was behind, run 3 lost 10.9M). Fills for untracked refs wait here, by ref in arrival
     * order, and {@link #track} takes its ref's. One leaves unreported only by age ({@link #EARLY_MAX_AGE_NANOS}, past
     * the ack deadline) or count ({@link #EARLY_CAPACITY}); on the routed feed every one of those is this gateway's
     * own order's fill, so it is counted lost and ALERTed. A 4,096-slot ring overwritten in arrival order lost 87% of
     * the fills at 85K orders/s, uncounted.
     */
    static final int EARLY_CAPACITY = Integer.parseInt(System.getenv().getOrDefault("FIX_EARLY_FILLS", "65536"));
    static final long EARLY_MAX_AGE_NANOS = 30_000_000_000L;
    long earlyMaxAgeNanos = EARLY_MAX_AGE_NANOS;   // not final: tests shorten it
    private static final class Early {
        final long at = System.nanoTime();
        final List<byte[]> msgs = new java.util.ArrayList<>(1);
    }
    private final LinkedHashMap<Long, Early> early = new LinkedHashMap<>(1024);
    /** Only this gateway's own orders' fills arrive (FR-FFR-14), so a fill evicted unmatched is a lost report. */
    boolean ownFillsOnly = "routed".equals(System.getenv("FIX_FILL_FEED"));   // not final: tests set it
    private final AtomicLong fillsLost = new AtomicLong();
    private final AtomicLong fillsEvicted = new AtomicLong();
    /** One lock for {@link #tracked}, the early ring and each {@link Tracked}'s counters, so a
     *  feed update and the registration of its order can never miss each other. */
    private final Object feedLock = new Object();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * ADR-092 (FR-FGM-01): messages this gateway may hold for a session's client that the client has not read.
     * One more and QuickFIX/J drops the session as a slow consumer, instead of queueing reports in the heap for as
     * long as the client lags (f15ho: ~690 bytes each, without limit). A dropped session's unsent reports are in its
     * store under ADR-086 (resent on ResendRequest), and a client of an in-memory session reconciles with H/AF.
     */
    static final int MAX_QUEUED_WRITES = Integer.parseInt(System.getenv().getOrDefault("FIX_MAX_QUEUED_WRITES", "2048"));
    /**
     * ADR-092 (FR-FGM-02): inbound messages a session may have waiting for its dispatcher. When it is full, the MINA
     * I/O thread that reads the session waits for room, so a client that sends faster than its orders are answered
     * is held back by TCP, not queued here. QuickFIX/J's default was 10,000 per session (~2.5 KB each). 256 is
     * ~0.75 s of a session at f15ho's 343 orders/s. Not QuickFIX/J's watermarks: their resumeRead races MINA
     * 2.1.4's unsynchronised interestOps update on the I/O thread and can lose OP_READ, wedging the session.
     */
    static final int INBOUND_QUEUE = Integer.parseInt(System.getenv().getOrDefault("FIX_INBOUND_QUEUE", "256"));

    /**
     * FR-BSP-02: NewOrderSingles one session may have undecided. Half the write bound, so a window's reports and its
     * fills land inside {@link #MAX_QUEUED_WRITES} for a client that reads.
     */
    static final int MAX_IN_FLIGHT = Math.max(1, MAX_QUEUED_WRITES / 2);
    private final Map<SessionID, Pipeline> pipelines = new ConcurrentHashMap<>();
    /**
     * FR-FFR-18: where a pipelined order's ack completes, so {@link #acked} tracks it at once. On the session's report
     * thread (as before) the ack waited behind every report the session had queued, which a saturated store holds
     * back: the 2026-11-10 acceptance run 3 lost 10.9M fills and ends that aged out of the early buffer meanwhile.
     */
    private final ExecutorService acks = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "fix-acks");
        t.setDaemon(true);
        return t;
    });

    /**
     * FR-BSP-02: one session's NewOrderSingles in flight. Submitted on QuickFIX/J's session thread in arrival order,
     * answered on the session's one report thread in completion order. A full window blocks {@code fromApp}, so
     * QuickFIX/J stops reading the session (ADR-092). {@link #undecided} keeps the stored target below every order
     * still undecided (FR-GFS-19): QuickFIX/J advances it when {@code fromApp} returns, before the order is decided.
     */
    private final class Pipeline {
        final SessionID sid;
        final ExecutorService reports;
        final Semaphore slots = new Semaphore(MAX_IN_FLIGHT);
        final Map<String, CompletableFuture<?>> byClOrdId = new ConcurrentHashMap<>();
        private final TreeMap<Integer, Integer> undecided = new TreeMap<>();   // inbound MsgSeqNum -> orders

        Pipeline(final SessionID sid) {
            this.sid = sid;
            this.reports = Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "fix-report-" + sid.getTargetCompID());
                t.setDaemon(true);
                return t;
            });
        }

        synchronized void hold(final int msgSeqNum) {
            undecided.merge(msgSeqNum, 1, Integer::sum);
            publish();
        }

        synchronized void decided(final int msgSeqNum) {
            undecided.computeIfPresent(msgSeqNum, (k, n) -> n == 1 ? null : n - 1);
            publish();
        }

        private void publish() {
            if (sessionStore != null) {
                sessionStore.undecidedFrom(sid, undecided.isEmpty() ? Integer.MAX_VALUE : undecided.firstKey());
            }
        }
    }

    private static final class Tracked {
        final SessionID session;
        final String symbol;
        final char side;
        String clOrdId;
        String epoch;
        int cumQty;
        double notional;
        boolean cancelRequested;   // FR-FFR-17: the client's OrderCancelRequest answers this order's cancel
        List<byte[]> pending;      // FR-FFR-18: tracked at its ack, its New not yet sent: its updates wait here

        Tracked(final SessionID session, final String clOrdId, final String symbol, final char side) {
            this.session = session;
            this.clOrdId = clOrdId;
            this.symbol = symbol;
            this.side = side;
        }
    }

    private ThreadedSocketAcceptor acceptor;

    /** No status source: order status (H/AF) is answered "unavailable". Used by deploys that wire only
     *  order entry, and by the failover-survival test whose concern is the submitter seam alone. */
    public FixGatewayAcceptor(final OrderSubmitter submitter, final int port, final String serverCompId,
                              final int defaultAccountId, final List<String> compIds) {
        this(submitter, null, port, serverCompId, defaultAccountId, compIds);
    }

    public FixGatewayAcceptor(final OrderSubmitter submitter, final OrderStatusSource statusSource,
                              final int port, final String serverCompId,
                              final int defaultAccountId, final List<String> compIds) {
        this(submitter, statusSource, port, serverCompId, defaultAccountId, compIds, FixSessionAuth.fromEnv(null));
    }

    /** ENGINE-REVIEW S3: with session credentials and per-CompID account binding. */
    public FixGatewayAcceptor(final OrderSubmitter submitter, final OrderStatusSource statusSource,
                              final int port, final String serverCompId,
                              final int defaultAccountId, final List<String> compIds,
                              final FixSessionAuth auth) {
        this.submitter = submitter;
        this.statusSource = statusSource;
        this.port = port;
        this.serverCompId = serverCompId;
        this.defaultAccountId = defaultAccountId;
        this.compIds = compIds;
        this.auth = auth;
    }

    private final FixSessionAuth auth;

    /** ADR-095: CompIDs served only on a second, TLS-only socket (the external entry); empty = none. */
    private int tlsPort;
    private List<String> tlsCompIds = List.of();
    private String tlsKeystore;
    private String tlsKeystorePassword;

    /**
     * ADR-095 (FR-FTL-01/02): serve {@code tlsCompIds} on {@code tlsPort} over TLS, and only there. QuickFIX/J binds
     * each session to one acceptor socket, so these CompIDs are unknown on the plaintext port and the plaintext
     * CompIDs are unknown here. The keystore is PKCS12 (the format {@code keytool} and openssl both write).
     */
    FixGatewayAcceptor withTls(final int port, final List<String> compIds, final String keystore,
                               final String keystorePassword) {
        if (keystore == null || keystore.isBlank()) {
            throw new IllegalStateException("FIX_TLS_PORT needs FIX_SSL_KEYSTORE (ADR-095): no plaintext fallback");
        }
        if (compIds.isEmpty() || compIds.stream().anyMatch(String::isBlank)
            || compIds.stream().anyMatch(this.compIds::contains)) {
            throw new IllegalStateException("FIX_TLS_COMPIDS must name CompIDs not on the plaintext port (ADR-095): " + compIds);
        }
        this.tlsPort = port;
        this.tlsCompIds = compIds;
        this.tlsKeystore = keystore;
        this.tlsKeystorePassword = keystorePassword == null ? "" : keystorePassword;
        return this;
    }

    /** ADR-104 (tests): tag NEWs for the engine's session ring; otherwise {@code FIX_EXACTLY_ONCE=engine} decides. */
    FixGatewayAcceptor withSessionKeyed() {
        this.sessionKeyed = true;
        return this;
    }

    /** ADR-086: persist sessions in this store (tests); otherwise {@code FIX_SESSION_STORE} decides. */
    FixGatewayAcceptor withSessionStore(final FixSessionStore store) {
        this.sessionStore = store;
        return this;
    }

    public void start() throws Exception {
        if (sessionStore == null && "jetstream".equals(System.getenv("FIX_SESSION_STORE"))) {
            final String nats = System.getenv().getOrDefault("NATS_ADDRESS",
                "nats://" + System.getenv().getOrDefault("NATS_BROKER_HOST", "nats") + ":4222");
            sessionStore = new FixSessionStore(nats,
                System.getenv().getOrDefault("HOSTNAME", "gateway") + "/" + EXEC_ID_PREFIX);
        }
        if ("jetstream".equals(System.getenv("FIX_EXACTLY_ONCE"))) {   // ADR-090 (FR-XOD-12)
            if (sessionStore == null) {
                throw new IllegalStateException("FIX_EXACTLY_ONCE=jetstream needs FIX_SESSION_STORE=jetstream (ADR-086)");
            }
            sessionStore.sequenceOrders = true;
        }
        if ("engine".equals(System.getenv("FIX_EXACTLY_ONCE"))) {   // ADR-104 (FR-XOD-24)
            if (sessionStore == null) {   // the session-day's creation time must be the same on every replica
                throw new IllegalStateException("FIX_EXACTLY_ONCE=engine needs FIX_SESSION_STORE=jetstream (ADR-086)");
            }
            sessionKeyed = true;
        }
        final SessionSettings settings = new SessionSettings();
        final Properties defaults = new Properties();
        defaults.setProperty("ConnectionType", "acceptor");
        defaults.setProperty("SocketAcceptPort", String.valueOf(port));
        // ADR-086 §6: the FIX day, and with it the resend horizon, ends here. Default 17:00 New York: after
        // the 16:00 ET close and its marks, so a FIX day is a US trading day. 00:00 UTC is 8 pm ET (7 pm in
        // winter), in the middle of the US evening, and would reset sessions during after-hours trading.
        defaults.setProperty("StartTime", System.getenv().getOrDefault("FIX_SESSION_RESET_TIME", "17:00:00"));
        defaults.setProperty("EndTime", System.getenv().getOrDefault("FIX_SESSION_RESET_TIME", "17:00:00"));
        defaults.setProperty("TimeZone", System.getenv().getOrDefault("FIX_SESSION_TIME_ZONE", "America/New_York"));
        defaults.setProperty("HeartBtInt", "30");
        // ADR-086: persistent sessions keep their numbers across reconnects and replicas; RefreshOnLogon
        // rereads the store, which another replica may have advanced since this one last held it.
        final boolean persistent = sessionStore != null;
        defaults.setProperty("ResetOnLogon", persistent ? "N" : "Y");
        defaults.setProperty("PersistMessages", persistent ? "Y" : "N"); // in-memory: TD-AC01
        defaults.setProperty("RefreshOnLogon", persistent ? "Y" : "N");
        defaults.setProperty("MaxScheduledWriteRequests", String.valueOf(MAX_QUEUED_WRITES));   // ADR-092
        // ENGINE-REVIEW S3: TLS on the session when a keystore is configured (QuickFIX/J native SSL). With ADR-095's
        // second socket the keystore is that socket's alone and the main port stays plaintext.
        final String keystore = System.getenv("FIX_SSL_KEYSTORE");
        if (tlsCompIds.isEmpty() && keystore != null && !keystore.isBlank()) {
            defaults.setProperty("SocketUseSSL", "Y");
            defaults.setProperty("SocketKeyStore", keystore);
            defaults.setProperty("SocketKeyStorePassword", System.getenv().getOrDefault("FIX_SSL_KEYSTORE_PASSWORD", ""));
        }
        if (!auth.configured()) {
            log.warn("FIX acceptor INSECURE (ALLOW_DEV_SECRETS=true): FIX_SESSION_AUTH unset — logon is CompID-only"
                + " and Account(1) is unbound");
        }
        settings.set(defaults);
        for (final String compId : compIds) {
            final SessionID sid = new SessionID("FIX.4.4", serverCompId, compId);
            settings.setString(sid, "BeginString", "FIX.4.4");
        }
        for (final String compId : tlsCompIds) {   // ADR-095: per-session settings give this socket its own port
            final SessionID sid = new SessionID("FIX.4.4", serverCompId, compId);
            settings.setString(sid, "BeginString", "FIX.4.4");
            settings.setString(sid, "SocketAcceptPort", String.valueOf(tlsPort));
            settings.setString(sid, "SocketUseSSL", "Y");
            settings.setString(sid, "SocketKeyStore", tlsKeystore);
            settings.setString(sid, "SocketKeyStorePassword", tlsKeystorePassword);
            settings.setString(sid, "KeyStoreType", "PKCS12");
            // FR-FTL-10: TLS 1.2 only. MINA 2.1.4 (under QuickFIX/J 2.3.1) holds TLS 1.3 application data that arrives
            // with the handshake until the next read, so a client's Logon sat unread until it gave up.
            // ponytail: drop the pin when QuickFIX/J ships a MINA whose SslHandler handles TLS 1.3 (2.2+).
            settings.setString(sid, "EnabledProtocols", "TLSv1.2");
        }
        final FixWriteBarrier barrier = persistent ? new FixWriteBarrier(sessionStore) : null;
        if (persistent) {
            sessionStore.onAcquired = this::adopt;
            sessionStore.onDurable = barrier::durable;   // ADR-099
            sessionStore.onLost = sid -> {
                barrier.drop(sid);
                final Session session = sessionOf(sid);
                try {
                    if (session != null) {
                        session.disconnect("FIX session lease or store lost (ADR-086)", true);
                    }
                } catch (final java.io.IOException e) {
                    log.warn("FIX session {}: disconnect after lease loss failed: {}", sid, e.toString());
                }
            };
        }
        acceptor = ThreadedSocketAcceptor.newBuilder()
            .withApplication(new App())
            .withMessageStoreFactory(persistent ? sessionStore : new MemoryStoreFactory())
            .withSettings(settings)
            .withLogFactory(RedactingLog::new)
            .withMessageFactory(new DefaultMessageFactory())
            .withQueueCapacity(INBOUND_QUEUE)   // ADR-092
            .build();
        if (persistent) {
            acceptor.setIoFilterChainBuilder(barrier.installer());   // ADR-099: stored before sent
        }
        acceptor.start();
        log.info("FIX gateway acceptor on :{} for {} CompID(s), sessions {}", port, compIds.size(),
            persistent ? "in JetStream (ADR-086)" : "in memory");
        if (!tlsCompIds.isEmpty()) {
            log.info("FIX TLS acceptor on :{} for {} CompID(s) (ADR-095)", tlsPort, tlsCompIds.size());
        }
    }

    /** ResendRequest messages answered by GapFill because they were past the stored window (ADR-086, FR-GFS-14). */
    long resendGapFills() {
        return sessionStore == null ? 0 : sessionStore.gapFilled();
    }

    /** FR-GFS-20: the session store's group-commit counters ("" with in-memory sessions). */
    String storeMetrics() {
        return sessionStore == null ? "" : sessionStore.commitMetrics();
    }

    /** Inbound messages waiting for session dispatchers, all sessions (ADR-092's FR-FGM-02 bound). */
    int inboundQueued() {
        return acceptor.getQueueSize();
    }

    /**
     * FR-PRG-07: ask every logged-on session to log out and refuse new Logons here. The session store stays
     * open, so a report for an order still in flight is stored and resent where the client resumes (ADR-086).
     */
    void logoutAll(final String reason) {
        if (acceptor == null) {
            return;
        }
        for (final Session s : acceptor.getManagedSessions()) {
            if (s.isLoggedOn()) {
                s.logout(reason);
            }
        }
    }

    public void stop() {
        pipelines.values().forEach(p -> p.reports.shutdown());
        acks.shutdown();
        if (acceptor != null) {
            acceptor.stop();
        }
        if (sessionStore != null) {
            sessionStore.close();
        }
    }

    /**
     * This acceptor's own Session for {@code sid}. Not {@code Session.lookupSession}/{@code sendToTarget}:
     * QuickFIX/J's registry is JVM-global, so with two acceptors in one JVM (FixSessionPersistenceIT's
     * two replicas) a reply would go to whichever registered the SessionID last.
     */
    private Session sessionOf(final SessionID sid) {
        for (final Session s : acceptor.getManagedSessions()) {
            if (s.getSessionID().equals(sid)) {
                return s;
            }
        }
        return null;
    }

    /**
     * ADR-086 §3: this gateway just took {@code sid}'s lease. The session's stored outbound log is its
     * order index: rebuild ClOrdID → orderRef (so a cancel by OrigClOrdID resolves on whichever replica
     * the counterparty reconnected to), and every open order's reported CumQty into {@link #tracked}
     * (ADR-084), so this gateway reports the fills nobody has told the session about yet and never one
     * it has. Fills that arrived while the order was untracked here are in the early ring and replay now.
     *
     * <p>ponytail: an adopted entry learns its epoch from its next feed update. A fresh cluster epoch is a
     * wipe of the venue, and purging {@code FIX_SESSIONS} belongs to that wipe, or today's refs would
     * name the new epoch's orders.
     *
     * <p>ADR-092 (FR-FGM-03): the log is read one message at a time and only the session's open orders are kept
     * (at most {@link #TRACKED_CAPACITY}, as {@link #tracked} itself), so adopting a long FIX day costs no more heap
     * than adopting a short one.
     */
    private void adopt(final SessionID sid, final FixSessionStore.SentLog sent) {
        final Map<Long, Tracked> open = new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Long, Tracked> eldest) {
                return size() > TRACKED_CAPACITY;
            }
        };
        final int[] messages = new int[1];
        try {
            sent.forEach(raw -> {
                messages[0]++;
                try {
                    final Message m = new Message();
                    m.fromString(raw, null, false);
                    if (!"8".equals(m.getHeader().getString(35)) || !m.isSetField(ClOrdID.FIELD)
                        || !m.isSetField(OrderID.FIELD) || !m.getString(OrderID.FIELD).startsWith("ord-")
                        || m.getChar(ExecType.FIELD) == ExecType.ORDER_STATUS) {
                        return;   // a status answer reports the read model; it tells the session no fill
                    }
                    final long ref = Long.parseLong(m.getString(OrderID.FIELD).substring(4));
                    final String key = sid.getTargetCompID() + '\001' + m.getString(ClOrdID.FIELD);
                    if (m.getChar(ExecType.FIELD) == ExecType.CANCELED) {
                        refByClOrdId.remove(key);
                    } else {
                        refByClOrdId.put(key, ref);
                    }
                    open.remove(ref);   // re-insert: iteration follows each order's latest report
                    final char status = m.getChar(OrdStatus.FIELD);
                    if (status != OrdStatus.NEW && status != OrdStatus.PARTIALLY_FILLED) {
                        synchronized (feedLock) {   // done: nothing is left to report for it here
                            final Tracked t = tracked.get(ref);
                            if (t != null && t.session.equals(sid)) {
                                tracked.remove(ref);
                            }
                        }
                        return;
                    }
                    final Tracked a = new Tracked(sid, m.getString(ClOrdID.FIELD),
                        m.isSetField(Symbol.FIELD) ? m.getString(Symbol.FIELD) : "",
                        m.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B');
                    a.cumQty = (int) m.getDouble(CumQty.FIELD);
                    a.notional = a.cumQty * (m.isSetField(AvgPx.FIELD) ? m.getDouble(AvgPx.FIELD) : 0d);
                    open.put(ref, a);
                } catch (final Exception e) {
                    // not an order report this acceptor wrote; nothing to adopt
                }
            });
        } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        synchronized (feedLock) {
            for (final Map.Entry<Long, Tracked> e : open.entrySet()) {
                final Tracked a = e.getValue();
                Tracked t = tracked.get(e.getKey());
                if (t == null) {
                    t = a;
                    tracked.put(e.getKey(), t);
                } else if (!t.session.equals(sid)) {
                    continue;
                } else {
                    t.clOrdId = a.clOrdId;
                    t.cumQty = a.cumQty;
                    t.notional = a.notional;
                }
                replayEarly(e.getKey(), t);
            }
        }
        log.info("FIX session {}: adopted {} open order(s) from {} stored message(s)", sid, open.size(), messages[0]);
    }

    /**
     * ADR-082 S3: Password(554) and NewPassword(925) masked in a FIX message or any text that embeds one. QuickFIX/J
     * puts the raw message into session events (an invalid or garbled message, a rejected one, a disconnect
     * reason), so every session event goes through here. QuickFIX/J's own SLF4J lines (e.g. a logon for an
     * unconfigured CompID, logged before any session exists) are masked by the same rule in logback.xml.
     */
    static String redact(final String text) {
        return text == null ? null : PASSWORD_FIELD.matcher(text).replaceAll("$1$2=********");
    }

    private static final java.util.regex.Pattern PASSWORD_FIELD =
        java.util.regex.Pattern.compile("(^|[\\x01|])(554|925)=[^\\x01|]*");

    /**
     * The session log, in place of ScreenLogFactory(false, false, false): messages are not logged (as before);
     * events and error events are, through SLF4J and redacted. Rejected logons still read "Logon rejected: ...".
     */
    private static final class RedactingLog implements quickfix.Log {
        private static final Logger sessionLog = LoggerFactory.getLogger("quickfix.session");
        private final String session;

        RedactingLog(final SessionID sessionId) {
            this.session = sessionId.toString();
        }

        @Override public void clear() { }
        @Override public void onIncoming(final String message) { }
        @Override public void onOutgoing(final String message) { }
        @Override public void onEvent(final String text) { sessionLog.info("{}: {}", session, redact(text)); }
        @Override public void onErrorEvent(final String text) { sessionLog.error("{}: {}", session, redact(text)); }
    }

    private final class App implements Application {
        @Override
        public void onCreate(final SessionID sessionId) { }

        @Override
        public void onLogon(final SessionID sessionId) {
            if (sessionStore != null) {
                sessionStore.live(sessionId, true);
            }
            log.info("FIX logon: {}", sessionId.getTargetCompID());
        }

        @Override
        public void onLogout(final SessionID sessionId) {
            if (sessionStore != null) {
                sessionStore.live(sessionId, false);
            }
            log.info("FIX logout: {}", sessionId.getTargetCompID());
        }

        @Override
        public void toAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void fromAdmin(final Message message, final SessionID sessionId) throws RejectLogon {
            // Accept the logon if the CompID is configured AND (S3) its Password(554) matches when
            // FIX_SESSION_AUTH is set; account entitlement beyond the session binding is the cluster's.
            try {
                if ("A".equals(message.getHeader().getString(35))) {
                    final String compId = sessionId.getTargetCompID();
                    if (!compIds.contains(compId) && !tlsCompIds.contains(compId)) {
                        throw new RejectLogon("unknown CompID");
                    }
                    final String password = message.isSetField(quickfix.field.Password.FIELD)
                        ? message.getString(quickfix.field.Password.FIELD) : null;
                    if (!auth.logonAllowed(compId, password)) {
                        throw new RejectLogon("invalid credentials");
                    }
                    // ADR-086: one gateway per live session. A live owner refuses at once; a dead or draining
                    // one is waited out (§6b). The lease is taken only after the password.
                    if (sessionStore != null && !sessionStore.available()) {
                        throw new RejectLogon("FIX session store unavailable; retry later");
                    }
                    final boolean reset = message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD)
                        && message.getBoolean(quickfix.field.ResetSeqNumFlag.FIELD);
                    if (sessionStore != null && !acquire(sessionId, reset)) {
                        throw new RejectLogon(sessionStore.available() ? "session is live on another gateway"
                            : "FIX session store unavailable; retry later");
                    }
                }
            } catch (final FieldNotFound ignore) {
                // non-logon admin message
            }
        }

        private String msgTypeOf(final Message message) {
            try {
                return "35=" + message.getHeader().getString(35);
            } catch (final FieldNotFound e) {
                return "a message";
            }
        }

        private boolean acquire(final SessionID sessionId, final boolean reset) {
            try {
                return sessionStore.acquireForLogon(sessionId, FixSessionStore.LOGON_WAIT_MILLIS, reset);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void toApp(final Message message, final SessionID sessionId) { }

        @Override
        public void fromApp(final Message message, final SessionID sessionId) {
            // ADR-086: nothing reaches the cluster for a session this gateway cannot prove it still owns
            // (paused past the lease TTL and taken over, or its store down). The answer could not be stored,
            // so it could never be sent: end the session instead of accepting an order it cannot report.
            if (sessionStore != null && !sessionStore.confirm(sessionId)) {
                log.warn("FIX {}: lease not confirmed (store {}); disconnecting without acting on {}",
                    sessionId.getTargetCompID(), sessionStore.available() ? "up" : "down", msgTypeOf(message));
                final Session session = sessionOf(sessionId);
                if (session != null) {
                    try {
                        session.disconnect("FIX session lease not confirmed", true);
                    } catch (final java.io.IOException ignore) {
                        // already gone
                    }
                }
                return;
            }
            // ADR-086: an answer that completes after its client left must still find the lease.
            if (sessionStore != null) {
                sessionStore.requestStarted(sessionId);
            }
            boolean answeredLater = false;   // FR-BSP-02: a pipelined order's answer ends its request
            try {
                final String msgType = message.getHeader().getString(35);
                if ("D".equals(msgType)) {
                    answeredLater = onNewOrderSingle((NewOrderSingle) message, sessionId);
                    return;
                }
                settle(sessionId, message, msgType);
                if ("F".equals(msgType)) {
                    onOrderCancelRequest((OrderCancelRequest) message, sessionId);
                } else if ("G".equals(msgType)) {
                    onOrderCancelReplaceRequest((OrderCancelReplaceRequest) message, sessionId);
                } else if ("H".equals(msgType)) {
                    onOrderStatusRequest((OrderStatusRequest) message, sessionId);
                } else if ("AF".equals(msgType)) {
                    onOrderMassStatusRequest((OrderMassStatusRequest) message, sessionId);
                }
            } catch (final FieldNotFound ignore) {
                // malformed; QuickFIX/J session layer already validated structure
            } finally {
                if (sessionStore != null && !answeredLater) {
                    sessionStore.requestEnded(sessionId);
                }
            }
        }

        /**
         * FR-BSP-02: a request naming a ClOrdID this session still has in flight (F and G by OrigClOrdID, H by ClOrdID)
         * waits for that order's answer, and AF for all of them, so each sees what it would have when orders were
         * answered one at a time. Bounded: every in-flight order is answered within the gateway's ack deadline.
         */
        private void settle(final SessionID sid, final Message m, final String msgType) throws FieldNotFound {
            final Pipeline p = pipelines.get(sid);
            if (p == null) {
                return;
            }
            if ("AF".equals(msgType)) {
                CompletableFuture.allOf(p.byClOrdId.values().toArray(new CompletableFuture<?>[0])).join();
                return;
            }
            final int tag = "H".equals(msgType) ? ClOrdID.FIELD : OrigClOrdID.FIELD;
            final CompletableFuture<?> f = m.isSetField(tag) ? p.byClOrdId.get(m.getString(tag)) : null;
            if (f != null) {
                f.join();
            }
        }

        /** True when the order was pipelined (FR-BSP-02): its answer, and the end of its request, come later. */
        private boolean onNewOrderSingle(final NewOrderSingle order, final SessionID sessionId)
            throws FieldNotFound {
            final String clOrdId = order.getString(ClOrdID.FIELD);
            final String ticker = order.getString(Symbol.FIELD).trim().toUpperCase();
            final char side = order.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
            final int qty = (int) order.getDouble(OrderQty.FIELD);
            final int accountId = accountOf(sessionId, order);
            if (accountId < 0) {   // S3: Account(1) outside this session's binding
                sendOrderReject(sessionId, clOrdId, ticker, side, qty, "account not permitted for this session");
                return false;
            }

            // YU18 (FR-OT04): every NewOrderSingle is answered. F1 was a Price-less market order
            // throwing FieldNotFound into the swallow in fromApp; the mapping reads each field only
            // where the type requires it, and a refusal is an ExecutionReport, never silence.
            final Object mapped = mapOrder(order, side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY, qty, false);
            if (mapped instanceof String error) {
                sendOrderReject(sessionId, clOrdId, ticker, side, qty, error);
                return false;
            }
            if (sessionStore == null || !sessionStore.sequenceOrders) {
                pipeline(order, sessionId, clOrdId, accountId, ticker, side, qty, mapped);
                return true;
            }
            // ADR-090 (FIX_EXACTLY_ONCE): one order at a time. FR-BSP-02 leaves its store-then-submit and FR-XOD-12
            // catch-up unpipelined.
            // FR-XOC-06: an order the gateway reaped at a leader change is resubmitted under its key, which the engine
            // dedupes (FR-IMRG14): lost with the old leader it is booked once, committed it is restated. ADR-091 left
            // that to the client, but FIX has no "ambiguous" answer to resend on, so the client heard nothing. Only
            // across an election, which answers within ~1 s: a plain timeout can be past the engine's key window.
            OrderSubmitter.ExecResult result;
            for (int attempt = 0; ; attempt++) {
                final long elections = submitter.leaderChanges();
                try {
                    final quickfix.Message.Header h = order.getHeader();
                    final boolean mayBeResend = attempt > 0 || h.isSetField(quickfix.field.PossResend.FIELD)
                        && h.getBoolean(quickfix.field.PossResend.FIELD)
                        || h.isSetField(quickfix.field.PossDupFlag.FIELD) && h.getBoolean(quickfix.field.PossDupFlag.FIELD);
                    if (mayBeResend && sessionStore.orderSeq(sessionId, accountId, clOrdId, true)
                            == FixSessionStore.AGED_OUT) {
                        sendAgedOut(sessionId, clOrdId);
                        return false;
                    }
                    result = submitSequenced(sessionId, clOrdId, accountId, ticker, side, qty, mapped, order.toString(),
                        mayBeResend);
                } catch (final java.io.IOException e) {
                    // FR-XOD-12: not stored, so not submitted; the store has already ended the session
                    log.warn("FIX order {} not stored, not submitted: {}", clOrdId, e.toString());
                    return false;
                }
                if (result != null || attempt == FAILOVER_RESUBMITS || submitter.leaderChanges() == elections) {
                    break;
                }
                log.warn("FIX order {} stranded by a leader change: resubmitted under its key", clOrdId);
            }
            answer(sessionId, clOrdId, accountId, ticker, side, qty, result);
            return false;
        }

        /** FR-BSP-02: submit now, in arrival order; answer on the session's report thread when decided. */
        private void pipeline(final NewOrderSingle order, final SessionID sid, final String clOrdId, final int accountId,
                              final String ticker, final char side, final int qty, final Object mapped)
            throws FieldNotFound {
            final Pipeline p = pipelines.computeIfAbsent(sid, Pipeline::new);
            final int msgSeqNum = order.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
            final int possFlags = sessionKeyed ? possFlags(order.getHeader()) : -1;
            p.slots.acquireUninterruptibly();
            p.hold(msgSeqNum);
            final CompletableFuture<Void> done = submitNew(sid, clOrdId, qualify(sid, clOrdId), accountId, ticker, side,
                    qty, mapped, p, 0, msgSeqNum, possFlags)
                .thenApply(result -> {
                    acked(sid, clOrdId, ticker, side, result);
                    return result;
                })
                .handleAsync((result, failure) -> {
                    try {
                        answer(sid, clOrdId, accountId, ticker, side, qty, failure == null ? result : null);
                    } catch (final RuntimeException e) {
                        log.warn("FIX order {} not answered: {}", clOrdId, e.toString());
                    } finally {
                        p.decided(msgSeqNum);
                        p.slots.release();
                        if (sessionStore != null) {
                            sessionStore.requestEnded(sid);
                        }
                    }
                    return null;
                }, p.reports);
            p.byClOrdId.put(clOrdId, done);
            done.whenComplete((v, e) -> p.byClOrdId.remove(clOrdId, done));
        }

        /**
         * FR-XOC-06 for a pipelined order: stranded by a leader change, it is resubmitted under its key. ADR-104:
         * {@code possFlags >= 0} tags it for the engine's session ring, and a ring answer naming another order of this
         * gateway is a key-and-check collision (FR-XOD-25), resubmitted once under a salted key.
         */
        private CompletableFuture<OrderSubmitter.ExecResult> submitNew(final SessionID sid, final String clOrdId,
                                                                       final String key, final int accountId,
                                                                       final String ticker, final char side,
                                                                       final int qty, final Object mapped,
                                                                       final Pipeline p, final int attempt,
                                                                       final int msgSeqNum, final int possFlags) {
            final long elections = submitter.leaderChanges();
            final OrderSubmitter.TypedOrder typed = mapped instanceof OrderSubmitter.TypedOrder t ? t : null;
            final long px = typed == null ? (Long) mapped : 0L;
            return (possFlags < 0
                    ? submitter.submitOrderAsync(key, accountId, ticker, side, qty, px, typed, acks)
                    : submitter.submitSessionKeyedAsync(key, accountId, ticker, side, qty, px, typed, sessionStream(sid),
                        msgSeqNum, possFlags, acks))
                .thenCompose(r -> {
                    if (possFlags >= 0 && key.equals(qualify(sid, clOrdId)) && answersAnotherOrder(key, r)) {
                        log.warn("FIX order {}: the engine's ring answered with order {} of another ClOrdID; resubmitted"
                            + " under a salted key", clOrdId, r.orderRef());
                        return submitNew(sid, clOrdId, key + "\001#", accountId, ticker, side, qty, mapped, p, attempt,
                            msgSeqNum, possFlags);
                    }
                    if (r != null || attempt == FAILOVER_RESUBMITS) {
                        return CompletableFuture.completedFuture(r);
                    }
                    if (submitter.leaderChanges() != elections) {
                        log.warn("FIX order {} stranded by a leader change: resubmitted under its key", clOrdId);
                        return submitNew(sid, clOrdId, key, accountId, ticker, side, qty, mapped, p, attempt + 1,
                            msgSeqNum, possFlags);
                    }
                    if (possFlags < 0) {
                        // FR-XOC-06: without the session ring a plain timeout can outlast the key window
                        return CompletableFuture.completedFuture(r);
                    }
                    // FR-XOC-08: no answer and no election (run 7b: the leader dropped this gateway's egress,
                    // FR-BSP-03). As a PossDup the ring restates it if it committed, books it if its MsgSeqNum is above
                    // everything the ring evicted, and otherwise refuses SESSION_AGED_OUT (FR-XOD-22): never twice.
                    unansweredResubmits.incrementAndGet();
                    log.warn("FIX order {} unanswered with no leader change: resubmitted as PossDup", clOrdId);
                    return submitNew(sid, clOrdId, key, accountId, ticker, side, qty, mapped, p, attempt + 1, msgSeqNum,
                        possFlags | finos.traderx.ordermatcher.lmax.AeronReplicationCodec.INPUT_FLAG_POSS_DUP);
                });
        }

        /** FR-XOD-25: a duplicate answer whose ref this gateway holds under another ClOrdID (refs are venue-unique). */
        private boolean answersAnotherOrder(final String key, final OrderSubmitter.ExecResult r) {
            if (r == null || r.accepted() || r.riskReason() != DUPLICATE || r.orderRef() <= 0) {
                return false;
            }
            final Long ref = r.orderRef();
            synchronized (refByClOrdId) {
                if (ref.equals(refByClOrdId.get(key))) {
                    return false;
                }
                for (final Map.Entry<String, Long> e : refByClOrdId.entrySet()) {
                    if (ref.equals(e.getValue())) {
                        return true;   // ponytail: a scan, on duplicate answers only (resends); index refs if hot
                    }
                }
            }
            return false;
        }

        /** ADR-104: the session-day stream, FNV-1a 64 of the session and its store's creation time (ADR-086 shares it). */
        private long sessionStream(final SessionID sid) {
            final Session session = Session.lookupSession(sid);
            long created = 0L;
            try {
                created = session == null ? 0L : session.getStore().getCreationTime().getTime();
            } catch (final java.io.IOException e) {
                log.warn("FIX session {}: no store creation time: {}", sid, e.toString());
            }
            long h = 0xcbf29ce484222325L;
            for (final char c : (sid + "/" + created).toCharArray()) {
                h = (h ^ c) * 0x100000001b3L;
            }
            return h == 0L ? 1L : h;
        }

        /** FR-XOD-20: the NewOrderSingle's PossDup (43) and PossResend (97) as ingress flag bits. */
        private int possFlags(final quickfix.Message.Header h) throws FieldNotFound {
            int flags = 0;
            if (h.isSetField(quickfix.field.PossDupFlag.FIELD) && h.getBoolean(quickfix.field.PossDupFlag.FIELD)) {
                flags |= finos.traderx.ordermatcher.lmax.AeronReplicationCodec.INPUT_FLAG_POSS_DUP;
            }
            if (h.isSetField(quickfix.field.PossResend.FIELD) && h.getBoolean(quickfix.field.PossResend.FIELD)) {
                flags |= finos.traderx.ordermatcher.lmax.AeronReplicationCodec.INPUT_FLAG_POSS_RESEND;
            }
            return flags;
        }

        /** The answer to one NewOrderSingle's submit (also a stored order resubmitted by FR-XOD-12's catch-up). */
        private void answer(final SessionID sessionId, final String clOrdId, final int accountId, final String ticker,
                            final char side, final int qty, final OrderSubmitter.ExecResult result) {
            if (result == null) {
                // Post-publish ambiguity: no reject may be sent; the counterparty reconciles.
                log.warn("FIX order {} ambiguous (no committed ack)", clOrdId);
                return;
            }
            if (!result.accepted() && result.riskReason() == DUPLICATE && result.orderRef() > 0) {
                refByClOrdId.put(qualify(sessionId, clOrdId), result.orderRef());
                sendRestatement(sessionId, clOrdId, accountId, ticker, side, qty, result.orderRef());
                return;
            }
            if (!result.accepted() && result.riskReason() == SESSION_AGED_OUT) {
                // ADR-104 FR-XOD-22: a resend the session ring may have evicted; never booked by this request
                sendExactlyOnceReject(sessionId, clOrdId, "EXACTLY_ONCE_AGED_OUT: ClOrdID may be older than the last "
                    + "orders the engine keeps for this session today; not booked by this request; query its status");
                return;
            }
            if (!result.accepted() && result.riskReason() == DUPLICATE) {
                // ADR-090 FR-XOD-03/14: committed earlier, once, its ref past the stream's answer window. Not 39=8: that
                // reads as "did not execute" (ADR-091 §3) for an order that did.
                sendExactlyOnceReject(sessionId, clOrdId, "EXACTLY_ONCE_COMMITTED_REF_UNKNOWN: this ClOrdID executed"
                    + " earlier, once; its order id is past the engine's answer window; not booked again; query its status");
                return;
            }
            if (result.accepted()) {
                refByClOrdId.put(qualify(sessionId, clOrdId), result.orderRef());
            }
            sendReport(sessionId, clOrdId, ticker, side, qty, result);
            // After the New, so a fill can never be reported ahead of the order it fills. An ack that
            // is already terminal (filled by its own ack, FOK/IOC canceled) has nothing left to report.
            if (result.accepted() && result.kind() != OutputEvent.KIND_ORDER_CANCELED
                    && result.kind() != OutputEvent.KIND_ORDER_FILLED) {
                answered(result.orderRef(), sessionId, clOrdId, ticker, side);
            } else if (result.accepted()) {
                discardEarly(result.orderRef());
            }
        }

        /**
         * ADR-090 (FR-XOD-12): a NewOrderSingle on this session's gateway-sequenced stream. A ClOrdID already stored
         * today is resubmitted at its stored sequence, so the engine answers it with the original order; a new one is
         * stored first (create-if-absent under the lease) and only then submitted. OUT_OF_SEQUENCE means an earlier
         * stored order never reached the engine (its gateway died between store and submit): those go first, in
         * order, each answered, and then this one again. FR-XOD-13: a ClOrdID older than the store's heap window is
         * found only when the message says it may be a resend (PossResend/PossDup).
         */
        private OrderSubmitter.ExecResult submitSequenced(final SessionID sid, final String clOrdId, final int accountId,
                                                          final String ticker, final char side, final int qty,
                                                          final Object mapped, final String raw,
                                                          final boolean mayBeResend) throws java.io.IOException {
            final long stored = sessionStore.orderSeq(sid, accountId, clOrdId, mayBeResend);
            final long seq = stored > 0 ? stored : sessionStore.recordOrder(sid, accountId, clOrdId, raw);
            OrderSubmitter.ExecResult r = submitAt(sid, clOrdId, accountId, ticker, side, qty, mapped, seq);
            if (r != null && !r.accepted() && r.riskReason() == OUT_OF_SEQUENCE) {
                for (long s = r.orderRef(); s >= 1 && s < seq; s++) {
                    if (!resubmitStored(sid, accountId, s)) {
                        break;
                    }
                }
                r = submitAt(sid, clOrdId, accountId, ticker, side, qty, mapped, seq);
            }
            return r;
        }

        private OrderSubmitter.ExecResult submitAt(final SessionID sid, final String clOrdId, final int accountId,
                                                   final String ticker, final char side, final int qty,
                                                   final Object mapped, final long seq) {
            final long stream = sessionStore.orderStream(sid);
            final OrderSubmitter.ExecResult r = mapped instanceof OrderSubmitter.TypedOrder typed
                ? submitter.submitSequencedOrder(qualify(sid, clOrdId), accountId, ticker, side, qty, 0L, typed, stream, seq)
                : submitter.submitSequencedOrder(qualify(sid, clOrdId), accountId, ticker, side, qty, (Long) mapped, null,
                    stream, seq);
            // FR-XOD-13: an answer says what the engine expects next; nothing below it can be asked for again
            if (r != null && (r.accepted() || r.riskReason() != STREAM_LIMIT)) {
                sessionStore.answeredBelow(sid, accountId,
                    !r.accepted() && r.riskReason() == OUT_OF_SEQUENCE ? r.orderRef() : seq + 1);
            }
            return r;
        }

        /** FR-XOD-12 catch-up: submit and answer the stored order at {@code seq}; false stops the catch-up. */
        private boolean resubmitStored(final SessionID sid, final int accountId, final long seq) {
            final String raw = sessionStore.storedOrder(sid, accountId, seq);
            if (raw == null) {
                return false;
            }
            try {
                final NewOrderSingle o = new NewOrderSingle();
                o.fromString(raw, null, false);
                final String c = o.getString(ClOrdID.FIELD);
                final String ticker = o.getString(Symbol.FIELD).trim().toUpperCase();
                final char side = o.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
                final int qty = (int) o.getDouble(OrderQty.FIELD);
                final Object mapped = mapOrder(o, side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY, qty, false);
                if (mapped instanceof String) {
                    return false;
                }
                answer(sid, c, accountId, ticker, side, qty, submitAt(sid, c, accountId, ticker, side, qty, mapped, seq));
                return true;
            } catch (final Exception e) {
                log.warn("FIX session {}: stored order {}.{} not resubmitted: {}", sid, accountId, seq, e.toString());
                return false;
            }
        }

        /**
         * OrderCancelRequest (F). Resolves the target orderRef from OrderID (37) first — this
         * gateway mints it as {@code ord-<orderRef>}, so it survives a reconnect onto any replica —
         * and falls back to the local OrigClOrdID map. The accept/reject verdict is NOT made here:
         * it comes back from the replicated state machine, which decides it from {@code
         * lookup(orderRef)} against replicated state alone.
         */
        private void onOrderCancelRequest(final OrderCancelRequest cancel, final SessionID sessionId)
            throws FieldNotFound {
            final String clOrdId = cancel.getString(ClOrdID.FIELD);
            final String origClOrdId = cancel.getString(OrigClOrdID.FIELD);
            final char side = cancel.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
            final String ticker = cancel.isSetField(Symbol.FIELD)
                ? cancel.getString(Symbol.FIELD).trim().toUpperCase() : "";

            final long orderRef = resolveOrderRef(cancel, qualify(sessionId, origClOrdId));
            if (orderRef <= 0) {
                sendCancelReject(sessionId, "NONE", clOrdId, origClOrdId, OrdStatus.REJECTED,
                    CxlRejReason.UNKNOWN_ORDER, "unknown OrigClOrdID; resend with OrderID (37)");
                return;
            }

            // G3: the session's account is its ownership claim; the engine refuses a foreign ref.
            // S3: and the claim must be one of the session's own accounts.
            final int account = accountOf(sessionId, cancel);
            if (account < 0) {
                sendCancelReject(sessionId, "ord-" + orderRef, clOrdId, origClOrdId, OrdStatus.REJECTED,
                    CxlRejReason.OTHER, "account not permitted for this session");
                return;
            }
            cancelRequested(orderRef, true);   // FR-FFR-17: this cancel is answered here, not as the venue's
            final OrderSubmitter.ExecResult result = submitter.submitCancel(orderRef, account);
            if (result == null || !result.accepted()) {
                cancelRequested(orderRef, false);   // not this cancel's: a venue end still reaches the client
            }
            if (result == null) {
                // Post-publish ambiguity: the cancel may yet apply, so no reject may be sent.
                log.warn("FIX cancel of ord-{} ambiguous (no committed ack)", orderRef);
                return;
            }
            if (result.accepted()) {
                sendCancelReport(sessionId, clOrdId, origClOrdId, ticker, side, orderRef, cancel);
                return;
            }
            final boolean agedOut = agedOut(sessionId, origClOrdId, orderRef, result);
            final boolean unknown = result.kind() == OutputEvent.KIND_ORDER_NOT_FOUND && !agedOut;
            sendCancelReject(sessionId, unknown ? "NONE" : "ord-" + orderRef, clOrdId, origClOrdId,
                unknown ? OrdStatus.REJECTED : agedOut ? OrdStatus.DONE_FOR_DAY : ordStatusOf(result.kind()),
                unknown ? CxlRejReason.UNKNOWN_ORDER : CxlRejReason.TOO_LATE_TO_CANCEL,
                unknown ? "no such order" : agedOut ? AGED_OUT : "order already terminal");
        }

        private static final String AGED_OUT = "order already terminal; its final state is no longer retained";

        private void cancelRequested(final long orderRef, final boolean requested) {
            synchronized (feedLock) {
                final Tracked t = tracked.get(orderRef);
                if (t != null) {
                    t.cancelRequested = requested;
                }
            }
        }

        /**
         * FR-XOC-03: the engine no longer knows a ref this session's own ClOrdID mapping names. The mapping comes only
         * from an accepted or DUPLICATE answer, so the order existed and has left terminal retention: it is terminal
         * (DONE_FOR_DAY, as the restatement says without a read model), never "no such order" / OrderID NONE.
         */
        private boolean agedOut(final SessionID sessionId, final String origClOrdId, final long orderRef,
                                final OrderSubmitter.ExecResult result) {
            return result.kind() == OutputEvent.KIND_ORDER_NOT_FOUND
                && Long.valueOf(orderRef).equals(refByClOrdId.get(qualify(sessionId, origClOrdId)));
        }

        /**
         * ClOrdID is unique per SESSION per day, not globally — FIX 4.4 §"Unique identifier". Two
         * counterparties both numbering their orders from 1 is entirely legal, so the raw ClOrdID
         * cannot be the idempotency key: the second session's order 1 would be suppressed as a
         * duplicate of the first session's. Qualifying by SenderCompID is what makes the engine's
         * clientOrderKey mean what FIX says ClOrdID means.
         */
        private String qualify(final SessionID sessionId, final String clOrdId) {
            // Octal escape, not a literal control byte in the source: SOH cannot appear inside a
            // CompID or a ClOrdID, so it is a separator no client value can forge.
            return sessionId.getTargetCompID() + '\001' + clOrdId;
        }

        /**
         * OrderCancelReplaceRequest (G), answered by ONE sequenced atomic replace (ADR-058).
         *
         * <p>The FIX-level contract is what makes atomicity visible to a counterparty: exactly one
         * response per request. Either an ExecutionReport with {@code ExecType=5} (Replace) — the
         * order now stands at the new size/price, under the same OrderID — or an OrderCancelReject
         * with the OLD order left untouched and still live. A cancel-then-add implementation could
         * not offer that: a rejected add answers one request with a cancel confirm AND a reject,
         * and leaves the counterparty with no order at all.
         */
        private void onOrderCancelReplaceRequest(final OrderCancelReplaceRequest replace,
                                                 final SessionID sessionId) throws FieldNotFound {
            final String clOrdId = replace.getString(ClOrdID.FIELD);
            final String origClOrdId = replace.getString(OrigClOrdID.FIELD);
            final char side = replace.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
            final String ticker = replace.isSetField(Symbol.FIELD)
                ? replace.getString(Symbol.FIELD).trim().toUpperCase() : "";
            final int qty = (int) replace.getDouble(OrderQty.FIELD);
            // YU18 (FR-OT04/29): a replace naming a non-limit shape (stop, stop-limit, iceberg,
            // peg, trailing) is a TYPED replace; the engine refuses a change of type or TIF.
            final Object typedReplace = isTypedMessage(replace)
                ? mapOrder(replace, side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY, qty, true) : null;
            if (typedReplace instanceof String error) {
                sendCancelReject(sessionId, "ord-" + resolveOrderRef(replace, qualify(sessionId, origClOrdId)),
                    clOrdId, origClOrdId, OrdStatus.REJECTED, CxlRejReason.OTHER, error,
                    CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
                return;
            }
            if (typedReplace == null && !replace.isSetField(Price.FIELD)) {
                // A replace always carries a limit price; the engine rejects an absent one anyway
                // (a market order never rests, so there is nothing to replace). Answering here
                // rather than letting the FieldNotFound path swallow it keeps the FIX contract
                // "exactly one response per request" true.
                sendCancelReject(sessionId, "ord-" + resolveOrderRef(replace, qualify(sessionId, origClOrdId)),
                    clOrdId, origClOrdId, OrdStatus.REJECTED, CxlRejReason.OTHER,
                    "replace requires Price(44)", CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
                return;
            }
            final long limitPxTicks = replace.isSetField(Price.FIELD)
                ? Math.round(replace.getDouble(Price.FIELD) * 1_000_000d) : 0L;

            final long orderRef = resolveOrderRef(replace, qualify(sessionId, origClOrdId));
            if (orderRef <= 0) {
                sendCancelReject(sessionId, "NONE", clOrdId, origClOrdId, OrdStatus.REJECTED,
                    CxlRejReason.UNKNOWN_ORDER, "unknown OrigClOrdID; resend with OrderID (37)",
                    CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
                return;
            }

            final int account = accountOf(sessionId, replace);   // S3
            if (account < 0) {
                sendCancelReject(sessionId, "ord-" + orderRef, clOrdId, origClOrdId, OrdStatus.REJECTED,
                    CxlRejReason.OTHER, "account not permitted for this session",
                    CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
                return;
            }
            final OrderSubmitter.ExecResult result = typedReplace instanceof OrderSubmitter.TypedOrder typed
                ? submitter.submitTypedReplace(orderRef, qualify(sessionId, clOrdId), qty, typed, account)
                : submitter.submitReplace(orderRef, qualify(sessionId, clOrdId), qty, limitPxTicks, account);
            if (result == null) {
                // Post-publish ambiguity: the replace may yet apply, so no reject may be sent.
                log.warn("FIX replace of ord-{} ambiguous (no committed ack)", orderRef);
                return;
            }
            if (!result.accepted()) {
                final boolean agedOut = agedOut(sessionId, origClOrdId, orderRef, result);
                final boolean unknown = result.kind() == OutputEvent.KIND_ORDER_NOT_FOUND && !agedOut;
                sendCancelReject(sessionId, unknown ? "NONE" : "ord-" + orderRef, clOrdId, origClOrdId,
                    unknown ? OrdStatus.REJECTED : agedOut ? OrdStatus.DONE_FOR_DAY : ordStatusOf(result.kind()),
                    unknown ? CxlRejReason.UNKNOWN_ORDER : CxlRejReason.TOO_LATE_TO_CANCEL,
                    unknown ? "no such order" : agedOut ? AGED_OUT : "replace rejected; order unchanged",
                    CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
                return;
            }
            // The order keeps its ref, so the NEW ClOrdID must now resolve to it too — otherwise a
            // follow-up cancel by OrigClOrdID would 'unknown order' an order that plainly exists.
            refByClOrdId.put(qualify(sessionId, clOrdId), orderRef);
            final boolean filled = result.kind() == OutputEvent.KIND_ORDER_FILLED;
            synchronized (feedLock) {   // later fills report under the ClOrdID the order now has
                final Tracked t = tracked.get(orderRef);
                if (t != null) {
                    t.clOrdId = clOrdId;
                }
            }
            final ExecutionReport report = new ExecutionReport(
                new OrderID("ord-" + orderRef),
                new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
                new ExecType(ExecType.REPLACED),
                new OrdStatus(filled ? OrdStatus.FILLED : OrdStatus.NEW),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(filled ? 0 : qty),
                new CumQty(filled ? qty : 0),
                new AvgPx(0));
            report.set(new ClOrdID(clOrdId));
            report.set(new OrigClOrdID(origClOrdId));
            if (!ticker.isEmpty()) {
                report.set(new Symbol(ticker));
            }
            report.set(new OrderQty(qty));
            send(report, sessionId, clOrdId);
        }

        /**
         * OrderStatusRequest (H). Answers the current state of ONE order — resolved by OrderID (37)
         * or the request's ClOrdID (11) — with an ExecutionReport carrying {@code ExecType=OrderStatus}.
         * State comes from the read model, not the cluster: the acceptor holds only orderRefs, while
         * the live state (remaining qty, terminal status) lives in the orderbook projection.
         */
        private void onOrderStatusRequest(final OrderStatusRequest req, final SessionID sessionId)
            throws FieldNotFound {
            final String clOrdId = req.getString(ClOrdID.FIELD);
            final char side = req.isSetField(Side.FIELD)
                && req.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
            final long orderRef = resolveOrderRef(req, qualify(sessionId, clOrdId));

            // ADR-093: a ClOrdID this gateway holds no ref for (another replica booked it, or its ref was lost past
            // ADR-090's answer ring) is found by the key the gateway submits it under; the read model scopes it.
            // FR-XOC-07: by its key first even when this gateway holds the ref, so the common request is one row; the
            // whole-account listing is only for an order the key does not find (a status by OrderID under another
            // ClOrdID), and the source bounds it.
            List<OrderStatusSource.OrderView> byKey = statusSource == null
                || accountOf(sessionId, req) < 0 ? null
                : statusSource.ordersByKey(accountOf(sessionId, req),
                    ClusterGatewayMain.clientOrderKey(qualify(sessionId, clOrdId)));
            if (byKey != null && !byKey.isEmpty() && orderRef >= 0 && byKey.get(0).orderRef() != orderRef) {
                byKey = null;   // the key names another order than the one the request resolves to: find that one
            }
            final List<OrderStatusSource.OrderView> orders = byKey != null && !byKey.isEmpty() ? byKey
                : statusSource == null || accountOf(sessionId, req) < 0 ? null   // S3: own accounts only
                    : statusSource.orders(accountOf(sessionId, req), true);
            if (orders == null) {
                sendStatusReject(sessionId, clOrdId, null, side,
                    "order status unavailable (no read model configured)");
                return;
            }
            OrderStatusSource.OrderView match = byKey != null && !byKey.isEmpty() ? byKey.get(0) : null;
            for (final OrderStatusSource.OrderView v : orders) {
                if (match == null && v.orderRef() == orderRef) {
                    match = v;
                    break;
                }
            }
            if (match == null) {
                // FIX 4.4: an unknown order in an OrderStatusRequest is answered with an
                // ExecutionReport, OrdStatus=Rejected — an OrderCancelReject is for F/G only.
                sendStatusReject(sessionId, clOrdId, null, side,
                    "unknown order; resend with OrderID (37)");
                return;
            }
            final ExecutionReport report = statusReportFor(match);
            report.set(new ClOrdID(clOrdId));
            send(report, sessionId, clOrdId);
        }

        /**
         * OrderMassStatusRequest (AF). Streams one ExecutionReport per OPEN order for the account —
         * the same set {@code GET /accounts/{id}/orders} returns — each tagged with the request's
         * MassStatusReqID (584), the last carrying LastRptRequested=Y (912) to close the batch. An
         * account with no open orders still gets one report so the counterparty is never left waiting.
         */
        private void onOrderMassStatusRequest(final OrderMassStatusRequest req, final SessionID sessionId)
            throws FieldNotFound {
            final String reqId = req.getString(MassStatusReqID.FIELD);
            final List<OrderStatusSource.OrderView> orders =
                statusSource == null || accountOf(sessionId, req) < 0 ? null   // S3: own accounts only
                    : statusSource.orders(accountOf(sessionId, req), false);
            if (orders == null || orders.isEmpty()) {
                final ExecutionReport report = statusReport(0, 'B', 0, 0, OrdStatus.REJECTED, "");
                report.set(new MassStatusReqID(reqId));
                report.set(new LastRptRequested(true));
                report.set(new Text(orders == null
                    ? "order status unavailable (no read model configured)" : "no open orders"));
                send(report, sessionId, reqId);
                return;
            }
            for (int i = 0; i < orders.size(); i++) {
                final ExecutionReport report = statusReportFor(orders.get(i));
                report.set(new MassStatusReqID(reqId));
                report.set(new LastRptRequested(i == orders.size() - 1));
                send(report, sessionId, reqId);
            }
        }

        private int accountOf(final Message req) throws FieldNotFound {
            return req.isSetField(quickfix.field.Account.FIELD)
                ? Integer.parseInt(req.getString(quickfix.field.Account.FIELD)) : defaultAccountId;
        }

        /** S3: the request's account, or -1 when it is not one this session is bound to. */
        private int accountOf(final SessionID sessionId, final Message req) throws FieldNotFound {
            final int account = accountOf(req);
            return auth.accountAllowed(sessionId.getTargetCompID(), account, defaultAccountId) ? account : -1;
        }

        private void sendStatusReject(final SessionID sessionId, final String clOrdId,
                                      final String reqId, final char side, final String text) {
            final ExecutionReport report = statusReport(0, side, 0, 0, OrdStatus.REJECTED, "");
            if (clOrdId != null) {
                report.set(new ClOrdID(clOrdId));
            }
            if (reqId != null) {
                report.set(new MassStatusReqID(reqId));
            }
            report.set(new Text(text));
            send(report, sessionId, clOrdId == null ? reqId : clOrdId);
        }

        private ExecutionReport statusReportFor(final OrderStatusSource.OrderView v) {
            final char side = "Sell".equalsIgnoreCase(v.side()) ? 'S' : 'B';
            // FR-DRS-27: CumQty is the read model's filled quantity. A read model too old to send it
            // leaves qty - remaining, right while remaining > 0 or FILLED; a CANCELED/REJECTED order's
            // remaining is 0, so there it would claim the whole order filled: report 0 instead.
            final int cum = v.filled() >= 0 ? v.filled()
                : v.remaining() > 0 || "FILLED".equals(v.status()) ? v.quantity() - v.remaining() : 0;
            return statusReport(v.orderRef(), side, v.quantity(), v.remaining(), cum,
                ordStatusOfName(v.status()), v.security());
        }

        private ExecutionReport statusReport(final long orderRef, final char side, final int qty,
                                             final int remaining, final char ordStatus,
                                             final String security) {
            return statusReport(orderRef, side, qty, remaining, qty - remaining, ordStatus, security);
        }

        /** ExecutionReport(150=OrderStatus) skeleton; caller adds ClOrdID and/or MassStatusReqID. */
        private ExecutionReport statusReport(final long orderRef, final char side, final int qty,
                                             final int remaining, final int cum, final char ordStatus,
                                             final String security) {
            final ExecutionReport report = new ExecutionReport(
                new OrderID(orderRef > 0 ? "ord-" + orderRef : "NONE"),
                new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
                new ExecType(ExecType.ORDER_STATUS),
                new OrdStatus(ordStatus),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(remaining),
                new CumQty(cum),
                new AvgPx(0));
            if (qty > 0) {
                report.set(new OrderQty(qty));
            }
            if (security != null && !security.isEmpty()) {
                report.set(new Symbol(security));
            }
            return report;
        }

        private char ordStatusOfName(final String status) {
            return switch (status) {
                case "PARTIALLY_FILLED" -> OrdStatus.PARTIALLY_FILLED;
                case "FILLED" -> OrdStatus.FILLED;
                case "CANCELED" -> OrdStatus.CANCELED;
                case "REJECTED" -> OrdStatus.REJECTED;
                // YU17 (ADR-069): a pre-open queued order is FIX PENDING_NEW — accepted by the
                // venue, not yet working. Falling through to NEW would tell a FIX client its
                // order is live in a book it is not in.
                case "QUEUED" -> OrdStatus.PENDING_NEW;
                // YU18: an untriggered stop is accepted and working (NEW); a peg out of the book
                // is FIX SUSPENDED.
                case "SUSPENDED" -> OrdStatus.SUSPENDED;
                default -> OrdStatus.NEW;
            };
        }

        private long resolveOrderRef(final quickfix.Message request, final String qualifiedOrigClOrdId)
            throws FieldNotFound {
            if (request.isSetField(OrderID.FIELD)) {
                final String orderId = request.getString(OrderID.FIELD);
                if (orderId.startsWith("ord-")) {
                    try {
                        return Long.parseLong(orderId.substring(4));
                    } catch (final NumberFormatException ignore) {
                        // fall through to the OrigClOrdID map
                    }
                }
            }
            final Long mapped = refByClOrdId.get(qualifiedOrigClOrdId);
            return mapped == null ? -1 : mapped;
        }

        private char ordStatusOf(final byte kind) {
            return switch (kind) {
                case OutputEvent.KIND_ORDER_FILLED -> OrdStatus.FILLED;
                case OutputEvent.KIND_ORDER_PARTIALLY_FILLED -> OrdStatus.PARTIALLY_FILLED;
                case OutputEvent.KIND_ORDER_CANCELED -> OrdStatus.CANCELED;
                default -> OrdStatus.REJECTED;
            };
        }

        private void sendCancelReport(final SessionID sessionId, final String clOrdId,
                                      final String origClOrdId, final String ticker, final char side,
                                      final long orderRef, final OrderCancelRequest cancel) {
            // FR-FIX05: a cancel ends the order, not its fills. What this gateway reported filled
            // (tracked, or rebuilt from the session store on adoption) stands in the Canceled report.
            int cum = 0;
            double notional = 0;
            synchronized (feedLock) {
                // FR-FFR-17: kept until the venue's CANCELED update, which then ends it unreported (cancelRequested);
                // dropped here, that update would wait as an untracked order's and age out counted as lost.
                final Tracked t = tracked.get(orderRef);
                if (t != null) {
                    cum = t.cumQty;
                    notional = t.notional;
                }
            }
            final ExecutionReport report = new ExecutionReport(
                new OrderID("ord-" + orderRef),
                new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
                new ExecType(ExecType.CANCELED),
                new OrdStatus(OrdStatus.CANCELED),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(0),
                new CumQty(cum),
                new AvgPx(cum > 0 ? notional / cum : 0));
            report.set(new ClOrdID(clOrdId));
            report.set(new OrigClOrdID(origClOrdId));
            if (!ticker.isEmpty()) {
                report.set(new Symbol(ticker));
            }
            if (cancel.isSetField(OrderQty.FIELD)) {
                try {
                    report.set(new OrderQty(cancel.getDouble(OrderQty.FIELD)));
                } catch (final FieldNotFound ignore) {
                    // optional on a cancel request
                }
            }
            send(report, sessionId, clOrdId);
            refByClOrdId.remove(qualify(sessionId, origClOrdId));
        }

        private void sendCancelReject(final SessionID sessionId, final String orderId,
                                      final String clOrdId, final String origClOrdId,
                                      final char ordStatus, final int rejReason, final String text) {
            sendCancelReject(sessionId, orderId, clOrdId, origClOrdId, ordStatus, rejReason, text,
                CxlRejResponseTo.ORDER_CANCEL_REQUEST);
        }

        /** CxlRejResponseTo(434) must say which request was rejected — a replace reject carrying
         *  ORDER_CANCEL_REQUEST tells the counterparty their cancel failed, not their replace. */
        private void sendCancelReject(final SessionID sessionId, final String orderId,
                                      final String clOrdId, final String origClOrdId,
                                      final char ordStatus, final int rejReason, final String text,
                                      final char respondingTo) {
            final OrderCancelReject reject = new OrderCancelReject(
                new OrderID(orderId),
                new ClOrdID(clOrdId),
                new OrigClOrdID(origClOrdId),
                new OrdStatus(ordStatus),
                new CxlRejResponseTo(respondingTo));
            reject.set(new CxlRejReason(rejReason));
            reject.set(new Text(text));
            send(reject, sessionId, clOrdId);
        }

        private void send(final Message message, final SessionID sessionId, final String clOrdId) {
            final Session session = sessionOf(sessionId);
            if (session == null) {
                log.warn("FIX session gone before response for {}", clOrdId);
                return;
            }
            session.send(message);
        }

        private void sendReport(final SessionID sessionId, final String clOrdId, final String ticker,
                                final char side, final int qty, final OrderSubmitter.ExecResult result) {
            final boolean filled = result.kind() == OutputEvent.KIND_ORDER_FILLED;
            // YU18: a FOK answered CANCELED FOK_UNFILLABLE (or an IOC with nothing to take) is a
            // cancel, not a working order.
            final boolean canceled = result.accepted() && result.kind() == OutputEvent.KIND_ORDER_CANCELED;
            final char ordStatus = !result.accepted() ? OrdStatus.REJECTED
                : filled ? OrdStatus.FILLED : canceled ? OrdStatus.CANCELED : OrdStatus.NEW;
            final char execType = !result.accepted() ? ExecType.REJECTED
                : filled ? ExecType.FILL : canceled ? ExecType.CANCELED : ExecType.NEW;
            final ExecutionReport report = new ExecutionReport(
                new OrderID(result.accepted() ? "ord-" + result.orderRef() : "NONE"),
                new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
                new ExecType(execType),
                new OrdStatus(ordStatus),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(filled || canceled || !result.accepted() ? 0 : qty),
                new CumQty(filled ? qty : 0),
                new AvgPx(0));
            report.set(new ClOrdID(clOrdId));
            report.set(new Symbol(ticker));
            report.set(new OrderQty(qty));
            final int reason = result.riskReason();
            if (!result.accepted()) {
                report.set(new Text(reason > 0 && reason < REASONS.length
                    ? "rejected: " + REASONS[reason].name() : "rejected by cluster risk"));
            } else if (canceled && reason > 0 && reason < REASONS.length) {
                report.set(new Text(REASONS[reason].name()));
            }
            send(report, sessionId, clOrdId);
        }

        /**
         * FR-XOC-03: a NewOrderSingle whose ClOrdID this session already executed (a PossResend after a disconnect or an
         * unanswered order) whose original has left terminal retention. The engine names that original
         * (REJECTED/DUPLICATE + its ref): the order exists, so it is restated, ExecType=I, never rejected. Its state
         * comes from the read model's by-key lookup when one is configured; without one only "terminal" is known
         * (DONE_FOR_DAY).
         */
        private void sendRestatement(final SessionID sessionId, final String clOrdId, final int accountId,
                                     final String ticker, final char side, final int qty, final long orderRef) {
            OrderStatusSource.OrderView found = null;
            // FR-XOC-07: the one order booked under this ClOrdID's key, never the account's whole list. A leader change
            // restates every stranded order at once (GKE run 7: 1,100-1,450 a gateway, each a whole-account listing,
            // and every gateway out of heap within 13 s).
            final List<OrderStatusSource.OrderView> orders = statusSource == null ? null
                : statusSource.ordersByKey(accountId, ClusterGatewayMain.clientOrderKey(qualify(sessionId, clOrdId)));
            if (orders != null) {
                for (final OrderStatusSource.OrderView v : orders) {
                    if (v.orderRef() == orderRef) {
                        found = v;
                        break;
                    }
                }
            }
            final ExecutionReport report = found != null ? statusReportFor(found)
                : statusReport(orderRef, side, qty, 0, 0, OrdStatus.DONE_FOR_DAY, ticker);
            report.set(new ClOrdID(clOrdId));
            report.set(new Text("DUPLICATE: ClOrdID already executed as ord-" + orderRef
                + (found == null ? "; its final state is no longer retained" : "")));
            send(report, sessionId, clOrdId);
        }

        /**
         * FR-XOD-13: a resend of a ClOrdID the exactly-once ring may have lost. It may have executed, so it is neither
         * booked again nor rejected as an order (an ExecutionReport 39=8 would read as "did not execute"): a
         * BusinessMessageReject with the reason, and the client reconciles by OrderStatusRequest/OrderMassStatusRequest.
         */
        private void sendAgedOut(final SessionID sessionId, final String clOrdId) {
            sendExactlyOnceReject(sessionId, clOrdId, "EXACTLY_ONCE_AGED_OUT: ClOrdID is older than the last "
                + sessionStore.orderWindow + " orders kept for this account today; not booked by this request; query its"
                + " status");
        }

        /** A NewOrderSingle that may have executed and is not booked again: 35=j with the reason, never 39=8. */
        private void sendExactlyOnceReject(final SessionID sessionId, final String clOrdId, final String text) {
            final quickfix.fix44.BusinessMessageReject reject = new quickfix.fix44.BusinessMessageReject(
                new quickfix.field.RefMsgType("D"), new quickfix.field.BusinessRejectReason(0));
            reject.set(new quickfix.field.BusinessRejectRefID(clOrdId));
            reject.set(new Text(text));
            send(reject, sessionId, clOrdId);
        }

        /** YU18 (FR-OT03/04): a boundary refusal — nothing was sequenced. */
        private void sendOrderReject(final SessionID sessionId, final String clOrdId, final String ticker,
                                     final char side, final int qty, final String text) {
            final ExecutionReport report = new ExecutionReport(
                new OrderID("NONE"),
                new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
                new ExecType(ExecType.REJECTED),
                new OrdStatus(OrdStatus.REJECTED),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(0),
                new CumQty(0),
                new AvgPx(0));
            report.set(new ClOrdID(clOrdId));
            report.set(new Symbol(ticker));
            report.set(new OrderQty(qty));
            report.set(new Text(text));
            send(report, sessionId, clOrdId);
        }
    }

    /**
     * FR-FIX05: one {@code /orders} feed message (the leader's order bridge, ADR-048 sibling). Every
     * gateway sees every order's updates and acts only on the refs it tracks, so the session that
     * owns a resting order hears its fill whichever gateway the aggressor came through. Called on
     * the NATS dispatcher thread. The byte scans keep an untracked message (almost all of them on a
     * busy venue) free of parsing and allocation.
     */
    public void onOrderUpdate(final byte[] json) {
        final long ref = feedRef(json);
        if (ref <= 0) {
            return;
        }
        synchronized (feedLock) {
            final Tracked t = tracked.get(ref);
            if (t != null && t.pending != null) {
                t.pending.add(json);   // FR-FFR-18: after its New
            } else if (t != null) {
                applyUpdate(ref, t, json);
            } else if (notFix.containsKey(ref)) {
                // FR-FFR-21: this gateway's REST order; nothing to report
            } else if (indexOf(json, FILL_STATUS, 0) >= 0 || indexOf(json, PARTIAL_STATUS, 0) >= 0
                    || indexOf(json, CANCELED_STATUS, 0) >= 0 || indexOf(json, REJECTED_STATUS, 0) >= 0) {
                early.computeIfAbsent(ref, r -> new Early()).msgs.add(json);   // FR-FFR-15, FR-FFR-17
                expireEarly();
            }
        }
    }

    /** Under {@link #feedLock}: drop the oldest early fills past the age or count bound, and count them. */
    private void expireEarly() {
        final long now = System.nanoTime();
        for (final java.util.Iterator<Map.Entry<Long, Early>> it = early.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Long, Early> e = it.next();
            if (early.size() <= EARLY_CAPACITY && now - e.getValue().at < earlyMaxAgeNanos) {
                return;
            }
            it.remove();
            if (!ownFillsOnly) {
                fillsEvicted.addAndGet(e.getValue().msgs.size());   // the venue's fills: mostly other gateways' orders
            } else if (fillsLost.getAndAdd(e.getValue().msgs.size()) == 0) {
                log.error("ALERT FIX fill or end of ord-{} not reported: its order was not tracked here (never, or evicted:"
                    + " traderx_fix_tracked_evicted_total) within {} s or {} fills (traderx_fix_fill_reports_total{outcome=\"lost\"}"
                    + " counts every one)", e.getKey(),
                    EARLY_MAX_AGE_NANOS / 1_000_000_000L, EARLY_CAPACITY);
            }
        }
    }

    /**
     * FR-FFR-21: an order this gateway submitted outside FIX (REST). Its updates are routed here like a FIX order's
     * (the booking names the gateway, FR-FFR-14) but no FIX session reports them, so they are passed over instead of
     * waiting in the early buffer and aging out counted lost (2 per REST cross on kind, 2026-11-12).
     * ponytail: the newest {@link #NOT_FIX_CAPACITY} such orders; an older one still resting when it fills is counted
     * lost again. A per-order origin on the booking event would remove the set.
     */
    static final int NOT_FIX_CAPACITY = 65_536;
    private final java.util.LinkedHashMap<Long, Boolean> notFix = new java.util.LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(final Map.Entry<Long, Boolean> eldest) {
            return size() > NOT_FIX_CAPACITY;
        }
    };

    public void notFix(final long ref) {
        if (ref <= 0) {
            return;
        }
        synchronized (feedLock) {
            notFix.put(ref, Boolean.TRUE);
            early.remove(ref);   // an update that beat the answer
        }
    }

    /** An order answered terminal (filled or canceled by its own ack) is never tracked: its early fills are its own. */
    private void discardEarly(final long ref) {
        synchronized (feedLock) {
            early.remove(ref);
        }
    }

    /**
     * FR-FFR-18: a pipelined order is tracked at its ack, on {@link #acks}, not when its answer reaches the session's
     * report thread. Its updates (and the early buffer's, which now spans only the ack's own hop) wait on the order
     * until {@link #answered} has sent its New. They are bounded by the session's window ({@link #MAX_IN_FLIGHT}, freed
     * when the answer runs) times an order's few updates, not by time.
     */
    private void acked(final SessionID sid, final String clOrdId, final String ticker, final char side,
                       final OrderSubmitter.ExecResult r) {
        if (r == null || !r.accepted()) {
            return;
        }
        if (r.kind() == OutputEvent.KIND_ORDER_CANCELED || r.kind() == OutputEvent.KIND_ORDER_FILLED) {
            discardEarly(r.orderRef());
            return;
        }
        synchronized (feedLock) {
            final Tracked t = new Tracked(sid, clOrdId, ticker, side);
            final Early e = early.remove(r.orderRef());
            t.pending = e == null ? new java.util.ArrayList<>(2) : e.msgs;
            tracked.put(r.orderRef(), t);
        }
    }

    /** After the New: what waited on the order (FR-FFR-18), oldest first; an order not tracked at its ack, tracked now. */
    private void answered(final long ref, final SessionID session, final String clOrdId, final String symbol,
                          final char side) {
        synchronized (feedLock) {
            final Tracked t = tracked.get(ref);
            if (t == null || !t.session.equals(session)) {
                track(ref, session, clOrdId, symbol, side);
                return;
            }
            final List<byte[]> held = t.pending;
            t.pending = null;
            if (held != null) {
                for (final byte[] m : held) {
                    if (tracked.get(ref) != t) {
                        break;   // ended by one of them
                    }
                    applyUpdate(ref, t, m);
                }
            }
        }
    }

    private void track(final long ref, final SessionID session, final String clOrdId, final String symbol,
                       final char side) {
        synchronized (feedLock) {
            final Tracked t = new Tracked(session, clOrdId, symbol, side);
            tracked.put(ref, t);
            replayEarly(ref, t);
        }
    }

    /** Under {@link #feedLock}: apply the early fills for {@code ref}, oldest first. */
    private void replayEarly(final long ref, final Tracked t) {
        final Early e = early.remove(ref);
        if (e != null) {
            for (final byte[] m : e.msgs) {
                applyUpdate(ref, t, m);
            }
        }
    }

    /** Under {@link #feedLock}. Reports the quantity this update adds over what the session has
     *  been told; a NEW, a redelivery or a stale state adds none, and a missed message folds into
     *  the next one's LastQty, so CumQty is always the engine's. */
    private void applyUpdate(final long ref, final Tracked t, final byte[] json) {
        final com.fasterxml.jackson.databind.JsonNode p;
        try {
            p = JSON.readTree(json).path("payload");
        } catch (final java.io.IOException e) {
            return;
        }
        final String id = p.path("id").asText("");
        final String epoch = id.substring(0, Math.max(0, id.lastIndexOf('-')));
        if (t.epoch == null) {
            t.epoch = epoch;
        } else if (!t.epoch.equals(epoch)) {
            tracked.remove(ref);   // a new epoch reuses refs: this is a stranger's order
            return;
        }
        final String status = p.path("status").asText("");
        final boolean filled = "FILLED".equals(status);
        if (!filled && !"PARTIALLY_FILLED".equals(status)) {
            final boolean canceled = "CANCELED".equals(status);
            if (canceled || "REJECTED".equals(status)) {
                // FR-FFR-17: the venue ended the order (self-trade prevention, expiry, a trigger refusal...). Its
                // client hears it here, unless it asked for the cancel itself, which its own request answers. Under
                // the lease as a fill is (ADR-086 §3); taking it adopts the stored log, which may hold the end.
                if (!t.cancelRequested && (sessionStore == null || sessionStore.held(t.session)
                        || sessionStore.acquire(t.session) && tracked.get(ref) == t)) {
                    sendUnsolicited(t.session, t.clOrdId, endReport(t, ref, p, canceled), endReports);
                }
                tracked.remove(ref);
            }
            return;
        }
        final int qty = p.path("quantity").asInt();
        final int leaves = filled ? 0 : p.path("remainingQuantity").asInt();
        final int cum = qty - leaves;
        if (cum <= t.cumQty) {
            return;
        }
        // ADR-086 §3: a fill is stored only under its session's lease. Live on another gateway: that one
        // adopted this order from the stored log and reports it. Live nowhere: take the lease now, so the
        // fill is sequenced when it happens and resent on the client's ResendRequest. Taking it resyncs t
        // from the log (adopt), which may already hold this fill.
        if (sessionStore != null && !sessionStore.held(t.session)
            && (!sessionStore.acquire(t.session) || cum <= t.cumQty)) {
            return;
        }
        final int lastQty = cum - t.cumQty;
        final double lastPx = p.path("lastExecutionPrice").asDouble();
        // Each update names its own fill. More cumulative quantity than that means updates this
        // gateway never received (core NATS, or a bridge drop upstream of the stream): CumQty stays
        // the engine's, but the client's LastQty folds fills it should have seen one by one.
        final int ownFill = p.path("lastFillQuantity").asInt();
        if (ownFill > 0 && lastQty > ownFill) {
            fillGaps.incrementAndGet();
            log.error("ALERT FIX fill gap on ord-{} ({}): {} filled in order updates this gateway never received;"
                + " reported as one fill of {} (CumQty {} is the engine's)", ref, t.clOrdId, lastQty - ownFill, lastQty, cum);
        }
        t.notional += lastQty * lastPx;
        t.cumQty = cum;
        sendUnsolicited(t.session, t.clOrdId, fillReport(t, ref, qty, cum, lastQty, lastPx), fillReports);
        if (leaves == 0) {
            tracked.remove(ref);
        }
    }

    /**
     * FR-FFR-16: a fill report is built under {@link #feedLock} and sent on its session's report thread, after the
     * session's earlier reports (an order's New is sent there before the order is tracked). Sent here, under the lock,
     * one feed thread carried every fill of the gateway through QuickFIX/J and the store one at a time (~4.6K/s) and
     * held every session's {@link #track} behind it.
     */
    private void sendUnsolicited(final SessionID sid, final String clOrdId, final ExecutionReport report,
                                 final AtomicLong sent) {
        try {
            pipelines.computeIfAbsent(sid, Pipeline::new).reports.execute(() -> {
                final Session session = sessionOf(sid);
                if (session == null) {
                    log.warn("FIX session gone before fill report for {}", clOrdId);
                } else if (session.send(report)) {   // false: off-lease (ADR-086)
                    sent.incrementAndGet();
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException stopping) {
            log.warn("FIX gateway stopping: fill report for {} not sent", clOrdId);
        }
    }

    /** FR-FFR-17: the unsolicited Canceled (or Rejected) report of an order the venue ended; what was filled stands. */
    private ExecutionReport endReport(final Tracked t, final long ref, final com.fasterxml.jackson.databind.JsonNode p,
                                      final boolean canceled) {
        final ExecutionReport report = new ExecutionReport(
            new OrderID("ord-" + ref),
            new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
            new ExecType(canceled ? ExecType.CANCELED : ExecType.REJECTED),
            new OrdStatus(canceled ? OrdStatus.CANCELED : OrdStatus.REJECTED),
            new Side(t.side == 'S' ? Side.SELL : Side.BUY),
            new LeavesQty(0),
            new CumQty(t.cumQty),
            new AvgPx(t.cumQty > 0 ? t.notional / t.cumQty : 0));
        report.set(new ClOrdID(t.clOrdId));
        report.set(new Symbol(t.symbol));
        report.set(new OrderQty(p.path("quantity").asInt()));
        final String reason = p.path("reason").asText("");
        if (!reason.isEmpty()) {
            report.set(new Text(reason));
        }
        return report;
    }

    /** The unsolicited fill ExecutionReport (ADR-084 §3); {@code t.notional} already includes it. */
    private ExecutionReport fillReport(final Tracked t, final long ref, final int qty, final int cum,
                                       final int lastQty, final double lastPx) {
        final int leaves = qty - cum;
        final ExecutionReport report = new ExecutionReport(
            new OrderID("ord-" + ref),
            new ExecID(EXEC_ID_PREFIX + execSeq.incrementAndGet()),
            new ExecType(ExecType.TRADE),
            new OrdStatus(leaves == 0 ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED),
            new Side(t.side == 'S' ? Side.SELL : Side.BUY),
            new LeavesQty(leaves),
            new CumQty(cum),
            new AvgPx(t.notional / cum));
        report.set(new ClOrdID(t.clOrdId));
        report.set(new Symbol(t.symbol));
        report.set(new OrderQty(qty));
        report.set(new LastQty(lastQty));
        report.set(new LastPx(lastPx));
        return report;
    }

    private final AtomicLong fillReports = new AtomicLong();
    private final AtomicLong endReports = new AtomicLong();

    /** FR-FFR-17: unsolicited Canceled/Rejected reports sent for orders the venue ended. */
    public long endReports() {
        return endReports.get();
    }
    private final AtomicLong fillGaps = new AtomicLong();

    /** Fill reports sent, and fill gaps ALERTed (gateway {@code /metrics}). */
    public long fillReports() {
        return fillReports.get();
    }

    public long fillGaps() {
        return fillGaps.get();
    }

    /** FR-FFR-15: fills of this gateway's own orders left unreported (routed feed), and the venue's fills dropped
     *  unmatched from the early buffer on a venue-wide feed (mostly other gateways' orders). */
    public long fillsLost() {
        return fillsLost.get();
    }

    public long fillsEvicted() {
        return fillsEvicted.get();
    }

    /** FR-FFR-19: working orders evicted past {@link #TRACKED_CAPACITY}. */
    public long trackedEvicted() {
        return trackedEvicted.get();
    }

    public long unansweredResubmits() {
        return unansweredResubmits.get();
    }

    private static final byte[] ID_KEY = "\"id\":\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] FILL_STATUS = "\"status\":\"FILLED\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PARTIAL_STATUS =
        "\"status\":\"PARTIALLY_FILLED\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] CANCELED_STATUS = "\"status\":\"CANCELED\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] REJECTED_STATUS = "\"status\":\"REJECTED\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** The orderRef of {@code "id":"<epoch>-<orderRef>"}, or -1. ADR-105: 64-bit, bounded by the engine's ceiling. */
    static long feedRef(final byte[] json) {
        final int start = indexOf(json, ID_KEY, 0);
        if (start < 0) {
            return -1;
        }
        int i = start + ID_KEY.length;
        int digitsFrom = i;
        while (i < json.length && json[i] != '"') {
            if (json[i] == '-') {
                digitsFrom = i + 1;
            }
            i++;
        }
        long ref = 0;
        for (int k = digitsFrom; k < i; k++) {
            if (json[k] < '0' || json[k] > '9' || ref > MatchingEngineClusteredService.MAX_ORDER_REF) {
                return -1;
            }
            ref = ref * 10 + (json[k] - '0');
        }
        return i > digitsFrom && ref <= MatchingEngineClusteredService.MAX_ORDER_REF ? ref : -1;
    }

    private static int indexOf(final byte[] hay, final byte[] needle, final int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final int DUPLICATE = finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal();
    /** FR-XOC-06: resubmissions of one order across back-to-back elections before it stays ambiguous. */
    private static final int FAILOVER_RESUBMITS = 2;
    private static final int OUT_OF_SEQUENCE = finos.traderx.ordermatcher.risk.RiskReason.OUT_OF_SEQUENCE.ordinal();
    private static final int STREAM_LIMIT = finos.traderx.ordermatcher.risk.RiskReason.STREAM_LIMIT.ordinal();
    private static final int SESSION_AGED_OUT =
        finos.traderx.ordermatcher.risk.RiskReason.SESSION_AGED_OUT.ordinal();
    private static final finos.traderx.ordermatcher.risk.RiskReason[] REASONS =
        finos.traderx.ordermatcher.risk.RiskReason.values();

    // FIX 4.4 tags read by number, so the mapping needs no generated field classes.
    static final int TAG_EXEC_INST = 18;
    static final int TAG_ORD_TYPE = 40;
    static final int TAG_PRICE = 44;
    static final int TAG_TIME_IN_FORCE = 59;
    static final int TAG_STOP_PX = 99;
    static final int TAG_MAX_FLOOR = 111;
    static final int TAG_PEG_OFFSET_VALUE = 211;
    static final int TAG_PEG_MOVE_TYPE = 835;
    static final int TAG_PEG_OFFSET_TYPE = 836;
    static final int TAG_PEG_LIMIT_TYPE = 837;
    static final int TAG_PEG_ROUND_DIRECTION = 838;
    static final int TAG_PEG_SCOPE = 840;

    /** A replace that is not a plain Price/OrderQty change of a limit-shaped order. */
    static boolean isTypedMessage(final quickfix.FieldMap m) {
        try {
            final char ordType = m.isSetField(TAG_ORD_TYPE) ? m.getChar(TAG_ORD_TYPE) : '2';
            return ordType != '2' || m.isSetField(TAG_MAX_FLOOR) || m.isSetField(TAG_STOP_PX)
                || m.isSetField(TAG_EXEC_INST);
        } catch (final FieldNotFound e) {
            return false;
        }
    }

    /**
     * YU18 (FR-OT04): FIX 4.4 order shape to an untyped limit/market price (a {@code Long}, sent on
     * template 1 exactly as before), a typed order, or a refusal Text. An OrdType 1 or 2 message
     * with no TimeInForce, MaxFloor or ExecInst stays UNTYPED so existing FIX flow is byte-for-byte
     * unchanged (NFR-OT05). Peg offsets in price units are accepted only when zero: the grid
     * belongs to the engine, so a non-zero offset must arrive in ticks (PegOffsetType=2); a
     * trailing amount arrives in price (0) or basis points (1).
     */
    static Object mapOrder(final quickfix.FieldMap m, final byte side, final int qty, final boolean replace) {
        try {
            final char ordType = m.isSetField(TAG_ORD_TYPE) ? m.getChar(TAG_ORD_TYPE) : '2';
            final boolean hasTif = m.isSetField(TAG_TIME_IN_FORCE);
            byte tif = OrderTypes.TIF_NONE;
            if (hasTif) {
                switch (m.getChar(TAG_TIME_IN_FORCE)) {
                    case '0' -> tif = OrderTypes.DAY;
                    case '1' -> tif = OrderTypes.GTC;
                    case '3' -> tif = OrderTypes.IOC;
                    case '4' -> tif = OrderTypes.FOK;
                    default -> { return "TimeInForce(59) not supported"; }
                }
            }
            final long price = m.isSetField(TAG_PRICE) ? Math.round(m.getDouble(TAG_PRICE) * 1_000_000d) : 0L;
            final String execInst = m.isSetField(TAG_EXEC_INST) ? m.getString(TAG_EXEC_INST).trim() : "";
            byte type;
            int displayQty = 0;
            byte pegRef = 0;
            int pegOffset = 0;
            byte trailMode = 0;
            long trailValue = 0L;
            switch (ordType) {
                case '1' -> {
                    if (m.isSetField(TAG_PRICE)) {
                        return "Price(44) not allowed on a market order";
                    }
                    if (!hasTif && execInst.isEmpty() && !replace) {
                        return Long.valueOf(0L);   // untyped market, exactly as REST limitPrice 0
                    }
                    type = OrderTypes.MARKET;
                }
                case '2' -> {
                    if (m.isSetField(TAG_MAX_FLOOR)) {
                        type = OrderTypes.ICEBERG;
                    } else if (!hasTif && execInst.isEmpty() && !replace) {
                        if (price <= 0L) {
                            return "Price(44) required on a limit order";
                        }
                        return Long.valueOf(price);   // untyped limit: today's path
                    } else {
                        type = OrderTypes.LIMIT;
                    }
                }
                case '3' -> type = OrderTypes.STOP;
                case '4' -> type = OrderTypes.STOP_LIMIT;
                case 'P' -> {
                    final String err = pegFieldsError(m);
                    if (err != null) {
                        return err;
                    }
                    final int offsetType = m.isSetField(TAG_PEG_OFFSET_TYPE) ? m.getInt(TAG_PEG_OFFSET_TYPE) : 0;
                    final boolean hasOffset = m.isSetField(TAG_PEG_OFFSET_VALUE);
                    if ("R".equals(execInst) || "M".equals(execInst)) {
                        type = OrderTypes.PEGGED;
                        pegRef = "R".equals(execInst) ? OrderTypes.PEG_PRIMARY : OrderTypes.PEG_MIDPOINT;
                        final long ticks = hasOffset ? exactTag(m, TAG_PEG_OFFSET_VALUE, 0) : 0L;
                        if (offsetType == 2) {
                            if (ticks == OrderTypes.NOT_EXACT) {
                                return "PegOffsetValue(211) must be a whole number of ticks";
                            }
                            pegOffset = OrderTypes.saturatedInt(ticks);
                        } else if (offsetType != 0 || ticks != 0L) {
                            return "peg offset must be in ticks: PegOffsetType(836)=2";
                        }
                    } else if ("a".equals(execInst)) {
                        type = OrderTypes.TRAILING_STOP;
                        // FIX signs the offset by direction; the side already fixes it, so |value|.
                        if (offsetType == 0) {
                            trailMode = OrderTypes.TRAIL_AMOUNT;
                            trailValue = hasOffset ? exactTag(m, TAG_PEG_OFFSET_VALUE, 6) : 0L;
                        } else if (offsetType == 1) {
                            trailMode = OrderTypes.TRAIL_BPS;
                            trailValue = hasOffset ? exactTag(m, TAG_PEG_OFFSET_VALUE, 0) : 0L;
                        } else {
                            return "trailing stop offset must be price (836=0) or basis points (836=1)";
                        }
                    } else if ("P".equals(execInst)) {
                        return "market peg (ExecInst P) not supported: pegs are passive (local book only)";
                    } else {
                        return "OrdType P requires exactly one ExecInst(18) of R, M or a";
                    }
                }
                default -> {
                    return "OrdType(40) not supported";
                }
            }
            if (ordType != 'P' && !execInst.isEmpty()) {
                return "ExecInst(18) not supported on this OrdType";
            }
            // Typed from here on (review I3): values exactly as sent, and presence judged per field.
            // Untyped orders returned above with today's rounding, byte for byte (NFR-OT05).
            if (trailValue == OrderTypes.NOT_EXACT) {
                return trailMode == OrderTypes.TRAIL_BPS
                    ? "PegOffsetValue(211) must be a whole number of basis points"
                    : "PegOffsetValue(211) allows at most 6 decimal places";
            }
            trailValue = Math.abs(trailValue);
            final long exactQty = m.isSetField(OrderQty.FIELD) ? exactTag(m, OrderQty.FIELD, 0) : 0L;
            if (exactQty == OrderTypes.NOT_EXACT) {
                return "OrderQty(38) must be a whole number";
            }
            if (exactQty > Integer.MAX_VALUE) {
                return OrderTypes.reasonText(OrderTypes.BAD_QUANTITY);   // the int cast saturates
            }
            final boolean pegTags = m.isSetField(TAG_PEG_OFFSET_VALUE) || m.isSetField(TAG_PEG_OFFSET_TYPE);
            final int present = (m.isSetField(TAG_PRICE) ? OrderTypes.F_LIMIT : 0)
                | (m.isSetField(TAG_STOP_PX) ? OrderTypes.F_STOP : 0)
                | (m.isSetField(TAG_MAX_FLOOR) ? OrderTypes.F_DISPLAY : 0)
                | (type == OrderTypes.TRAILING_STOP ? (pegTags ? OrderTypes.F_TRAIL : 0)
                    : pegRef != 0 || pegTags ? OrderTypes.F_PEG : 0);
            if (!OrderTypes.fieldsAllowed(type, present)) {
                return OrderTypes.reasonText(OrderTypes.FIELD_NOT_ALLOWED);
            }
            final long exactPrice = m.isSetField(TAG_PRICE) ? exactTag(m, TAG_PRICE, 6) : 0L;
            final long exactStop = m.isSetField(TAG_STOP_PX) ? exactTag(m, TAG_STOP_PX, 6) : 0L;
            if (exactPrice == OrderTypes.NOT_EXACT || exactStop == OrderTypes.NOT_EXACT) {
                return "Price(44) and StopPx(99) allow at most 6 decimal places";
            }
            if (m.isSetField(TAG_MAX_FLOOR)) {
                final long floor = exactTag(m, TAG_MAX_FLOOR, 0);
                if (floor == OrderTypes.NOT_EXACT) {
                    return "MaxFloor(111) must be a whole number";
                }
                displayQty = OrderTypes.saturatedInt(floor);
            }
            // Sent as 0 is a bad price, not a missing one (as REST): -1 keeps it "present".
            final long typedPrice = m.isSetField(TAG_PRICE) && exactPrice == 0L ? -1L : exactPrice;
            final long typedStop = m.isSetField(TAG_STOP_PX) && exactStop == 0L ? -1L : exactStop;
            final byte resolvedTif = tif != OrderTypes.TIF_NONE ? tif
                : replace ? OrderTypes.TIF_NONE : OrderTypes.defaultTif(type);
            // A replace with no TimeInForce keeps the order's own; validate against the default.
            final int code = OrderTypes.validate(type,
                resolvedTif == OrderTypes.TIF_NONE ? OrderTypes.defaultTif(type) : resolvedTif,
                side, qty, typedPrice, typedStop, displayQty, pegRef, pegOffset, trailMode, trailValue);
            if (code != OrderTypes.OK) {
                return OrderTypes.reasonText(code);
            }
            return new OrderSubmitter.TypedOrder(type, resolvedTif, typedPrice, typedStop, displayQty, pegRef,
                pegOffset, trailMode, trailValue);
        } catch (final FieldNotFound | quickfix.FieldException | NumberFormatException e) {
            return "malformed order fields";
        }
    }

    /** A tag's exact decimal value x 10^places, or {@link OrderTypes#NOT_EXACT} (review I3). */
    private static long exactTag(final quickfix.FieldMap m, final int tag, final int places) throws FieldNotFound {
        return OrderTypes.exactScaled(new java.math.BigDecimal(m.getString(tag).trim()), places);
    }

    /** FR-OT04 refusals for the PegInstructions component: local scope, floating, passive only. */
    private static String pegFieldsError(final quickfix.FieldMap m) throws FieldNotFound {
        if (m.isSetField(TAG_PEG_SCOPE) && m.getInt(TAG_PEG_SCOPE) != 1) {
            return "PegScope(840) must be 1 (local): this venue has no national or global reference";
        }
        if (m.isSetField(TAG_PEG_MOVE_TYPE) && m.getInt(TAG_PEG_MOVE_TYPE) != 0) {
            return "PegMoveType(835) fixed not supported";
        }
        if (m.isSetField(TAG_PEG_ROUND_DIRECTION) && m.getInt(TAG_PEG_ROUND_DIRECTION) != 2) {
            return "PegRoundDirection(838) must be 2 (more passive)";
        }
        if (m.isSetField(TAG_PEG_LIMIT_TYPE)) {
            return "PegLimitType(837) not supported";
        }
        if (m.isSetField(TAG_PEG_OFFSET_TYPE) && m.getInt(TAG_PEG_OFFSET_TYPE) == 3) {
            return "PegOffsetType(836)=3 (price tier) not supported";
        }
        return null;
    }
}
