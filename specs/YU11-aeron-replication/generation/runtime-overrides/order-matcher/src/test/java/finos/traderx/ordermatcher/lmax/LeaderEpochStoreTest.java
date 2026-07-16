package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderEpochStoreTest {
    @TempDir Path tempDir;

    @Test
    void primarySessionEpochAdvancesAcrossRestartAndHonorsObservedFloor() {
        Path path = tempDir.resolve("leader.epoch");
        assertThat(LeaderEpochStore.claimNext(path, 1L)).isEqualTo(2L);
        assertThat(LeaderEpochStore.claimNext(path, 1L)).isEqualTo(3L);
        assertThat(LeaderEpochStore.claimNext(path, 9L)).isEqualTo(10L);
    }
}
