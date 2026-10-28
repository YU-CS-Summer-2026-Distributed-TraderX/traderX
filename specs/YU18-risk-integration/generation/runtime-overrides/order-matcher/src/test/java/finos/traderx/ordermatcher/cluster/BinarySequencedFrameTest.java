package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ADR-090 (FR-XOD-11): NEW flags bit 0 turns clOrdId into streamId:u32 | seq:u32; the gateway passes both through. */
class BinarySequencedFrameTest {
    static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    long[] seen;   // {clientKey, streamId, seq}, or {clientKey} for an unsequenced NEW

    final OrderSubmitter fake = new OrderSubmitter() {
        @Override
        public ExecResult submitOrder(String clOrdId, int accountId, String ticker, char side, int qty, long px) {
            return null;
        }

        @Override
        public ExecResult submitOrder(long clientKey, int accountId, int securityId, char side, int qty, long px) {
            seen = new long[] { clientKey };
            return new ExecResult(true, 5, OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitSequencedOrder(long clientKey, int accountId, int securityId, char side, int qty,
                                               long px, long streamId, long seq) {
            seen = new long[] { clientKey, streamId, seq };
            return new ExecResult(false, 3, OutputEvent.KIND_ORDER_REJECTED, (byte) RiskReason.OUT_OF_SEQUENCE.ordinal());
        }
    };

    int send(BinaryGatewayAcceptor acc, int flags, long clOrdId, UnsafeBuffer ack) {
        final UnsafeBuffer in = new UnsafeBuffer(new byte[32]);
        in.putByte(0, (byte) 1);
        in.putByte(2, (byte) flags);
        in.putInt(4, 7, LE);
        in.putInt(12, 1, LE);
        in.putLong(16, 100_000_000L, LE);
        in.putLong(24, clOrdId, LE);
        return acc.dispatch(in, 0, 32, ack);
    }

    @Test
    void theFlagSplitsTheKeyAndTheAckCarriesTheExpectedSequence() {
        final BinaryGatewayAcceptor acc = new BinaryGatewayAcceptor(fake, 0);
        final UnsafeBuffer ack = new UnsafeBuffer(new byte[18]);
        send(acc, 1, (0xABCDL << 32) | 9L, ack);
        assertEquals(0xABCDL, seen[1]);
        assertEquals(9L, seen[2]);
        assertEquals((0xABCDL << 32) | 9L, seen[0], "the key is the client's u64, as unsequenced");
        assertEquals(0, ack.getByte(3), "REJECTED");
        assertEquals(RiskReason.OUT_OF_SEQUENCE.ordinal(), ack.getByte(5));
        assertEquals(3, ack.getInt(6, LE), "the expected seq in orderRef");

        send(acc, 0, (0xABCDL << 32) | 9L, ack);
        assertEquals(1, seen.length, "no flag: the unsequenced path, unchanged");

        send(acc, 1, 0xABCDL << 32, ack);
        assertEquals(3, ack.getByte(3), "seq 0 is a protocol error: sequences start at 1");
    }
}
