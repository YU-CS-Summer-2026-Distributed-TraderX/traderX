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
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderStatusRequest;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-FIX05 (ADR-084, audit suspect 8): a fill after the direct ack reaches the FIX session that
 * owns the order as ExecutionReport ExecType=F. Two real QuickFIX/J sessions; the stub submitter
 * answers every order ACCEPTED (the engine's direct ack for a new order, fill or no fill), and the
 * feed messages are encoded by the production {@link OrderNatsPublisher}, so the acceptor's byte
 * scan reads exactly what the leader publishes.
 */
@Timeout(60)
class FixGatewayFillReportTest {
    private static final byte NEW = 0, PARTIAL = 1, FILLED = 2;
    private FixGatewayAcceptor acceptor;
    private final List<SocketInitiator> initiators = new CopyOnWriteArrayList<>();
    private final OrderNatsPublisher encoder = new OrderNatsPublisher("", "/orders", "7", null, 16, 1);

    @AfterEach
    void tearDown() {
        initiators.forEach(SocketInitiator::stop);
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    /** Refs 1, 2, ...; {@code beforeAck} runs between "sequenced" and "acked", where the feed can win. */
    private static final class StubSubmitter implements OrderSubmitter {
        final AtomicInteger nextRef = new AtomicInteger(1);
        volatile IntConsumer beforeAck = ref -> { };
        volatile ExecResult answer;   // when set, the engine's answer to every order

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            if (answer != null) {
                return answer;
            }
            final int ref = nextRef.getAndIncrement();
            beforeAck.accept(ref);
            return new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitCancel(final int orderRef) {
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }

    private byte[] feed(final int ref, final byte side, final int qty, final int remaining, final byte status,
                        final double lastPx, final int lastQty) {
        final OrderNatsPublisher.Rec r = new OrderNatsPublisher.Rec();
        r.orderRef = ref;
        r.accountId = 11;
        r.security = "IBM";
        r.side = side;
        r.quantity = qty;
        r.remainingQty = remaining;
        r.limitPx = 50_000_000L;
        r.status = status;
        r.lastExecPx = Math.round(lastPx * 1_000_000d);
        r.lastFillQty = lastQty;
        return encoder.encode(new StringBuilder(1024), r);
    }

    @Test
    void bothSidesOfACrossReceiveFilledWithTheirQuantityAndPrice() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client seller = new Client("CLIENT1");
        final Client buyer = new Client("CLIENT2");
        start(submitter, seller, buyer);

        sendOrder(seller, "s1", Side.SELL, 5, 50.0);
        assertNotNull(seller.await(r -> is(r, "s1", ExecType.NEW)), "no New for the resting sell");
        sendOrder(buyer, "b1", Side.BUY, 5, 51.0);
        assertNotNull(buyer.await(r -> is(r, "b1", ExecType.NEW)), "no New for the aggressing buy");

        // The cross, as the leader publishes it: the buy's own NEW, the resting sell (ref 1), the buy (ref 2).
        acceptor.onOrderUpdate(feed(2, (byte) 0, 5, 5, NEW, 0, 0));
        acceptor.onOrderUpdate(feed(1, (byte) 1, 5, 0, FILLED, 50.0, 5));
        acceptor.onOrderUpdate(feed(2, (byte) 0, 5, 0, FILLED, 50.0, 5));

        final ExecutionReport s = seller.await(r -> is(r, "s1", ExecType.TRADE));
        final ExecutionReport b = buyer.await(r -> is(r, "b1", ExecType.TRADE));
        assertNotNull(s, "the resting sell got no fill report");
        assertNotNull(b, "the aggressing buy got no fill report");
        assertFill(s, "ord-1", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
        assertFill(b, "ord-2", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
        assertEquals(Side.SELL, s.getChar(Side.FIELD));

        // Each fill went to its own session only, once.
        Thread.sleep(300);
        assertEquals(1, seller.count(r -> exec(r) == ExecType.TRADE), "seller fill reports");
        assertEquals(1, buyer.count(r -> exec(r) == ExecType.TRADE), "buyer fill reports");
        assertTrue(seller.reports.stream().noneMatch(r -> "b1".equals(cl(r))), "the buy's report reached the seller");
    }

    @Test
    void aPartialFillReportsPartiallyFilledThenFilledWithTheAveragePrice() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "p1", Side.BUY, 10, 52.0);
        assertNotNull(c.await(r -> is(r, "p1", ExecType.NEW)));

        final byte[] partial = feed(1, (byte) 0, 10, 6, PARTIAL, 50.0, 4);
        acceptor.onOrderUpdate(partial);
        acceptor.onOrderUpdate(partial);   // a redelivery adds nothing
        acceptor.onOrderUpdate(feed(1, (byte) 0, 10, 0, FILLED, 52.0, 6));
        acceptor.onOrderUpdate(feed(99, (byte) 0, 10, 0, FILLED, 52.0, 10));   // not this gateway's

        assertTrue(c.awaitCount(r -> exec(r) == ExecType.TRADE, 2), "want two fill reports");
        Thread.sleep(300);
        final List<ExecutionReport> fills = c.reports.stream().filter(r -> exec(r) == ExecType.TRADE).toList();
        assertEquals(2, fills.size(), "the redelivered partial or the stranger's fill was reported");
        assertEquals(0, acceptor.fillGaps(), "a complete sequence raised a gap ALERT");
        assertFill(fills.get(0), "ord-1", OrdStatus.PARTIALLY_FILLED, 4, 50.0, 4, 6, 50.0);
        assertFill(fills.get(1), "ord-1", OrdStatus.FILLED, 6, 52.0, 10, 0, 51.2);
    }

    @Test
    void aFillThisGatewayNeverReceivedIsAlertedAndCumQtyStaysTheEngines() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "g1", Side.BUY, 10, 50.0);
        assertNotNull(c.await(r -> is(r, "g1", ExecType.NEW)));

        // The partial (4 @ 50) is lost before it reaches this gateway; the final fill (6) arrives.
        acceptor.onOrderUpdate(feed(1, (byte) 0, 10, 0, FILLED, 50.0, 6));

        final ExecutionReport f = c.await(r -> is(r, "g1", ExecType.TRADE));
        assertNotNull(f, "the surviving fill was not reported");
        assertFill(f, "ord-1", OrdStatus.FILLED, 10, 50.0, 10, 0, 50.0);
        assertEquals(1, acceptor.fillGaps(), "the lost partial was not detected");
        assertEquals(1, acceptor.fillReports());
    }

    @Test
    void aFillPublishedBeforeTheAckIsReportedAfterTheNew() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        // The leader's feed beats its own egress ack to this gateway.
        submitter.beforeAck = ref -> acceptor.onOrderUpdate(feed(ref, (byte) 1, 5, 0, FILLED, 50.0, 5));
        sendOrder(c, "e1", Side.SELL, 5, 50.0);

        assertTrue(c.awaitCount(r -> "e1".equals(cl(r)), 2), "want New then the fill");
        assertEquals(ExecType.NEW, exec(c.reports.get(0)), "the fill overtook the New");
        assertFill(c.reports.get(1), "ord-1", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
    }

    /** FR-FIX05: a cancel ends the order, not its fills; the Canceled report keeps CumQty and AvgPx. */
    @Test
    void aCancelAfterAPartialFillReportsWhatWasFilled() throws Exception {
        final Client c = new Client("CLIENT1");
        start(new StubSubmitter(), c);
        sendOrder(c, "k1", Side.BUY, 10, 52.0);
        assertNotNull(c.await(r -> is(r, "k1", ExecType.NEW)));
        acceptor.onOrderUpdate(feed(1, (byte) 0, 10, 6, PARTIAL, 50.0, 4));
        assertNotNull(c.await(r -> is(r, "k1", ExecType.TRADE)), "no partial fill report");

        final OrderCancelRequest f = new OrderCancelRequest(new OrigClOrdID("k1"), new ClOrdID("k1x"),
            new Side(Side.BUY), new TransactTime());
        f.set(new Symbol("IBM"));
        Session.sendToTarget(f, c.sessionId);

        final ExecutionReport x = c.await(r -> is(r, "k1x", ExecType.CANCELED));
        assertNotNull(x, "no Canceled report");
        assertEquals(OrdStatus.CANCELED, x.getChar(OrdStatus.FIELD));
        assertEquals(4.0, x.getDouble(CumQty.FIELD), "a canceled order keeps its fill");
        assertEquals(0.0, x.getDouble(LeavesQty.FIELD));
        assertEquals(50.0, x.getDouble(AvgPx.FIELD), 1e-9);
    }

    /** FR-DRS-27: OrderStatus for a CANCELED order reports the read model's filled quantity, never qty - 0. */
    @Test
    void orderStatusOfACanceledOrderReportsItsFillNotItsQuantity() throws Exception {
        final Client c = new Client("CLIENT1");
        final List<OrderStatusSource.OrderView> rows = new CopyOnWriteArrayList<>(List.of(
            new OrderStatusSource.OrderView(1, "Buy", 10, 0, "CANCELED", "IBM", 4),
            new OrderStatusSource.OrderView(2, "Sell", 3, 0, "CANCELED", "IBM"),            // read model without the field
            new OrderStatusSource.OrderView(3, "Buy", 10, 6, "PARTIALLY_FILLED", "IBM")));   // ditto, still open
        start(new StubSubmitter(), (account, all) -> rows, c);
        for (int ref = 1; ref <= 3; ref++) {
            final OrderStatusRequest h = new OrderStatusRequest();
            h.set(new ClOrdID("h" + ref));
            h.set(new OrderID("ord-" + ref));
            h.set(new Side(Side.BUY));
            h.set(new Symbol("IBM"));
            h.set(new Account("11"));
            Session.sendToTarget(h, c.sessionId);
        }
        final double[][] want = {{4, 0}, {0, 0}, {4, 6}};   // CumQty, LeavesQty
        for (int ref = 1; ref <= 3; ref++) {
            final String id = "h" + ref;
            final ExecutionReport r = c.await(m -> is(m, id, ExecType.ORDER_STATUS));
            assertNotNull(r, "no status report for ord-" + ref);
            assertEquals(want[ref - 1][0], r.getDouble(CumQty.FIELD), "CumQty of ord-" + ref);
            assertEquals(want[ref - 1][1], r.getDouble(LeavesQty.FIELD), "LeavesQty of ord-" + ref);
        }
    }

    /** ADR-093: the fill feed is a strict consumer of /orders; a booking event carrying clientOrderKey reads as before. */
    @Test
    void aBookingEventCarryingTheClientKeyIsReadAsBefore() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "k1", Side.BUY, 5, 51.0);
        assertNotNull(c.await(r -> is(r, "k1", ExecType.NEW)), "no New");
        final String booking = new String(feed(1, (byte) 0, 5, 5, NEW, 0, 0), java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"status\"", "\"clientOrderKey\":\"18446744073709551615\",\"status\"");
        assertTrue(booking.contains("clientOrderKey"), booking);
        acceptor.onOrderUpdate(booking.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        acceptor.onOrderUpdate(feed(1, (byte) 0, 5, 0, FILLED, 50.0, 5));
        final ExecutionReport r = c.await(m -> is(m, "k1", ExecType.TRADE));
        assertNotNull(r, "no fill report after a keyed booking event");
        assertFill(r, "ord-1", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
    }

    /**
     * ADR-093: an OrderStatusRequest for a ClOrdID this gateway holds no ref for (another replica booked it, or its ref
     * was lost past ADR-090's answer ring) is found by the key the gateway submits it under. Fails on a gateway that
     * answers "unknown order".
     */
    @Test
    void orderStatusOfAClOrdIdThisGatewayNeverSawIsFoundByItsClientKey() throws Exception {
        final Client c = new Client("CLIENT1");
        final long key = ClusterGatewayMain.clientOrderKey("CLIENT1\u0001x9");
        start(new StubSubmitter(), new OrderStatusSource() {
            @Override
            public List<OrderView> orders(final int account, final boolean all) {
                return List.of(new OrderView(7, "Buy", 10, 10, "NEW", "IBM", 0));   // not this order
            }

            @Override
            public List<OrderView> ordersByKey(final int account, final long clientKey) {
                return account == 11 && clientKey == key ? List.of(new OrderView(42, "Buy", 5, 0, "FILLED", "IBM", 5))
                    : List.of();
            }
        }, c);
        final OrderStatusRequest h = new OrderStatusRequest();
        h.set(new ClOrdID("x9"));
        h.set(new Side(Side.BUY));
        h.set(new Symbol("IBM"));
        h.set(new Account("11"));
        Session.sendToTarget(h, c.sessionId);
        final ExecutionReport r = c.await(m -> is(m, "x9", ExecType.ORDER_STATUS));
        assertNotNull(r, "no status report found by the client key");
        assertEquals("ord-42", r.getString(OrderID.FIELD));
        assertEquals(OrdStatus.FILLED, r.getChar(OrdStatus.FIELD));
    }

    /** FR-XOC-03: a resent ClOrdID whose original left retention is restated as that order, never rejected. */
    @Test
    void aResentClOrdIdWhoseOriginalLeftRetentionIsRestatedNotRejected() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        submitter.answer = new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_REJECTED,
            (byte) finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal());
        final Client c = new Client("CLIENT1");
        start(submitter, (account, all) -> List.of(new OrderStatusSource.OrderView(42, "Buy", 5, 0, "FILLED", "IBM", 5)), c);
        sendOrder(c, "r1", Side.BUY, 5, 50.0);
        final ExecutionReport r = c.await(m -> "r1".equals(cl(m)));
        assertNotNull(r, "the resend was not answered");
        assertEquals(ExecType.ORDER_STATUS, r.getChar(ExecType.FIELD));
        assertEquals("ord-42", r.getString(OrderID.FIELD), "the original order, not NONE");
        assertEquals(OrdStatus.FILLED, r.getChar(OrdStatus.FIELD), "its state from the read model");
        assertEquals(5.0, r.getDouble(CumQty.FIELD));
        assertTrue(r.getString(Text.FIELD).startsWith("DUPLICATE"));
    }

    @Test
    void withoutAReadModelTheRestatementSaysOnlyThatTheOrderIsDone() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        submitter.answer = new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_REJECTED,
            (byte) finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal());
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "r2", Side.BUY, 5, 50.0);
        final ExecutionReport r = c.await(m -> "r2".equals(cl(m)));
        assertNotNull(r, "the resend was not answered");
        assertEquals(ExecType.ORDER_STATUS, r.getChar(ExecType.FIELD));
        assertEquals("ord-42", r.getString(OrderID.FIELD));
        assertEquals(OrdStatus.DONE_FOR_DAY, r.getChar(OrdStatus.FIELD), "never OrdStatus=8");
    }

    // ----- harness ---------------------------------------------------------------------------

    private static void assertFill(final ExecutionReport r, final String orderId, final char ordStatus,
                                   final int lastQty, final double lastPx, final int cum, final int leaves,
                                   final double avgPx) throws FieldNotFound {
        assertEquals(orderId, r.getString(OrderID.FIELD));
        assertEquals(ExecType.TRADE, r.getChar(ExecType.FIELD));
        assertEquals(ordStatus, r.getChar(OrdStatus.FIELD));
        assertEquals(lastQty, r.getDouble(LastQty.FIELD));
        assertEquals(lastPx, r.getDouble(LastPx.FIELD), 1e-9);
        assertEquals(cum, r.getDouble(CumQty.FIELD));
        assertEquals(leaves, r.getDouble(LeavesQty.FIELD));
        assertEquals(avgPx, r.getDouble(AvgPx.FIELD), 1e-9);
    }

    private void start(final OrderSubmitter submitter, final Client... clients) throws Exception {
        start(submitter, null, clients);
    }

    private void start(final OrderSubmitter submitter, final OrderStatusSource status, final Client... clients)
        throws Exception {
        final int port = freePort();
        acceptor = new FixGatewayAcceptor(submitter, status, port, "TRADERX", 11, List.of("CLIENT1", "CLIENT2"));
        acceptor.start();
        for (final Client c : clients) {
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
            final SessionID sid = new SessionID("FIX.4.4", c.compId, "TRADERX");
            settings.setString(sid, "BeginString", "FIX.4.4");
            final SocketInitiator init = new SocketInitiator(c, new MemoryStoreFactory(), settings,
                new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
            init.start();
            initiators.add(init);
            assertTrue(c.loggedOn.await(20, TimeUnit.SECONDS), c.compId + " did not log on");
        }
    }

    private static void sendOrder(final Client c, final String clOrdId, final char side, final int qty,
                                  final double px) throws Exception {
        final NewOrderSingle order = new NewOrderSingle(new ClOrdID(clOrdId), new Side(side),
            new TransactTime(), new OrdType(OrdType.LIMIT));
        order.set(new Symbol("IBM"));
        order.set(new OrderQty(qty));
        order.set(new Price(px));
        order.set(new Account("11"));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        Session.sendToTarget(order, c.sessionId);
    }

    private static boolean is(final ExecutionReport r, final String clOrdId, final char execType) {
        return clOrdId.equals(cl(r)) && exec(r) == execType;
    }

    private static char exec(final ExecutionReport r) {
        try {
            return r.getChar(ExecType.FIELD);
        } catch (final FieldNotFound e) {
            return 0;
        }
    }

    private static String cl(final ExecutionReport r) {
        try {
            return r.isSetField(ClOrdID.FIELD) ? r.getString(ClOrdID.FIELD) : null;
        } catch (final FieldNotFound e) {
            return null;
        }
    }

    private static final class Client implements Application {
        final String compId;
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();
        volatile SessionID sessionId;

        Client(final String compId) {
            this.compId = compId;
        }

        ExecutionReport await(final Predicate<ExecutionReport> p) throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                for (final ExecutionReport r : reports) {
                    if (p.test(r)) {
                        return r;
                    }
                }
                Thread.sleep(50);
            }
            return null;
        }

        boolean awaitCount(final Predicate<ExecutionReport> p, final int n) throws InterruptedException {
            for (int i = 0; i < 100 && count(p) < n; i++) {
                Thread.sleep(50);
            }
            return count(p) >= n;
        }

        long count(final Predicate<ExecutionReport> p) {
            return reports.stream().filter(p).count();
        }

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
                reports.add(report);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
