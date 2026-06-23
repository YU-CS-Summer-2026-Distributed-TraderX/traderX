package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlpRiskSnapshotCodecTest {
    @TempDir Path directory;

    @Test
    void roundTripsReservationsRestrictionsPolicyAndIdempotency() throws Exception {
        BlpRiskState source = state();
        source.putAccount(101, true);
        source.putSecurity(3, true);
        source.putSecurity(4, true);
        source.putRestriction(4, true);
        source.putEntitlement(77L, 101, true);
        source.putPolicy(42L, false);
        source.onPrice(3, 125L, 1_000L);
        assertEquals(RiskReason.ACCEPTED,
            source.decideAndReserve(91L, 77L, 7, 101, 3, (byte) 0, 0, 5, 125L, 1_001L));

        Path snapshot = directory.resolve("risk.snapshot");
        BlpRiskSnapshotCodec.write(snapshot, source);
        BlpRiskState restored = state();
        BlpRiskSnapshotCodec.restore(snapshot, restored);

        assertEquals(625L, restored.reservedNotional(101));
        assertEquals(625L, restored.reservedBuyNotional(101));
        assertEquals(42L, restored.policyVersion());
        assertEquals(RiskReason.ACCEPTED,
            restored.decideAndReserve(91L, 77L, 99, 101, 3, (byte) 0, 0, 5, 125L, 1_001L));
        assertEquals(RiskReason.RESTRICTED,
            restored.decideAndReserve(92L, 77L, 8, 101, 4, (byte) 0, 0, 1, 125L, 1_001L));
    }

    @Test
    void rejectsCorruptSnapshotBeforeMutatingState() throws Exception {
        Path snapshot = directory.resolve("risk.snapshot");
        BlpRiskState source = state();
        source.putAccount(101, true);
        BlpRiskSnapshotCodec.write(snapshot, source);
        byte[] bytes = Files.readAllBytes(snapshot);
        bytes[bytes.length - 1] ^= 1;
        Files.write(snapshot, bytes);

        BlpRiskState target = state();
        assertThrows(IOException.class, () -> BlpRiskSnapshotCodec.restore(snapshot, target));
        assertEquals(RiskReason.UNKNOWN_ACCOUNT,
            target.decideMarketTrade(1L, 101, 0, 1, 1L, 1L));
    }

    private static BlpRiskState state() {
        BlpRiskState state = new BlpRiskState(8, 8, 32, 16,
            1_000_000L, 1_000, 1_000_000L, 30_000L, new RiskMetrics());
        state.putLimits(1_000, 1_000_000L);
        return state;
    }
}
