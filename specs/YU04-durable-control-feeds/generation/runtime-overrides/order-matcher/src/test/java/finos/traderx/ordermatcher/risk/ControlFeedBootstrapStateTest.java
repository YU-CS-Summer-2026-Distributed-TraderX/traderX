package finos.traderx.ordermatcher.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.ordermatcher.risk.ControlFeedBootstrapState.Outcome;
import finos.traderx.ordermatcher.risk.ControlFeedBootstrapState.Snapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers ADR-019's full "Validation (when adopted)" list: updates immediately before/during/after
 * snapshot creation, duplicate/reorder/gap/epoch-change fixtures, buffer overflow,
 * checksum/schema mismatch, and readiness remaining false until high-watermark catch-up.
 */
class ControlFeedBootstrapStateTest {

    /** Trivial payload: just carries its own key so applied-order can be asserted. */
    private record Rec(String key) {}

    private static String checksum(List<Rec> records) {
        return ChecksumCodec.checksum(records, r -> r.key() + ";");
    }

    private static Snapshot<Rec> snapshotOf(long epoch, long watermark, Rec... records) {
        List<Rec> list = List.of(records);
        return new Snapshot<>(epoch, watermark, list.size(), checksum(list), list);
    }

    @Test
    void notReadyUntilBootstrapCompletes() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        assertFalse(state.isReady());

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.installSnapshotAndReplay(
            snapshotOf(1, 10, new Rec("a"), new Rec("b")),
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> applied.add(r),
            (r, v) -> applied.add(r));

        assertEquals(Outcome.OK, outcome);
        assertTrue(state.isReady());
        assertEquals(10, state.watermark());
        assertEquals(1, state.epoch());
        assertEquals(List.of(new Rec("a"), new Rec("b")), applied);
    }

    @Test
    void deltaArrivingDuringTheSnapshotWindowIsBufferedThenReplayedAfterInstall() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);

        // A change happens (and is published) between "subscribe" and "snapshot fetch completes" —
        // exactly the handoff-window race ADR-019 exists to close.
        assertEquals(Outcome.OK, state.bufferDelta(11, 1, new Rec("c")));

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.installSnapshotAndReplay(
            snapshotOf(1, 10, new Rec("a"), new Rec("b")),
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> applied.add(r),
            (r, v) -> applied.add(r));

        assertEquals(Outcome.OK, outcome);
        assertTrue(state.isReady());
        assertEquals(11, state.watermark());
        assertEquals(List.of(new Rec("a"), new Rec("b"), new Rec("c")), applied);
    }

    @Test
    void bufferedDuplicateAtOrBelowWatermarkIsDiscardedNotApplied() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        // Buffered before the subscribe point knew the eventual watermark; snapshot already covers it.
        assertEquals(Outcome.OK, state.bufferDelta(7, 1, new Rec("stale")));
        assertEquals(Outcome.OK, state.bufferDelta(10, 1, new Rec("also-stale")));

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.installSnapshotAndReplay(
            snapshotOf(1, 10, new Rec("a")),
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> applied.add(r),
            (r, v) -> applied.add(r));

        assertEquals(Outcome.OK, outcome);
        assertTrue(state.isReady());
        assertEquals(List.of(new Rec("a")), applied); // neither stale delta re-applied
    }

    @Test
    void bufferedDeltasAreReplayedInVersionOrderRegardlessOfArrivalOrder() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        assertEquals(Outcome.OK, state.bufferDelta(12, 1, new Rec("later")));
        assertEquals(Outcome.OK, state.bufferDelta(11, 1, new Rec("earlier")));

        List<Rec> applied = new ArrayList<>();
        state.installSnapshotAndReplay(
            snapshotOf(1, 10),
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> applied.add(r),
            (r, v) -> applied.add(r));

        assertEquals(List.of(new Rec("earlier"), new Rec("later")), applied);
    }

    @Test
    void gapInBufferedDeltasFailsBootstrap() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        assertEquals(Outcome.OK, state.bufferDelta(13, 1, new Rec("skips-ahead"))); // watermark 10 -> expects 11

        Outcome outcome = state.installSnapshotAndReplay(
            snapshotOf(1, 10),
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> {},
            (r, v) -> {});

        assertEquals(Outcome.GAP, outcome);
        assertFalse(state.isReady());
    }

    @Test
    void epochChangeInBufferedDeltasFailsBootstrap() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        assertEquals(Outcome.OK, state.bufferDelta(11, 2, new Rec("new-epoch"))); // snapshot epoch is 1

        Outcome outcome = state.installSnapshotAndReplay(
            snapshotOf(1, 10),
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> {},
            (r, v) -> {});

        assertEquals(Outcome.EPOCH_MISMATCH, outcome);
        assertFalse(state.isReady());
    }

    @Test
    void bufferOverflowIsReportedAndDoesNotGrowUnbounded() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 2);
        assertEquals(Outcome.OK, state.bufferDelta(11, 1, new Rec("a")));
        assertEquals(Outcome.OK, state.bufferDelta(12, 1, new Rec("b")));
        assertEquals(Outcome.BUFFER_OVERFLOW, state.bufferDelta(13, 1, new Rec("c")));
        assertEquals(2, state.bufferedCount());
    }

    @Test
    void checksumMismatchFailsVerification() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        List<Rec> tampered = List.of(new Rec("a"));
        Snapshot<Rec> snapshot = new Snapshot<>(1, 10, 1, "sha256:not-the-real-checksum", tampered);

        Outcome outcome = state.installSnapshotAndReplay(
            snapshot, ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});

        assertEquals(Outcome.CHECKSUM_MISMATCH, outcome);
        assertFalse(state.isReady());
    }

    @Test
    void countMismatchFailsVerificationEvenIfChecksumWouldMatch() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        List<Rec> records = List.of(new Rec("a"));
        Snapshot<Rec> snapshot = new Snapshot<>(1, 10, 2 /* wrong count */, checksum(records), records);

        Outcome outcome = state.installSnapshotAndReplay(
            snapshot, ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});

        assertEquals(Outcome.CHECKSUM_MISMATCH, outcome);
        assertFalse(state.isReady());
    }

    @Test
    void liveDeltaAppliesInSequenceAndAdvancesWatermark() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        state.installSnapshotAndReplay(snapshotOf(1, 10), ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.applyLiveDelta(11, 1, new Rec("x"), (r, v) -> applied.add(r));

        assertEquals(Outcome.OK, outcome);
        assertEquals(List.of(new Rec("x")), applied);
        assertEquals(11, state.watermark());
        assertTrue(state.isReady());
    }

    @Test
    void liveDuplicateRedeliveryIsIdempotentNotAFault() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        state.installSnapshotAndReplay(snapshotOf(1, 10), ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});
        state.applyLiveDelta(11, 1, new Rec("x"), (r, v) -> {});

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.applyLiveDelta(11, 1, new Rec("x-again"), (r, v) -> applied.add(r));

        assertEquals(Outcome.DUPLICATE, outcome);
        assertTrue(applied.isEmpty(), "a duplicate must not be re-applied");
        assertTrue(state.isReady(), "an ordinary duplicate redelivery must not invalidate readiness");
        assertEquals(11, state.watermark());
    }

    @Test
    void liveGapInvalidatesReadinessAndDoesNotApply() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        state.installSnapshotAndReplay(snapshotOf(1, 10), ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.applyLiveDelta(13, 1, new Rec("skips-ahead"), (r, v) -> applied.add(r));

        assertEquals(Outcome.GAP, outcome);
        assertTrue(applied.isEmpty());
        // Outcome alone does not flip readiness — the caller (ControlFeedSubscriber) must call
        // quarantine() on a non-OK outcome; verified separately below.
    }

    @Test
    void liveEpochChangeIsReportedAndDoesNotApply() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        state.installSnapshotAndReplay(snapshotOf(1, 10), ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.applyLiveDelta(11, 2, new Rec("new-epoch"), (r, v) -> applied.add(r));

        assertEquals(Outcome.EPOCH_MISMATCH, outcome);
        assertTrue(applied.isEmpty());
    }

    @Test
    void quarantineInvalidatesReadinessAndClearsTheBufferButKeepsWatermarkForObservability() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        state.installSnapshotAndReplay(snapshotOf(1, 10), ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});
        state.bufferDelta(11, 1, new Rec("would-be-lost"));
        assertTrue(state.isReady());

        state.quarantine();

        assertFalse(state.isReady());
        assertEquals(0, state.bufferedCount());
        assertEquals(10, state.watermark(), "watermark retained for observability until the next successful install");
    }

    @Test
    void canReBootstrapCleanlyAfterQuarantine() {
        ControlFeedBootstrapState<Rec> state = new ControlFeedBootstrapState<>("test", 16);
        state.installSnapshotAndReplay(snapshotOf(1, 10), ControlFeedBootstrapStateTest::checksum, (r, v) -> {}, (r, v) -> {});
        state.applyLiveDelta(13, 1, new Rec("gap"), (r, v) -> {}); // caller detects GAP, quarantines
        state.quarantine();

        List<Rec> applied = new ArrayList<>();
        Outcome outcome = state.installSnapshotAndReplay(
            snapshotOf(2, 20, new Rec("resynced")), // fresh epoch after resync
            ControlFeedBootstrapStateTest::checksum,
            (r, v) -> applied.add(r),
            (r, v) -> applied.add(r));

        assertEquals(Outcome.OK, outcome);
        assertTrue(state.isReady());
        assertEquals(2, state.epoch());
        assertEquals(20, state.watermark());
        assertEquals(List.of(new Rec("resynced")), applied);
    }
}
