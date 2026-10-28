package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.StreamDedupe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import quickfix.SessionID;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-090 (FR-XOD-12): two gateway replicas sequence a FIX session's orders through the ADR-086 store, and a venue
 * that applies the engine's own stream rule (StreamDedupe) books each order once. A NewOrderSingle resent on the
 * other replica after a drop is answered with the original order; an order a replica stored and never submitted is
 * submitted, in order, when the engine reports the gap.
 *
 * <p>Falsifiable: without FIX_EXACTLY_ONCE the same resend reaches the venue unsequenced, twice
 * ({@link #withoutSequencingTheResendIsASecondSubmission}).
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(120)
class FixExactlyOnceIT {
    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCommand("-js")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private final List<FixGatewayAcceptor> gateways = new ArrayList<>();
    private final Map<String, FixSessionStore> stores = new HashMap<>();

    @AfterEach
    void stop() {
        gateways.forEach(FixGatewayAcceptor::stop);
    }

    /** The engine's stream rule behind both replicas. The first submit of {@code ambiguous} books but returns null. */
    private static final class Venue implements OrderSubmitter {
        final StreamDedupe streams = new StreamDedupe(256, 64, 3_600_000L);
        final Map<String, Integer> refBySeq = new HashMap<>();
        final Map<String, Integer> booked = new HashMap<>();   // clOrdId -> times booked
        int nextRef = 1;
        int unsequenced;
        String ambiguous;
        boolean pastWindow;   // answer a duplicate as the engine does past its ring: REJECTED/DUPLICATE, ref 0

        @Override
        public synchronized ExecResult submitSequencedOrder(final String clOrdId, final int accountId, final String ticker,
                                                            final char side, final int qty, final long px,
                                                            final TypedOrder typed, final long streamId, final long seq) {
            final String k = accountId + "/" + streamId + "/" + seq;
            switch (streams.admit(accountId, streamId, seq, 0L)) {
                case StreamDedupe.ADMIT -> {
                    final int ref = nextRef++;
                    refBySeq.put(k, ref);
                    booked.merge(clOrdId, 1, Integer::sum);
                    if (clOrdId.endsWith("\u0001" + ambiguous)) {
                        ambiguous = null;
                        return null;   // committed, but its ack never reached this gateway
                    }
                    return new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED);
                }
                case StreamDedupe.DUPLICATE -> {
                    return pastWindow
                        ? new ExecResult(false, 0, OutputEvent.KIND_ORDER_REJECTED, (byte) RiskReason.DUPLICATE.ordinal())
                        : new ExecResult(true, refBySeq.get(k), OutputEvent.KIND_ORDER_ACCEPTED);
                }
                default -> {
                    return new ExecResult(false, (int) streams.expected(), OutputEvent.KIND_ORDER_REJECTED,
                        (byte) RiskReason.OUT_OF_SEQUENCE.ordinal());
                }
            }
        }

        @Override
        public synchronized ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                                   final char side, final int qty, final long px) {
            unsequenced++;
            booked.merge(clOrdId, 1, Integer::sum);
            if (clOrdId.endsWith("\u0001" + ambiguous)) {
                ambiguous = null;
                return null;
            }
            return new ExecResult(true, nextRef++, OutputEvent.KIND_ORDER_ACCEPTED);
        }
    }

    private int startGateway(final Venue venue, final String name, final String compId, final boolean sequenced)
            throws Exception {
        final int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        final FixSessionStore store = new FixSessionStore("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222), name);
        store.sequenceOrders = sequenced;
        for (int i = 0; i < 100 && !store.available(); i++) {
            Thread.sleep(100);
        }
        assertTrue(store.available(), "store never became available");
        stores.put(name, store);
        final FixGatewayAcceptor gw = new FixGatewayAcceptor(venue, null, port, "TRADERX", 11, List.of(compId))
            .withSessionStore(store);
        gw.start();
        gateways.add(gw);
        return port;
    }

    /** Send s1, then s2 whose ack is lost; drop; resend s2 (PossResend) on the other replica. Returns that answer. */
    private Map<String, String> resendAfterDrop(final Venue venue, final String compId, final boolean sequenced)
            throws Exception {
        final int a = startGateway(venue, "gw-a-" + compId, compId, sequenced);
        final int b = startGateway(venue, "gw-b-" + compId, compId, sequenced);
        final Client c1 = new Client(a, compId);
        assertEquals("A", c1.logon().get("35"));
        c1.order("s1", "");
        assertEquals("s1", c1.awaitType("8").get("11"));
        venue.ambiguous = "s2";
        c1.order("s2", "");
        Thread.sleep(500);   // booked; the gateway logs it ambiguous and answers nothing
        c1.rst();
        final Client c2 = new Client(b, compId);
        c2.out = 4;          // Logon, s1, s2 sent
        assertEquals("A", c2.logon().get("35"));
        c2.order("s2", "97=Y\u0001");   // an application resend (PossResend): new MsgSeqNum, same ClOrdID
        final Map<String, String> er = c2.awaitType("8");
        c2.close();
        return er;
    }

    @Test
    void aResendOnTheOtherReplicaIsAnsweredWithTheOriginal() throws Exception {
        final Venue venue = new Venue();
        final Map<String, String> er = resendAfterDrop(venue, "CLIENT5", true);
        assertEquals("s2", er.get("11"));
        assertEquals("ord-2", er.get("37"), "the ORIGINAL order: " + er);
        assertEquals(1, venue.booked.get("CLIENT5\u0001s2"), "booked once");
        assertEquals(0, venue.unsequenced);
    }

    /**
     * FR-XOD-14: a resend whose original executed but whose ref is past the engine's answer ring (REJECTED/DUPLICATE,
     * ref 0) is answered 35=j EXACTLY_ONCE_COMMITTED_REF_UNKNOWN, never 39=8 (ADR-091 §3: "rejected" reads as "did not
     * execute"). Fails on a gateway that sends the ExecutionReport.
     */
    @Test
    void aResendPastTheEngineWindowIsNotAnsweredAsRejected() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a-C9", "CLIENT9", true);
        final int b = startGateway(venue, "gw-b-C9", "CLIENT9", true);
        final Client c1 = new Client(a, "CLIENT9");
        assertEquals("A", c1.logon().get("35"));
        venue.ambiguous = "s1";
        c1.order("s1", "");
        Thread.sleep(500);   // booked; the gateway logs it ambiguous and answers nothing
        c1.rst();
        venue.pastWindow = true;
        final Client c2 = new Client(b, "CLIENT9");
        c2.out = 3;          // Logon, s1 sent
        assertEquals("A", c2.logon().get("35"));
        c2.order("s1", "97=Y\u0001");
        assertExactlyOnceReject(c2, "s1", "EXACTLY_ONCE_COMMITTED_REF_UNKNOWN");
        assertEquals(1, venue.booked.get("CLIENT9\u0001s1"), "booked once");
        c2.close();
    }

    @Test
    void withoutSequencingTheResendIsASecondSubmission() throws Exception {
        final Venue venue = new Venue();
        resendAfterDrop(venue, "CLIENT6", false);
        assertEquals(2, venue.booked.get("CLIENT6\u0001s2"), "control: the venue saw it twice, unsequenced");
    }

    @Test
    void anOrderStoredButNeverSubmittedGoesFirstWhenTheEngineReportsTheGap() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a-C7", "CLIENT7", true);
        final int b = startGateway(venue, "gw-b-C7", "CLIENT7", true);
        final Client c1 = new Client(a, "CLIENT7");
        assertEquals("A", c1.logon().get("35"));
        // Replica A stores s1 and dies before submitting it.
        final NewOrderSingle s1 = new NewOrderSingle(new ClOrdID("s1"), new Side(Side.SELL), new TransactTime(),
            new OrdType(OrdType.LIMIT));
        s1.set(new Symbol("IBM"));
        s1.set(new OrderQty(10));
        s1.set(new Price(100));
        s1.set(new Account("11"));
        final SessionID sid = new SessionID("FIX.4.4", "TRADERX", "CLIENT7");
        assertEquals(1L, stores.get("gw-a-C7").recordOrder(sid, 11, "s1", s1.toString()));
        c1.rst();
        Thread.sleep(3_000);   // A lets the lease go

        final Client c2 = new Client(b, "CLIENT7");
        c2.out = 2;          // c1 sent only its Logon
        assertEquals("A", c2.logon().get("35"));
        c2.order("s2", "");
        final Map<String, String> first = c2.awaitType("8");
        final Map<String, String> second = c2.awaitType("8");
        assertEquals("s1", first.get("11"), "the stored order is answered first: " + first);
        assertEquals("s2", second.get("11"));
        assertEquals(Map.of("CLIENT7\u0001s1", 1, "CLIENT7\u0001s2", 1), venue.booked, "each booked once, in order");
        c2.close();
    }

    /**
     * FR-XOD-13: 30 orders through a ring of 8. The order bucket holds 8 keys and the session bucket none; the heap
     * holds 8 ClOrdIDs and no answered request. A PossResend inside the ring is the original order; one past it is
     * refused (35=j) and NOT booked again, on this replica and on the one that adopts the session. Fails on a store
     * that keeps the day.
     */
    @Test
    void theOrderRecordsAreARingAndAnAgedOutResendIsRefusedNotBooked() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a-C8", "CLIENT8", true);
        final int b = startGateway(venue, "gw-b-C8", "CLIENT8", true);
        stores.get("gw-a-C8").orderWindow = 8;
        stores.get("gw-b-C8").orderWindow = 8;
        final SessionID sid = new SessionID("FIX.4.4", "TRADERX", "CLIENT8");
        final Client c1 = new Client(a, "CLIENT8");
        assertEquals("A", c1.logon().get("35"));
        for (int i = 1; i <= 30; i++) {
            c1.order("o" + i, "");
            assertEquals("ord-" + i, c1.awaitType("8").get("37"));
        }
        assertArrayEquals(new int[] { 8, 0 }, stores.get("gw-a-C8").heldOrders(sid), "{ClOrdIDs, requests} held");
        try (io.nats.client.Connection nc = io.nats.client.Nats.connect(
                "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
            assertEquals(8, nc.keyValue(FixSessionStore.ORDER_BUCKET).keys().stream()
                .filter(k -> k.contains("CLIENT8")).count(), "order keys: a ring of 8");
            assertEquals(0, nc.keyValue(FixSessionStore.BUCKET).keys().stream()
                .filter(k -> k.contains("CLIENT8") && (k.contains(".o.") || k.contains(".c."))).count(),
                "no order records in the session bucket");
        }
        c1.order("o25", "97=Y\u0001");
        assertEquals("ord-25", c1.awaitType("8").get("37"), "a resend inside the ring is the original");
        c1.order("o1", "97=Y\u0001");
        assertAgedOut(c1, "o1");
        c1.rst();
        Thread.sleep(3_000);   // A lets the lease go

        final Client c2 = new Client(b, "CLIENT8");
        c2.out = 34;           // Logon, 30 orders, 2 resends
        assertEquals("A", c2.logon().get("35"));
        c2.order("o2", "97=Y\u0001");
        assertAgedOut(c2, "o2");
        c2.order("o26", "97=Y\u0001");
        assertEquals("ord-26", c2.awaitType("8").get("37"), "adopted: a resend inside the ring is the original");
        final int[] adopted = stores.get("gw-b-C8").heldOrders(sid);
        assertEquals(8, adopted[0], "adopted ClOrdIDs");
        assertTrue(adopted[1] <= 8, "adopted requests: the ring's, until the engine answers past them: " + adopted[1]);
        assertEquals(30, venue.booked.size());
        for (int i = 1; i <= 30; i++) {
            assertEquals(1, venue.booked.get("CLIENT8\u0001o" + i), "o" + i + " booked once");
        }
        c2.close();
    }

    private static void assertAgedOut(final Client c, final String clOrdId) throws IOException {
        assertExactlyOnceReject(c, clOrdId, "EXACTLY_ONCE_AGED_OUT");
    }

    private static void assertExactlyOnceReject(final Client c, final String clOrdId, final String reason)
            throws IOException {
        Map<String, String> m;
        do {
            m = c.read();
        } while ("0".equals(m.get("35")));
        assertEquals("j", m.get("35"), "refused as possibly executed, never 39=8: " + m);
        assertEquals(clOrdId, m.get("379"));
        assertTrue(m.get("58").startsWith(reason), m.get("58"));
    }

    private static String now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"));
    }

    /** Minimal raw FIX 4.4 counterparty, as FixSessionPersistenceIT's. */
    private static final class Client {
        final Socket socket;
        final InputStream in;
        final OutputStream os;
        final String compId;
        int out = 1;
        private final StringBuilder buf = new StringBuilder();

        Client(final int port, final String compId) throws IOException {
            this.compId = compId;
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(15_000);
            in = socket.getInputStream();
            os = socket.getOutputStream();
        }

        Map<String, String> logon() throws IOException {
            send("A", "98=0\u0001108=30");
            return read();
        }

        /** {@code header}: extra header fields, each ending in SOH (PossResend 97 is a header field). */
        void order(final String clOrdId, final String header) throws IOException {
            send("D", "11=" + clOrdId + "\u00011=11\u000155=IBM\u000154=2\u000160=" + now()
                + "\u000138=10\u000140=2\u000144=100", header);
        }

        void send(final String type, final String body) throws IOException {
            send(type, body, "");
        }

        void send(final String type, final String body, final String header) throws IOException {
            final String b = "35=" + type + "\u000149=" + compId + "\u000156=TRADERX\u000134=" + (out++)
                + "\u000152=" + now() + "\u0001" + header + body + "\u0001";
            final String head = "8=FIX.4.4\u00019=" + b.getBytes(StandardCharsets.US_ASCII).length + "\u0001";
            int sum = 0;
            for (final byte x : (head + b).getBytes(StandardCharsets.US_ASCII)) {
                sum += x & 0xff;
            }
            os.write((head + b + String.format("10=%03d\u0001", sum % 256)).getBytes(StandardCharsets.US_ASCII));
            os.flush();
        }

        Map<String, String> awaitType(final String type) throws IOException {
            while (true) {
                final Map<String, String> m = read();
                if (type.equals(m.get("35"))) {
                    return m;
                }
                if (java.util.Set.of("3", "5", "j").contains(m.get("35"))) {
                    throw new AssertionError("waiting for 35=" + type + ", got " + m);
                }
            }
        }

        Map<String, String> read() throws IOException {
            while (true) {
                final int end = buf.indexOf("\u000110=");
                final int stop = end < 0 ? -1 : buf.indexOf("\u0001", end + 4);
                if (stop >= 0) {
                    final String msg = buf.substring(0, stop + 1);
                    buf.delete(0, stop + 1);
                    final Map<String, String> m = new LinkedHashMap<>();
                    for (final String f : msg.split("\u0001")) {
                        final int eq = f.indexOf('=');
                        m.put(f.substring(0, eq), f.substring(eq + 1));
                    }
                    return m;
                }
                final byte[] chunk = new byte[4096];
                final int n = in.read(chunk);
                if (n < 0) {
                    throw new IOException("connection closed; buffered: " + buf);
                }
                buf.append(new String(chunk, 0, n, StandardCharsets.US_ASCII));
            }
        }

        void rst() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        void close() throws IOException {
            socket.close();
        }
    }
}
