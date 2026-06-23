package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputEventJournalCodecTest {
    @TempDir Path directory;

    @Test
    void versionedRecordRoundTripsAllDecisionFields() throws Exception {
        Path journal = directory.resolve("input-events.journal");
        try (var channel = java.nio.channels.FileChannel.open(journal,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            InputEventJournalCodec.writeHeader(channel);
            InputEvent event = event();
            ByteBuffer record = ByteBuffer.allocate(InputEventJournalCodec.RECORD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
            InputEventJournalCodec.encode(event, record, new CRC32());
            while (record.hasRemaining()) channel.write(record);
        }
        List<InputEvent> replayed = new ArrayList<>();
        long last = InputEventJournalCodec.replay(journal, -1L, event -> replayed.add(copy(event)));
        assertEquals(44L, last);
        assertEquals(1, replayed.size());
        InputEvent actual = replayed.getFirst();
        assertEquals(44L, actual.seq);
        assertEquals(66L, actual.ingressNanos);
        assertEquals(77L, actual.eventTimeMillis);
        assertEquals(88L, actual.clientOrderKey);
        assertEquals(99L, actual.principalKey);
        assertEquals(111L, actual.controlVersion);
        assertEquals(true, actual.controlEnabled);
    }

    @Test
    void legacy009bRecordIsExplicitlyUpcast() throws Exception {
        Path journal = directory.resolve("legacy.journal");
        ByteBuffer record = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        InputEvent event = event();
        record.putLong(event.seq).put(event.type).put(event.side).putShort((short) 0)
            .putInt(event.orderRef).putInt(event.accountId).putInt(event.securityId).putInt(event.qty)
            .putLong(event.limitPx).putLong(event.priceTicks).putLong(event.eventTimeMillis)
            .putInt(0).putLong(0L);
        Files.write(journal, record.array());
        List<InputEvent> replayed = new ArrayList<>();
        InputEventJournalCodec.replay(journal, -1L, value -> replayed.add(copy(value)));
        assertEquals(0L, replayed.getFirst().principalKey);
        assertEquals(0L, replayed.getFirst().controlVersion);
        assertEquals(77L, replayed.getFirst().eventTimeMillis);
    }

    @Test
    void corruptedRecordFailsBeforeReplay() throws Exception {
        Path journal = directory.resolve("input-events.journal");
        try (var channel = java.nio.channels.FileChannel.open(journal,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            InputEventJournalCodec.writeHeader(channel);
            ByteBuffer record = ByteBuffer.allocate(InputEventJournalCodec.RECORD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
            InputEventJournalCodec.encode(event(), record, new CRC32());
            while (record.hasRemaining()) channel.write(record);
        }
        byte[] bytes = Files.readAllBytes(journal);
        bytes[InputEventJournalCodec.HEADER_SIZE + 10] ^= 1;
        Files.write(journal, bytes);
        assertThrows(IOException.class,
            () -> InputEventJournalCodec.replay(journal, -1L, ignored -> {}));
    }

    private static InputEvent event() {
        InputEvent event = new InputEvent();
        event.seq = 44L;
        event.type = InputEvent.TYPE_ENTITLEMENT_CONTROL;
        event.side = 1;
        event.orderRef = 2;
        event.accountId = 3;
        event.securityId = 4;
        event.qty = 5;
        event.limitPx = 6L;
        event.priceTicks = 7L;
        event.ingressNanos = 66L;
        event.eventTimeMillis = 77L;
        event.clientOrderKey = 88L;
        event.principalKey = 99L;
        event.controlVersion = 111L;
        event.controlEnabled = true;
        return event;
    }

    private static InputEvent copy(InputEvent source) {
        InputEvent target = new InputEvent();
        target.seq = source.seq;
        target.type = source.type;
        target.side = source.side;
        target.orderRef = source.orderRef;
        target.accountId = source.accountId;
        target.securityId = source.securityId;
        target.qty = source.qty;
        target.limitPx = source.limitPx;
        target.priceTicks = source.priceTicks;
        target.ingressNanos = source.ingressNanos;
        target.eventTimeMillis = source.eventTimeMillis;
        target.clientOrderKey = source.clientOrderKey;
        target.principalKey = source.principalKey;
        target.controlVersion = source.controlVersion;
        target.controlEnabled = source.controlEnabled;
        return target;
    }
}
