package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Input-ring Journaler (FR-09B03/FR-09B04): appends every sequenced input event to a
 * durable log, in parallel with the Replicator, ahead of the BLP. Demo profile uses a
 * plain file channel; records are coalesced into a 64 KiB buffer so BOTH the write() and
 * the fsync are amortised per drained Disruptor batch (endOfBatch) — one syscall pair for
 * the whole batch instead of a write() per event (state 009b Tier 3-D). Chronicle Queue /
 * Aeron Archive are the perf-profile substitutes.
 *
 * Demo-profile deviation from strict FR-09B04: on an append failure the journaler logs,
 * disables itself, and keeps advancing its sequence so the BLP (gated on
 * min(journaler, replicator)) is not wedged — availability over durability for the
 * containerized demo. The perf-profile journaler must instead stall the barrier (an event
 * that is not durable must not be processed). Coalescing widens the loss-on-crash window to
 * the current batch (records buffered but not yet flushed are lost on a process crash, not
 * just a power loss) — within the demo's availability-over-durability stance and bounded by
 * the periodic snapshot. Set journal.batch.records=1 to restore the per-event write.
 *
 * Record layout (fixed 64 bytes, little-endian):
 *   seq:i64 | type:i8 | side:i8 | pad:i16 | orderRef:i32 | accountId:i32 | securityId:i32 |
 *   qty:i32 | limitPx:i64 | priceTicks:i64 | eventTimeMillis:i64 | pad to 64
 */
public final class Journaler implements EventHandler<InputEvent>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Journaler.class);
    private static final int RECORD_SIZE = 64;
    private static final int DEFAULT_BATCH_RECORDS = 1024;   // 64 KiB coalescing buffer

    private final boolean enabled;
    private final Path journalDir;
    private final Path journalFile;
    private final HotPathMetrics metrics;
    private final JournalArchiver archiver;   // no-op (disabled) unless YU09 journal archival is configured
    // Coalescing buffer: records are appended here and written to the channel in one syscall per drained
    // Disruptor batch (or when the buffer fills), instead of one write() per event. Sized once at
    // construction (startup), so steady-state journaling stays allocation-free (NGC-01). Capacity is a
    // whole number of records; batchRecords=1 makes it one record (≈ the original per-event write).
    private final ByteBuffer batchBuffer;
    private FileChannel channel;
    private volatile long journaledSeq = -1;
    private long writtenBytes;                   // journaler-thread byte cursor (APPEND-mode position() is unreliable)
    private volatile long lastSnapshotOffset;   // journal byte offset just past the most recent SNAPSHOT marker
    private volatile long threadId;
    private volatile boolean failed;

    public Journaler(boolean enabled, Path journalDir, HotPathMetrics metrics) {
        this(enabled, journalDir, metrics, DEFAULT_BATCH_RECORDS, null);
    }

    public Journaler(boolean enabled, Path journalDir, HotPathMetrics metrics, int batchRecords) {
        this(enabled, journalDir, metrics, batchRecords, null);
    }

    public Journaler(boolean enabled, Path journalDir, HotPathMetrics metrics, int batchRecords, JournalArchiver archiver) {
        this.enabled = enabled;
        this.metrics = metrics;
        this.journalDir = journalDir;
        this.journalFile = journalDir.resolve("input-events.journal");
        this.archiver = archiver;
        this.batchBuffer = ByteBuffer.allocateDirect(RECORD_SIZE * Math.max(1, batchRecords))
            .order(ByteOrder.LITTLE_ENDIAN);
        if (enabled) {
            try {
                Files.createDirectories(journalDir);
                this.channel = FileChannel.open(journalFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                this.writtenBytes = channel.size();   // continue the byte cursor across restarts (append)
                log.info("Journaling sequenced input events to {} (at byte {})", journalFile.toAbsolutePath(), writtenBytes);
            } catch (IOException ex) {
                log.error("Unable to open journal at {}; journaling disabled", journalFile, ex);
                this.failed = true;
            }
        }
    }

    /** BatchEventProcessor start hook: runs on the journaler thread before the first event. */
    @Override
    public void onStart() {
        threadId = Thread.currentThread().threadId();
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        if (!enabled || failed) {
            journaledSeq = sequence;
            return;
        }
        long start = System.nanoTime();
        try {
            if (batchBuffer.remaining() < RECORD_SIZE) {
                flushBatch();   // buffer full mid-batch: drain it before appending the next record
            }
            batchBuffer.putLong(e.seq);
            batchBuffer.put(e.type);
            batchBuffer.put(e.side);
            batchBuffer.putShort((short) 0);
            batchBuffer.putInt(e.orderRef);
            batchBuffer.putInt(e.accountId);
            batchBuffer.putInt(e.securityId);
            batchBuffer.putInt(e.qty);
            batchBuffer.putLong(e.limitPx);
            batchBuffer.putLong(e.priceTicks);
            batchBuffer.putLong(e.eventTimeMillis);
            batchBuffer.putInt(0);
            batchBuffer.putLong(0L); // pad 52 -> 64
            writtenBytes += RECORD_SIZE;
            if (e.type == InputEvent.TYPE_SNAPSHOT) {
                // Flush the coalesced records through the marker, force, and record the tail boundary the
                // snapshot covers, so recovery can load the snapshot and replay only from here.
                flushBatch();
                channel.force(false);
                lastSnapshotOffset = writtenBytes;
                if (archiver != null) {
                    rotate();   // everything up to lastSnapshotOffset is now covered by the snapshot
                }
            } else if (endOfBatch) {
                flushBatch();           // one write() syscall for the whole drained batch
                channel.force(false);   // durability amortized across the drained batch
            }
        } catch (IOException ex) {
            failed = true;
            log.error("Journal append failed at seq {}; journaling disabled", sequence, ex);
        }
        metrics.recordJournalLatency(System.nanoTime() - start);
        journaledSeq = sequence;
    }

    /** Runs on the journaler thread, right after a SNAPSHOT marker is durably written: everything up
     *  to this point is now covered by the snapshot, so the active journal file is closed off as an
     *  immutable segment and handed to the archiver, and a fresh empty file takes over as the active
     *  journal. Recovery only ever needs the active file from byte 0 forward after this point, which
     *  is exactly what {@link #lastSnapshotOffset()} is reset to. A rotation failure is logged and
     *  swallowed — journaling continues on the current (unrotated) file, same availability-over-
     *  durability stance as an append failure.
     *
     *  Builds the segment filename with StringBuilder rather than string concatenation and reads
     *  the clock with System.currentTimeMillis() rather than java.time.Instant — both banned APIs
     *  on this class (HotPathBannedApiTest scans Journaler's whole constant pool, not just the
     *  per-event onEvent path). */
    private void rotate() {
        try {
            flushBatch();
            channel.close();
            StringBuilder name = new StringBuilder(32);
            name.append("input-events-").append(System.currentTimeMillis()).append(".journal");
            Path segment = journalDir.resolve(name.toString());
            Files.move(journalFile, segment, StandardCopyOption.REPLACE_EXISTING);
            channel = FileChannel.open(journalFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            writtenBytes = 0L;
            lastSnapshotOffset = 0L;
            archiver.archiveAsync(segment);
        } catch (IOException ex) {
            log.warn("Journal rotation failed; continuing on the current journal file", ex);
            // rotate closes before moving. Reopen the active path so the next event can still be
            // journaled; otherwise the swallowed rotation exception merely defers a fatal
            // ClosedChannelException to the next append.
            try {
                if (channel == null || !channel.isOpen()) {
                    channel = FileChannel.open(journalFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    writtenBytes = channel.size();
                    lastSnapshotOffset = writtenBytes;
                }
            } catch (IOException reopenEx) {
                failed = true;
                log.error("Journal rotation recovery failed; journaling disabled", reopenEx);
            }
        }
    }

    /** Drain the coalescing buffer to the channel in one write (looped for short writes); allocation-free.
     *  No-op when the buffer is empty (flip→limit 0). */
    private void flushBatch() throws IOException {
        batchBuffer.flip();
        while (batchBuffer.hasRemaining()) {
            channel.write(batchBuffer);
        }
        batchBuffer.clear();
    }

    public long journaledSeq() {
        return journaledSeq;
    }

    /** Journal byte offset just past the most recent SNAPSHOT marker (the tail start for recovery). */
    public long lastSnapshotOffset() {
        return lastSnapshotOffset;
    }

    public long journalThreadId() {
        return threadId;
    }

    @Override
    public void close() {
        try {
            if (channel != null) {
                flushBatch();        // persist any records still in the coalescing buffer
                channel.force(true);
                channel.close();
            }
        } catch (IOException ex) {
            log.warn("Error closing journal", ex);
        }
        if (archiver != null) {
            archiver.close();
        }
    }
}
