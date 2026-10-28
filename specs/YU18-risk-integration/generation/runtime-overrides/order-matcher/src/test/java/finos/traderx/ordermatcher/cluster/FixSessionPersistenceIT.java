package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-086 (FR-FIX11): two gateway replicas share one JetStream session store. A counterparty that
 * drops abruptly mid-request resumes on the OTHER replica, ResendRequests what it missed, and gets
 * exactly those reports, at their original numbers, PossDupFlag=Y; a second replica asked to host
 * the session while it is live refuses the Logon. Raw FIX on a socket, so the test controls every
 * sequence number and the drop is a real RST.
 *
 * <p>Falsifiable: with the in-memory store (TD-AC01) replica B answers the reconnect Logon at
 * MsgSeqNum 1, so {@code logonSeq == 7} fails, which is what the proof audit measured (suspect 9).
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(120)
class FixSessionPersistenceIT {
    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCommand("-js")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private final List<FixGatewayAcceptor> gateways = new ArrayList<>();
    private final List<FixSessionStore> stores = new ArrayList<>();

    @AfterEach
    void stop() {
        gateways.forEach(FixGatewayAcceptor::stop);
    }

    /** One "cluster" behind both replicas: refs are global; a Buy fills, a Sell rests; Sell qty 99 is slow. */
    private static final class Venue implements OrderSubmitter {
        final AtomicInteger nextRef = new AtomicInteger(1);
        final AtomicInteger submitted = new AtomicInteger();

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            submitted.incrementAndGet();
            if (qty == 99) {
                try {
                    Thread.sleep(1_500);   // the ack arrives after the client is gone
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ExecResult(true, nextRef.getAndIncrement(),
                side == 'B' ? OutputEvent.KIND_ORDER_FILLED : OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitCancel(final int orderRef, final int accountId) {
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }

    /** One /orders feed message, as every gateway receives it (ADR-084). */
    private void feed(final int ref, final String status, final int qty, final int remaining, final double px) {
        final byte[] json = ("{\"payload\":{\"id\":\"e1-" + ref + "\",\"status\":\"" + status + "\",\"quantity\":" + qty
            + ",\"remainingQuantity\":" + remaining + ",\"lastExecutionPrice\":" + px + "}}")
            .getBytes(StandardCharsets.US_ASCII);
        gateways.forEach(g -> g.onOrderUpdate(json));
    }

    private int startGateway(final Venue venue, final String name, final String compId) throws Exception {
        return startGateway(venue, name, compId, NATS);
    }

    private int startGateway(final Venue venue, final String name, final String compId,
                             final GenericContainer<?> nats) throws Exception {
        final int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        final String url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
        final FixSessionStore store = new FixSessionStore(url, name);
        stores.add(store);
        for (int i = 0; i < 100 && !store.available(); i++) {
            Thread.sleep(100);   // the store sets up its buckets on connect, off the caller's thread
        }
        assertTrue(store.available(), "store never became available");
        final FixGatewayAcceptor gw = new FixGatewayAcceptor(venue, null, port, "TRADERX", 11, List.of(compId))
            .withSessionStore(store);
        gw.start();
        gateways.add(gw);
        return port;
    }

    @Test
    void resumesOnAnotherReplicaAndResendsExactlyWhatWasMissed() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a", "CLIENT1");
        final int b = startGateway(venue, "gw-b", "CLIENT1");

        final Map<String, Integer> seen = new LinkedHashMap<>();   // ClOrdID -> times received
        final Client c1 = new Client(a, "CLIENT1");
        assertEquals("A", c1.logon().get("35"));
        c1.order("s1", '2', 10);                                    // rests: New
        c1.order("b1", '1', 10);                                    // crosses: Fill
        for (final String id : List.of("s1", "b1")) {
            final Map<String, String> er = c1.awaitType("8");
            assertEquals(id, er.get("11"));
            seen.merge(id, 1, Integer::sum);
        }
        assertEquals("2", c1.lastIn.get("39"), "b1 must report filled");
        final int processed = Integer.parseInt(c1.lastIn.get("34"));  // 3: Logon, s1, b1

        // Two more orders; the client reads neither answer, and drops while s3's ack is in flight.
        c1.order("s2", '2', 5);
        c1.order("s3", '2', 99);
        Thread.sleep(400);
        c1.rst();

        // Reconnect to the OTHER replica, continuing our own numbers (Logon + 4 orders sent = next 6).
        final Client c2 = new Client(b, "CLIENT1");
        c2.out = 6;
        final Map<String, String> logon = c2.logon();
        assertEquals("A", logon.get("35"), "replica B must accept the resumed session: " + logon);
        final int logonSeq = Integer.parseInt(logon.get("34"));
        assertEquals(processed + 3, logonSeq, "B continues A's numbering: s2 and s3 were sent at 4 and 5");

        c2.send("2", "7=" + (processed + 1) + "\u000116=0");      // ResendRequest(4, infinity)
        for (final int n : List.of(processed + 1, processed + 2)) {
            final Map<String, String> er = c2.awaitType("8");
            assertEquals(String.valueOf(n), er.get("34"), "resent at its original number");
            assertEquals("Y", er.get("43"), "a resent report carries PossDupFlag=Y");
            assertNotNull(er.get("122"), "and OrigSendingTime");
            seen.merge(er.get("11"), 1, Integer::sum);
        }

        // Cancel by OrigClOrdID: B adopted A's ClOrdID -> orderRef from the stored reports.
        c2.send("F", "41=s2\u000111=x2\u000154=2\u000155=IBM\u000160=" + now());
        final Map<String, String> cancel = c2.awaitType("8");
        assertEquals("4", cancel.get("39"), "cancel by OrigClOrdID resolves on the other replica: " + cancel);
        assertEquals(String.valueOf(logonSeq + 1), cancel.get("34"), "new messages continue after the Logon");
        assertTrue(!"Y".equals(cancel.get("43")), "a first transmission is not a possible duplicate");

        assertEquals(Map.of("s1", 1, "b1", 1, "s2", 1, "s3", 1), seen, "every report exactly once");
        c2.close();
    }

    @Test
    void fillsReachTheSessionWhereverItIsOrIsNot() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a", "CLIENT3");
        final int b = startGateway(venue, "gw-b", "CLIENT3");
        final Client c1 = new Client(a, "CLIENT3");
        assertEquals("A", c1.logon().get("35"));
        c1.order("s1", '2', 10);
        final int ref1 = Integer.parseInt(c1.awaitType("8").get("37").substring(4));
        c1.order("s2", '2', 7);
        final int ref2 = Integer.parseInt(c1.awaitType("8").get("37").substring(4));
        final int processed = Integer.parseInt(c1.lastIn.get("34"));   // 3: Logon, s1, s2
        c1.rst();
        Thread.sleep(3_000);   // A lets the lease go: the session is live nowhere

        // s1 fills while nobody hosts the session. A tracks it, takes the lease, and stores the report.
        feed(ref1, "FILLED", 10, 0, 101.5);
        Thread.sleep(1_000);

        final Client c2 = new Client(b, "CLIENT3");
        c2.out = 4;
        final Map<String, String> logon = c2.logon();
        assertEquals("A", logon.get("35"), logon.toString());
        assertEquals(String.valueOf(processed + 2), logon.get("34"), "the stored fill took number 4");
        c2.send("2", "7=" + (processed + 1) + "\u000116=0");
        final Map<String, String> fill1 = c2.awaitType("8");
        assertEquals("s1", fill1.get("11"));
        assertEquals("F", fill1.get("150"));
        assertEquals("10", fill1.get("14"));
        assertEquals("Y", fill1.get("43"), "stored while disconnected, so it arrives as a resend");

        // s2 fills while B hosts the session. B adopted s2 from the log; A must not report it too.
        feed(ref2, "PARTIALLY_FILLED", 7, 3, 99);
        final Map<String, String> fill2 = c2.awaitType("8");
        assertEquals("s2", fill2.get("11"));
        assertEquals("4", fill2.get("32"), "LastQty");
        assertEquals("1", fill2.get("39"));
        assertTrue(!"Y".equals(fill2.get("43")));
        feed(ref2, "PARTIALLY_FILLED", 7, 3, 99);   // a redelivery adds nothing
        feed(ref2, "FILLED", 7, 0, 98);
        final Map<String, String> fill3 = c2.awaitType("8");
        assertEquals("3", fill3.get("32"));
        assertEquals("7", fill3.get("14"));
        assertEquals(String.valueOf(Integer.parseInt(fill2.get("34")) + 1), fill3.get("34"),
            "nothing between the two fills: no duplicate from A, none for the redelivery");
        c2.close();
    }

    /**
     * The owner stops renewing (a SIGSTOP stand-in) past the lease TTL; the other replica takes the session
     * over. The old owner, resumed and handed an order on its stale connection, must not submit it to the
     * cluster or write the session: it re-confirms the lease, finds it gone, and drops the client.
     */
    @Test
    void anExpiredOwnerIsFencedAfterTakeover() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a", "CLIENT4");
        final int b = startGateway(venue, "gw-b", "CLIENT4");
        final Client c1 = new Client(a, "CLIENT4");
        assertEquals("A", c1.logon().get("35"));
        stores.get(0).renewalsStalled = true;
        Thread.sleep(FixSessionStore.LEASE_TTL.toMillis() + 3_000);

        final Client c2 = new Client(b, "CLIENT4");
        c2.out = 2;
        assertEquals("A", c2.logon().get("35"), "B takes over once A's lease has aged out");
        c2.order("s1", '2', 10);
        assertEquals("s1", c2.awaitType("8").get("11"));
        final int before = venue.submitted.get();

        stores.get(0).renewalsStalled = false;   // SIGCONT
        c1.order("stale", '2', 10);
        try {
            final Map<String, String> m = c1.read();
            throw new AssertionError("the fenced owner answered: " + m);
        } catch (final IOException closed) {
            // dropped without an answer
        }
        assertEquals(before, venue.submitted.get(), "the fenced owner put an order into the cluster");
        c2.order("s2", '2', 10);
        final Map<String, String> next = c2.awaitType("8");
        assertEquals("s2", next.get("11"));
        assertEquals("4", next.get("34"), "B's sequence is untouched by A: Logon 2, s1 3, s2 4");
        c2.close();
    }

    /** NATS goes away mid-session: the live session ends at once (no hang), and a Logon is refused by name. */
    @Test
    void aStoreOutageEndsSessionsAndRefusesLogons() throws Exception {
        try (GenericContainer<?> nats = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
                 .withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))) {
            nats.start();
            final Venue venue = new Venue();
            final int a = startGateway(venue, "gw-a", "CLIENT5", nats);
            final Client c1 = new Client(a, "CLIENT5");
            assertEquals("A", c1.logon().get("35"));
            c1.order("s1", '2', 10);
            assertEquals("s1", c1.awaitType("8").get("11"));

            nats.stop();
            final long t0 = System.nanoTime();
            try {
                final Map<String, String> m = c1.read();
                throw new AssertionError("the session outlived its store: " + m);
            } catch (final IOException closed) {
                assertTrue(System.nanoTime() - t0 < 10_000_000_000L, "dropped promptly, not after a timeout");
            }
            final Client c2 = new Client(a, "CLIENT5");
            c2.out = 3;
            final Map<String, String> refused = c2.logon();
            assertEquals("5", refused.get("35"), refused.toString());
            assertTrue(refused.getOrDefault("58", "").contains("store unavailable"), refused.toString());
            assertEquals(1, venue.submitted.get());
        }
    }

    /** A gateway that starts before NATS still starts its acceptor: Logons are refused by name until NATS is up. */
    @Test
    void aGatewayStartedBeforeNatsServesFixOnceItArrives() throws Exception {
        final int natsPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            natsPort = s.getLocalPort();
        }
        final int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        final FixSessionStore store = new FixSessionStore("nats://127.0.0.1:" + natsPort, "gw-early");
        stores.add(store);
        final FixGatewayAcceptor gw = new FixGatewayAcceptor(new Venue(), null, port, "TRADERX", 11, List.of("CLIENT6"))
            .withSessionStore(store);
        gw.start();   // must not throw with NATS absent
        gateways.add(gw);
        final Client c1 = new Client(port, "CLIENT6");
        final Map<String, String> refused = c1.logon();
        assertEquals("5", refused.get("35"), refused.toString());
        assertTrue(refused.getOrDefault("58", "").contains("store unavailable"), refused.toString());
        // A refusal writes transiently and must not move the cached numbers: the client's retry, at the same
        // MsgSeqNum, is refused by name again, not dropped as "MsgSeqNum too low".
        final Client c1b = new Client(port, "CLIENT6");
        final Map<String, String> again = c1b.logon();
        assertTrue(again.getOrDefault("58", "").contains("store unavailable"), again.toString());

        try (org.testcontainers.containers.FixedHostPortGenericContainer<?> nats =
                 new org.testcontainers.containers.FixedHostPortGenericContainer<>("nats:2.14-alpine")) {
            nats.withFixedExposedPort(natsPort, 4222).withCommand("-js")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1)).start();
            for (int i = 0; i < 300 && !store.available(); i++) {
                Thread.sleep(100);   // jnats reconnect backoff, then bucket setup
            }
            assertTrue(store.available(), "the store never came up after NATS did");
            final Client c2 = new Client(port, "CLIENT6");
            assertEquals("A", c2.logon().get("35"), "Logon accepted once the store is up");
            c2.close();
        }
    }

    @Test
    void aSecondReplicaRefusesALiveSession() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a", "CLIENT2");
        final int b = startGateway(venue, "gw-b", "CLIENT2");
        final Client c1 = new Client(a, "CLIENT2");
        assertEquals("A", c1.logon().get("35"));

        final Client c2 = new Client(b, "CLIENT2");
        c2.out = 2;   // a second instance of the counterparty, continuing its numbers: past the sequence check
        final long asked = System.nanoTime();
        final Map<String, String> refused = c2.logon();
        final long refusedMs = (System.nanoTime() - asked) / 1_000_000;
        assertEquals("5", refused.get("35"), "B answers the Logon with Logout: " + refused);
        assertTrue(refused.getOrDefault("58", "").contains("live on another gateway"), refused.toString());
        // FR-GFS-16: A renews as live, so B refuses within a renewal or two, not after a 5 s wait.
        assertTrue(refusedMs < 1_500, "refused after " + refusedMs + " ms");

        // The live session on A is untouched.
        c1.order("s1", '2', 10);
        assertEquals("s1", c1.awaitType("8").get("11"));
        c1.close();
        c2.close();
    }

    /**
     * FR-GFS-16 (ADR-086 §6b): a gateway dies (no renewals, no release, its client cut off). The client's first Logon on
     * another gateway is held until the lease lapses and accepted then, about one TTL after the last renewal. It was
     * refused after 5 s, and accepted only on a retry after the 10 s TTL (xof: ~12 s for 48 sessions).
     */
    @Test
    void aDeadOwnersSessionIsTakenOverOnTheFirstLogonWithinTheLease() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a", "CLIENT5");
        final int b = startGateway(venue, "gw-b", "CLIENT5");
        final Client c1 = new Client(a, "CLIENT5");
        assertEquals("A", c1.logon().get("35"));
        c1.order("before", '2', 10);
        assertEquals("before", c1.awaitType("8").get("11"));

        final long died = System.nanoTime();
        stores.get(0).renewalsStalled = true;   // SIGKILL: no renewal, no release
        c1.rst();
        final Client c2 = new Client(b, "CLIENT5");
        c2.out = c1.out;
        final Map<String, String> logon = c2.logon();
        final long takeoverMs = (System.nanoTime() - died) / 1_000_000;
        assertEquals("A", logon.get("35"), "the first Logon after the owner died: " + logon);
        System.out.println("[takeover] " + takeoverMs + " ms (lease TTL " + FixSessionStore.LEASE_TTL.toMillis() + " ms)");
        assertTrue(takeoverMs <= FixSessionStore.LEASE_TTL.toMillis() + 1_500,
            "takeover took " + takeoverMs + " ms; the bound is the lease TTL plus a renewal");
        c2.order("after", '2', 10);
        assertEquals("after", c2.awaitType("8").get("11"));
        c2.close();
    }

    /** FR-GFS-16: a live owner stalled past the TTL, with nobody taking the session meanwhile, keeps it. */
    @Test
    void aStalledOwnerThatNobodyReplacedKeepsItsSession() throws Exception {
        final Venue venue = new Venue();
        final int a = startGateway(venue, "gw-a", "CLIENT6");
        startGateway(venue, "gw-b", "CLIENT6");
        final Client c1 = new Client(a, "CLIENT6");
        assertEquals("A", c1.logon().get("35"));
        stores.get(0).renewalsStalled = true;   // a CPU- or NATS-starved gateway
        Thread.sleep(FixSessionStore.LEASE_TTL.toMillis() + 1_000);
        stores.get(0).renewalsStalled = false;
        c1.order("still-mine", '2', 10);
        assertEquals("still-mine", c1.awaitType("8").get("11"), "the stalled owner lost a session nobody took");
        c1.close();
    }

    private static String now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"));
    }

    /** Minimal raw FIX 4.4 counterparty. Field maps are keyed by tag number. */
    private static final class Client {
        final Socket socket;
        final InputStream in;
        final OutputStream os;
        final String compId;
        int out = 1;
        Map<String, String> lastIn = Map.of();
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

        void order(final String clOrdId, final char side, final int qty) throws IOException {
            send("D", "11=" + clOrdId + "\u00011=11\u000155=IBM\u000154=" + side + "\u000160=" + now()
                + "\u000138=" + qty + "\u000140=2\u000144=100");
        }

        void send(final String type, final String body) throws IOException {
            final String b = "35=" + type + "\u000149=" + compId + "\u000156=TRADERX\u000134=" + (out++)
                + "\u000152=" + now() + "\u0001" + body + "\u0001";
            final String head = "8=FIX.4.4\u00019=" + b.getBytes(StandardCharsets.US_ASCII).length + "\u0001";
            int sum = 0;
            for (final byte x : (head + b).getBytes(StandardCharsets.US_ASCII)) {
                sum += x & 0xff;
            }
            os.write((head + b + String.format("10=%03d\u0001", sum % 256)).getBytes(StandardCharsets.US_ASCII));
            os.flush();
        }

        /** Next message of this type, skipping heartbeats and the like. */
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
                    lastIn = m;
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

        /** Abrupt: no Logout, unread data discarded, RST on the wire. */
        void rst() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        void close() throws IOException {
            socket.close();
        }
    }
}
