package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

class AeronFollowerCheckpointStoreTest {
    @TempDir Path tempDir;

    @Test
    void newestForcedSlotSurvivesCorruptionOfTheOtherSlot() throws Exception {
        Path path = tempDir.resolve("follower.checkpoint");
        try (AeronFollowerCheckpointStore store = new AeronFollowerCheckpointStore(path)) {
            store.write(3L, 10L, 128L, 111L, 1001);
            store.write(3L, 11L, 192L, 222L, 1001);
            assertThat(store.read().inputSeq()).isEqualTo(11L);
        }

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 0}), 0L);
        }
        try (AeronFollowerCheckpointStore reopened = new AeronFollowerCheckpointStore(path)) {
            assertThat(reopened.read().inputSeq()).isEqualTo(10L);
        }
    }
}
