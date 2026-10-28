package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quickfix.Session;
import quickfix.SessionID;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-092 (FR-FGM-01/02): what a FIX session can make this gateway hold is bounded by a count of messages, not by
 * how long it runs or how far its client lags. f15ho (1 h at 400K/s, ~22K orders/s per gateway) filled a 768 MiB
 * heap; locally, every unread ExecutionReport stayed queued in MINA (~690 bytes each) for as long as the client
 * kept reading too slowly (one that reads nothing is cut off only by MINA's 60 s write timeout). Raw sockets, so the
 * test decides whether the client reads; the stub submitter answers at once.
 */
@Timeout(90)
class FixGatewayMemoryBoundTest {
    private static final SessionID CLIENT1 = new SessionID("FIX.4.4", "TRADERX", "CLIENT1");
    private FixGatewayAcceptor acceptor;
    private int port;

    @AfterEach
    void tearDown() {
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    private final class Venue implements OrderSubmitter {
        final AtomicInteger nextRef = new AtomicInteger(1);
        volatile CountDownLatch open = new CountDownLatch(0);

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            try {
                open.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecResult(true, nextRef.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitCancel(final int orderRef) {
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }

    @Test
    void aClientThatStopsReadingIsDroppedAtTheLimit() throws Exception {
        start(new Venue());
        final int orders = 8 * FixGatewayAcceptor.MAX_QUEUED_WRITES;

        // Control: a client that reads gets every answer and keeps its session.
        try (Socket reading = logon("CLIENT2")) {
            final AtomicInteger reports = countReports(reading);
            final OutputStream out = reading.getOutputStream();
            for (int i = 1; i <= orders; i++) {
                out.write(order("CLIENT2", i + 1, "r-" + i));
            }
            out.flush();
            await(() -> reports.get() >= orders, "the reading client's " + orders + " answers");
            assertTrue(Session.lookupSession(new SessionID("FIX.4.4", "TRADERX", "CLIENT2")).isLoggedOn(),
                "a client that keeps up was dropped");
        }

        // The same orders from a client that never reads: its answers can queue up to the limit, then the session ends.
        try (Socket silent = logon("CLIENT1")) {
            final Thread sender = new Thread(() -> {
                try {
                    final OutputStream out = silent.getOutputStream();
                    for (int i = 1; i <= orders; i++) {
                        out.write(order("CLIENT1", i + 1, "s-" + i));
                    }
                    out.flush();
                } catch (final IOException closedByTheGateway) {
                    // expected once the gateway drops the session
                }
            });
            sender.setDaemon(true);
            sender.start();
            await(() -> !Session.lookupSession(CLIENT1).isLoggedOn(),
                "the gateway to drop a client that read none of " + orders + " answers (it queued them all instead)");
            // QuickFIX/J closes on flush: what it queued (at most the limit) is still readable, then EOF.
            silent.setSoTimeout(10_000);
            final InputStream in = silent.getInputStream();
            final byte[] buf = new byte[1 << 16];
            try {
                while (in.read(buf) >= 0) { }
            } catch (final SocketTimeoutException stillOpen) {
                fail("the gateway left the slow consumer's connection open");
            } catch (final IOException reset) {
                // closed with unread data: also closed
            }
        }
    }

    @Test
    void aClientThatOutrunsItsAnswersIsHeldBackByTcpInsteadOfQueued() throws Exception {
        final Venue venue = new Venue();
        venue.open = new CountDownLatch(1);   // the venue does not answer: every order waits on the first one
        start(venue);
        final int orders = 20_000;
        try (Socket fast = logon("CLIENT1")) {
            outrun(venue, fast, orders);
        } finally {
            venue.open.countDown();   // or a full queue keeps a MINA I/O thread blocked and stop() never returns
        }
    }

    private void outrun(final Venue venue, final Socket fast, final int orders) throws Exception {
        {
            final AtomicInteger reports = countReports(fast);
            final Thread sender = new Thread(() -> {
                try {
                    final OutputStream out = fast.getOutputStream();
                    for (int i = 1; i <= orders; i++) {
                        out.write(order("CLIENT1", i + 1, "f-" + i));
                    }
                    out.flush();
                } catch (final IOException e) {
                    // reported by the count below
                }
            });
            sender.setDaemon(true);
            sender.start();

            // Wait until the queue stops growing: the gateway stopped reading, or it held everything.
            int queued = -1;
            for (int stable = 0; stable < 10; ) {
                Thread.sleep(200);
                final int now = acceptor.inboundQueued();
                stable = now == queued ? stable + 1 : 0;
                queued = now;
            }
            assertTrue(queued > FixGatewayAcceptor.INBOUND_QUEUE / 2, "the client did not outrun its answers: " + queued);
            assertTrue(queued <= FixGatewayAcceptor.INBOUND_QUEUE,
                queued + " orders queued in the gateway for one session; the bound is " + FixGatewayAcceptor.INBOUND_QUEUE);

            venue.open.countDown();
            await(() -> reports.get() >= orders, "every order answered once the venue answers (" + reports.get() + ")");
            assertEquals(orders, reports.get(), "held back is not lost: each order answered exactly once");
            assertTrue(Session.lookupSession(CLIENT1).isLoggedOn(), "backpressure must not end the session");
        }
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private void start(final OrderSubmitter submitter) throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        acceptor = new FixGatewayAcceptor(submitter, null, port, "TRADERX", 11, List.of("CLIENT1", "CLIENT2"));
        acceptor.start();
    }

    private Socket logon(final String comp) throws Exception {
        final Socket s = new Socket("127.0.0.1", port);
        s.setTcpNoDelay(true);
        s.getOutputStream().write(frame(comp, 1, "A", "98=0", "108=30", "141=Y"));
        await(() -> {
            final Session session = Session.lookupSession(new SessionID("FIX.4.4", "TRADERX", comp));
            return session != null && session.isLoggedOn();
        }, comp + " logon");
        return s;
    }

    private static byte[] order(final String comp, final int seq, final String clOrdId) {
        return frame(comp, seq, "D", "11=" + clOrdId, "55=AAPL", "54=1", "60=20261028-12:00:00.000", "38=1", "40=2",
            "44=150", "21=1");
    }

    /** ExecutionReports read off {@code s} on a reader thread. */
    private static AtomicInteger countReports(final Socket s) throws IOException {
        final AtomicInteger n = new AtomicInteger();
        final InputStream in = s.getInputStream();
        final Thread reader = new Thread(() -> {
            final byte[] pat = "\u000135=8\u0001".getBytes(StandardCharsets.US_ASCII);
            final byte[] buf = new byte[1 << 16];
            int m = 0;
            try {
                for (int r; (r = in.read(buf)) > 0; ) {
                    for (int i = 0; i < r; i++) {
                        m = buf[i] == pat[m] ? m + 1 : buf[i] == pat[0] ? 1 : 0;
                        if (m == pat.length) {
                            n.incrementAndGet();
                            m = 1;
                        }
                    }
                }
            } catch (final IOException closed) {
                // test over
            }
        });
        reader.setDaemon(true);
        reader.start();
        return n;
    }

    private static byte[] frame(final String comp, final int seq, final String type, final String... fields) {
        final StringBuilder body = new StringBuilder("35=").append(type).append("\u000149=").append(comp)
            .append("\u000156=TRADERX\u000134=").append(seq).append("\u000152=")
            .append(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))).append('\u0001');
        for (final String f : fields) {
            body.append(f).append('\u0001');
        }
        final String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001";
        int sum = 0;
        for (final char c : (head + body).toCharArray()) {
            sum += c;
        }
        return (head + body + "10=" + String.format("%03d", sum % 256) + "\u0001").getBytes(StandardCharsets.US_ASCII);
    }

    private static void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }
}
