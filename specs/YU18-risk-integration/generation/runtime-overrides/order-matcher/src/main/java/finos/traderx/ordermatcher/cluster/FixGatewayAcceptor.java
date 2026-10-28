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
    private final Map<String, Integer> refByClOrdId = Collections.synchronizedMap(
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Integer> eldest) {
                return size() > CLORDID_MAP_CAPACITY;
            }
        });

    /**
     * FR-FIX05: orderRef → the FIX order a feed fill belongs to. Bounded like the map above, and
     * an entry leaves on its terminal update (filled, canceled, rejected). ponytail: an order
     * evicted while still working gets no further fill reports; it stays answerable by H.
     */
    private static final int TRACKED_CAPACITY = 65_536;
    private final Map<Integer, Tracked> tracked = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Integer, Tracked> eldest) {
            return size() > TRACKED_CAPACITY;
        }
    };

    /**
     * The feed can beat the direct ack: the leader publishes both from one apply, and the ack
     * still has to complete a future and wake the FIX thread before the order is tracked. Fill
     * updates for untracked refs are kept here, and {@link #track} replays any for its ref.
     * ponytail: the last 4,096 fills on the venue; at ~400k fills/s that is a 10 ms window, which
     * an ack-to-track gap of microseconds sits well inside. Raise it if a gap ever outlasts it.
     */
    private static final int EARLY_CAPACITY = 4_096;
    private final int[] earlyRef = new int[EARLY_CAPACITY];
    private final byte[][] earlyMsg = new byte[EARLY_CAPACITY][];
    private int earlyNext;
    /** One lock for {@link #tracked}, the early ring and each {@link Tracked}'s counters, so a
     *  feed update and the registration of its order can never miss each other. */
    private final Object feedLock = new Object();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static final class Tracked {
        final SessionID session;
        final String symbol;
        final char side;
        String clOrdId;
        String epoch;
        int cumQty;
        double notional;

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
        // ENGINE-REVIEW S3: TLS on the session when a keystore is configured (QuickFIX/J native SSL).
        final String keystore = System.getenv("FIX_SSL_KEYSTORE");
        if (keystore != null && !keystore.isBlank()) {
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
        if (persistent) {
            sessionStore.onAcquired = this::adopt;
            sessionStore.onLost = sid -> {
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
        acceptor = new ThreadedSocketAcceptor(new App(),
            persistent ? sessionStore : new MemoryStoreFactory(), settings,
            RedactingLog::new, new DefaultMessageFactory());
        acceptor.start();
        log.info("FIX gateway acceptor on :{} for {} CompID(s), sessions {}", port, compIds.size(),
            persistent ? "in JetStream (ADR-086)" : "in memory");
    }

    public void stop() {
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
     */
    private void adopt(final SessionID sid, final List<String> sent) {
        final Map<Integer, Message> last = new LinkedHashMap<>();
        for (final String raw : sent) {
            try {
                final Message m = new Message();
                m.fromString(raw, null, false);
                if (!"8".equals(m.getHeader().getString(35)) || !m.isSetField(ClOrdID.FIELD)
                    || !m.isSetField(OrderID.FIELD) || !m.getString(OrderID.FIELD).startsWith("ord-")
                    || m.getChar(ExecType.FIELD) == ExecType.ORDER_STATUS) {
                    continue;   // a status answer reports the read model; it tells the session no fill
                }
                final int ref = Integer.parseInt(m.getString(OrderID.FIELD).substring(4));
                final String key = sid.getTargetCompID() + '\001' + m.getString(ClOrdID.FIELD);
                if (m.getChar(ExecType.FIELD) == ExecType.CANCELED) {
                    refByClOrdId.remove(key);
                } else {
                    refByClOrdId.put(key, ref);
                }
                last.remove(ref);   // re-insert: iteration follows each order's latest report
                last.put(ref, m);
            } catch (final Exception e) {
                // not an order report; nothing to adopt
            }
        }
        int open = 0;
        synchronized (feedLock) {
            for (final Map.Entry<Integer, Message> e : last.entrySet()) {
                try {
                    final Message m = e.getValue();
                    final char status = m.getChar(OrdStatus.FIELD);
                    Tracked t = tracked.get(e.getKey());
                    if (status != OrdStatus.NEW && status != OrdStatus.PARTIALLY_FILLED) {
                        if (t != null && t.session.equals(sid)) {
                            tracked.remove(e.getKey());
                        }
                        continue;
                    }
                    if (t == null) {
                        t = new Tracked(sid, m.getString(ClOrdID.FIELD),
                            m.isSetField(Symbol.FIELD) ? m.getString(Symbol.FIELD) : "",
                            m.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B');
                        tracked.put(e.getKey(), t);
                    } else if (!t.session.equals(sid)) {
                        continue;
                    }
                    t.clOrdId = m.getString(ClOrdID.FIELD);
                    t.cumQty = (int) m.getDouble(CumQty.FIELD);
                    t.notional = t.cumQty * (m.isSetField(AvgPx.FIELD) ? m.getDouble(AvgPx.FIELD) : 0d);
                    replayEarly(e.getKey(), t);
                    open++;
                } catch (final FieldNotFound ignore) {
                    // a report without Side/CumQty/OrdStatus is not one this acceptor wrote
                }
            }
        }
        log.info("FIX session {}: adopted {} order(s), {} open, from {} stored message(s)",
            sid, last.size(), open, sent.size());
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
                    if (!compIds.contains(compId)) {
                        throw new RejectLogon("unknown CompID");
                    }
                    final String password = message.isSetField(quickfix.field.Password.FIELD)
                        ? message.getString(quickfix.field.Password.FIELD) : null;
                    if (!auth.logonAllowed(compId, password)) {
                        throw new RejectLogon("invalid credentials");
                    }
                    // ADR-086: one gateway per live session. Wait briefly for a previous owner (a
                    // client that just dropped) to let go; the lease is taken only after the password.
                    if (sessionStore != null && !sessionStore.available()) {
                        throw new RejectLogon("FIX session store unavailable; retry later");
                    }
                    if (sessionStore != null && !acquire(sessionId)) {
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

        private boolean acquire(final SessionID sessionId) {
            try {
                return sessionStore.acquireForLogon(sessionId, 5_000);
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
            try {
                final String msgType = message.getHeader().getString(35);
                if ("D".equals(msgType)) {
                    onNewOrderSingle((NewOrderSingle) message, sessionId);
                } else if ("F".equals(msgType)) {
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
                if (sessionStore != null) {
                    sessionStore.requestEnded(sessionId);
                }
            }
        }

        private void onNewOrderSingle(final NewOrderSingle order, final SessionID sessionId)
            throws FieldNotFound {
            final String clOrdId = order.getString(ClOrdID.FIELD);
            final String ticker = order.getString(Symbol.FIELD).trim().toUpperCase();
            final char side = order.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
            final int qty = (int) order.getDouble(OrderQty.FIELD);
            final int accountId = accountOf(sessionId, order);
            if (accountId < 0) {   // S3: Account(1) outside this session's binding
                sendOrderReject(sessionId, clOrdId, ticker, side, qty, "account not permitted for this session");
                return;
            }

            // YU18 (FR-OT04): every NewOrderSingle is answered. F1 was a Price-less market order
            // throwing FieldNotFound into the swallow in fromApp; the mapping reads each field only
            // where the type requires it, and a refusal is an ExecutionReport, never silence.
            final Object mapped = mapOrder(order, side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY, qty, false);
            if (mapped instanceof String error) {
                sendOrderReject(sessionId, clOrdId, ticker, side, qty, error);
                return;
            }
            final OrderSubmitter.ExecResult result;
            if (sessionStore != null && sessionStore.sequenceOrders) {
                try {
                    result = submitSequenced(sessionId, clOrdId, accountId, ticker, side, qty, mapped, order.toString());
                } catch (final java.io.IOException e) {
                    // FR-XOD-12: not stored, so not submitted; the store has already ended the session
                    log.warn("FIX order {} not stored, not submitted: {}", clOrdId, e.toString());
                    return;
                }
            } else {
                result = mapped instanceof OrderSubmitter.TypedOrder typed
                    ? submitter.submitTypedOrder(qualify(sessionId, clOrdId), accountId, ticker, side, qty, typed)
                    : submitter.submitOrder(qualify(sessionId, clOrdId), accountId, ticker, side, qty, (Long) mapped);
            }
            answer(sessionId, clOrdId, accountId, ticker, side, qty, result);
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
            if (result.accepted()) {
                refByClOrdId.put(qualify(sessionId, clOrdId), result.orderRef());
            }
            sendReport(sessionId, clOrdId, ticker, side, qty, result);
            // After the New, so a fill can never be reported ahead of the order it fills. An ack that
            // is already terminal (filled by its own ack, FOK/IOC canceled) has nothing left to report.
            if (result.accepted() && result.kind() != OutputEvent.KIND_ORDER_CANCELED
                    && result.kind() != OutputEvent.KIND_ORDER_FILLED) {
                track(result.orderRef(), sessionId, clOrdId, ticker, side);
            }
        }

        /**
         * ADR-090 (FR-XOD-12): a NewOrderSingle on this session's gateway-sequenced stream. A ClOrdID already stored
         * today is resubmitted at its stored sequence, so the engine answers it with the original order; a new one is
         * stored first (create-if-absent under the lease) and only then submitted. OUT_OF_SEQUENCE means an earlier
         * stored order never reached the engine (its gateway died between store and submit): those go first, in
         * order, each answered, and then this one again.
         */
        private OrderSubmitter.ExecResult submitSequenced(final SessionID sid, final String clOrdId, final int accountId,
                                                          final String ticker, final char side, final int qty,
                                                          final Object mapped, final String raw) throws java.io.IOException {
            final long[] stored = sessionStore.orderSeq(sid, clOrdId);
            final long seq = stored != null && stored[0] == accountId ? stored[1]
                : sessionStore.recordOrder(sid, accountId, clOrdId, raw);
            OrderSubmitter.ExecResult r = submitAt(sid, clOrdId, accountId, ticker, side, qty, mapped, seq);
            if (r != null && !r.accepted() && r.riskReason() == OUT_OF_SEQUENCE) {
                for (long s = Integer.toUnsignedLong(r.orderRef()); s >= 1 && s < seq; s++) {
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
            return mapped instanceof OrderSubmitter.TypedOrder typed
                ? submitter.submitSequencedOrder(qualify(sid, clOrdId), accountId, ticker, side, qty, 0L, typed, stream, seq)
                : submitter.submitSequencedOrder(qualify(sid, clOrdId), accountId, ticker, side, qty, (Long) mapped, null,
                    stream, seq);
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

            final int orderRef = resolveOrderRef(cancel, qualify(sessionId, origClOrdId));
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
            final OrderSubmitter.ExecResult result = submitter.submitCancel(orderRef, account);
            if (result == null) {
                // Post-publish ambiguity: the cancel may yet apply, so no reject may be sent.
                log.warn("FIX cancel of ord-{} ambiguous (no committed ack)", orderRef);
                return;
            }
            if (result.accepted()) {
                sendCancelReport(sessionId, clOrdId, origClOrdId, ticker, side, orderRef, cancel);
                return;
            }
            final boolean unknown = result.kind() == OutputEvent.KIND_ORDER_NOT_FOUND;
            sendCancelReject(sessionId, unknown ? "NONE" : "ord-" + orderRef, clOrdId, origClOrdId,
                unknown ? OrdStatus.REJECTED : ordStatusOf(result.kind()),
                unknown ? CxlRejReason.UNKNOWN_ORDER : CxlRejReason.TOO_LATE_TO_CANCEL,
                unknown ? "no such order" : "order already terminal");
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

            final int orderRef = resolveOrderRef(replace, qualify(sessionId, origClOrdId));
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
                final boolean unknown = result.kind() == OutputEvent.KIND_ORDER_NOT_FOUND;
                sendCancelReject(sessionId, unknown ? "NONE" : "ord-" + orderRef, clOrdId, origClOrdId,
                    unknown ? OrdStatus.REJECTED : ordStatusOf(result.kind()),
                    unknown ? CxlRejReason.UNKNOWN_ORDER : CxlRejReason.TOO_LATE_TO_CANCEL,
                    unknown ? "no such order" : "replace rejected; order unchanged",
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
            final int orderRef = resolveOrderRef(req, qualify(sessionId, clOrdId));

            final List<OrderStatusSource.OrderView> orders =
                statusSource == null || accountOf(sessionId, req) < 0 ? null   // S3: own accounts only
                    : statusSource.orders(accountOf(sessionId, req), true);
            if (orders == null) {
                sendStatusReject(sessionId, clOrdId, null, side,
                    "order status unavailable (no read model configured)");
                return;
            }
            OrderStatusSource.OrderView match = null;
            for (final OrderStatusSource.OrderView v : orders) {
                if (v.orderRef() == orderRef) {
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

        private ExecutionReport statusReport(final int orderRef, final char side, final int qty,
                                             final int remaining, final char ordStatus,
                                             final String security) {
            return statusReport(orderRef, side, qty, remaining, qty - remaining, ordStatus, security);
        }

        /** ExecutionReport(150=OrderStatus) skeleton; caller adds ClOrdID and/or MassStatusReqID. */
        private ExecutionReport statusReport(final int orderRef, final char side, final int qty,
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

        private int resolveOrderRef(final quickfix.Message request, final String qualifiedOrigClOrdId)
            throws FieldNotFound {
            if (request.isSetField(OrderID.FIELD)) {
                final String orderId = request.getString(OrderID.FIELD);
                if (orderId.startsWith("ord-")) {
                    try {
                        return Integer.parseInt(orderId.substring(4));
                    } catch (final NumberFormatException ignore) {
                        // fall through to the OrigClOrdID map
                    }
                }
            }
            final Integer mapped = refByClOrdId.get(qualifiedOrigClOrdId);
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
                                      final int orderRef, final OrderCancelRequest cancel) {
            // FR-FIX05: a cancel ends the order, not its fills. What this gateway reported filled
            // (tracked, or rebuilt from the session store on adoption) stands in the Canceled report.
            int cum = 0;
            double notional = 0;
            synchronized (feedLock) {
                final Tracked t = tracked.remove(orderRef);
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
         * comes from the read model when one is configured; without one only "terminal" is known (DONE_FOR_DAY).
         */
        private void sendRestatement(final SessionID sessionId, final String clOrdId, final int accountId,
                                     final String ticker, final char side, final int qty, final int orderRef) {
            OrderStatusSource.OrderView found = null;
            final List<OrderStatusSource.OrderView> orders =
                statusSource == null ? null : statusSource.orders(accountId, true);
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
        final int ref = feedRef(json);
        if (ref <= 0) {
            return;
        }
        synchronized (feedLock) {
            final Tracked t = tracked.get(ref);
            if (t != null) {
                applyUpdate(ref, t, json);
            } else if (indexOf(json, FILL_STATUS, 0) >= 0 || indexOf(json, PARTIAL_STATUS, 0) >= 0) {
                earlyRef[earlyNext] = ref;
                earlyMsg[earlyNext] = json;
                earlyNext = (earlyNext + 1) % EARLY_CAPACITY;
            }
        }
    }

    private void track(final int ref, final SessionID session, final String clOrdId, final String symbol,
                       final char side) {
        synchronized (feedLock) {
            final Tracked t = new Tracked(session, clOrdId, symbol, side);
            tracked.put(ref, t);
            replayEarly(ref, t);
        }
    }

    /** Under {@link #feedLock}: apply the early ring's fills for {@code ref}, oldest first. */
    private void replayEarly(final int ref, final Tracked t) {
        for (int i = 0; i < EARLY_CAPACITY; i++) {
            final int k = (earlyNext + i) % EARLY_CAPACITY;
            if (earlyRef[k] == ref && earlyMsg[k] != null) {
                final byte[] m = earlyMsg[k];
                earlyMsg[k] = null;
                applyUpdate(ref, t, m);
            }
        }
    }

    /** Under {@link #feedLock}. Reports the quantity this update adds over what the session has
     *  been told; a NEW, a redelivery or a stale state adds none, and a missed message folds into
     *  the next one's LastQty, so CumQty is always the engine's. */
    private void applyUpdate(final int ref, final Tracked t, final byte[] json) {
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
            if ("CANCELED".equals(status) || "REJECTED".equals(status)) {
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
        final Session session = sessionOf(t.session);
        if (session == null) {
            log.warn("FIX session gone before fill report for {}", t.clOrdId);
        } else if (session.send(fillReport(t, ref, qty, cum, lastQty, lastPx))) {   // false: off-lease (ADR-086)
            fillReports.incrementAndGet();
        }
        if (leaves == 0) {
            tracked.remove(ref);
        }
    }

    /** The unsolicited fill ExecutionReport (ADR-084 §3); {@code t.notional} already includes it. */
    private ExecutionReport fillReport(final Tracked t, final int ref, final int qty, final int cum,
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
    private final AtomicLong fillGaps = new AtomicLong();

    /** Fill reports sent, and fill gaps ALERTed (gateway {@code /metrics}). */
    public long fillReports() {
        return fillReports.get();
    }

    public long fillGaps() {
        return fillGaps.get();
    }

    private static final byte[] ID_KEY = "\"id\":\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] FILL_STATUS = "\"status\":\"FILLED\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PARTIAL_STATUS =
        "\"status\":\"PARTIALLY_FILLED\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** The orderRef of {@code "id":"<epoch>-<orderRef>"}, or -1. */
    static int feedRef(final byte[] json) {
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
            if (json[k] < '0' || json[k] > '9' || ref > Integer.MAX_VALUE) {
                return -1;
            }
            ref = ref * 10 + (json[k] - '0');
        }
        return i > digitsFrom && ref <= Integer.MAX_VALUE ? (int) ref : -1;
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
    private static final int OUT_OF_SEQUENCE = finos.traderx.ordermatcher.risk.RiskReason.OUT_OF_SEQUENCE.ordinal();
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
