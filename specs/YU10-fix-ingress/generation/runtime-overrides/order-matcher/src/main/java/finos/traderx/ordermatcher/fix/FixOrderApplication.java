package finos.traderx.ordermatcher.fix;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import quickfix.Application;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QuickFIX/J application callback: session identity, inbound translation (D/F/H), and the
 * admission half of the ExecutionReport flow (FR-FIX02..04, FR-FIX07..08, FR-FIX12).
 *
 * <p>Threading (ADR-034/ADR-037): the session's message thread only validates, pre-checks
 * duplicates, and enqueues NewOrderSingles to the session's bounded submit queue — it never
 * blocks on the ring, so heartbeats and session duties stay live. A per-session SUBMITTER
 * thread drains the queue and sequences the whole drain in ONE {@code createOrderBatch} call —
 * the same amortized ack-block the REST batch path uses, which is where the FIX throughput
 * tier comes from. Cancels and status requests are rare and handled synchronously on the
 * session thread through the same service entry points REST uses (identical risk screen,
 * entitlement, and journal path).
 */
public final class FixOrderApplication implements Application {
    private static final Logger log = LoggerFactory.getLogger(FixOrderApplication.class);

    private static final int SUBMIT_QUEUE_CAPACITY = 4096;
    private static final int MAX_DRAIN = 512;

    private final OrderMatcherService orders;
    private final FixIdentity identity;
    private final ClOrdIdLedger ledger;
    private final FixOrderRegistry registry;
    private final String serverCompId;
    private final Map<SessionID, SessionCtx> sessions = new ConcurrentHashMap<>();

    public FixOrderApplication(OrderMatcherService orders, FixIdentity identity,
                               ClOrdIdLedger ledger, FixOrderRegistry registry, String serverCompId) {
        this.orders = orders;
        this.identity = identity;
        this.ledger = ledger;
        this.registry = registry;
        this.serverCompId = serverCompId;
    }

    private record PendingOrder(String clOrdId, String ticker, char side, int qty, BigDecimal px) { }

    private final class SessionCtx {
        final SessionID sessionId;
        final long sessionKey;
        final int accountId;
        final String bearer;
        final BlockingQueue<PendingOrder> submitQueue = new ArrayBlockingQueue<>(SUBMIT_QUEUE_CAPACITY);
        final Thread submitter;
        volatile boolean live = true;

        SessionCtx(SessionID sessionId, int accountId, String bearer) {
            this.sessionId = sessionId;
            this.sessionKey = ClOrdIdLedger.sessionKey(sessionId.getTargetCompID(), serverCompId);
            this.accountId = accountId;
            this.bearer = bearer;
            this.submitter = new Thread(() -> submitLoop(this),
                "fix-submitter-" + sessionId.getTargetCompID());
            this.submitter.setDaemon(true);
        }
    }

    // ---- session lifecycle -------------------------------------------------------------------

    @Override
    public void onCreate(SessionID sessionId) { }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws RejectLogon {
        try {
            if (!"A".equals(message.getHeader().getString(35))) {
                return;
            }
        } catch (Exception ex) {
            throw new RejectLogon("malformed logon");
        }
        // Fail-closed identity (ADR-036): CompID must be mapped AND Password(554) must carry a
        // JWT entitled to the mapped account. Any miss rejects the logon before a session exists.
        String compId = sessionId.getTargetCompID();   // the counterparty's SenderCompID
        String password;
        try {
            password = message.getString(554);
        } catch (Exception ex) {
            throw new RejectLogon("credentials required");
        }
        FixIdentity.Authenticated auth = identity.authenticate(compId, password);  // throws RejectLogon
        SessionCtx ctx = new SessionCtx(sessionId, auth.accountId(), auth.bearer());
        SessionCtx previous = sessions.put(sessionId, ctx);
        if (previous != null) {
            previous.live = false;
            previous.submitter.interrupt();
        }
        ctx.submitter.start();
        log.info("FIX logon: {} -> account {}", compId, auth.accountId());
    }

    @Override
    public void onLogon(SessionID sessionId) { }

    @Override
    public void onLogout(SessionID sessionId) {
        SessionCtx ctx = sessions.remove(sessionId);
        if (ctx != null) {
            ctx.live = false;
            ctx.submitter.interrupt();
        }
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) { }

    @Override
    public void toApp(Message message, SessionID sessionId) { }

    // ---- inbound application messages ---------------------------------------------------------

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        SessionCtx ctx = sessions.get(sessionId);
        if (ctx == null) {
            return;   // logout raced an in-flight message; the session is gone
        }
        String msgType;
        try {
            msgType = message.getHeader().getString(35);
        } catch (Exception ex) {
            return;
        }
        switch (msgType) {
            case "D" -> onNewOrderSingle(message, ctx);
            case "F" -> onCancelRequest(message, ctx);
            case "H" -> onStatusRequest(message, ctx);
            default -> FixMessages.sendBusinessReject(ctx.sessionId, msgType, null,
                "unsupported message type");
        }
    }

    private void onNewOrderSingle(Message m, SessionCtx ctx) {
        String clOrdId;
        PendingOrder order;
        try {
            clOrdId = m.getString(11);
            char side = m.getChar(54);
            if (side != '1' && side != '2') {
                FixMessages.sendBusinessReject(ctx.sessionId, "D", m.getString(11), "unsupported Side(54)");
                return;
            }
            char ordType = m.isSetField(40) ? m.getChar(40) : '2';
            if (ordType != '2') {
                FixMessages.sendBusinessReject(ctx.sessionId, "D", clOrdId, "limit orders only (OrdType=2)");
                return;
            }
            order = new PendingOrder(clOrdId, m.getString(55).trim().toUpperCase(),
                side, (int) m.getDouble(38), m.getDecimal(44));
        } catch (Exception ex) {
            FixMessages.sendBusinessReject(ctx.sessionId, "D", null, "missing/invalid required field");
            return;
        }
        // Duplicate pre-filter (fast path). The AUTHORITY is the engine's clientOrderKey
        // idempotency (FR-IMRG14): a crash-window retry that slips past this check maps back to
        // the one original order inside the BLP — never a second execution.
        if (ledger.byClOrdId(ctx.sessionKey, clOrdId) != null) {
            FixMessages.sendBusinessReject(ctx.sessionId, "D", clOrdId, "duplicate ClOrdID");
            return;
        }
        if (!ledger.available()) {
            // FR-FIX10: no correlation, no admission.
            FixMessages.sendBusinessReject(ctx.sessionId, "D", clOrdId, "order admission unavailable");
            return;
        }
        if (!ctx.submitQueue.offer(order)) {
            // Outcome (a) of FR-FIX12: pre-publish capacity failure — provably no order exists.
            FixMessages.sendBusinessReject(ctx.sessionId, "D", clOrdId, "session capacity exceeded, retry");
        }
    }

    private void onCancelRequest(Message m, SessionCtx ctx) {
        String clOrdId = null;
        String origClOrdId;
        try {
            clOrdId = m.getString(11);
            origClOrdId = m.getString(41);
        } catch (Exception ex) {
            FixMessages.sendBusinessReject(ctx.sessionId, "F", clOrdId, "missing/invalid required field");
            return;
        }
        ClOrdIdLedger.Entry entry = ledger.byClOrdId(ctx.sessionKey, origClOrdId);
        if (entry == null) {
            FixMessages.sendCancelReject(ctx.sessionId, clOrdId, origClOrdId, "unknown order");
            return;
        }
        try {
            OrderResponse response = orders.cancelOrder(FixMessages.publicOrderId(entry.orderRef()), ctx.bearer);
            FixMessages.sendReportFromResponse(ctx.sessionId, entry.clOrdId(), response, '4');
        } catch (ResponseStatusException ex) {
            FixMessages.sendCancelReject(ctx.sessionId, clOrdId, origClOrdId, ex.getReason());
        } catch (Exception ex) {
            FixMessages.sendCancelReject(ctx.sessionId, clOrdId, origClOrdId, "cancel failed");
        }
    }

    private void onStatusRequest(Message m, SessionCtx ctx) {
        String clOrdId;
        try {
            clOrdId = m.isSetField(41) ? m.getString(41) : m.getString(11);
        } catch (Exception ex) {
            FixMessages.sendBusinessReject(ctx.sessionId, "H", null, "missing/invalid required field");
            return;
        }
        ClOrdIdLedger.Entry entry = ledger.byClOrdId(ctx.sessionKey, clOrdId);
        if (entry == null) {
            FixMessages.sendBusinessReject(ctx.sessionId, "H", clOrdId, "unknown order");
            return;
        }
        try {
            OrderResponse response = orders.getOrder(FixMessages.publicOrderId(entry.orderRef()));
            FixMessages.sendReportFromResponse(ctx.sessionId, entry.clOrdId(), response, 'I');
        } catch (Exception ex) {
            FixMessages.sendBusinessReject(ctx.sessionId, "H", clOrdId, "status lookup failed");
        }
    }

    // ---- the submitter: one amortized sequenced batch per drain --------------------------------

    private void submitLoop(SessionCtx ctx) {
        List<PendingOrder> drain = new ArrayList<>(MAX_DRAIN);
        while (ctx.live) {
            drain.clear();
            try {
                drain.add(ctx.submitQueue.take());
            } catch (InterruptedException ie) {
                return;
            }
            ctx.submitQueue.drainTo(drain, MAX_DRAIN - 1);
            List<OrderCreateRequest> requests = new ArrayList<>(drain.size());
            for (PendingOrder p : drain) {
                requests.add(FixMessages.toCreateRequest(p.clOrdId(), ctx.accountId,
                    ctx.sessionId.getTargetCompID(), p.ticker(), p.side(), p.qty(), p.px()));
            }
            List<OrderResponse> responses;
            try {
                responses = orders.createOrderBatch(requests, ctx.bearer);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().is5xxServerError()) {
                    // Outcome (d) of FR-FIX12: post-publish ambiguity. NO reject may be sent —
                    // the eventual ExecutionReport is the outcome; clients reconcile via H or a
                    // same-ClOrdID retry (engine idempotency answers it deterministically).
                    log.warn("FIX batch outcome ambiguous ({} orders): {}", drain.size(), ex.getReason());
                } else {
                    // Pre-sequencing validation failure: provably nothing published.
                    for (PendingOrder p : drain) {
                        FixMessages.sendBusinessReject(ctx.sessionId, "D", p.clOrdId(), ex.getReason());
                    }
                }
                continue;
            } catch (Exception ex) {
                log.warn("FIX batch outcome ambiguous ({} orders): {}", drain.size(), ex.toString());
                continue;
            }
            for (int i = 0; i < responses.size(); i++) {
                PendingOrder p = drain.get(i);
                OrderResponse r = responses.get(i);
                int orderRef = FixMessages.parseOrderRef(r.getOrderId());
                if (orderRef > 0) {
                    ledger.append(ctx.sessionKey, p.clOrdId(), orderRef);
                    registry.put(orderRef, new FixOrderRegistry.Ctx(ctx.sessionId, ctx.sessionKey,
                        p.clOrdId(), p.ticker(), p.side(), p.qty()));
                }
                // Admission report from the sequenced response: New (possibly already filled —
                // the report carries the actual state) or Rejected with the risk reason.
                FixMessages.sendAdmissionReport(ctx.sessionId, p.clOrdId(), r);
            }
        }
    }
}
