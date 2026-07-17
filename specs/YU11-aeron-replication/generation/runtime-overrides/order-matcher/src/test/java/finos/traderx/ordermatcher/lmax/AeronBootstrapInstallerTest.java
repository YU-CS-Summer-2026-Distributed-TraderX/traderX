package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeronBootstrapInstallerTest {
    private static final byte[] SECRET =
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final AeronSnapshotBoundary BOUNDARY =
        new AeronSnapshotBoundary(7L, 1388L, 101_472L, 0x1234L, 77);

    @TempDir Path tempDir;

    @Test
    void atomicallyInstallsSnapshotSymbolsAnchorAndReplayCheckpoint() throws Exception {
        Path bundle = bundle(99L);
        Path follower = oldFollowerGeneration();

        AeronBootstrapInstaller.InstallResult result =
            new AeronBootstrapInstaller(follower).install(
                bundle, SECRET, 7L, 99L, () -> true);

        assertThat(result.manifest().inputSeq()).isEqualTo(1388L);
        assertThat(new JournalReader(follower).lastInputSeq()).isEqualTo(1388L);
        assertThat(new SnapshotStore(follower).read().coveredOffset()).isEqualTo(64L);
        assertThat(Files.readString(follower.resolve("symbols.tab")))
            .isEqualTo("0\tAAPL\n");
        try (AeronFollowerCheckpointStore checkpoints = new AeronFollowerCheckpointStore(
                follower.resolve("aeron-follower.checkpoint"))) {
            AeronFollowerCheckpointStore.Record checkpoint = checkpoints.read();
            assertThat(checkpoint.epoch()).isEqualTo(7L);
            assertThat(checkpoint.inputSeq()).isEqualTo(1388L);
            assertThat(checkpoint.recordingPosition()).isEqualTo(101_472L);
            assertThat(checkpoint.payloadChecksum()).isEqualTo(0x1234L);
            assertThat(checkpoint.dataSessionId()).isEqualTo(77);
        }
        assertThat(Files.exists(follower.resolve("old-generation"))).isFalse();
    }

    @Test
    void corruptionAndEpochChangePreserveTheOldRecoveryGeneration() throws Exception {
        Path corrupt = bundle(100L);
        byte[] bytes = Files.readAllBytes(corrupt);
        bytes[AeronBootstrapManifest.BYTES + 8] ^= 1;
        Files.write(corrupt, bytes);
        Path follower = oldFollowerGeneration();

        assertThatThrownBy(() -> new AeronBootstrapInstaller(follower).install(
            corrupt, SECRET, 7L, 100L, () -> true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHA-256");
        assertThat(Files.readString(follower.resolve("old-generation"))).isEqualTo("old");

        Path valid = bundle(101L);
        assertThatThrownBy(() -> new AeronBootstrapInstaller(follower).install(
            valid, SECRET, 7L, 101L, () -> false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("epoch changed");
        assertThat(Files.readString(follower.resolve("old-generation"))).isEqualTo("old");
    }

    @Test
    void restartCompletesARecordedGenerationSwitch() throws Exception {
        Path active = tempDir.resolve("journal-restart");
        Files.createDirectory(active);
        Files.writeString(active.resolve("old"), "old");
        Path staging = tempDir.resolve("journal-restart.bootstrap-staging-55");
        Files.createDirectory(staging);
        Files.writeString(staging.resolve("new"), "new");
        Path transaction = tempDir.resolve("journal-restart.bootstrap-transaction");
        Files.writeString(transaction,
            staging.getFileName() + "\n"
                + "journal-restart.bootstrap-backup-55\n");

        AeronBootstrapInstaller.recoverInterruptedInstall(active);

        assertThat(Files.readString(active.resolve("new"))).isEqualTo("new");
        assertThat(Files.exists(active.resolve("old"))).isFalse();
        assertThat(Files.exists(transaction)).isFalse();
    }

    private Path bundle(long correlation) throws Exception {
        Path primary = tempDir.resolve("primary-" + correlation);
        Files.createDirectories(primary);
        Files.writeString(primary.resolve("symbols.tab"), "0\tAAPL\n");
        SnapshotStore.Data source = new SnapshotStore.Data(
            9999L, 42, 3L, List.of(new long[] {0L, 12_300L}),
            List.of(), List.of(), -1L);
        AeronBootstrapBundleStore store = new AeronBootstrapBundleStore(primary);
        store.requestCapture();
        store.capture(BOUNDARY, source);
        return store.build(BOUNDARY, correlation, SECRET);
    }

    private Path oldFollowerGeneration() throws Exception {
        Path follower = tempDir.resolve("follower-" + System.nanoTime());
        Files.createDirectories(follower);
        Files.writeString(follower.resolve("old-generation"), "old");
        return follower;
    }
}
