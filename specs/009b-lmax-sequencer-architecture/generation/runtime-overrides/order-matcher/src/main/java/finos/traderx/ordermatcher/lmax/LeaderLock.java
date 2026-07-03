package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive leader election over the shared journal volume (FR-09B30 failover). The LIVE node —
 * the only one allowed to append to the journal, symbols.tab, and snapshot.dat — holds an OS file
 * lock on {@code leader.lock} in the journal directory. A standby may promote only after acquiring
 * it, so a primary that is merely slow (GC pause, HTTP hiccup) still fences the standby out: the
 * health probe is the failover TRIGGER, the lock is the ARBITER. The kernel releases the lock when
 * the holder's process dies, which is exactly the failure promotion must wait for.
 *
 * <p>Scope: containers sharing one host kernel (the compose stack's named volume). Cross-host
 * volumes (NFS et al.) have weaker lock semantics — the perf/DR profile replaces this with a real
 * consensus lease alongside the Aeron replicator.
 */
public final class LeaderLock implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LeaderLock.class);

    private final Path lockFile;
    private FileChannel channel;
    private FileLock lock;

    public LeaderLock(Path lockFile) {
        this.lockFile = lockFile;
    }

    /** One non-blocking acquisition attempt. Safe to call repeatedly; true once held. */
    public synchronized boolean tryAcquire(String holderTag) {
        if (lock != null && lock.isValid()) {
            return true;
        }
        try {
            if (channel == null || !channel.isOpen()) {
                if (lockFile.getParent() != null) {
                    Files.createDirectories(lockFile.getParent());
                }
                channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            }
            lock = channel.tryLock();
        } catch (OverlappingFileLockException ex) {
            return false;   // held by another thread in this JVM (e.g. a racing promote call)
        } catch (IOException ex) {
            log.warn("Leader lock attempt on {} failed: {}", lockFile, ex.toString());
            return false;
        }
        if (lock == null) {
            return false;   // held by another process (the live node)
        }
        writeHolderTag(holderTag);
        log.info("Acquired leader lock {} as '{}'", lockFile.toAbsolutePath(), holderTag);
        return true;
    }

    /** Retry {@link #tryAcquire} for up to {@code timeoutMs}; false if another node kept the lock. */
    public boolean acquireWithin(long timeoutMs, String holderTag) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            if (tryAcquire(holderTag)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    public synchronized boolean held() {
        return lock != null && lock.isValid();
    }

    /** Best-effort debug breadcrumb (who is leader) readable with `cat leader.lock`. */
    private void writeHolderTag(String holderTag) {
        try {
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(
                (holderTag + " pid=" + ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8)), 0);
            channel.force(false);
        } catch (IOException ex) {
            log.debug("Could not write leader tag: {}", ex.toString());
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ex) {
            log.warn("Error releasing leader lock {}", lockFile, ex);
        }
        lock = null;
        channel = null;
    }
}
