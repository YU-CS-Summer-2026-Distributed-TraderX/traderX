package finos.traderx.ordermatcher.lmax;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AeronReplicationCodecTest {
    private static final String GOLDEN_INPUT_V1 =
        "380001006f000100" +
        "0807060504030201" +
        "1817161514131211" +
        "2827262524232221" +
        "3837363534333231" +
        "44434241" +
        "54535251" +
        "64636261" +
        "74737271" +
        "84838281" +
        "09" +
        "01" +
        "0100";

    @Test
    void inputV1IsExactly64BytesAndMatchesGoldenVector() {
        AeronReplicationCodec codec = new AeronReplicationCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(AeronReplicationCodec.INPUT_BYTES));
        InputEvent event = InputEvent.newInstance();
        event.eventTimeMillis = 0x1112131415161718L;
        event.limitPx = 0x2122232425262728L;
        event.priceTicks = 0x3132333435363738L;
        event.orderRef = 0x41424344;
        event.accountId = 0x51525354;
        event.securityId = 0x61626364;
        event.qty = 0x71727374;
        event.type = 9;
        event.side = 1;

        codec.encodeInput(buffer, 0, event, 0x0102030405060708L, 0x81828384L, 1);

        byte[] actual = new byte[AeronReplicationCodec.INPUT_BYTES];
        buffer.getBytes(0, actual);
        assertThat(actual).hasSize(64);
        assertThat(HexFormat.of().formatHex(actual)).isEqualTo(GOLDEN_INPUT_V1);
    }

    @Test
    void inputRoundTripsDirectlyIntoReusableRingSlot() {
        AeronReplicationCodec codec = new AeronReplicationCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(AeronReplicationCodec.INPUT_BYTES));
        InputEvent source = InputEvent.newInstance();
        source.eventTimeMillis = 123L;
        source.limitPx = 456L;
        source.priceTicks = 789L;
        source.orderRef = 11;
        source.accountId = 22;
        source.securityId = 33;
        source.qty = -44;
        source.type = InputEvent.TYPE_ORDER_NEW;
        source.side = InputEvent.SIDE_SELL;

        codec.encodeInput(buffer, 0, source, 77L, 9L, 0);
        InputEvent target = InputEvent.newInstance();
        assertThat(codec.tryDecodeInput(buffer, 0, 64, target)).isZero();
        assertThat(codec.inputSeq()).isEqualTo(77L);
        assertThat(codec.leaderEpoch()).isEqualTo(9L);
        assertThat(target.eventTimeMillis).isEqualTo(123L);
        assertThat(target.limitPx).isEqualTo(456L);
        assertThat(target.priceTicks).isEqualTo(789L);
        assertThat(target.orderRef).isEqualTo(11);
        assertThat(target.accountId).isEqualTo(22);
        assertThat(target.securityId).isEqualTo(33);
        assertThat(target.qty).isEqualTo(-44);
        assertThat(target.type).isEqualTo(InputEvent.TYPE_ORDER_NEW);
        assertThat(target.side).isEqualTo(InputEvent.SIDE_SELL);
        assertThat(codec.inspectedPayloadChecksum())
            .isEqualTo(AeronReplicationCodec.payloadChecksum(source));
    }

    @Test
    void durableAckRoundTripsAndRejectsUnknownFlags() {
        AeronReplicationCodec codec = new AeronReplicationCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(AeronReplicationCodec.ACK_BYTES));
        long flags = AeronReplicationCodec.ACK_ON_RING | AeronReplicationCodec.ACK_JOURNALED;
        codec.encodeAck(buffer, 0, 12L, flags, 99L, 4567L, 8901L);

        assertThat(codec.tryDecodeAck(buffer, 0, AeronReplicationCodec.ACK_BYTES)).isZero();
        assertThat(codec.leaderEpoch()).isEqualTo(12L);
        assertThat(codec.inputSeq()).isEqualTo(99L);
        assertThat(codec.ackFlags()).isEqualTo(flags);
        assertThat(codec.recordingPosition()).isEqualTo(4567L);
        assertThat(codec.journalForceNanos()).isEqualTo(8901L);

        codec.encodeAck(buffer, 0, 12L, 1L << 31, 99L, 4567L, 8901L);
        assertThat(codec.tryDecodeAck(buffer, 0, AeronReplicationCodec.ACK_BYTES))
            .isEqualTo(AeronReplicationCodec.UNKNOWN_FLAGS);
    }
}
