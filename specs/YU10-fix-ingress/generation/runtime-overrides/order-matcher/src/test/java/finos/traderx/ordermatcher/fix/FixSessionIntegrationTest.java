package finos.traderx.ordermatcher.fix;

import finos.traderx.ordermatcher.auth.JwtTokenMinter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end FIX session proof inside one JVM (SC-FIX01/02/03 at the unit-test tier): a real
 * QuickFIX/J initiator logs on to the in-process acceptor with a JWT, trades through the real
 * ring/journal-off context, and receives ExecutionReports. Also proves the fail-closed logon
 * (wrong password never gets a session) and duplicate-ClOrdID rejection.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:fixsession;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "order.matcher.publisher=noop",
    "order.matcher.pricing-subscriber.enabled=false",
    "journal.enabled=false",
    "risk.bootstrap.enabled=false",
    "fix.session.accounts=BENCH01:22214",
    "fix.target.comp.id=TRADERX"
})
class FixSessionIntegrationTest {

    private static final int PORT = freePort();
    @TempDir
    static Path fixData;

    @DynamicPropertySource
    static void fixProps(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("fix-it");
        registry.add("fix.acceptor.port", () -> PORT);
        registry.add("fix.data.dir", () -> dir.toString());
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Captures everything the acceptor sends us. */
    static final class CapturingInitiator implements Application {
        final BlockingQueue<Message> appMessages = new LinkedBlockingQueue<>();
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final String password;
        CapturingInitiator(String password) { this.password = password; }
        @Override public void onCreate(SessionID sessionId) { }
        @Override public void onLogon(SessionID sessionId) { loggedOn.countDown(); }
        @Override public void onLogout(SessionID sessionId) { }
        @Override public void toAdmin(Message message, SessionID sessionId) {
            try {
                if ("A".equals(message.getHeader().getString(35))) {
                    message.setString(554, password);
                }
            } catch (Exception ignore) { }
        }
        @Override public void fromAdmin(Message message, SessionID sessionId) { }
        @Override public void toApp(Message message, SessionID sessionId) { }
        @Override public void fromApp(Message message, SessionID sessionId) { appMessages.add(message); }
    }

    private static SocketInitiator initiator;
    private static CapturingInitiator client;
    private static SessionID clientSession;

    private static void connect(String password) throws Exception {
        client = new CapturingInitiator(password);
        SessionSettings settings = new SessionSettings();
        Properties d = new Properties();
        d.setProperty("ConnectionType", "initiator");
        d.setProperty("HeartBtInt", "30");
        d.setProperty("ReconnectInterval", "1");
        d.setProperty("SocketConnectHost", "127.0.0.1");
        d.setProperty("SocketConnectPort", String.valueOf(PORT));
        d.setProperty("StartTime", "00:00:00");
        d.setProperty("EndTime", "00:00:00");
        d.setProperty("ResetOnLogon", "Y");
        settings.set(d);
        clientSession = new SessionID("FIX.4.4", "BENCH01", "TRADERX");
        settings.setString(clientSession, "BeginString", "FIX.4.4");
        initiator = new SocketInitiator(client, new MemoryStoreFactory(), settings,
            new SLF4JLogFactory(settings), new DefaultMessageFactory());
        initiator.start();
    }

    private static void disconnect() {
        if (initiator != null) {
            initiator.stop(true);
            initiator = null;
        }
    }

    @AfterAll
    static void tearDown() {
        disconnect();
    }

    private static String jwt() {
        return new JwtTokenMinter("dev-jwt-shared-secret")
            .mint("fix-it", Set.of(22214), false, 3600);
    }

    private Message newOrder(String clOrdId, char side, int qty, String px) throws Exception {
        Message m = new Message();
        m.getHeader().setString(35, "D");
        m.setString(11, clOrdId);
        m.setString(55, "IBM");
        m.setChar(54, side);
        m.setDouble(38, qty);
        m.setChar(40, '2');
        m.setDecimal(44, new java.math.BigDecimal(px));
        m.setUtcTimeStamp(60, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return m;
    }

    private Message awaitReport(String msgType, long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Message m = client.appMessages.poll(250, TimeUnit.MILLISECONDS);
            if (m != null && msgType.equals(m.getHeader().getString(35))) {
                return m;
            }
        }
        return null;
    }

    @Test
    @Order(1)
    void badCredentialsNeverGetASession() throws Exception {
        connect("not-a-jwt");
        assertFalse(client.loggedOn.await(4, TimeUnit.SECONDS),
            "logon must be rejected for an invalid JWT");
        disconnect();
    }

    @Test
    @Order(2)
    void orderCancelStatusAndDuplicateOverOneSession() throws Exception {
        connect(jwt());
        assertTrue(client.loggedOn.await(10, TimeUnit.SECONDS), "logon with valid JWT");

        // D -> ExecutionReport New
        Session.sendToTarget(newOrder("it-1", '1', 5, "140.0"), clientSession);
        Message er = awaitReport("8", 10);
        assertNotNull(er, "admission ExecutionReport");
        assertEquals("it-1", er.getString(11));
        assertEquals('0', er.getChar(150), "ExecType=New");
        String orderId = er.getString(37);
        assertTrue(orderId.startsWith("ord-013-"));

        // duplicate ClOrdID -> BusinessMessageReject
        Session.sendToTarget(newOrder("it-1", '1', 5, "140.0"), clientSession);
        Message rej = awaitReport("j", 10);
        assertNotNull(rej, "duplicate ClOrdID must be rejected");

        // H -> status snapshot
        Message status = new Message();
        status.getHeader().setString(35, "H");
        status.setString(11, "it-1");
        status.setString(55, "IBM");
        status.setChar(54, '1');
        Session.sendToTarget(status, clientSession);
        Message snap = awaitReport("8", 10);
        assertNotNull(snap, "OrderStatusRequest answer");
        assertEquals('I', snap.getChar(150), "ExecType=OrderStatus");

        // F -> ExecutionReport Canceled
        Message cancel = new Message();
        cancel.getHeader().setString(35, "F");
        cancel.setString(11, "it-1-cxl");
        cancel.setString(41, "it-1");
        cancel.setString(55, "IBM");
        cancel.setChar(54, '1');
        cancel.setUtcTimeStamp(60, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        Session.sendToTarget(cancel, clientSession);
        Message cxl = awaitReport("8", 10);
        assertNotNull(cxl, "cancel ExecutionReport");
        assertEquals('4', cxl.getChar(150), "ExecType=Canceled");
        disconnect();
    }
}
