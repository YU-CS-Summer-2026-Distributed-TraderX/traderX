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
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.util.List;
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
 * (not needed to prove session survival): cancel/status (F/H), the amortized submit-batch of the
 * parent state's in-process acceptor, and JWT entitlement (risk/entitlement is decided inside the
 * cluster, not at this tier).
 */
public final class FixGatewayAcceptor {
    private static final Logger log = LoggerFactory.getLogger(FixGatewayAcceptor.class);

    private final OrderSubmitter submitter;
    private final int port;
    private final String serverCompId;
    private final int defaultAccountId;
    private final List<String> compIds;
    private final AtomicLong execSeq = new AtomicLong();

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
                if ("D".equals(message.getHeader().getString(35))) {
                    onNewOrderSingle((NewOrderSingle) message, sessionId);
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
            sendReport(sessionId, clOrdId, ticker, side, qty, result);
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
            try {
                Session.sendToTarget(report, sessionId);
            } catch (final quickfix.SessionNotFound e) {
                log.warn("FIX session gone before report for {}", clOrdId);
            }
        }
    }
}
