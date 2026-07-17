package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cross-epoch bootstrap contract of the YU11 JournalReader: business-tail proof, exact
 *  boundary cuts of a divergent suffix, fail-closed behaviour when local history cannot prove
 *  the boundary, and anchor/marker transparency. */
class JournalReaderBootstrapTest {
    @TempDir Path tempDir;

    @Test
    void lastBusinessSeqIgnoresSnapshotMarkersAndCountsAnchors() throws IOException {
        writeRecords(record(0, InputEvent.TYPE_ORDER_NEW), record(1, InputEvent.TYPE_ORDER_NEW),
            record(999, InputEvent.TYPE_SNAPSHOT), record(2, InputEvent.TYPE_PRICE_TICK));
        assertThat(new JournalReader(tempDir).lastBusinessSeq()).isEqualTo(2L);

        writeRecords(record(41, Journaler.ANCHOR_TYPE));
        assertThat(new JournalReader(tempDir).lastBusinessSeq()).isEqualTo(41L);
    }

    @Test
    void replaySkipsAnchorsButSurfacesMarkers() throws IOException {
        writeRecords(record(9, Journaler.ANCHOR_TYPE), record(10, InputEvent.TYPE_ORDER_NEW),
            record(777, InputEvent.TYPE_SNAPSHOT), record(11, InputEvent.TYPE_ORDER_NEW));
        List<Long> seen = new ArrayList<>();
        long count = new JournalReader(tempDir).replay(e -> seen.add(e.seq));
        assertThat(count).isEqualTo(3L);
        assertThat(seen).containsExactly(10L, 777L, 11L);
    }

    @Test
    void truncateCutsDivergentSuffixAtExactBoundary() throws IOException {
        // Business 0..14 with a marker riding mid-stream; the new epoch starts at S0=13, so the
        // bound is 12: records 13,14 are the divergent suffix the new lineage never saw.
        byte[][] records = new byte[17][];
        for (int i = 0; i <= 9; i++) records[i] = record(i, InputEvent.TYPE_ORDER_NEW);
        records[10] = record(555, InputEvent.TYPE_SNAPSHOT);
        for (int i = 10; i <= 14; i++) records[i + 1] = record(i, InputEvent.TYPE_ORDER_NEW);
        records[16] = record(556, InputEvent.TYPE_SNAPSHOT);   // post-divergence marker: cut too
        writeRecords(records);

        JournalReader reader = new JournalReader(tempDir);
        long truncated = reader.truncateAfterBusinessSeq(12L);
        assertThat(truncated).isEqualTo(3L * 64L);
        assertThat(reader.lastBusinessSeq()).isEqualTo(12L);

        List<Long> seen = new ArrayList<>();
        reader.replay(e -> seen.add(e.seq));
        assertThat(seen).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 555L, 10L, 11L, 12L);

        // Idempotent when the file already ends at the boundary.
        assertThat(reader.truncateAfterBusinessSeq(12L)).isZero();
    }

    @Test
    void truncateFailsClosedWhenLocalHistoryEndsBeforeTheBoundary() throws IOException {
        writeRecords(record(0, InputEvent.TYPE_ORDER_NEW), record(1, InputEvent.TYPE_ORDER_NEW));
        assertThatThrownBy(() -> new JournalReader(tempDir).truncateAfterBusinessSeq(7L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fell behind");
    }

    @Test
    void truncateFailsClosedWhenRotationAlreadyCoversPastTheBoundary() throws IOException {
        // Post-rotation file: anchor at 9, business 10.. — a bound of 5 is unreachable.
        writeRecords(record(9, Journaler.ANCHOR_TYPE), record(10, InputEvent.TYPE_ORDER_NEW));
        assertThatThrownBy(() -> new JournalReader(tempDir).truncateAfterBusinessSeq(5L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("covers past the boundary");
    }

    @Test
    void truncateFailsClosedOnNonMonotonicLegacyJournal() throws IOException {
        // Pre-lineage journals restarted numbering per boot: 0,1,2,0,1 — no exact boundary exists.
        writeRecords(record(0, InputEvent.TYPE_ORDER_NEW), record(1, InputEvent.TYPE_ORDER_NEW),
            record(2, InputEvent.TYPE_ORDER_NEW), record(0, InputEvent.TYPE_ORDER_NEW),
            record(1, InputEvent.TYPE_ORDER_NEW));
        assertThatThrownBy(() -> new JournalReader(tempDir).truncateAfterBusinessSeq(2L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non-monotonic");
    }

    @Test
    void truncateOfMissingJournalIsOnlyValidAtTheOrigin() throws IOException {
        assertThat(new JournalReader(tempDir).truncateAfterBusinessSeq(-1L)).isZero();
        assertThatThrownBy(() -> new JournalReader(tempDir).truncateAfterBusinessSeq(3L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void anchorBoundaryKeepsOnlyTheAnchor() throws IOException {
        // Replacement whose entire tail is divergent: the anchor proves 9, everything after goes.
        writeRecords(record(9, Journaler.ANCHOR_TYPE), record(10, InputEvent.TYPE_ORDER_NEW),
            record(11, InputEvent.TYPE_ORDER_NEW));
        JournalReader reader = new JournalReader(tempDir);
        assertThat(reader.truncateAfterBusinessSeq(9L)).isEqualTo(2L * 64L);
        assertThat(reader.lastBusinessSeq()).isEqualTo(9L);
        assertThat(reader.replay(e -> { })).isZero();
    }

    private void writeRecords(byte[]... records) throws IOException {
        Path file = tempDir.resolve("input-events.journal");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (byte[] record : records) {
                ByteBuffer buf = ByteBuffer.wrap(record);
                while (buf.hasRemaining()) channel.write(buf);
            }
        }
    }

    private static byte[] record(long seq, byte type) {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(seq);
        buf.put(type);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(101);
        buf.putInt(22214);
        buf.putInt(7);
        buf.putInt(10);
        buf.putLong(123_000_000L);
        buf.putLong(122_000_000L);
        buf.putLong(17L);
        buf.putInt(0);
        buf.putLong(0L);
        return buf.array();
    }
}
