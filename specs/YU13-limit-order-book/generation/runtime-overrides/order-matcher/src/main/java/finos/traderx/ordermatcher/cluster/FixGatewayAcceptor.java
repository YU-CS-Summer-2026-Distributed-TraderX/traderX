package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.RejectLogon;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.ThreadedSocketAcceptor;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
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
import quickfix.field.OrigClOrdID;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelRequest;

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
 * Ephemeral by design (TD-AC01): a {@link MemoryStoreFactory} holds session state, so gateway
 * loss drops sessions to ordinary reconnect while cluster order state is unaffected. Deferred
 * (not needed to prove session survival): order status (H), the amortized submit-batch of the
 * parent state's in-process acceptor, and JWT entitlement (risk/entitlement is decided inside the
 * cluster, not at this tier).
 *
 * <p>YU13 adds OrderCancelRequest (F), answered with an ExecutionReport carrying
 * {@code OrdStatus=Canceled} or an OrderCancelReject (9). The cancel verdict itself is made inside
 * the replicated state machine, so every member reaches it identically; this tier only resolves
 * <em>which</em> orderRef the counterparty means.
 */
public final class FixGatewayAcceptor {
    private static final Logger log = LoggerFactory.getLogger(FixGatewayAcceptor.class);

    private final OrderSubmitter submitter;
    private final int port;
    private final String serverCompId;
    private final int defaultAccountId;
    private final List<String> compIds;
    private final AtomicLong execSeq = new AtomicLong();

    /**
     * ClOrdID → orderRef, so a counterparty can cancel by OrigClOrdID (41) alone, as FIX 4.4
     * requires. Bounded LRU: an unbounded map here would be the {@code ordersByRef} trap again —
     * that one stayed invisible until 33M orders. Eviction only costs the OrigClOrdID convenience;
     * OrderID (37) still resolves, because this gateway mints it as {@code ord-<orderRef>}.
     *
     * <p>ponytail: gateway-local and therefore best-effort — a counterparty that reconnects onto a
     * different gateway replica, or comes back after a gateway restart, must cancel by OrderID.
     * The durable cross-gateway version is the engine's replicated clientOrderKey table, which is
     * a separate decision (see the ClOrdID-suppression work).
     */
    private static final int CLORDID_MAP_CAPACITY = 65_536;
    private final Map<String, Integer> refByClOrdId = Collections.synchronizedMap(
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Integer> eldest) {
                return size() > CLORDID_MAP_CAPACITY;
            }
        });

    private ThreadedSocketAcceptor acceptor;

    public FixGatewayAcceptor(final OrderSubmitter submitter, final int port, final String serverCompId,
                              final int defaultAccountId, final List<String> compIds) {
        this.submitter = submitter;
        this.port = port;
        this.serverCompId = serverCompId;
        this.defaultAccountId = defaultAccountId;
        this.compIds = compIds;
    }

    public void start() throws Exception {
        final SessionSettings settings = new SessionSettings();
        final Properties defaults = new Properties();
        defaults.setProperty("ConnectionType", "acceptor");
        defaults.setProperty("SocketAcceptPort", String.valueOf(port));
        defaults.setProperty("StartTime", "00:00:00");
        defaults.setProperty("EndTime", "00:00:00");
        defaults.setProperty("HeartBtInt", "30");
        defaults.setProperty("ResetOnLogon", "Y");
        defaults.setProperty("PersistMessages", "N"); // ephemeral (TD-AC01)
        settings.set(defaults);
        for (final String compId : compIds) {
            final SessionID sid = new SessionID("FIX.4.4", serverCompId, compId);
            settings.setString(sid, "BeginString", "FIX.4.4");
        }
        acceptor = new ThreadedSocketAcceptor(new App(), new MemoryStoreFactory(), settings,
            new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        acceptor.start();
        log.info("FIX gateway acceptor on :{} for {} CompID(s)", port, compIds.size());
    }

    public void stop() {
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    private final class App implements Application {
        @Override
        public void onCreate(final SessionID sessionId) { }

        @Override
        public void onLogon(final SessionID sessionId) {
            log.info("FIX logon: {}", sessionId.getTargetCompID());
        }

        @Override
        public void onLogout(final SessionID sessionId) {
            log.info("FIX logout: {}", sessionId.getTargetCompID());
        }

        @Override
        public void toAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void fromAdmin(final Message message, final SessionID sessionId) throws RejectLogon {
            // Accept the logon if the CompID is configured; entitlement is the cluster's job.
            try {
                if ("A".equals(message.getHeader().getString(35))
                    && !compIds.contains(sessionId.getTargetCompID())) {
                    throw new RejectLogon("unknown CompID");
                }
            } catch (final FieldNotFound ignore) {
                // non-logon admin message
            }
        }

        @Override
        public void toApp(final Message message, final SessionID sessionId) { }

        @Override
        public void fromApp(final Message message, final SessionID sessionId) {
            try {
                final String msgType = message.getHeader().getString(35);
                if ("D".equals(msgType)) {
                    onNewOrderSingle((NewOrderSingle) message, sessionId);
                } else if ("F".equals(msgType)) {
                    onOrderCancelRequest((OrderCancelRequest) message, sessionId);
                }
            } catch (final FieldNotFound ignore) {
                // malformed; QuickFIX/J session layer already validated structure
            }
        }

        private void onNewOrderSingle(final NewOrderSingle order, final SessionID sessionId)
            throws FieldNotFound {
            final String clOrdId = order.getString(ClOrdID.FIELD);
            final String ticker = order.getString(Symbol.FIELD).trim().toUpperCase();
            final char side = order.getChar(Side.FIELD) == Side.SELL ? 'S' : 'B';
            final int qty = (int) order.getDouble(OrderQty.FIELD);
            final long limitPxTicks = Math.round(order.getDouble(Price.FIELD) * 1_000_000d);
            final int accountId = order.isSetField(quickfix.field.Account.FIELD)
                ? Integer.parseInt(order.getString(quickfix.field.Account.FIELD)) : defaultAccountId;

            final OrderSubmitter.ExecResult result =
                submitter.submitOrder(clOrdId, accountId, ticker, side, qty, limitPxTicks);
            if (result == null) {
                // Post-publish ambiguity: no reject may be sent; the counterparty reconciles.
                log.warn("FIX order {} ambiguous (no committed ack)", clOrdId);
                return;
            }
            if (result.accepted()) {
                refByClOrdId.put(clOrdId, result.orderRef());
            }
            sendReport(sessionId, clOrdId, ticker, side, qty, result);
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

            final int orderRef = resolveOrderRef(cancel, origClOrdId);
            if (orderRef <= 0) {
                sendCancelReject(sessionId, "NONE", clOrdId, origClOrdId, OrdStatus.REJECTED,
                    CxlRejReason.UNKNOWN_ORDER, "unknown OrigClOrdID; resend with OrderID (37)");
                return;
            }

            final OrderSubmitter.ExecResult result = submitter.submitCancel(orderRef);
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

        private int resolveOrderRef(final OrderCancelRequest cancel, final String origClOrdId)
            throws FieldNotFound {
            if (cancel.isSetField(OrderID.FIELD)) {
                final String orderId = cancel.getString(OrderID.FIELD);
                if (orderId.startsWith("ord-")) {
                    try {
                        return Integer.parseInt(orderId.substring(4));
                    } catch (final NumberFormatException ignore) {
                        // fall through to the OrigClOrdID map
                    }
                }
            }
            final Integer mapped = refByClOrdId.get(origClOrdId);
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
            final ExecutionReport report = new ExecutionReport(
                new OrderID("ord-" + orderRef),
                new ExecID(String.valueOf(execSeq.incrementAndGet())),
                new ExecType(ExecType.CANCELED),
                new OrdStatus(OrdStatus.CANCELED),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(0),
                new CumQty(0),
                new AvgPx(0));
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
            refByClOrdId.remove(origClOrdId);
        }

        private void sendCancelReject(final SessionID sessionId, final String orderId,
                                      final String clOrdId, final String origClOrdId,
                                      final char ordStatus, final int rejReason, final String text) {
            final OrderCancelReject reject = new OrderCancelReject(
                new OrderID(orderId),
                new ClOrdID(clOrdId),
                new OrigClOrdID(origClOrdId),
                new OrdStatus(ordStatus),
                new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST));
            reject.set(new CxlRejReason(rejReason));
            reject.set(new Text(text));
            send(reject, sessionId, clOrdId);
        }

        private void send(final Message message, final SessionID sessionId, final String clOrdId) {
            try {
                Session.sendToTarget(message, sessionId);
            } catch (final quickfix.SessionNotFound e) {
                log.warn("FIX session gone before response for {}", clOrdId);
            }
        }

        private void sendReport(final SessionID sessionId, final String clOrdId, final String ticker,
                                final char side, final int qty, final OrderSubmitter.ExecResult result) {
            final boolean filled = result.kind() == OutputEvent.KIND_ORDER_FILLED;
            final char ordStatus = !result.accepted() ? OrdStatus.REJECTED
                : filled ? OrdStatus.FILLED : OrdStatus.NEW;
            final char execType = !result.accepted() ? ExecType.REJECTED
                : filled ? ExecType.FILL : ExecType.NEW;
            final ExecutionReport report = new ExecutionReport(
                new OrderID(result.accepted() ? "ord-" + result.orderRef() : "NONE"),
                new ExecID(String.valueOf(execSeq.incrementAndGet())),
                new ExecType(execType),
                new OrdStatus(ordStatus),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(filled || !result.accepted() ? 0 : qty),
                new CumQty(filled ? qty : 0),
                new AvgPx(0));
            report.set(new ClOrdID(clOrdId));
            report.set(new Symbol(ticker));
            report.set(new OrderQty(qty));
            if (!result.accepted()) {
                report.set(new Text("rejected by cluster risk"));
            }
            send(report, sessionId, clOrdId);
        }
    }
}
