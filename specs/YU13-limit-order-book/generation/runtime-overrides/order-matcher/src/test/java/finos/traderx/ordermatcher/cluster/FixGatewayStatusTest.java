package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LastRptRequested;
import quickfix.field.LeavesQty;
import quickfix.field.MassStatusReqID;
import quickfix.field.MassStatusReqType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderMassStatusRequest;
import quickfix.fix44.OrderStatusRequest;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX order status (H) and mass status (AF), plus the cancel (F) resolve→reject path, over a real
 * QuickFIX/J session — no cluster, no read model. A stub {@link OrderSubmitter} models the sequenced
 * cancel verdict and a stub {@link OrderStatusSource} stands in for the {@code orderbook} read model,
 * so the test exercises the exact FIX translation the acceptor does: OrigClOrdID→orderRef resolution,
 * ExecutionReport(ExecType=OrderStatus) rendering, and the mass-status batch end marker.
 *
 * <p>Falsifiable arm ({@link #cancelOfUnknownOrigClOrdIdRejects}): with no ClOrdID→orderRef mapping
 * the cancel cannot resolve and comes back as an OrderCancelReject — the failure the resolution map
 * exists to prevent.
 */
@Timeout(60)
class FixGatewayStatusTest {
    private FixGatewayAcceptor acceptor;
    private SocketInitiator initiator;

    @AfterEach
    void tearDown() {
        if (initiator != null) {
            initiator.stop();
        }
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    /** Accepts every order with an incrementing ref; a cancel of a live ref succeeds, otherwise it
     *  answers NOT_FOUND — exactly the shape the replicated verdict has at this seam. */
    private static final class StubSubmitter implements OrderSubmitter {
        final AtomicInteger nextRef = new AtomicInteger(1);
        final java.util.Set<Integer> live = ConcurrentHashMap.newKeySet();

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            final int ref = nextRef.getAndIncrement();
            live.add(ref);
            return new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitCancel(final int orderRef) {
            return live.remove(orderRef)
                ? new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED)
                : new ExecResult(false, orderRef, OutputEvent.KIND_ORDER_NOT_FOUND);
        }
    }

    /** Settable read-model stand-in: {@code openOrders}/{@code allOrders} are returned per the
     *  includeTerminal flag; a null list models "read model unavailable". */
    private static final class StubStatus implements OrderStatusSource {
        volatile List<OrderView> openOrders = List.of();
        volatile List<OrderView> allOrders = List.of();

        @Override
        public List<OrderView> orders(final int accountId, final boolean includeTerminal) {
            return includeTerminal ? allOrders : openOrders;
        }
    }

    @Test
    void orderStatusRequestReturnsCurrentState() throws Exception {
        final StubStatus status = new StubStatus();
        final ClientApp app = startStack(new StubSubmitter(), status);

        sendNewOrder(app.sessionId, "c1", "IBM", 10);
        assertNotNull(awaitExec(app, r -> "c1".equals(clOrdId(r)) && execType(r) == ExecType.NEW),
            "no NEW ack for c1");

        // The read model now shows that order partially filled: 4 of 10 done.
        status.allOrders = List.of(new OrderStatusSource.OrderView(1, "Buy", 10, 6,
            "PARTIALLY_FILLED", "IBM"));

        final OrderStatusRequest h = new OrderStatusRequest();
        h.set(new ClOrdID("c1"));
        h.set(new Side(Side.BUY));
        h.set(new Symbol("IBM"));
        h.set(new Account("11"));
        Session.sendToTarget(h, app.sessionId);

        final ExecutionReport status1 = awaitExec(app, r -> execType(r) == ExecType.ORDER_STATUS);
        assertNotNull(status1, "no status report for H");
        assertEquals(OrdStatus.PARTIALLY_FILLED, status1.getChar(OrdStatus.FIELD));
        assertEquals("ord-1", status1.getString(OrderID.FIELD));
        assertEquals(6.0, status1.getDouble(LeavesQty.FIELD));
        assertEquals(4.0, status1.getDouble(CumQty.FIELD));
    }

    @Test
    void massStatusRequestStreamsOpenOrdersThenEndMarker() throws Exception {
        final StubStatus status = new StubStatus();
        status.openOrders = List.of(
            new OrderStatusSource.OrderView(1, "Buy", 10, 10, "NEW", "IBM"),
            new OrderStatusSource.OrderView(2, "Sell", 5, 5, "NEW", "MSFT"));
        final ClientApp app = startStack(new StubSubmitter(), status);

        final OrderMassStatusRequest af = new OrderMassStatusRequest();
        af.set(new MassStatusReqID("m1"));
        af.set(new MassStatusReqType(MassStatusReqType.STATUS_FOR_ALL_ORDERS));
        af.set(new Account("11"));
        Session.sendToTarget(af, app.sessionId);

        // Two reports, both tagged with the request id; exactly one closes the batch.
        assertTrue(awaitCount(app, r -> "m1".equals(massReqId(r)), 2), "expected 2 mass-status reports");
        final List<ExecutionReport> mass = execs(app, r -> "m1".equals(massReqId(r)));
        assertEquals(2, mass.size());
        final long endMarkers = mass.stream().filter(FixGatewayStatusTest::isLast).count();
        assertEquals(1, endMarkers, "exactly one report must carry LastRptRequested=Y");
        assertTrue(isLast(mass.get(mass.size() - 1)), "the LAST report must be the end marker");
        assertEquals(ExecType.ORDER_STATUS, execType(mass.get(0)));
    }

    @Test
    void cancelResolvesByOrigClOrdIdAndConfirms() throws Exception {
        final ClientApp app = startStack(new StubSubmitter(), new StubStatus());

        sendNewOrder(app.sessionId, "c1", "IBM", 10);
        assertNotNull(awaitExec(app, r -> "c1".equals(clOrdId(r)) && execType(r) == ExecType.NEW),
            "no NEW ack for c1");

        final OrderCancelRequest f = new OrderCancelRequest(new OrigClOrdID("c1"), new ClOrdID("x1"),
            new Side(Side.BUY), new TransactTime());
        f.set(new Symbol("IBM"));
        Session.sendToTarget(f, app.sessionId);

        final ExecutionReport canceled = awaitExec(app, r -> execType(r) == ExecType.CANCELED);
        assertNotNull(canceled, "no cancel ExecutionReport");
        assertEquals(OrdStatus.CANCELED, canceled.getChar(OrdStatus.FIELD));
        assertEquals("ord-1", canceled.getString(OrderID.FIELD));
    }

    @Test
    void cancelOfUnknownOrigClOrdIdRejects() throws Exception {
        final ClientApp app = startStack(new StubSubmitter(), new StubStatus());
        assertTrue(app.loggedOn.await(20, TimeUnit.SECONDS), "counterparty did not log on");

        // Never placed: the acceptor has no ClOrdID->orderRef mapping for "ghost", so it cannot resolve.
        final OrderCancelRequest f = new OrderCancelRequest(new OrigClOrdID("ghost"), new ClOrdID("x1"),
            new Side(Side.BUY), new TransactTime());
        f.set(new Symbol("IBM"));
        Session.sendToTarget(f, app.sessionId);

        final OrderCancelReject reject = awaitReject(app);
        assertNotNull(reject, "unknown OrigClOrdID must be an OrderCancelReject");
        assertEquals("ghost", reject.getString(OrigClOrdID.FIELD));
        assertNull(awaitExecQuiet(app, r -> execType(r) == ExecType.CANCELED),
            "an unresolved cancel must never confirm");
    }

    // ----- harness ---------------------------------------------------------------------------

    private ClientApp startStack(final OrderSubmitter submitter, final OrderStatusSource status)
        throws Exception {
        final int port = freePort();
        acceptor = new FixGatewayAcceptor(submitter, status, port, "TRADERX", 11, List.of("CLIENT1"));
        acceptor.start();
        final ClientApp app = new ClientApp();
        initiator = startInitiator(port, app);
        assertTrue(app.loggedOn.await(20, TimeUnit.SECONDS), "counterparty did not log on");
        return app;
    }

    private static boolean isLast(final ExecutionReport r) {
        try {
            return r.isSetField(LastRptRequested.FIELD) && r.getBoolean(LastRptRequested.FIELD);
        } catch (final FieldNotFound e) {
            return false;
        }
    }

    private static char execType(final ExecutionReport r) {
        try {
            return r.getChar(ExecType.FIELD);
        } catch (final FieldNotFound e) {
            return 0;
        }
    }

    private static String clOrdId(final ExecutionReport r) {
        try {
            return r.isSetField(ClOrdID.FIELD) ? r.getString(ClOrdID.FIELD) : null;
        } catch (final FieldNotFound e) {
            return null;
        }
    }

    private static String massReqId(final ExecutionReport r) {
        try {
            return r.isSetField(MassStatusReqID.FIELD) ? r.getString(MassStatusReqID.FIELD) : null;
        } catch (final FieldNotFound e) {
            return null;
        }
    }

    private interface Pred {
        boolean test(ExecutionReport r);
    }

    private static ExecutionReport awaitExec(final ClientApp app, final Pred p) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            final ExecutionReport hit = firstMatch(app, p);
            if (hit != null) {
                return hit;
            }
            Thread.sleep(50);
        }
        return firstMatch(app, p);
    }

    /** Short wait used only to assert absence — a negative check must not burn the full timeout. */
    private static ExecutionReport awaitExecQuiet(final ClientApp app, final Pred p)
        throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            final ExecutionReport hit = firstMatch(app, p);
            if (hit != null) {
                return hit;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static boolean awaitCount(final ClientApp app, final Pred p, final int n)
        throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (execs(app, p).size() >= n) {
                return true;
            }
            Thread.sleep(50);
        }
        return execs(app, p).size() >= n;
    }

    private static OrderCancelReject awaitReject(final ClientApp app) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (!app.rejects.isEmpty()) {
                return app.rejects.get(0);
            }
            Thread.sleep(50);
        }
        return app.rejects.isEmpty() ? null : app.rejects.get(0);
    }

    private static ExecutionReport firstMatch(final ClientApp app, final Pred p) {
        for (final ExecutionReport r : app.execReports) {
            if (p.test(r)) {
                return r;
            }
        }
        return null;
    }

    private static List<ExecutionReport> execs(final ClientApp app, final Pred p) {
        final List<ExecutionReport> out = new ArrayList<>();
        for (final ExecutionReport r : app.execReports) {
            if (p.test(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private SocketInitiator startInitiator(final int port, final ClientApp app) throws Exception {
        final SessionSettings settings = new SessionSettings();
        final Properties d = new Properties();
        d.setProperty("ConnectionType", "initiator");
        d.setProperty("SocketConnectHost", "127.0.0.1");
        d.setProperty("SocketConnectPort", String.valueOf(port));
        d.setProperty("StartTime", "00:00:00");
        d.setProperty("EndTime", "00:00:00");
        d.setProperty("HeartBtInt", "5");
        d.setProperty("ReconnectInterval", "1");
        d.setProperty("PersistMessages", "N");
        settings.set(d);
        final SessionID sid = new SessionID("FIX.4.4", "CLIENT1", "TRADERX");
        settings.setString(sid, "BeginString", "FIX.4.4");
        final SocketInitiator init = new SocketInitiator(app, new MemoryStoreFactory(), settings,
            new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        init.start();
        return init;
    }

    private void sendNewOrder(final SessionID sid, final String clOrdId, final String ticker, final int qty)
        throws Exception {
        final NewOrderSingle order = new NewOrderSingle(new ClOrdID(clOrdId),
            new Side(Side.BUY), new TransactTime(), new OrdType(OrdType.LIMIT));
        order.set(new Symbol(ticker));
        order.set(new OrderQty(qty));
        order.set(new Price(100.0));
        order.set(new Account("11"));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        Session.sendToTarget(order, sid);
    }

    private static final class ClientApp implements Application {
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final List<ExecutionReport> execReports = new CopyOnWriteArrayList<>();
        final List<OrderCancelReject> rejects = new CopyOnWriteArrayList<>();
        volatile SessionID sessionId;

        @Override
        public void onCreate(final SessionID sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onLogon(final SessionID sessionId) {
            this.sessionId = sessionId;
            loggedOn.countDown();
        }

        @Override
        public void onLogout(final SessionID sessionId) { }

        @Override
        public void toAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void fromAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void toApp(final Message message, final SessionID sessionId) { }

        @Override
        public void fromApp(final Message message, final SessionID sessionId) {
            if (message instanceof ExecutionReport report) {
                execReports.add(report);
            } else if (message instanceof OrderCancelReject reject) {
                rejects.add(reject);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
