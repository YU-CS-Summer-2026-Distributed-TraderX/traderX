package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeronBootstrapBundleStoreTest {
    private static final byte[] SECRET =
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;

    @Test
    void writesAnAuthenticatedImmutableManifestFollowedByExactPayloads() throws Exception {
        byte[] symbols = "1=AAPL\n".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("symbols.tab"), symbols);
        AeronSnapshotBoundary boundary =
            new AeronSnapshotBoundary(7L, 1388L, 101_472L, 0x1234L, 77);
        SnapshotStore.Data source = new SnapshotStore.Data(
            9999L, 42, 3L, List.of(new long[] {1L, 12_300L}),
            List.of(), List.of(), -1L);
        AeronBootstrapBundleStore store = new AeronBootstrapBundleStore(tempDir);
        store.requestCapture();
        store.capture(boundary, source);

        Path bundle = store.build(boundary, 99L, SECRET);
        byte[] bytes = Files.readAllBytes(bundle);
        AeronBootstrapManifest manifest = AeronBootstrapManifest.decode(
            java.util.Arrays.copyOf(bytes, AeronBootstrapManifest.BYTES), SECRET);

        assertThat(manifest.inputSeq()).isEqualTo(1388L);
        assertThat(manifest.archivePosition()).isEqualTo(101_472L);
        assertThat(manifest.dataSessionId()).isEqualTo(77);
        assertThat(manifest.symbolsLength()).isEqualTo(symbols.length);
        assertThat(java.util.Arrays.copyOfRange(bytes,
            AeronBootstrapManifest.BYTES + (int) manifest.snapshotLength(), bytes.length))
            .containsExactly(symbols);

        Path decodedDir = tempDir.resolve("decoded");
        Files.createDirectories(decodedDir);
        Files.write(decodedDir.resolve("snapshot.dat"), java.util.Arrays.copyOfRange(bytes,
            AeronBootstrapManifest.BYTES,
            AeronBootstrapManifest.BYTES + (int) manifest.snapshotLength()));
        SnapshotStore.Data transferred = new SnapshotStore(decodedDir).read();
        assertThat(transferred.coveredOffset()).isEqualTo(64L);
        assertThat(transferred.nextOrderRef()).isEqualTo(42);
        assertThat(transferred.tradeCounter()).isEqualTo(3L);
    }

    @Test
    void refusesToPublishABundleWithoutAnExactCapturedBoundary() {
        AeronSnapshotBoundary boundary =
            new AeronSnapshotBoundary(7L, 1388L, 101_472L, 0x1234L, 77);

        assertThatThrownBy(() ->
            new AeronBootstrapBundleStore(tempDir).build(boundary, 99L, SECRET))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no immutable snapshot");
    }
}
