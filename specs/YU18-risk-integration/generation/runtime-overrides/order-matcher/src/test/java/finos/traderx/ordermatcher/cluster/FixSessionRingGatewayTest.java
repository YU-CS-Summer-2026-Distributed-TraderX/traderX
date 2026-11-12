package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import finos.traderx.ordermatcher.risk.SessionRing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quickfix.Session;
import quickfix.SessionID;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-104 (FR-XOD-24/25) at the FIX gateway: each pipelined NewOrderSingle carries its session tail, a ring answer is
 * restated, an aged-out resend is a 35=j, and a ring answer naming another of this gateway's orders is resubmitted
 * under a salted key, so a collision never refuses a real order. The venue runs the engine's {@link SessionRing}.
 */
@Timeout(60)
class FixSessionRingGatewayTest {
    private FixGatewayAcceptor acceptor;
    private int port;
    private final StringBuffer wire = new StringBuffer();

    @AfterEach
    void tearDown() {
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    /** The engine's ring decisions, N = 4; {@code forceRef} answers the next NEW as a ring hit on that ref. */
    private static final class Venue implements OrderSubmitter {
        final SessionRing ring = new SessionRing(4, 4, 3_600_000L);
        final List<long[]> tags = new ArrayList<>();   // {sessionStream, check, msgSeqNum, possFlags}
        final List<String> keys = new ArrayList<>();
        int nextRef = 1;
        volatile int forceRef;

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker, final char side,
                                      final int qty, final long limitPxTicks) {
            throw new AssertionError("an untagged NEW under FIX_EXACTLY_ONCE=engine");
        }

        @Override
        public synchronized CompletableFuture<ExecResult> submitSessionKeyedAsync(
                final String clOrdId, final int accountId, final String ticker, final char side, final int qty,
                final long limitPxTicks, final TypedOrder typed, final long sessionStream, final int msgSeqNum,
                final int possFlags, final Executor completions) {
            tags.add(new long[] { sessionStream, ClusterGatewayMain.clientOrderCheck(clOrdId), msgSeqNum, possFlags });
            keys.add(clOrdId);
            final byte dup = (byte) RiskReason.DUPLICATE.ordinal();
            if (forceRef > 0) {
                final int ref = forceRef;
                forceRef = 0;
                return CompletableFuture.completedFuture(new ExecResult(false, ref, OutputEvent.KIND_ORDER_REJECTED, dup));
            }
            final ExecResult r = switch (ring.admit(sessionStream, ClusterGatewayMain.clientOrderKey(clOrdId),
                    ClusterGatewayMain.clientOrderCheck(clOrdId), msgSeqNum,
                    (possFlags & AeronReplicationCodec.INPUT_FLAG_POSS_DUP) != 0,
                    (possFlags & AeronReplicationCodec.INPUT_FLAG_POSS_RESEND) != 0, 0L)) {
                case SessionRing.ADMIT -> {
                    final int ref = nextRef++;
                    ring.record(ref, (byte) RiskReason.ACCEPTED.ordinal());
                    yield new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED);
                }
                case SessionRing.DUPLICATE -> new ExecResult(false, ring.duplicateRef(), OutputEvent.KIND_ORDER_REJECTED, dup);
                case SessionRing.AGED_OUT -> new ExecResult(false, 0, OutputEvent.KIND_ORDER_REJECTED,
                    (byte) RiskReason.SESSION_AGED_OUT.ordinal());
                default -> throw new AssertionError("LIMIT");
            };
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public ExecResult submitCancel(final long orderRef) {
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }

    @Test
    void aFixSessionRidesTheEngineRing() throws Exception {
        final Venue venue = new Venue();
        start(venue);
        try (Socket s = logon("CLIENT1")) {
            for (int i = 1; i <= 5; i++) {
                s.getOutputStream().write(order(s, i + 1, "c-" + i, ""));
                awaitWire("37=ord-" + i);
            }
            synchronized (venue) {
                final long stream = venue.tags.get(0)[0];
                assertNotEquals(0L, stream);
                for (int i = 0; i < 5; i++) {
                    final long[] t = venue.tags.get(i);
                    assertEquals(stream, t[0], "one session-day, one stream");
                    assertEquals(ClusterGatewayMain.clientOrderCheck("CLIENT1\001c-" + (i + 1)), (int) t[1]);
                    assertEquals(i + 2, t[2], "the NewOrderSingle's MsgSeqNum (34)");
                    assertEquals(0L, t[3], "neither PossDup nor PossResend");
                }
            }

            s.getOutputStream().write(order(s, 7, "c-5", "97=Y"));   // inside the ring: its original
            awaitWire("DUPLICATE: ClOrdID already executed as ord-5");
            synchronized (venue) {
                assertEquals(AeronReplicationCodec.INPUT_FLAG_POSS_RESEND, venue.tags.get(5)[3]);
            }

            s.getOutputStream().write(order(s, 8, "c-1", "97=Y"));   // evicted: may have executed
            awaitWire("EXACTLY_ONCE_AGED_OUT");
            assertTrue(wire.indexOf("35=j") >= 0, "a BusinessMessageReject, never 39=8");

            venue.forceRef = 2;   // FR-XOD-25: the ring names c-2's order for a new ClOrdID (key and check collide)
            s.getOutputStream().write(order(s, 9, "c-6", ""));
            awaitWire("37=ord-6");
            synchronized (venue) {
                assertEquals("CLIENT1\001c-6\001#", venue.keys.get(venue.keys.size() - 1), "resubmitted salted");
            }
            assertTrue(Session.lookupSession(new SessionID("FIX.4.4", "TRADERX", "CLIENT1")).isLoggedOn());
        }
    }

    /** FR-XOD-24: the engine ring holds twice what one FIX session can have undecided, at the shipped defaults. */
    @Test
    void theRingHoldsTwiceTheGatewaysInFlightCap() {
        assertTrue(MatchingEngineClusteredService.SESSION_RING >= 2 * FixGatewayAcceptor.MAX_IN_FLIGHT,
            "EXACTLY_ONCE_SESSION_RING " + MatchingEngineClusteredService.SESSION_RING + " < 2 x MAX_IN_FLIGHT "
                + FixGatewayAcceptor.MAX_IN_FLIGHT);
    }

    // ---- plumbing (FixGatewayMemoryBoundTest's raw-socket client) ---------------------------------------------------

    private void start(final OrderSubmitter submitter) throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        acceptor = new FixGatewayAcceptor(submitter, null, port, "TRADERX", 11, List.of("CLIENT1")).withSessionKeyed();
        acceptor.start();
    }

    private Socket logon(final String comp) throws Exception {
        final Socket s = new Socket("127.0.0.1", port);
        s.setTcpNoDelay(true);
        final InputStream in = s.getInputStream();
        final Thread reader = new Thread(() -> {
            final byte[] buf = new byte[1 << 16];
            try {
                for (int r; (r = in.read(buf)) > 0; ) {
                    wire.append(new String(buf, 0, r, StandardCharsets.US_ASCII));
                }
            } catch (final java.io.IOException closed) {
                // test over
            }
        });
        reader.setDaemon(true);
        reader.start();
        s.getOutputStream().write(frame(comp, 1, "A", "", "98=0", "108=30", "141=Y"));
        await(() -> {
            final Session session = Session.lookupSession(new SessionID("FIX.4.4", "TRADERX", comp));
            return session != null && session.isLoggedOn();
        }, comp + " logon");
        return s;
    }

    private static byte[] order(final Socket s, final int seq, final String clOrdId, final String header) {
        return frame("CLIENT1", seq, "D", header, "11=" + clOrdId, "55=AAPL", "54=1", "60=20261028-12:00:00.000",
            "38=1", "40=2", "44=150", "21=1");
    }

    private static byte[] frame(final String comp, final int seq, final String type, final String header,
                                final String... fields) {
        final StringBuilder body = new StringBuilder("35=").append(type).append("\u000149=").append(comp)
            .append("\u000156=TRADERX\u000134=").append(seq).append("\u000152=")
            .append(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))).append('\u0001');
        if (!header.isEmpty()) {
            body.append(header).append('\u0001');
        }
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

    private void awaitWire(final String text) throws InterruptedException {
        await(() -> wire.indexOf(text) >= 0, "'" + text + "' from the gateway");
    }

    private static void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }
}
