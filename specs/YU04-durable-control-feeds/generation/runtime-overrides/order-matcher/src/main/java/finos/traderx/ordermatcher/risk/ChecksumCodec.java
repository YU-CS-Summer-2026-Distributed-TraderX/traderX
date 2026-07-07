package finos.traderx.ordermatcher.risk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Recomputes a source snapshot's checksum for verification (ADR-019 step 3). Must produce
 * byte-identical output to account-service's {@code AccountSnapshotService.checksum()} and
 * reference-data's {@code buildControlSnapshot} — same canonical line format ({@code
 * "<key>:<value>;"}, sorted by key) and the same SHA-256 hex encoding — or every snapshot fetch
 * would spuriously fail verification even though the source computed it correctly.
 */
final class ChecksumCodec {
    private ChecksumCodec() {}

    static <T> String checksum(List<T> records, Function<T, String> canonicalLine) {
        StringBuilder canonical = new StringBuilder();
        for (T record : records) {
            canonical.append(canonicalLine.apply(record));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
