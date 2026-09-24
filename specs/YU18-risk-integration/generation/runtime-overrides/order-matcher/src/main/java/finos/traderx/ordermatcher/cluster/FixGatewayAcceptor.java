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
 * Ephemeral by design (TD-AC01): a {@link MemoryStoreFactory} holds session state, so gateway
 * loss drops sessions to ordinary reconnect while cluster order state is unaffected. Deferred
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

    /** No status source: order status (H/AF) is answered "unavailable". Used by deploys that wire only
     *  order entry, and by the failover-survival test whose concern is the submitter seam alone. */
    public FixGatewayAcceptor(final OrderSubmitter submitter, final int port, final String serverCompId,
                              final int defaultAccountId, final List<String> compIds) {
        this(submitter, null, port, serverCompId, defaultAccountId, compIds);
    }

    public FixGatewayAcceptor(final OrderSubmitter submitter, final OrderStatusSource statusSource,
                              final int port, final String serverCompId,
                              final int defaultAccountId, final List<String> compIds) {
        this.submitter = submitter;
        this.statusSource = statusSource;
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
                } else if ("G".equals(msgType)) {
                    onOrderCancelReplaceRequest((OrderCancelReplaceRequest) message, sessionId);
                } else if ("H".equals(msgType)) {
                    onOrderStatusRequest((OrderStatusRequest) message, sessionId);
                } else if ("AF".equals(msgType)) {
                    onOrderMassStatusRequest((OrderMassStatusRequest) message, sessionId);
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
            final int accountId = order.isSetField(quickfix.field.Account.FIELD)
                ? Integer.parseInt(order.getString(quickfix.field.Account.FIELD)) : defaultAccountId;

            // YU18 (FR-OT04): every NewOrderSingle is answered. F1 was a Price-less market order
            // throwing FieldNotFound into the swallow in fromApp; the mapping reads each field only
            // where the type requires it, and a refusal is an ExecutionReport, never silence.
            final Object mapped = mapOrder(order, side == 'S' ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY, qty, false);
            if (mapped instanceof String error) {
                sendOrderReject(sessionId, clOrdId, ticker, side, qty, error);
                return;
            }
            final OrderSubmitter.ExecResult result = mapped instanceof OrderSubmitter.TypedOrder typed
                ? submitter.submitTypedOrder(qualify(sessionId, clOrdId), accountId, ticker, side, qty, typed)
                : submitter.submitOrder(qualify(sessionId, clOrdId), accountId, ticker, side, qty, (Long) mapped);
            if (result == null) {
                // Post-publish ambiguity: no reject may be sent; the counterparty reconciles.
                log.warn("FIX order {} ambiguous (no committed ack)", clOrdId);
                return;
            }
            if (result.accepted()) {
                refByClOrdId.put(qualify(sessionId, clOrdId), result.orderRef());
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

            final int orderRef = resolveOrderRef(cancel, qualify(sessionId, origClOrdId));
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

            final OrderSubmitter.ExecResult result = typedReplace instanceof OrderSubmitter.TypedOrder typed
                ? submitter.submitTypedReplace(orderRef, qualify(sessionId, clOrdId), qty, typed)
                : submitter.submitReplace(orderRef, qualify(sessionId, clOrdId), qty, limitPxTicks);
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
            final ExecutionReport report = new ExecutionReport(
                new OrderID("ord-" + orderRef),
                new ExecID(String.valueOf(execSeq.incrementAndGet())),
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
                statusSource == null ? null : statusSource.orders(accountOf(req), true);
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
                statusSource == null ? null : statusSource.orders(accountOf(req), false);
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
            return statusReport(v.orderRef(), side, v.quantity(), v.remaining(),
                ordStatusOfName(v.status()), v.security());
        }

        /** ExecutionReport(150=OrderStatus) skeleton; caller adds ClOrdID and/or MassStatusReqID. */
        private ExecutionReport statusReport(final int orderRef, final char side, final int qty,
                                             final int remaining, final char ordStatus,
                                             final String security) {
            final ExecutionReport report = new ExecutionReport(
                new OrderID(orderRef > 0 ? "ord-" + orderRef : "NONE"),
                new ExecID(String.valueOf(execSeq.incrementAndGet())),
                new ExecType(ExecType.ORDER_STATUS),
                new OrdStatus(ordStatus),
                new Side(side == 'S' ? Side.SELL : Side.BUY),
                new LeavesQty(remaining),
                new CumQty(qty - remaining),
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
            try {
                Session.sendToTarget(message, sessionId);
            } catch (final quickfix.SessionNotFound e) {
                log.warn("FIX session gone before response for {}", clOrdId);
            }
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
                new ExecID(String.valueOf(execSeq.incrementAndGet())),
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

        /** YU18 (FR-OT03/04): a boundary refusal — nothing was sequenced. */
        private void sendOrderReject(final SessionID sessionId, final String clOrdId, final String ticker,
                                     final char side, final int qty, final String text) {
            final ExecutionReport report = new ExecutionReport(
                new OrderID("NONE"),
                new ExecID(String.valueOf(execSeq.incrementAndGet())),
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
