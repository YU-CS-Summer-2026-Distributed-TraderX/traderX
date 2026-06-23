package finos.traderx.ordermatcher.risk;

import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.InputEventJournalCodec;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryRiskGatewaySnapshotCodecTest {
    @TempDir Path directory;

    @Test
    void roundTripsRiskAndMatcherState() throws Exception {
        BlpRiskState risk = state();
        MatchingEngine matcher = matcher(risk);
        apply(matcher, event(0L, InputEvent.TYPE_ORDER_NEW, 41, 101, 3, InputEvent.SIDE_BUY, 5, 125L, 0L, 91L, 77L, 1_000L));
        apply(matcher, event(1L, InputEvent.TYPE_PRICE_TICK, 0, 0, 3, (byte) 0, 0, 0L, 125L, 0L, 0L, 1_001L));
        MatchingEngine.Image expected = matcher.captureImage();

        Path snapshot = directory.resolve("recovery.snapshot");
        InMemoryRiskGatewaySnapshotCodec.write(snapshot, 1L, risk, matcher);

        BlpRiskState restoredRisk = state();
        MatchingEngine restoredMatcher = matcher(restoredRisk);
        InMemoryRiskGatewaySnapshotCodec.Snapshot restored =
            InMemoryRiskGatewaySnapshotCodec.restore(snapshot, restoredRisk);
        restoredMatcher.restoreImage(restored.matchingImage());
        MatchingEngine.Image actual = restoredMatcher.captureImage();

        assertEquals(1L, restored.lastAppliedSequence());
        assertEquals(0L, restoredRisk.reservedNotional(101));
        assertEquals(expected.orderRefs()[0], actual.orderRefs()[0]);
        assertEquals(expected.statuses()[0], actual.statuses()[0]);
        assertEquals(expected.remainingQuantities()[0], actual.remainingQuantities()[0]);
        assertEquals(expected.positions().keys().length, actual.positions().keys().length);
        if (expected.positions().quantities().length > 0) {
            assertEquals(expected.positions().quantities()[0], actual.positions().quantities()[0]);
        }
    }

    @Test
    void replaysJournalTailAfterSnapshot() throws Exception {
        BlpRiskState sourceRisk = state();
        MatchingEngine sourceMatcher = matcher(sourceRisk);
        InputEvent create = event(0L, InputEvent.TYPE_ORDER_NEW, 52, 101, 3, InputEvent.SIDE_BUY, 10, 125L, 0L, 92L, 77L, 1_000L);
        InputEvent cancel = event(1L, InputEvent.TYPE_ORDER_CANCEL, 52, 0, 0, (byte) 0, 0, 0L, 0L, 0L, 0L, 1_001L);
        apply(sourceMatcher, create);

        Path snapshot = directory.resolve("recovery.snapshot");
        InMemoryRiskGatewaySnapshotCodec.write(snapshot, 0L, sourceRisk, sourceMatcher);
        apply(sourceMatcher, cancel);
        MatchingEngine.Image expected = sourceMatcher.captureImage();

        Path journal = directory.resolve("input-events.journal");
        writeJournal(journal, create, cancel);

        BlpRiskState recoveredRisk = state();
        MatchingEngine recoveredMatcher = matcher(recoveredRisk);
        InMemoryRiskGatewaySnapshotCodec.Snapshot restored =
            InMemoryRiskGatewaySnapshotCodec.restore(snapshot, recoveredRisk);
        recoveredMatcher.restoreImage(restored.matchingImage());
        long last = InputEventJournalCodec.replay(journal, restored.lastAppliedSequence(),
            event -> recoveredMatcher.onEvent(event, event.seq, true));

        assertEquals(1L, last);
        MatchingEngine.Image image = recoveredMatcher.captureImage();
        assertEquals(expected.orderRefs()[0], image.orderRefs()[0]);
        assertEquals(expected.remainingQuantities()[0], image.remainingQuantities()[0]);
        assertEquals(expected.statuses()[0], image.statuses()[0]);
        assertEquals(0L, recoveredRisk.reservedNotional(101));
    }

    private static BlpRiskState state() {
        BlpRiskState state = new BlpRiskState(8, 8, 32, 16,
            1_000_000L, 1_000, 1_000_000L, 30_000L, new RiskMetrics());
        state.putLimits(1_000, 1_000_000L);
        state.putAccount(101, true);
        state.putSecurity(3, true);
        state.putEntitlement(77L, 101, true);
        state.onPrice(3, 125L, 999L);
        return state;
    }

    private static MatchingEngine matcher(BlpRiskState risk) {
        return new MatchingEngine(new OutputPublisher(null), new HotPathMetrics(), 8, 1_000, 32, 32, risk, 60_000L);
    }

    private static void apply(MatchingEngine matcher, InputEvent event) throws Exception {
        matcher.onEvent(event, event.seq, true);
    }

    private static InputEvent event(long seq, byte type, int orderRef, int accountId, int securityId, byte side,
                                    int quantity, long limitPx, long priceTicks, long clientOrderKey,
                                    long principalKey, long eventTimeMillis) {
        InputEvent event = new InputEvent();
        event.seq = seq;
        event.type = type;
        event.orderRef = orderRef;
        event.accountId = accountId;
        event.securityId = securityId;
        event.side = side;
        event.qty = quantity;
        event.limitPx = limitPx;
        event.priceTicks = priceTicks;
        event.clientOrderKey = clientOrderKey;
        event.principalKey = principalKey;
        event.eventTimeMillis = eventTimeMillis;
        return event;
    }

    private static void writeJournal(Path journal, InputEvent... events) throws Exception {
        try (FileChannel channel = FileChannel.open(journal,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            InputEventJournalCodec.writeHeader(channel);
            ByteBuffer record = ByteBuffer.allocate(InputEventJournalCodec.RECORD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
            CRC32 checksum = new CRC32();
            for (InputEvent event : events) {
                InputEventJournalCodec.encode(event, record, checksum);
                while (record.hasRemaining()) {
                    channel.write(record);
                }
            }
        }
    }
}
