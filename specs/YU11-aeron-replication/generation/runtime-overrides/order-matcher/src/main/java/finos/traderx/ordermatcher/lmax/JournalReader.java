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
 * <p>YU11 additions. The journal carries two kinds of non-business records: SNAPSHOT markers
 * (whose {@code seq} is a local ring sequence, not a business sequence) and rotation ANCHOR
 * records ({@link Journaler#ANCHOR_TYPE}, journal-private carriers of the pre-rotation business
 * tail — never surfaced to replay). Business records carry the replicated stream's
 * lineage-continuous {@code event.seq}. This reader therefore distinguishes the three for the
 * cross-epoch follower bootstrap: {@link #lastBusinessSeq()} proves the local business tail, and
 * {@link #truncateAfterBusinessSeq(long)} cuts a divergent suffix at an exact business boundary,
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
     * The last business sequence this journal proves: the {@code seq} of the final business
     * record or rotation anchor (SNAPSHOT markers carry local ring sequences and are ignored).
     * Returns -1 when the journal is missing or holds no business history. A non-monotonic
     * business sequence (a pre-lineage-base journal, where numbering restarted per boot) is
     * tolerated here by returning the maximum seen — a safe upper bound for a lineage base.
     */
    public long lastBusinessSeq() throws IOException {
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
                    byte type = buf.get();
                    buf.position(buf.position() + (RECORD_SIZE - Long.BYTES - Byte.BYTES));
                    if (type == InputEvent.TYPE_SNAPSHOT) continue;
                    if (seq < last) monotonic = false;
                    last = seq;
                    max = Math.max(max, seq);
                }
                buf.compact();
                if (read < 0) break;
            }
        }
        if (!monotonic) {
            log.warn("Journal {} has non-monotonic business sequences (pre-lineage journal); "
                + "using max {} as the lineage base tail", journalFile, max);
        }
        return max;
    }

    /**
     * Cross-epoch bootstrap cut: truncates the journal so its business history ends at exactly
     * {@code boundSeq} (the new leader epoch's first input sequence minus one), discarding a
     * divergent suffix the new leader lineage never saw. SNAPSHOT markers ride with the region
     * that precedes them; anchors count as business carriers.
     *
     * <p>Fails closed ({@link IllegalStateException}) when local history cannot prove the
     * boundary: the journal ends before {@code boundSeq} (this node fell behind — it needs a
     * snapshot transfer, which YU11 does not implement), the snapshot already covers past
     * {@code boundSeq} (rotation discarded the pre-boundary history), or the business sequence is
     * non-monotonic (a pre-lineage journal that cannot anchor an exact boundary).
     *
     * @return the number of bytes truncated (0 when the journal already ends at the boundary).
     */
    public long truncateAfterBusinessSeq(long boundSeq) throws IOException {
        if (!Files.exists(journalFile)) {
            if (boundSeq < 0) return 0L;
            throw new IllegalStateException("bootstrap requires local history through business seq "
                + boundSeq + " but " + journalFile + " does not exist (snapshot transfer needed)");
        }
        long keepEnd = 0L;
        long lastKept = -1L;
        long firstBusiness = -1L;
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
                    byte type = buf.get();
                    buf.position(buf.position() + (RECORD_SIZE - Long.BYTES - Byte.BYTES));
                    if (type == InputEvent.TYPE_SNAPSHOT) {
                        if (!cut) keepEnd = offset + RECORD_SIZE;
                    } else {
                        if (firstBusiness < 0) firstBusiness = seq;
                        if (!cut && seq <= boundSeq) {
                            if (seq < lastKept) {
                                throw new IllegalStateException("journal " + journalFile
                                    + " has non-monotonic business sequences (" + seq + " after "
                                    + lastKept + "); cannot anchor a bootstrap boundary — wipe the"
                                    + " journal or transfer a snapshot");
                            }
                            lastKept = seq;
                            keepEnd = offset + RECORD_SIZE;
                        } else {
                            cut = true;
                            break outer;
                        }
                    }
                    offset += RECORD_SIZE;
                }
                buf.compact();
                if (read < 0) break;
            }
        }
        if (lastKept != boundSeq) {
            String reason = firstBusiness >= 0 && firstBusiness > boundSeq
                ? "the local snapshot/rotation already covers past the boundary (first retained"
                    + " business seq " + firstBusiness + ")"
                : "local history ends at business seq " + lastKept + " (node fell behind)";
            throw new IllegalStateException("cannot bootstrap at business boundary " + boundSeq
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
        log.info("Bootstrap journal cut: truncated {} bytes of divergent suffix after business seq {} in {}",
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
