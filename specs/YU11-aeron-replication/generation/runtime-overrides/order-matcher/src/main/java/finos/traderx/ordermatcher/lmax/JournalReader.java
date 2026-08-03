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
 * Replay-side reader for the input journal (state 009b recovery). Scans the fixed 64-byte
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
 *
 * <p>YU11 additions. SNAPSHOT markers do not mutate business state, but they are replicated inputs:
 * they consume the same lineage-continuous {@code event.seq} as commands and must participate in
 * restart and cross-epoch continuity. Rotation ANCHOR records ({@link Journaler#ANCHOR_TYPE}) are
 * journal-private carriers of the pre-rotation input tail and are never surfaced to replay.
 * {@link #lastInputSeq()} proves the local stream tail and
 * {@link #truncateAfterInputSeq(long)} cuts a divergent suffix at an exact stream boundary,
 * failing closed when local history cannot prove that boundary.
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
     *  to end of file. Used for snapshot+tail recovery — the snapshot covers everything before.
     *  Rotation anchors are journal-private bookkeeping and are not surfaced. */
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
                    if (event.type != Journaler.ANCHOR_TYPE) {
                        handler.onRecord(event);
                        count++;
                    }
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

    /**
     * The last replicated input sequence this journal proves: the {@code seq} of the final input
     * record or rotation anchor. SNAPSHOT markers count because they occupy an Aeron inputSeq.
     * Returns -1 when the journal is missing or holds no business history. A non-monotonic
     * input sequence (a pre-lineage-base journal, where numbering restarted per boot) is
     * tolerated here by returning the maximum seen — a safe upper bound for a lineage base.
     */
    public long lastInputSeq() throws IOException {
        if (!Files.exists(journalFile)) return -1L;
        long last = -1L;
        long max = -1L;
        boolean monotonic = true;
        try (FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(RECORD_SIZE * 4096).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                int read = channel.read(buf);
                buf.flip();
                while (buf.remaining() >= RECORD_SIZE) {
                    long seq = buf.getLong();
                    buf.get();   // type: every record, including SNAPSHOT and ANCHOR, carries the tail
                    buf.position(buf.position() + (RECORD_SIZE - Long.BYTES - Byte.BYTES));
                    if (seq < last) monotonic = false;
                    last = seq;
                    max = Math.max(max, seq);
                }
                buf.compact();
                if (read < 0) break;
            }
        }
        if (!monotonic) {
            log.warn("Journal {} has non-monotonic input sequences (pre-lineage journal); "
                + "using max {} as the lineage base tail", journalFile, max);
        }
        return max;
    }

    /**
     * Cross-epoch bootstrap cut: truncates the journal so its replicated input history ends exactly
     * {@code boundSeq} (the new leader epoch's first input sequence minus one), discarding a
     * divergent suffix the new leader lineage never saw. SNAPSHOT markers and anchors both prove
     * input-sequence boundaries.
     *
     * <p>Fails closed ({@link IllegalStateException}) when local history cannot prove the
     * boundary: the journal ends before {@code boundSeq} (this node fell behind and the caller
     * must use recovery-bundle transfer), the snapshot already covers past {@code boundSeq}
     * (rotation discarded the pre-boundary history), or the input sequence is non-monotonic
     * (a pre-lineage journal that cannot anchor an exact boundary).
     *
     * @return the number of bytes truncated (0 when the journal already ends at the boundary).
     */
    public long truncateAfterInputSeq(long boundSeq) throws IOException {
        if (!Files.exists(journalFile)) {
            if (boundSeq < 0) return 0L;
            throw new IllegalStateException("bootstrap requires local history through input seq "
                + boundSeq + " but " + journalFile + " does not exist (snapshot transfer needed)");
        }
        long keepEnd = 0L;
        long lastKept = -1L;
        long firstInput = -1L;
        boolean cut = false;
        long size;
        try (FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.READ)) {
            size = channel.size();
            ByteBuffer buf = ByteBuffer.allocateDirect(RECORD_SIZE * 4096).order(ByteOrder.LITTLE_ENDIAN);
            long offset = 0L;
            outer:
            while (true) {
                int read = channel.read(buf);
                buf.flip();
                while (buf.remaining() >= RECORD_SIZE) {
                    long seq = buf.getLong();
                    buf.get();   // type: SNAPSHOT and ANCHOR are both valid stream boundaries
                    buf.position(buf.position() + (RECORD_SIZE - Long.BYTES - Byte.BYTES));
                    if (firstInput < 0) firstInput = seq;
                    if (!cut && seq <= boundSeq) {
                        if (seq < lastKept) {
                            throw new IllegalStateException("journal " + journalFile
                                + " has non-monotonic input sequences (" + seq + " after "
                                + lastKept + "); cannot anchor a bootstrap boundary — wipe the"
                                + " journal or transfer a snapshot");
                        }
                        lastKept = seq;
                        keepEnd = offset + RECORD_SIZE;
                    } else {
                        cut = true;
                        break outer;
                    }
                    offset += RECORD_SIZE;
                }
                buf.compact();
                if (read < 0) break;
            }
        }
        if (lastKept != boundSeq) {
            String reason = firstInput >= 0 && firstInput > boundSeq
                ? "the local snapshot/rotation already covers past the boundary (first retained"
                    + " input seq " + firstInput + ")"
                : "local history ends at input seq " + lastKept + " (node fell behind)";
            throw new IllegalStateException("cannot bootstrap at input boundary " + boundSeq
                + ": " + reason + " — snapshot transfer needed");
        }
        if (keepEnd >= size) {
            return 0L;
        }
        try (FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.WRITE)) {
            channel.truncate(keepEnd);
            channel.force(false);
        }
        long truncated = size - keepEnd;
        log.info("Bootstrap journal cut: truncated {} bytes of divergent suffix after input seq {} in {}",
            truncated, boundSeq, journalFile);
        return truncated;
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
