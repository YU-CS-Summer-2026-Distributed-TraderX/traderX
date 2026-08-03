package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeronBootstrapManifestTest {
    private static final byte[] SECRET =
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsEveryReplayBoundaryAndPayloadBinding() throws Exception {
        byte[] snapshotHash = sha256("snapshot");
        byte[] symbolsHash = sha256("symbols");
        byte[] schemaHash = sha256("schema");
        AeronBootstrapManifest manifest = AeronBootstrapManifest.signed(
            7L, 1388L, 101_472L, 0x1234L, 77, 99L,
            4096L, 128L, snapshotHash, symbolsHash, schemaHash, SECRET);

        AeronBootstrapManifest decoded =
            AeronBootstrapManifest.decode(manifest.encode(), SECRET);

        assertThat(decoded.leaderEpoch()).isEqualTo(7L);
        assertThat(decoded.inputSeq()).isEqualTo(1388L);
        assertThat(decoded.archivePosition()).isEqualTo(101_472L);
        assertThat(decoded.payloadChecksum()).isEqualTo(0x1234L);
        assertThat(decoded.dataSessionId()).isEqualTo(77);
        assertThat(decoded.correlationId()).isEqualTo(99L);
        assertThat(decoded.snapshotLength()).isEqualTo(4096L);
        assertThat(decoded.symbolsLength()).isEqualTo(128L);
        assertThat(decoded.snapshotHash()).containsExactly(snapshotHash);
        assertThat(decoded.symbolsHash()).containsExactly(symbolsHash);
        assertThat(decoded.schemaChecksumHash()).containsExactly(schemaHash);
    }

    @Test
    void rejectsAnyManifestMutation() throws Exception {
        AeronBootstrapManifest manifest = AeronBootstrapManifest.signed(
            7L, 1388L, 101_472L, 0x1234L, 77, 99L, 4096L, 128L,
            sha256("snapshot"), sha256("symbols"), sha256("schema"), SECRET);
        byte[] corrupt = manifest.encode();
        corrupt[40] ^= 1;

        assertThatThrownBy(() -> AeronBootstrapManifest.decode(corrupt, SECRET))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HMAC");
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
