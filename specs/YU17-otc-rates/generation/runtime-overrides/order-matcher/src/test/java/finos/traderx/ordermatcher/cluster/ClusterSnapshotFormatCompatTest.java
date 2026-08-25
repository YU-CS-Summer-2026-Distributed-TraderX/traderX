package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot format compatibility. Originally the YU15 boundary (SNAPSHOT_FORMAT 3 -> 4); re-based
 * at the YU17 <b>format-8 mint</b>, which raised {@code MIN_READABLE_SNAPSHOT_FORMAT} from 3 to 8
 * -- its first raise ever.
 *
 * <p><b>The previous version of this class caught that raise, which is the tripwire working.</b>
 * It asserted that a format-3 snapshot STILL RESTORES, precisely so MIN_READABLE could not be
 * raised by accident behind a green suite and cost a cluster its epoch on the next roll. Format 8
 * raises it deliberately: T_BOOK now stores the book's tick, a pre-8 T_BOOK carries an anchor with
 * no unit, and this build's grid derivation is not the one that wrote it -- so restoring a pre-8
 * snapshot would reinterpret every anchor at whatever scale this build happens to derive, silently
 * and only for the securities whose tick changed. The assertion is therefore INVERTED here rather
 * than deleted, and the anti-vacuity control it carried (a wide symbol id restoring on a valid
 * header) is kept, re-based on the current format -- without it "format 3 is refused" would be
 * satisfied by a reader that refuses everything.
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
    void formatThreeIsRefusedSinceTheGridBecameStoredState() {
        // The format-8 inversion of this class's original assertion. The literal 3 is deliberate,
        // for the same reason it always was: writing MIN_READABLE_SNAPSHOT_FORMAT here would make
        // the test read the very constant it exists to pin, so the constant could move again and
        // nothing would ever fail. Lowering MIN_READABLE back below 8 must break THIS line.
        final MatchingEngineClusteredService target = newRestoreTarget();
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> target.onSnapshotRecord(header(3), 0),
            "a pre-8 snapshot's T_BOOK carries an anchor with no tick; restoring it would"
                + " reinterpret the anchor at this build's derived scale, silently");
        assertTrue(thrown.getMessage().contains("older than this build can restore"),
            "the refusal must name the direction (too old), got: " + thrown.getMessage());
    }

    @Test
    void theCurrentFormatStillRestoresOnTheWidenedSymbolTable() {
        // ANTI-VACUITY, and the surviving half of the YU15 incident's own assertion. Every other
        // case here asserts a REJECTION, so without one positive restore "format 3 is refused"
        // would be satisfied by a reader that refuses everything. A symbol id only the WIDER
        // (1024-entry) table can hold still loads on a current header — the exact combination the
        // 2026-08-05 incident produced.
        final MatchingEngineClusteredService target = newRestoreTarget();
        target.onSnapshotRecord(header(MatchingEngineClusteredService.SNAPSHOT_FORMAT), 0);

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
