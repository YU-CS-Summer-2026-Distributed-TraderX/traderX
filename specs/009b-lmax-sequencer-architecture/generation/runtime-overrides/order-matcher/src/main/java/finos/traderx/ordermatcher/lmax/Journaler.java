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
import java.nio.file.StandardOpenOption;

/**
 * Input-ring Journaler (FR-09B03/FR-09B04): appends every sequenced input event to a
 * durable log, in parallel with the Replicator, ahead of the BLP. Demo profile uses a
 * plain memory-buffered file channel with an fsync per drained batch (endOfBatch);
 * Chronicle Queue / Aeron Archive are the perf-profile substitutes.
 *
 * Demo-profile deviation from strict FR-09B04: on an append failure the journaler logs,
 * disables itself, and keeps advancing its sequence so the BLP (gated on
 * min(journaler, replicator)) is not wedged — availability over durability for the
 * containerized demo. The perf-profile journaler must instead stall the barrier (an event
 * that is not durable must not be processed).
 *
 * Record layout (fixed 64 bytes, little-endian):
 *   seq:i64 | type:i8 | side:i8 | pad:i16 | orderRef:i32 | accountId:i32 | securityId:i32 |
 *   qty:i32 | limitPx:i64 | priceTicks:i64 | eventTimeMillis:i64 | pad to 64
 */
public final class Journaler implements EventHandler<InputEvent>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Journaler.class);
    private static final int RECORD_SIZE = 64;

    private final boolean enabled;
    private final Path journalFile;
    private final HotPathMetrics metrics;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private FileChannel channel;
    private volatile long journaledSeq = -1;
    private long writtenBytes;                   // journaler-thread byte cursor (APPEND-mode position() is unreliable)
    private volatile long lastSnapshotOffset;   // journal byte offset just past the most recent SNAPSHOT marker
    private volatile long threadId;
    private volatile boolean failed;

    public Journaler(boolean enabled, Path journalDir, HotPathMetrics metrics) {
        this.enabled = enabled;
        this.metrics = metrics;
        this.journalFile = journalDir.resolve("input-events.journal");
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
            buffer.clear();
            buffer.putLong(e.seq);
            buffer.put(e.type);
            buffer.put(e.side);
            buffer.putShort((short) 0);
            buffer.putInt(e.orderRef);
            buffer.putInt(e.accountId);
            buffer.putInt(e.securityId);
            buffer.putInt(e.qty);
            buffer.putLong(e.limitPx);
            buffer.putLong(e.priceTicks);
            buffer.putLong(e.eventTimeMillis);
            buffer.putInt(0);
            buffer.putLong(0L); // pad 52 -> 64
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            writtenBytes += RECORD_SIZE;
            if (e.type == InputEvent.TYPE_SNAPSHOT) {
                // Force the journal through the marker and record the tail boundary the snapshot covers,
                // so recovery can load the snapshot and replay only from here.
                channel.force(false);
                lastSnapshotOffset = writtenBytes;
            } else if (endOfBatch) {
                channel.force(false); // durability amortized across the drained batch
            }
        } catch (IOException ex) {
            failed = true;
            log.error("Journal append failed at seq {}; journaling disabled", sequence, ex);
        }
        metrics.recordJournalLatency(System.nanoTime() - start);
        journaledSeq = sequence;
    }

    public long journaledSeq() {
        return journaledSeq;
    }

    /** True while appends are actually reaching the journal (enabled and not failed). */
    public boolean isWriting() {
        return enabled && !failed;
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
                channel.force(true);
                channel.close();
            }
        } catch (IOException ex) {
            log.warn("Error closing journal", ex);
        }
    }
}
