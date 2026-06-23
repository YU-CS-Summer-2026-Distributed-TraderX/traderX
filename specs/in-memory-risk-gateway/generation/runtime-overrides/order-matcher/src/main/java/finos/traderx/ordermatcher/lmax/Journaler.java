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
import java.util.zip.CRC32;

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
 * The file starts with the locked schema id/version header owned by
 * {@link InputEventJournalCodec}; each fixed-size record carries a CRC32. Legacy 009b and
 * pre-versioned risk journals are handled by explicit cold-path upcasters.
 */
public final class Journaler implements EventHandler<InputEvent>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Journaler.class);
    private final boolean enabled;
    private final Path journalFile;
    private final HotPathMetrics metrics;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(InputEventJournalCodec.RECORD_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32 checksum = new CRC32();
    private FileChannel channel;
    private volatile long journaledSeq = -1;
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
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (channel.size() == 0L) {
                    InputEventJournalCodec.writeHeader(channel);
                } else {
                    InputEventJournalCodec.validateHeader(channel);
                }
                channel.position(channel.size());
                log.info("Journaling sequenced input events to {}", journalFile.toAbsolutePath());
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
            journaledSeq = e.seq;
            return;
        }
        long start = System.nanoTime();
        try {
            InputEventJournalCodec.encode(e, buffer, checksum);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (endOfBatch) {
                channel.force(false); // durability amortized across the drained batch
            }
        } catch (IOException ex) {
            failed = true;
            log.error("Journal append failed at seq {}; journaling disabled", e.seq, ex);
        }
        metrics.recordJournalLatency(System.nanoTime() - start);
        journaledSeq = e.seq;
    }

    public long journaledSeq() {
        return journaledSeq;
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
