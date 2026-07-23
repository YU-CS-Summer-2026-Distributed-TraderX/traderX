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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
