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
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the ADR-047 failover-transparency property directly: the FIX session terminates on the
 * gateway and is wholly independent of the cluster-client lifecycle, so a leader change (modelled
 * as the {@link OrderSubmitter} going unavailable and recovering) does NOT log the counterparty
 * out — orders resume on the SAME session. No real cluster is needed: the submitter seam is the
 * exact boundary the property lives at, and a togglable stub models the leader blip precisely.
 */
@Timeout(60)
class FixGatewaySurvivalTest {
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

    /** Stub submitter: when {@code alive} is false it returns null (post-publish ambiguity /
     *  failover window); otherwise it accepts and echoes an incrementing orderRef. */
    private static final class ToggleSubmitter implements OrderSubmitter {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicInteger nextRef = new AtomicInteger(1);
        final java.util.Map<String, Integer> byClOrdId = new ConcurrentHashMap<>();

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            if (!alive.get()) {
                return null;
            }
            final int ref = byClOrdId.computeIfAbsent(clOrdId, k -> nextRef.getAndIncrement());
            return new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED);
        }
    }

    @Test
    void fixSessionSurvivesAClusterLeaderBlip() throws Exception {
        final int port = freePort();
        final ToggleSubmitter submitter = new ToggleSubmitter();
        acceptor = new FixGatewayAcceptor(submitter, port, "TRADERX", 11, List.of("CLIENT1"));
        acceptor.start();

        final ClientApp app = new ClientApp();
        initiator = startInitiator(port, app);

        assertTrue(app.loggedOn.await(20, TimeUnit.SECONDS), "counterparty did not log on");
        final SessionID sid = app.sessionId;

        // Order 1: submitter alive -> accepted, ExecutionReport New.
        sendOrder(sid, "c1", "IBM", 10);
        assertTrue(app.awaitReport("c1", 10), "no report for c1");
        assertEquals(OrdStatus.NEW, app.status("c1"));

        // ---- leader blip: the cluster client goes unavailable ----
        submitter.alive.set(false);
        // Order 2 lands during the blip: the submitter returns null (ambiguous), so no report —
        // but the SESSION must stay up. Give it time; the counterparty must not be logged out.
        sendOrder(sid, "c2", "IBM", 10);
        Thread.sleep(3_000);
        assertEquals(0, app.logouts.get(), "FIX session was logged out during the failover window");
        assertTrue(app.isLoggedOn(), "FIX session dropped during the failover window");

        // ---- new leader elected: the cluster client recovers ----
        submitter.alive.set(true);
        // Order 3 on the SAME session succeeds — transparent failover for the counterparty.
        sendOrder(sid, "c3", "IBM", 10);
        assertTrue(app.awaitReport("c3", 10), "no report for c3 after recovery");
        assertEquals(OrdStatus.NEW, app.status("c3"));

        assertEquals(0, app.logouts.get(), "session must never have logged out across the blip");
        assertFalse(app.reports.containsKey("c2"), "the blipped order must not have acked");
    }

    // ----- initiator harness -----------------------------------------------------------------

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

    private void sendOrder(final SessionID sid, final String clOrdId, final String ticker, final int qty)
        throws Exception {
        final NewOrderSingle order = new NewOrderSingle(new ClOrdID(clOrdId),
            new Side(Side.BUY), new TransactTime(), new OrdType(OrdType.LIMIT));
        order.set(new Symbol(ticker));
        order.set(new OrderQty(qty));
        order.set(new Price(100.0));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        Session.sendToTarget(order, sid);
    }

    private static final class ClientApp implements Application {
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final AtomicInteger logouts = new AtomicInteger();
        final java.util.Map<String, Character> reports = new ConcurrentHashMap<>();
        volatile SessionID sessionId;
        private volatile boolean loggedOnNow;

        boolean isLoggedOn() {
            final Session s = Session.lookupSession(sessionId);
            return s != null && s.isLoggedOn();
        }

        boolean awaitReport(final String clOrdId, final long timeoutSecondsIgnored) throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                if (reports.containsKey(clOrdId)) {
                    return true;
                }
                Thread.sleep(100);
            }
            return reports.containsKey(clOrdId);
        }

        char status(final String clOrdId) {
            return reports.get(clOrdId);
        }

        @Override
        public void onCreate(final SessionID sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onLogon(final SessionID sessionId) {
            this.sessionId = sessionId;
            loggedOnNow = true;
            loggedOn.countDown();
        }

        @Override
        public void onLogout(final SessionID sessionId) {
            loggedOnNow = false;
            logouts.incrementAndGet();
        }

        @Override
        public void toAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void fromAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void toApp(final Message message, final SessionID sessionId) { }

        @Override
        public void fromApp(final Message message, final SessionID sessionId) throws FieldNotFound {
            if (message instanceof ExecutionReport report) {
                reports.put(report.getString(ClOrdID.FIELD), report.getChar(OrdStatus.FIELD));
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
