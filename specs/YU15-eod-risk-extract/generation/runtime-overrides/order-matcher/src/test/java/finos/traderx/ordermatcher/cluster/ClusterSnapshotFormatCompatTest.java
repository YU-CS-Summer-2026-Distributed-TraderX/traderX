package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot format compatibility across the YU15 boundary (SNAPSHOT_FORMAT 3 -> 4).
 *
 * <p>Its own class rather than cases added to {@code ClusterSnapshotCodecTest}, because that test's
 * operative layer is YU14, where neither {@code MIN_READABLE_SNAPSHOT_FORMAT} nor a symbol id of 64
 * exists — adding them there would not fail YU14's suite, it would stop YU14 compiling. The format
 * widened at YU15, so its tests live at YU15.
 *
 * <p>The bump exists because 64 -> 1024 widened the symbol-id domain without a version change,
 * leaving two builds indistinguishable at the header while disagreeing about a valid symbol id.
 * A 2026-07-22 build handed a snapshot from a 2026-08-04 build died deep in record parsing with
 * "snapshot corrupt: symbol id 64" — an intact snapshot accused of being damaged — taking the
 * service agent down on all three members while the pods stayed READY.
 */
class ClusterSnapshotFormatCompatTest {
    private static final int HEADER_BYTES = 52;

    private MatchingEngineClusteredService newRestoreTarget() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        return service;
    }

    private UnsafeBuffer header(final int format) {
        final UnsafeBuffer header = new UnsafeBuffer(new byte[HEADER_BYTES]);
        header.putInt(0, MatchingEngineClusteredService.T_HEADER);
        header.putInt(4, format);
        return header;
    }

    @Test
    void formatThreeStillRestoresOnTheWidenedSymbolTable() {
        // The load-bearing half of the bump: 4 changed no record's shape and only WIDENED the
        // symbol-id domain, so a format-3 snapshot must still restore. That is precisely what lets
        // a format-4 build roll onto an existing epoch instead of wiping it — every other format
        // case in this suite asserts a REJECTION, so without this one MIN_READABLE_SNAPSHOT_FORMAT
        // could be raised by accident with a fully green suite and the next roll would quietly cost
        // the cluster its epoch.
        //
        // The literal 3 is deliberate. Writing MIN_READABLE_SNAPSHOT_FORMAT here instead makes the
        // test read the very constant it exists to pin, so raising the constant moves the test with
        // it and nothing can ever fail. That version of this test was written first and passed
        // against MIN_READABLE = 4, which is the whole reason for this comment.
        final MatchingEngineClusteredService target = newRestoreTarget();
        target.onSnapshotRecord(header(3), 0);

        // And an id only the WIDER table can hold still loads on top of a format-3 header — the
        // exact combination the incident produced.
        final UnsafeBuffer symbol = new UnsafeBuffer(new byte[64]);
        symbol.putInt(0, MatchingEngineClusteredService.T_SYMBOL);
        symbol.putInt(4, 64);
        symbol.putInt(8, 3);
        symbol.putBytes(12, "IBM".getBytes(StandardCharsets.US_ASCII));
        target.onSnapshotRecord(symbol, 0);
    }

    @Test
    void newerFormatIsRejectedAsTooNewRatherThanCorrupt() {
        // The message is the point. "unknown snapshot format" and "snapshot corrupt" both read as a
        // damaged file and send the operator toward wiping the epoch; the snapshot is intact and the
        // reader is merely too old, and rolling FORWARD restores it untouched.
        final MatchingEngineClusteredService target = newRestoreTarget();
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> target.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT + 1), 0));
        assertTrue(thrown.getMessage().contains("NEWER"),
            "a too-new snapshot must name the direction, got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("do not wipe the epoch"),
            "the message must steer away from wiping the epoch, got: " + thrown.getMessage());
    }

    @Test
    void formatBelowTheReadableFloorIsRejectedAsTooOld() {
        // Format 2 (YU13) has no multiplier column, so it genuinely cannot be read here. Literal 2
        // for the same reason as the literal 3 above.
        final MatchingEngineClusteredService target = newRestoreTarget();
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> target.onSnapshotRecord(header(2), 0));
        assertTrue(thrown.getMessage().contains("older than this build can restore"),
            "a too-old snapshot must name the direction, got: " + thrown.getMessage());
    }
}
