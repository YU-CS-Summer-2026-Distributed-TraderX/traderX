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
                log.info("Journaling sequenced input events to {}", journalFile.toAbsolutePath());
            } catch (IOException ex) {
                log.error("Unable to open journal at {}; journaling disabled", journalFile, ex);
                this.failed = true;
            }
        }
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
            while (buffer.position() < RECORD_SIZE) {
                buffer.put((byte) 0);
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (endOfBatch) {
                channel.force(false); // durability amortised across the drained batch
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
