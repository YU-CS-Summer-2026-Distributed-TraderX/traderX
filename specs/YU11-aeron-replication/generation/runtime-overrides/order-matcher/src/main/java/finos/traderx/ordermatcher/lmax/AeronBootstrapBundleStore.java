package finos.traderx.ordermatcher.lmax;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds an immutable, checksummed bootstrap bundle outside the replication hot path. */
public final class AeronBootstrapBundleStore {
    private static final long TRANSFER_JOURNAL_ANCHOR_BYTES = 64L;

    private final Path journalDir;
    private final Path bundleDir;
    private final Path captureRoot;
    private final AtomicBoolean captureRequested = new AtomicBoolean();
    private AeronSnapshotBoundary capturedBoundary = AeronSnapshotBoundary.NONE;
    private Path capturedGeneration;

    public AeronBootstrapBundleStore(Path journalDir) {
        this.journalDir = journalDir;
        this.bundleDir = journalDir.resolve("bootstrap-bundles");
        this.captureRoot = journalDir.resolve("bootstrap-captures");
    }

    public void requestCapture() {
        captureRequested.set(true);
    }

    public void cancelCapture() {
        captureRequested.set(false);
    }

    public boolean captureRequested() {
        return captureRequested.get();
    }

    /**
     * Persist an immutable transfer generation while still on the marker's BLP callback. This
     * binds the state bytes to the exact Aeron boundary; copying the mutable active snapshot later
     * could otherwise race the next scheduled snapshot and pair state from B+N with checkpoint B.
     */
    public synchronized void capture(AeronSnapshotBoundary boundary, SnapshotStore.Data source)
        throws IOException {
        if (!captureRequested.getAndSet(false)) return;
        if (boundary == null || !boundary.transferable()) {
            throw new IllegalArgumentException("snapshot boundary is not transferable");
        }
        Files.createDirectories(captureRoot);
        Path generation = captureRoot.resolve(
            boundary.leaderEpoch() + "-" + Long.toUnsignedString(boundary.inputSeq()));
        Files.createDirectories(generation);
        SnapshotStore transferStore = new SnapshotStore(generation);
        transferStore.write(new SnapshotStore.Data(
            TRANSFER_JOURNAL_ANCHOR_BYTES, source.nextOrderRef(), source.tradeCounter(),
            source.prices(), source.positions(), source.orders(), source.jetsStreamSeq(),
            source.riskPolicy(), source.riskAccounts(), source.riskSecurities(),
            source.riskIdempotency()));
        Path activeSymbols = journalDir.resolve("symbols.tab");
        Path capturedSymbols = generation.resolve("symbols.tab");
        if (Files.isRegularFile(activeSymbols)) {
            Path tmp = generation.resolve("symbols.tab.tmp");
            Files.copy(activeSymbols, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, capturedSymbols, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(capturedSymbols);
        }
        Path previous = capturedGeneration;
        capturedBoundary = boundary;
        capturedGeneration = generation;
        if (previous != null && !previous.equals(generation)) deleteCapture(previous);
    }

    public synchronized Path build(AeronSnapshotBoundary boundary, long correlationId, byte[] secret)
        throws IOException {
        if (boundary == null || !boundary.transferable()) {
            throw new IllegalArgumentException("snapshot boundary is not transferable");
        }
        if (!boundary.equals(capturedBoundary) || capturedGeneration == null) {
            throw new IllegalStateException("no immutable snapshot captured for boundary "
                + boundary.inputSeq());
        }
        Path snapshot = capturedGeneration.resolve("snapshot.dat");
        Path symbols = capturedGeneration.resolve("symbols.tab");
        if (!Files.isRegularFile(snapshot)) {
            throw new IOException("snapshot.dat missing after snapshot boundary "
                + boundary.inputSeq());
        }
        Files.createDirectories(bundleDir);
        byte[] snapshotHash = sha256(snapshot);
        byte[] symbolsHash = Files.isRegularFile(symbols) ? sha256(symbols) : sha256Empty();
        long symbolsLength = Files.isRegularFile(symbols) ? Files.size(symbols) : 0L;
        byte[] schemaHash = sha256(AeronReplicationCodec.SCHEMA_CHECKSUM.getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        AeronBootstrapManifest manifest = AeronBootstrapManifest.signed(
            boundary.leaderEpoch(), boundary.inputSeq(), boundary.recordingPosition(),
            boundary.payloadChecksum(), boundary.dataSessionId(), correlationId,
            Files.size(snapshot), symbolsLength, snapshotHash, symbolsHash, schemaHash, secret);

        Path target = bundleDir.resolve(Long.toUnsignedString(correlationId) + ".bundle");
        Path tmp = bundleDir.resolve(Long.toUnsignedString(correlationId) + ".bundle.tmp");
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(manifest.encode());
            Files.copy(snapshot, out);
            if (symbolsLength > 0L) Files.copy(symbols, out);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static void deleteCapture(Path generation) throws IOException {
        Files.deleteIfExists(generation.resolve("snapshot.dat.tmp"));
        Files.deleteIfExists(generation.resolve("snapshot.dat"));
        Files.deleteIfExists(generation.resolve("symbols.tab.tmp"));
        Files.deleteIfExists(generation.resolve("symbols.tab"));
        Files.deleteIfExists(generation);
    }

    private static byte[] sha256(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        }
    }

    private static byte[] sha256(byte[] bytes) {
        return digest().digest(bytes);
    }

    private static byte[] sha256Empty() {
        return sha256(new byte[0]);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
