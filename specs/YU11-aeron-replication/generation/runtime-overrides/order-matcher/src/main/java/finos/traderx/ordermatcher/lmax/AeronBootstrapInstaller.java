package finos.traderx.ordermatcher.lmax;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/**
 * Verifies and crash-consistently installs a bootstrap recovery generation before the journal,
 * symbol table, and follower transport are opened.
 */
public final class AeronBootstrapInstaller {
    private static final String TRANSACTION_SUFFIX = ".bootstrap-transaction";
    private static final String BACKUP_PREFIX = ".bootstrap-backup-";
    private static final String STAGING_PREFIX = ".bootstrap-staging-";

    private final Path journalDir;

    public AeronBootstrapInstaller(Path journalDir) {
        this.journalDir = journalDir;
    }

    public InstallResult install(Path bundle, byte[] secret, long expectedEpoch,
                                 long expectedCorrelation, BooleanSupplier epochStillCurrent)
        throws IOException {
        byte[] manifestBytes = new byte[AeronBootstrapManifest.BYTES];
        try (FileChannel in = FileChannel.open(bundle, StandardOpenOption.READ)) {
            readFully(in, ByteBuffer.wrap(manifestBytes), 0L);
        }
        AeronBootstrapManifest manifest = AeronBootstrapManifest.decode(manifestBytes, secret);
        validateManifest(bundle, manifest, expectedEpoch, expectedCorrelation);
        if (!epochStillCurrent.getAsBoolean()) {
            throw new IllegalStateException("leader epoch changed before bundle installation");
        }

        Path parent = requiredParent();
        String token = Long.toUnsignedString(expectedCorrelation);
        Path staging = parent.resolve(journalDir.getFileName() + STAGING_PREFIX + token);
        Path backup = parent.resolve(journalDir.getFileName() + BACKUP_PREFIX + token);
        Path transaction = transactionPath(journalDir);
        Files.createDirectory(staging);
        boolean transactionWritten = false;
        try {
            extractAndVerify(bundle, manifest, staging);
            preserveLeaderEpoch(staging);
            Journaler.writeBootstrapAnchor(staging, manifest.inputSeq());
            try (AeronFollowerCheckpointStore checkpoint =
                    new AeronFollowerCheckpointStore(staging.resolve("aeron-follower.checkpoint"))) {
                checkpoint.write(manifest.leaderEpoch(), manifest.inputSeq(),
                    manifest.archivePosition(), manifest.payloadChecksum(),
                    manifest.dataSessionId());
            }
            SnapshotStore.Data snapshot = new SnapshotStore(staging).read();
            if (snapshot == null || snapshot.coveredOffset() != 64L) {
                throw new IllegalArgumentException(
                    "bootstrap snapshot is not normalized to the 64-byte journal anchor");
            }
            if (manifest.symbolsLength() == 0L
                && (!snapshot.orders().isEmpty() || !snapshot.positions().isEmpty()
                    || !snapshot.prices().isEmpty())) {
                throw new IllegalArgumentException(
                    "stateful bootstrap snapshot is missing symbols.tab");
            }
            forceDirectory(staging);
            if (!epochStillCurrent.getAsBoolean()) {
                throw new IllegalStateException("leader epoch changed during bundle verification");
            }

            writeTransaction(transaction, staging.getFileName().toString(),
                backup.getFileName().toString());
            transactionWritten = true;
            completeTransaction(journalDir, transaction);
        } catch (Exception ex) {
            if (!transactionWritten) deleteTree(staging);
            if (ex instanceof IOException io) throw io;
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("bootstrap install failed", ex);
        }

        AeronFollowerCheckpointStore.Record checkpoint;
        try (AeronFollowerCheckpointStore store = new AeronFollowerCheckpointStore(
                journalDir.resolve("aeron-follower.checkpoint"))) {
            checkpoint = store.read();
        }
        return new InstallResult(manifest, checkpoint);
    }

    /** Completes or rolls back the one outstanding directory switch after a process crash. */
    public static void recoverInterruptedInstall(Path journalDir) throws IOException {
        Path transaction = transactionPath(journalDir);
        if (Files.exists(transaction)) completeTransaction(journalDir, transaction);
    }

    private void validateManifest(Path bundle, AeronBootstrapManifest manifest,
                                  long expectedEpoch, long expectedCorrelation) throws IOException {
        if (manifest.leaderEpoch() != expectedEpoch) {
            throw new IllegalArgumentException("bootstrap epoch " + manifest.leaderEpoch()
                + " differs from negotiated epoch " + expectedEpoch);
        }
        if (manifest.correlationId() != expectedCorrelation) {
            throw new IllegalArgumentException("bootstrap correlation mismatch");
        }
        byte[] expectedSchema = sha256(
            AeronReplicationCodec.SCHEMA_CHECKSUM.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expectedSchema, manifest.schemaChecksumHash())) {
            throw new IllegalArgumentException("bootstrap schema checksum mismatch");
        }
        if (manifest.inputSeq() < 0L || manifest.archivePosition() < 0L
            || manifest.dataSessionId() < 0 || manifest.snapshotLength() <= 0L
            || manifest.symbolsLength() < 0L) {
            throw new IllegalArgumentException("bootstrap manifest contains invalid boundary or lengths");
        }
        long payloadLength;
        try {
            payloadLength = Math.addExact(manifest.snapshotLength(), manifest.symbolsLength());
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("bootstrap payload length overflow", ex);
        }
        long expectedLength = Math.addExact(AeronBootstrapManifest.BYTES, payloadLength);
        if (Files.size(bundle) != expectedLength) {
            throw new IllegalArgumentException("bootstrap bundle length mismatch");
        }
    }

    private static void extractAndVerify(Path bundle, AeronBootstrapManifest manifest,
                                         Path staging) throws IOException {
        Path snapshot = staging.resolve("snapshot.dat");
        Path symbols = staging.resolve("symbols.tab");
        try (FileChannel in = FileChannel.open(bundle, StandardOpenOption.READ)) {
            copySection(in, AeronBootstrapManifest.BYTES, manifest.snapshotLength(),
                snapshot, manifest.snapshotHash());
            if (manifest.symbolsLength() > 0L) {
                copySection(in, AeronBootstrapManifest.BYTES + manifest.snapshotLength(),
                    manifest.symbolsLength(), symbols, manifest.symbolsHash());
            } else if (!MessageDigest.isEqual(sha256(new byte[0]), manifest.symbolsHash())) {
                throw new IllegalArgumentException("empty symbol payload hash mismatch");
            }
        }
    }

    private static void copySection(FileChannel in, long offset, long length, Path target,
                                    byte[] expectedHash) throws IOException {
        MessageDigest digest = digest();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = length;
        long position = offset;
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            while (remaining > 0L) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = in.read(buffer, position);
                if (read < 0) throw new IOException("truncated bootstrap payload");
                if (read == 0) continue;
                position += read;
                remaining -= read;
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) out.write(buffer);
            }
            out.force(true);
        }
        if (!MessageDigest.isEqual(digest.digest(), expectedHash)) {
            throw new IllegalArgumentException(target.getFileName() + " SHA-256 mismatch");
        }
    }

    private void preserveLeaderEpoch(Path staging) throws IOException {
        Path epoch = journalDir.resolve("leader.epoch");
        if (Files.isRegularFile(epoch)) {
            Files.copy(epoch, staging.resolve("leader.epoch"));
            forceFile(staging.resolve("leader.epoch"));
        }
    }

    private Path requiredParent() throws IOException {
        Path parent = journalDir.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("journal directory has no parent: " + journalDir);
        Files.createDirectories(parent);
        return parent;
    }

    private static Path transactionPath(Path journalDir) {
        Path absolute = journalDir.toAbsolutePath();
        return absolute.resolveSibling(absolute.getFileName() + TRANSACTION_SUFFIX);
    }

    private static void writeTransaction(Path transaction, String staging, String backup)
        throws IOException {
        String body = staging + "\n" + backup + "\n";
        try (FileChannel channel = FileChannel.open(transaction, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(body);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        forceDirectory(transaction.getParent());
    }

    private static void completeTransaction(Path journalDir, Path transaction) throws IOException {
        String[] lines = Files.readString(transaction, StandardCharsets.UTF_8).split("\\R");
        if (lines.length < 2) throw new IOException("invalid bootstrap transaction " + transaction);
        Path parent = journalDir.toAbsolutePath().getParent();
        Path staging = child(parent, lines[0]);
        Path backup = child(parent, lines[1]);
        Path active = journalDir.toAbsolutePath();

        if (Files.exists(staging)) {
            if (Files.exists(active) && !Files.exists(backup)) atomicMove(active, backup);
            if (!Files.exists(active)) atomicMove(staging, active);
        } else if (!Files.exists(active) && Files.exists(backup)) {
            atomicMove(backup, active);
        }
        if (!Files.exists(active)) {
            throw new IOException("bootstrap transaction left no active journal generation");
        }
        Files.deleteIfExists(transaction);
        forceDirectory(parent);
        if (Files.exists(backup)) deleteTree(backup);
    }

    private static Path child(Path parent, String name) throws IOException {
        if (name.isBlank() || !Path.of(name).getFileName().toString().equals(name)) {
            throw new IOException("invalid bootstrap transaction child: " + name);
        }
        return parent.resolve(name);
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("journal filesystem does not support atomic generation switch", ex);
        }
        forceDirectory(target.getParent());
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long offset)
        throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + buffer.position());
            if (read < 0) throw new IOException("truncated bootstrap manifest");
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.FileSystemException unsupported) {
            // Some non-POSIX test filesystems cannot open directories; atomic rename still
            // provides process-crash consistency, while Linux PVC filesystems take this path.
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static byte[] sha256(byte[] bytes) {
        return digest().digest(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record InstallResult(AeronBootstrapManifest manifest,
                                AeronFollowerCheckpointStore.Record checkpoint) { }
}
