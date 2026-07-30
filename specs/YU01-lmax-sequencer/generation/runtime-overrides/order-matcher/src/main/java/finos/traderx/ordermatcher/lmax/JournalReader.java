package finos.traderx.ordermatcher.lmax;

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
 * Replay-side reader for the input journal (state YU01 recovery). Scans the fixed 64-byte
 * little-endian records written by {@link Journaler} back into {@link InputEvent}s, in file
 * order, and hands each to a callback so the caller can re-drive the BLP (deterministic
 * event-sourced recovery: same inputs, in order, reproduce the same state).
 *
 * <p>Exact mirror of {@link Journaler}'s layout:
 * <pre>
 *   seq:i64 | type:i8 | side:i8 | pad:i16 | orderRef:i32 | accountId:i32 | securityId:i32 |
 *   qty:i32 | limitPx:i64 | priceTicks:i64 | eventTimeMillis:i64 | pad:i32 | pad:i64  (= 64)
 * </pre>
 * {@code ingressNanos} is not journaled (a latency-measurement field, not state) and is left 0.
 * A torn trailing record (a crash mid-append) is shorter than 64 bytes and is discarded — it was
 * never durable, so it is correctly excluded from replay.
 */
public final class JournalReader {
    private static final Logger log = LoggerFactory.getLogger(JournalReader.class);
    private static final int RECORD_SIZE = 64;

    private final Path journalFile;

    public JournalReader(Path journalDir) {
        this.journalFile = journalDir.resolve("input-events.journal");
    }

    /** Callback for each decoded record. The {@link InputEvent} is reused per record (do not retain). */
    public interface RecordHandler {
        void onRecord(InputEvent event);
    }

    public boolean exists() {
        return Files.exists(journalFile);
    }

    /** Replays every complete record to {@code handler} in file order; returns the count replayed. */
    public long replay(RecordHandler handler) throws IOException {
        return replayFrom(0, handler);
    }

    /** Replays records starting at byte {@code startOffset} (a record boundary; defensively aligned)
     *  to end of file. Used for snapshot+tail recovery — the snapshot covers everything before. */
    public long replayFrom(long startOffset, RecordHandler handler) throws IOException {
        if (!Files.exists(journalFile)) {
            return 0;
        }
        long count = 0;
        InputEvent event = new InputEvent();
        // Chunked read aligned to records: drain whole records, compact() keeps any partial tail for
        // the next read so records spanning a chunk boundary are reassembled.
        ByteBuffer buf = ByteBuffer.allocateDirect(RECORD_SIZE * 4096).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = Math.max(0, Math.min(startOffset, size));
            start -= start % RECORD_SIZE;   // align down to a record boundary
            channel.position(start);
            while (true) {
                int read = channel.read(buf);
                buf.flip();
                while (buf.remaining() >= RECORD_SIZE) {
                    decode(buf, event);
                    handler.onRecord(event);
                    count++;
                }
                buf.compact();
                if (read < 0) {
                    break;   // EOF; any <64-byte remainder is a torn final record, discarded
                }
            }
        }
        log.info("Journal replay: {} events from offset {} of {}", count, startOffset, journalFile.toAbsolutePath());
        return count;
    }

    private static void decode(ByteBuffer b, InputEvent e) {
        e.seq = b.getLong();
        e.type = b.get();
        e.side = b.get();
        b.getShort();            // pad i16
        e.orderRef = b.getInt();
        e.accountId = b.getInt();
        e.securityId = b.getInt();
        e.qty = b.getInt();
        e.limitPx = b.getLong();
        e.priceTicks = b.getLong();
        e.eventTimeMillis = b.getLong();
        b.getInt();              // pad i32
        b.getLong();             // pad i64  (52 -> 64)
        e.ingressNanos = 0L;     // not journaled; irrelevant to state reconstruction
    }
}
