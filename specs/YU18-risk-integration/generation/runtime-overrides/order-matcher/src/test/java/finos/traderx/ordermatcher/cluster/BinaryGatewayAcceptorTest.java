package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binary decode path with no cluster (the one non-trivial piece the acceptor adds is a
 * hand-rolled flyweight parser — offsets, little-endian widths, message-type dispatch). It proves
 * each framed client message lands on the RIGHT seam call with the RIGHT numeric fields, and that
 * the fixed-layout ack carries the committed outcome back with the client's clOrdId echoed — the
 * failure that would silently mis-map a field offset and book the wrong order. The live GKE run
 * proves the throughput/GC win; this proves the wire is decoded correctly while doing it.
 */
@Timeout(30)
class BinaryGatewayAcceptorTest {
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    /** Records the last seam call so the test can assert the decode mapped to it exactly. */
    private static final class FakeSubmitter implements OrderSubmitter {
        String call;
        long clientKey, limitPx;
        int accountId, securityId, qty, orderRef;
        char side;
        ExecResult next; // returned to the acceptor (null = post-publish ambiguity)

        @Override
        public ExecResult submitOrder(String clOrdId, int accountId, String ticker, char side,
                                      int qty, long limitPxTicks) {
            throw new AssertionError("binary path must use the numeric overload, not the String one");
        }

        @Override
        public ExecResult submitOrder(long clientKey, int accountId, int securityId, char side,
                                      int qty, long limitPxTicks) {
            this.call = "NEW";
            this.clientKey = clientKey;
            this.accountId = accountId;
            this.securityId = securityId;
            this.side = side;
            this.qty = qty;
            this.limitPx = limitPxTicks;
            return next;
        }

        @Override
        public ExecResult submitCancel(int orderRef) {
            this.call = "CANCEL";
            this.orderRef = orderRef;
            return next;
        }

        @Override
        public ExecResult submitReplace(int orderRef, long clientKey, int qty, long limitPxTicks) {
            this.call = "REPLACE";
            this.orderRef = orderRef;
            this.clientKey = clientKey;
            this.qty = qty;
            this.limitPx = limitPxTicks;
            return next;
        }
    }

    private static UnsafeBuffer buf() {
        return new UnsafeBuffer(new byte[BinaryGatewayAcceptor.MAX_PAYLOAD]);
    }

    @Test
    void newOrderDecodesToNumericSeamCallAndEchoesAck() {
        final FakeSubmitter fake = new FakeSubmitter();
        fake.next = new OrderSubmitter.ExecResult(true, 4242, OutputEvent.KIND_ORDER_ACCEPTED, (byte) 0);
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);

        final UnsafeBuffer in = buf();
        in.putByte(0, (byte) BinaryGatewayAcceptor.MSG_NEW);
        in.putByte(1, (byte) 1);                 // side = SELL
        in.putInt(4, 22214, LE);                 // account
        in.putInt(8, 7, LE);                     // security (pre-resolved)
        in.putInt(12, 10, LE);                   // qty
        in.putLong(16, 100_000_000L, LE);        // limitPx = 100.00 x1e6
        in.putLong(24, 0xABCDEF01L, LE);         // clOrdId

        final UnsafeBuffer ack = buf();
        final int ackLen = acc.dispatch(in, 0, 32, ack);

        assertEquals("NEW", fake.call);
        assertEquals(0xABCDEF01L, fake.clientKey, "clOrdId used directly as the idempotency key");
        assertEquals(22214, fake.accountId);
        assertEquals(7, fake.securityId);
        assertEquals('S', fake.side);
        assertEquals(10, fake.qty);
        assertEquals(100_000_000L, fake.limitPx);

        assertAck(ack, ackLen, BinaryGatewayAcceptor.STATUS_ACCEPTED,
            OutputEvent.KIND_ORDER_ACCEPTED, (byte) 0, 4242, 0xABCDEF01L);
    }

    @Test
    void cancelDecodesToOrderRefAndEchoesAck() {
        final FakeSubmitter fake = new FakeSubmitter();
        fake.next = new OrderSubmitter.ExecResult(true, 99, OutputEvent.KIND_ORDER_CANCELED, (byte) 0);
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);

        final UnsafeBuffer in = buf();
        in.putByte(0, (byte) BinaryGatewayAcceptor.MSG_CANCEL);
        in.putInt(4, 99, LE);                    // orderRef
        in.putLong(8, 0x1122L, LE);              // clOrdId

        final UnsafeBuffer ack = buf();
        final int ackLen = acc.dispatch(in, 0, 16, ack);

        assertEquals("CANCEL", fake.call);
        assertEquals(99, fake.orderRef);
        assertAck(ack, ackLen, BinaryGatewayAcceptor.STATUS_ACCEPTED,
            OutputEvent.KIND_ORDER_CANCELED, (byte) 0, 99, 0x1122L);
    }

    @Test
    void replaceDecodesNewQtyPriceAndKey() {
        final FakeSubmitter fake = new FakeSubmitter();
        fake.next = new OrderSubmitter.ExecResult(true, 77, OutputEvent.KIND_ORDER_ACCEPTED, (byte) 0);
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);

        final UnsafeBuffer in = buf();
        in.putByte(0, (byte) BinaryGatewayAcceptor.MSG_REPLACE);
        in.putInt(4, 77, LE);                    // orderRef
        in.putInt(8, 25, LE);                    // new qty
        in.putLong(16, 101_500_000L, LE);        // new limitPx
        in.putLong(24, 0x5A5AL, LE);             // clOrdId

        final UnsafeBuffer ack = buf();
        acc.dispatch(in, 0, 32, ack);

        assertEquals("REPLACE", fake.call);
        assertEquals(77, fake.orderRef);
        assertEquals(25, fake.qty);
        assertEquals(101_500_000L, fake.limitPx);
        assertEquals(0x5A5AL, fake.clientKey);
    }

    @Test
    void nullResultIsAmbiguousNotReject() {
        final FakeSubmitter fake = new FakeSubmitter();
        fake.next = null; // post-publish ambiguity
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);

        final UnsafeBuffer in = buf();
        in.putByte(0, (byte) BinaryGatewayAcceptor.MSG_CANCEL);
        in.putInt(4, 5, LE);
        in.putLong(8, 0x9L, LE);

        final UnsafeBuffer ack = buf();
        final int ackLen = acc.dispatch(in, 0, 16, ack);
        // Ambiguous must be distinct from a committed reject, and must still echo the clOrdId.
        assertAck(ack, ackLen, BinaryGatewayAcceptor.STATUS_AMBIGUOUS, (byte) 0, (byte) 0, 0, 0x9L);
    }

    @Test
    void unknownMessageTypeIsProtocolError() {
        final FakeSubmitter fake = new FakeSubmitter();
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);

        final UnsafeBuffer in = buf();
        in.putByte(0, (byte) 0x7E); // not a known type

        final UnsafeBuffer ack = buf();
        final int ackLen = acc.dispatch(in, 0, 32, ack);
        assertEquals(null, fake.call, "no seam call for an unknown message type");
        assertAck(ack, ackLen, BinaryGatewayAcceptor.STATUS_PROTOCOL_ERROR, (byte) 0, (byte) 0, 0, 0L);
    }

    /**
     * A real TCP round trip through {@code start()} / the accept loop / the per-connection read
     * framing (length prefix + readFully) — the socket path the dispatch unit tests skip. The frame is
     * built with the exact little-endian byte layout {@code bin-multi.mjs} writes, so a green run also
     * cross-checks that the generator and the gateway agree on the wire down to the byte.
     */
    @Test
    void realSocketRoundTripThroughFramingAndAck() throws Exception {
        final FakeSubmitter fake = new FakeSubmitter();
        fake.next = new OrderSubmitter.ExecResult(true, 4242, OutputEvent.KIND_ORDER_ACCEPTED, (byte) 0);
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0); // ephemeral port
        acc.start();
        try (Socket s = new Socket("localhost", acc.boundPort())) {
            s.setTcpNoDelay(true);
            final ByteBuffer f = ByteBuffer.allocate(2 + 32).order(ByteOrder.LITTLE_ENDIAN);
            f.putShort((short) 32);                        // length prefix
            f.put((byte) BinaryGatewayAcceptor.MSG_NEW);   // payload[0]
            f.put((byte) 1);                               // payload[1] side = SELL
            f.putShort((short) 0);                         // payload[2..3] pad
            f.putInt(22214);                               // payload[4] account
            f.putInt(7);                                   // payload[8] security
            f.putInt(10);                                  // payload[12] qty
            f.putLong(100_000_000L);                       // payload[16] limitPx
            f.putLong(0xABCDEF01L);                         // payload[24] clOrdId
            final OutputStream out = s.getOutputStream();
            out.write(f.array());
            out.flush();

            final DataInputStream in = new DataInputStream(s.getInputStream());
            final byte[] ackBytes = new byte[2 + BinaryGatewayAcceptor.ACK_PAYLOAD_LEN];
            in.readFully(ackBytes);
            final ByteBuffer a = ByteBuffer.wrap(ackBytes).order(ByteOrder.LITTLE_ENDIAN);
            // Receiving these values (the ExecResult the fake returned) proves the frame was received,
            // decoded, submitted and acked back through real sockets — and the clOrdId echo proves the
            // decoder read it from the right offset.
            assertEquals(BinaryGatewayAcceptor.ACK_PAYLOAD_LEN, a.getShort(0), "ack length prefix");
            assertEquals((byte) BinaryGatewayAcceptor.ACK_TYPE, a.get(2), "ack message type");
            assertEquals(BinaryGatewayAcceptor.STATUS_ACCEPTED, a.get(3), "status");
            assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, a.get(4), "kind");
            assertEquals(4242, a.getInt(6), "orderRef");
            assertEquals(0xABCDEF01L, a.getLong(10), "clOrdId echo");
        } finally {
            acc.stop();
        }
    }

    /**
     * FR-BSP-01: a submitter whose answers arrive late and in whatever order the test gives them, standing in for
     * the cluster. Each NEW is held, in the order the seam was called, until the test answers it; an accepted answer
     * carries orderRef = its clOrdId, so an ack can be matched to its request by both fields.
     */
    private static final class GatedSubmitter implements OrderSubmitter {
        record Held(long clientKey, CompletableFuture<ExecResult> answer) { }
        final LinkedBlockingDeque<Held> held = new LinkedBlockingDeque<>();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public ExecResult submitOrder(String clOrdId, int accountId, String ticker, char side, int qty,
                                      long limitPxTicks) {
            throw new AssertionError("binary path must use the numeric overload");
        }

        @Override
        public ExecResult submitOrder(long clientKey, int accountId, int securityId, char side, int qty,
                                      long limitPxTicks) {
            throw new AssertionError("the pipelined acceptor must not wait on the synchronous seam");
        }

        @Override
        public CompletableFuture<ExecResult> submitOrderAsync(long clientKey, int accountId, int securityId,
                                                              char side, int qty, long limitPxTicks,
                                                              Executor completions) {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            final Held h = new Held(clientKey, new CompletableFuture<>());
            held.add(h);
            return h.answer;
        }

        void answer(final Held h, final boolean ambiguous) {
            concurrent.decrementAndGet();
            h.answer.complete(ambiguous ? null
                : new ExecResult(true, (int) h.clientKey, OutputEvent.KIND_ORDER_ACCEPTED, (byte) 0));
        }
    }

    private static byte[] newFrame(final long clOrdId) {
        final ByteBuffer f = ByteBuffer.allocate(2 + 32).order(ByteOrder.LITTLE_ENDIAN);
        f.putShort((short) 32).put((byte) BinaryGatewayAcceptor.MSG_NEW).put((byte) 0).putShort((short) 0)
            .putInt(22214).putInt(7).putInt(10).putLong(100_000_000L).putLong(clOrdId);
        return f.array();
    }

    private static void await(final BooleanSupplier cond, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (!cond.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    /** Reads one ack; an accepted one must carry its own request's outcome (orderRef = clOrdId). */
    private static ByteBuffer readAck(final DataInputStream in) throws Exception {
        final byte[] ackBytes = new byte[2 + BinaryGatewayAcceptor.ACK_PAYLOAD_LEN];
        in.readFully(ackBytes);
        final ByteBuffer a = ByteBuffer.wrap(ackBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (a.get(3) == BinaryGatewayAcceptor.STATUS_ACCEPTED) {
            assertEquals(a.getLong(10), a.getInt(6), "the ack carries its own request's outcome");
        }
        return a;
    }

    private static java.util.Set<Thread> connectionThreads() {
        final java.util.Set<Thread> ts = new java.util.HashSet<>(Thread.getAllStackTraces().keySet());
        ts.removeIf(t -> !t.getName().equals("binary-conn") || !t.isAlive());
        return ts;
    }

    /**
     * FR-BSP-01: one connection carries many orders at once. 64 NEWs are written in one go with the
     * per-connection bound at 8: the gateway reads exactly 8 and stops (the rest wait in TCP), the
     * test answers each window newest first, and every clOrdId comes back exactly once, in the order
     * the answers were given rather than the order sent, with the bound reached and never exceeded.
     */
    @Test
    void aConnectionPipelinesUpToItsBoundAndAcksInCompletionOrder() throws Exception {
        final GatedSubmitter fake = new GatedSubmitter();
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);
        acc.maxInFlight = 8;
        acc.start();
        try (Socket s = new Socket("localhost", acc.boundPort())) {
            s.setTcpNoDelay(true);
            final OutputStream out = s.getOutputStream();
            final java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (long id = 1; id <= 64; id++) {
                all.write(newFrame(id));
            }
            out.write(all.toByteArray());
            out.flush();

            await(() -> fake.held.size() == 8, "the first window");
            Thread.sleep(200);
            assertEquals(8, acc.decodedFrames(), "a full window stops reading");

            final List<Long> answered = new ArrayList<>();
            while (answered.size() < 64) {
                final int expect = Math.min(8, 64 - answered.size());
                await(() -> fake.held.size() == expect, "a window of " + expect);
                final List<GatedSubmitter.Held> window = new ArrayList<>();
                fake.held.drainTo(window);
                for (int i = 0; i < window.size(); i++) {
                    assertEquals(answered.size() + i + 1, window.get(i).clientKey(),
                        "the seam sees a connection's orders in the order they arrived");
                }
                window.sort((a, b) -> Long.compare(b.clientKey(), a.clientKey())); // answer newest first
                for (final GatedSubmitter.Held h : window) {
                    final long before = acc.acknowledgedFrames();
                    fake.answer(h, false);
                    answered.add(h.clientKey());
                    await(() -> acc.acknowledgedFrames() == before + 1, "ack of " + h.clientKey());
                }
            }

            final DataInputStream in = new DataInputStream(s.getInputStream());
            final List<Long> acked = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final ByteBuffer a = readAck(in);
                assertEquals(BinaryGatewayAcceptor.STATUS_ACCEPTED, a.get(3), "status");
                acked.add(a.getLong(10));
            }
            assertEquals(answered, acked, "acks leave in completion order");
            final List<Long> expected = new ArrayList<>();
            for (long w = 8; w <= 64; w += 8) {
                for (long id = w; id > w - 8; id--) {
                    expected.add(id); // frames are read in order, so window k holds 8k-7..8k
                }
            }
            assertEquals(expected, acked, "each window answered newest first: 8..1, 16..9, ...");
            assertEquals(64, acked.stream().distinct().count(), "every clOrdId acked exactly once");
            assertEquals(8, fake.maxConcurrent.get(), "the bound is reached and never exceeded");
        } finally {
            acc.stop();
        }
    }

    /** FR-BSP-01: a client that half-closes after sending still receives every ack it is owed, and an order with no
     *  committed decision is still answered ambiguous, never rejected. */
    @Test
    void aHalfClosedConnectionIsStillAnsweredInFull() throws Exception {
        final GatedSubmitter fake = new GatedSubmitter();
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);
        acc.start();
        try (Socket s = new Socket("localhost", acc.boundPort())) {
            final OutputStream out = s.getOutputStream();
            for (long id = 1; id <= 3; id++) {
                out.write(newFrame(id));
            }
            out.flush();
            await(() -> fake.held.size() == 3, "three submits");
            s.shutdownOutput(); // EOF on the gateway's read side while all three are pending
            Thread.sleep(100);
            for (GatedSubmitter.Held h; (h = fake.held.pollFirst()) != null; ) {
                fake.answer(h, h.clientKey() == 2); // order 2: no committed decision
            }
            final DataInputStream in = new DataInputStream(s.getInputStream());
            final java.util.Map<Long, Byte> status = new java.util.HashMap<>();
            for (int i = 0; i < 3; i++) {
                final ByteBuffer a = readAck(in);
                assertEquals(null, status.put(a.getLong(10), a.get(3)), "acked once");
            }
            assertEquals(java.util.Map.of(1L, BinaryGatewayAcceptor.STATUS_ACCEPTED, 2L,
                BinaryGatewayAcceptor.STATUS_AMBIGUOUS, 3L, BinaryGatewayAcceptor.STATUS_ACCEPTED), status);
            assertEquals(-1, in.read(), "the gateway closes once the last ack is out");
        } finally {
            acc.stop();
        }
    }

    /**
     * FR-BSP-01: a client that vanishes with orders pending leaks nothing: every submit returns once
     * its answer arrives (frames the client sent before closing may still be read and submitted, as
     * TCP delivered them), the connection thread exits, and the gateway takes the next connection.
     */
    @Test
    void aClosedConnectionWithOrdersPendingLeaksNothing() throws Exception {
        final GatedSubmitter fake = new GatedSubmitter();
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);
        acc.maxInFlight = 4;
        acc.start();
        try {
            final java.util.Set<Thread> before = connectionThreads();
            final Thread conn;
            try (Socket s = new Socket("localhost", acc.boundPort())) {
                final OutputStream out = s.getOutputStream();
                for (long id = 1; id <= 6; id++) {
                    out.write(newFrame(id));
                }
                out.flush();
                await(() -> fake.held.size() == 4, "a full window");
                final java.util.Set<Thread> now = connectionThreads();
                now.removeAll(before);
                assertEquals(1, now.size(), "one connection thread for this socket");
                conn = now.iterator().next();
            } // closed with 4 submitted and 2 unread
            Thread.sleep(100);
            assertTrue(conn.isAlive(), "the connection waits for its pending orders");
            await(() -> {
                for (GatedSubmitter.Held h; (h = fake.held.pollFirst()) != null; ) {
                    fake.answer(h, false);
                }
                return !conn.isAlive();
            }, "the connection thread to exit");
            assertEquals(0, fake.concurrent.get(), "every submit answered");
            await(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.getName().equals("binary-ack") && t.isAlive()), "the writer to exit too");
            assertTrue(acc.decodedFrames() >= 4 && acc.decodedFrames() <= 6, "decoded " + acc.decodedFrames());
            assertTrue(fake.maxConcurrent.get() <= 4, "the bound held through the close");

            try (Socket s2 = new Socket("localhost", acc.boundPort())) {
                s2.getOutputStream().write(newFrame(99));
                await(() -> fake.held.size() == 1, "the next connection's submit");
                fake.answer(fake.held.pollFirst(), false);
                assertEquals(99L, readAck(new DataInputStream(s2.getInputStream())).getLong(10));
            }
        } finally {
            acc.stop();
        }
    }

    private static void assertAck(final UnsafeBuffer ack, final int ackLen, final byte status,
                                  final byte kind, final byte riskReason, final int orderRef,
                                  final long clOrdId) {
        assertEquals(2 + BinaryGatewayAcceptor.ACK_PAYLOAD_LEN, ackLen);
        assertEquals(BinaryGatewayAcceptor.ACK_PAYLOAD_LEN, ack.getShort(0, LE), "length prefix");
        assertEquals((byte) BinaryGatewayAcceptor.ACK_TYPE, ack.getByte(2), "ack message type");
        assertEquals(status, ack.getByte(3), "status");
        assertEquals(kind, ack.getByte(4), "kind");
        assertEquals(riskReason, ack.getByte(5), "riskReason");
        assertEquals(orderRef, ack.getInt(6, LE), "orderRef");
        assertEquals(clOrdId, ack.getLong(10, LE), "clOrdId echo");
    }
}
