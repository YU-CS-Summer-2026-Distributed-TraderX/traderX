package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code yu17-halt-survives-failover} / {@code yu17-closed-survives-restart}, at the seam where
 * their claim is actually decided (format-8 mint scope §1.4, §5 rows 6 and 7).
 *
 * <p><b>Why the durability proofs live here and not only on a rig.</b> "A halt a restart can
 * bypass is not a halt" (ADR-069 decision 5) is a claim about the SNAPSHOT CODEC, not about
 * Kubernetes: a new leader is a member that restored and replayed, and a restarted member is the
 * same thing. MECS exposes {@code writeSnapshot} / {@code onSnapshotRecord} as package-private
 * seams precisely so that question needs no cluster — the same route
 * {@link GridRestoreFormatTest} took for the grid half of this mint. The two shell proofs kill a
 * leader and restart a member, which the shared standing epoch cannot absorb, so their live arms
 * are gated {@code DESTRUCTIVE=1} and run at the mint; the red halves are the arms below.
 *
 * <p><b>The red half, runnable against the CURRENT sources</b>
 * ({@link #sessionStateIsAbsentFromTheSnapshotToday}): a snapshot written by this build carries no
 * session record and no queued-order record — not an empty one, none at all. Measured off the
 * record stream rather than argued from the writer's source, so it stays true of whatever the
 * writer becomes. Nothing about a halt can survive a restart on this build, which is exactly the
 * defect the mint closes and exactly what both shell proofs would otherwise have to discover by
 * killing a pod.
 *
 * <p><b>The FALSE arm</b> ({@link #restoreSilentlyAcceptsATruncatedRecordStream}): drop one record
 * TYPE out of an otherwise valid stream and restore accepts it — no throw, terminating cleanly on
 * T_END, with state silently missing. Demonstrated here against {@code T_ORDER}, a record type
 * that exists today; the mint's round trip swaps in {@code T_QUEUED_ORDER}. This is the arm that
 * makes the post-mint round trip discriminating: it proves "the restore returned" is NOT evidence
 * that the queue came back, so the round trip must assert the queue's CONTENT. A queue silently
 * restored empty is a halt that pending orders walk straight through.
 *
 * <p><b>Delivered by the MINT CHIP</b> (the arms after the red half): decision (a)'s default
 * phase; the PRE_OPEN round trip asserting the queue's CONTENT and INSERTION ORDER; the same trip
 * with the queued rows filtered out; both fail-closed rules (a queued ref at or beyond the
 * generator, and out-of-order rows); decision (b); and one arm for each of the two format-8
 * completeness guards. Every one asserts what came BACK, never that the restore returned -- which
 * is precisely what the false arm above bought.
 */
class SessionSnapshotRestoreTest {
    private static final long PX = 1_000_000L;
    private static final int ACCOUNT = 11;
    private static final int ACCOUNT_TAKER = 12;
    private static final int SECURITY = 1;

    /** The format-8 record types (scope §1.4). Literals, never constants: this test exists to
     *  observe that they are ABSENT, and reading a constant that does not exist yet would not
     *  compile — while reading one that does would make the assertion move with the code. */
    private static final int T_SESSION = 14;
    private static final int T_QUEUED_ORDER = 15;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    @Test
    void sessionStateIsAbsentFromTheSnapshotToday() {
        // RED HALF (current build), and the pre-mint half of BOTH durability proofs.
        // Post-mint this arm is skipped; a build where it neither runs nor is replaced by the
        // round trip owed above is a build where nobody checked.
        assumeTrue(MatchingEngineClusteredService.SNAPSHOT_FORMAT == 7,
            "current-sources arm: the mint has landed; the phase/queue round trip covers this now");

        final MatchingEngineClusteredService source = newLiveService();
        final Set<Integer> types = recordTypes(source);

        assertFalse(types.isEmpty(), "a live service must write SOME records, or nothing below means anything");
        assertTrue(types.contains(MatchingEngineClusteredService.T_ORDER),
            "the anti-vacuity guard: this stream must contain the resting orders it was built with,"
                + " or 'no session record' would just mean 'no records were read'. Types seen: " + types);
        assertFalse(types.contains(T_SESSION),
            "a T_SESSION record already exists (type " + T_SESSION + ") — the mint has landed and this"
                + " arm's assumption gate is wrong, not the system");
        assertFalse(types.contains(T_QUEUED_ORDER),
            "a T_QUEUED_ORDER record already exists (type " + T_QUEUED_ORDER + ")");
        // THE MEASUREMENT: the phase and the queue are nowhere in a snapshot on this build, so a
        // member that restores cannot come back halted and cannot come back holding a queue. Both
        // shell proofs would find this by killing a pod; it costs nothing to find here.
    }

    @Test
    void restoreSilentlyAcceptsATruncatedRecordStream() {
        // THE FALSE ARM. Not a defect report — a fail-closed restore would be nice but the record
        // stream is self-describing and knows no expected counts. It is a statement about what the
        // post-mint round trip may rely on: NOTHING. "onSnapshotRecord returned true on T_END" is
        // compatible with the queue having vanished, so the round trip must assert content.
        final MatchingEngineClusteredService source = newLiveService();
        final int liveOrders = source.engine().openOrderTuples().size();
        assertTrue(liveOrders > 0, "the source must hold orders for their omission to be observable");

        final MatchingEngineClusteredService full = restore(records(source, -1));
        assertEquals(liveOrders, full.engine().openOrderTuples().size(),
            "control: the untruncated stream restores every order — without this, the arm below"
                + " could be measuring a broken harness rather than a silent restore");

        final MatchingEngineClusteredService truncated =
            restore(records(source, MatchingEngineClusteredService.T_ORDER));
        assertEquals(0, truncated.engine().openOrderTuples().size(),
            "dropping every T_ORDER record must lose every order — if not, the omission is not"
                + " actually being exercised");
        // ...and it did not throw, and it did terminate on T_END. That is the whole finding.
    }

    // ----- the mint's own arms ----------------------------------------------------------------

    @Test
    void aServiceThatRestoresNothingComesUpOpen() {
        // Decision (a). Every proof and fixture assumes a trading book, so a fresh epoch trades;
        // CLOSED-until-commanded stays available by issuing the command at bring-up.
        final MatchingEngineClusteredService fresh = new MatchingEngineClusteredService();
        fresh.initEngine();
        assertEquals("OPEN", fresh.phaseName());
        assertEquals(0, fresh.queueDepth());
    }

    @Test
    void aHaltedVenueAndItsQueueSurviveTheSnapshotWithTheirCONTENT() {
        // THE ROUND TRIP the two shell durability proofs are actually deciding. A restarted member
        // and a newly elected leader are both "a member that restored and replayed", so this seam
        // is where "a halt a restart can bypass is not a halt" is settled.
        final MatchingEngineClusteredService source = newLiveService();
        setPhase(source, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        final int[] refs = new int[3];
        for (int i = 0; i < refs.length; i++) {
            refs[i] = queueOrder(source, 100L + i, (100 + i) * PX);
        }
        assertEquals(3, source.queueDepth(), "precondition: three orders held");

        final MatchingEngineClusteredService restored = restore(records(source, -1));

        assertEquals("PRE_OPEN", restored.phaseName(), "a member that snapshots halted restores halted");
        assertEquals(3, restored.queueDepth());
        // CONTENT, and in ORDER. A set-equality assertion would miss the defect that matters:
        // insertion order IS the release order at the open, so a queue restored with the same
        // members in a different order is a different opening.
        for (int i = 0; i < refs.length; i++) {
            final long[] row = restored.queuedOrderTuples().get(i);
            assertEquals(refs[i], (int) row[0], "queued row " + i + " is the wrong order");
            assertEquals(ACCOUNT, (int) row[1]);
            assertEquals(SECURITY, (int) row[2]);
            assertEquals(InputEvent.SIDE_BUY, (byte) row[3]);
            assertEquals(10, (int) row[4]);
            assertEquals((100 + i) * PX, row[5], "queued row " + i + " lost its limit price");
            assertEquals(100L + i, row[6], "queued row " + i + " lost its client key");
        }
        // ...and the derived key index is rebuilt FROM the queue, never snapshotted: a retry of a
        // key queued before the restart must still find its original.
        final int retried = queueOrder(restored, 100L, 100 * PX);
        assertEquals(refs[0], retried,
            "the transient clientOrderKey index was rebuilt on restore (the ADR-052/060 pattern)");
        assertEquals(3, restored.queueDepth(), "and the retry queued nothing new");
    }

    @Test
    void aQueueRestoredEMPTYIsCaughtByTheDECLAREDDEPTH() {
        // THE GUARD PRESENCE CANNOT PROVIDE, and the false arm above pointed at the record type
        // that matters. Zero T_QUEUED_ORDER rows is LEGITIMATE whenever the queue is empty, so
        // their absence cannot be caught by asking "did this type appear?". T_SESSION's declared
        // depth -- written from the LIVE queue, never tallied from the write loop's own output --
        // is what makes the difference observable.
        final MatchingEngineClusteredService source = newLiveService();
        setPhase(source, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        queueOrder(source, 200L, 100 * PX);
        queueOrder(source, 201L, 101 * PX);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> restore(records(source, MatchingEngineClusteredService.T_QUEUED_ORDER)),
            "dropping every queued row must be refused, not restored as an empty queue");
        assertTrue(thrown.getMessage().contains("queueDepth"),
            "the refusal must name the count that disagreed, got: " + thrown.getMessage());
    }

    @Test
    void aMissingSessionRecordIsCaughtByThePRESENCEBITMASK() {
        // The other guard: a whole record TYPE absent from an otherwise valid, cleanly terminated
        // stream. Before format 8 that restored in silence with the phase defaulted to OPEN -- a
        // halted venue coming back trading, no throw, nothing in a log to read.
        final MatchingEngineClusteredService source = newLiveService();
        setPhase(source, MatchingEngineClusteredService.PHASE_CLOSED);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> restore(records(source, MatchingEngineClusteredService.T_SESSION)),
            "a stream with no T_SESSION must be refused, not defaulted to OPEN");
        assertTrue(thrown.getMessage().contains("record types absent"),
            "the refusal must name what was missing, got: " + thrown.getMessage());

        // THE CONTROL: the same stream with T_SESSION present restores, and restores CLOSED.
        assertEquals("CLOSED", restore(records(source, -1)).phaseName());
    }

    @Test
    void aQueuedRowAtOrBeyondTheGeneratorIsRefused() {
        // The T_ORDER rule applied to the queue (FR-AC09): a queued order holds a ref the generator
        // already issued, so one at or beyond the restored generator is an order this member could
        // not have accepted. Fed by rewriting a REAL row, so the rest of the stream stays valid and
        // the refusal can only be this rule.
        final MatchingEngineClusteredService source = newLiveService();
        setPhase(source, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        queueOrder(source, 300L, 100 * PX);

        final List<byte[]> mangled = records(source, -1);
        boolean rewritten = false;
        for (final byte[] record : mangled) {
            final UnsafeBuffer view = new UnsafeBuffer(record);
            if (view.getInt(0) == MatchingEngineClusteredService.T_QUEUED_ORDER) {
                view.putLong(4, Integer.MAX_VALUE);
                rewritten = true;
            }
        }
        assertTrue(rewritten, "the stream must contain a queued row for this arm to mean anything");
        final IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> restore(mangled));
        assertTrue(thrown.getMessage().contains("nextOrderRef"),
            "the refusal must name the generator, got: " + thrown.getMessage());
    }

    @Test
    void outOfOrderQueuedRowsAreRefused() {
        // The T_CONTRACT rule applied to the queue: nothing else validates the order of these rows,
        // and the order IS the release order, so a reordered queue is a different opening.
        final MatchingEngineClusteredService source = newLiveService();
        setPhase(source, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        queueOrder(source, 400L, 100 * PX);
        queueOrder(source, 401L, 101 * PX);

        final List<byte[]> original = records(source, -1);
        final List<byte[]> queued = new ArrayList<>();
        for (final byte[] record : original) {
            if (new UnsafeBuffer(record).getInt(0) == MatchingEngineClusteredService.T_QUEUED_ORDER) {
                queued.add(record);
            }
        }
        assertEquals(2, queued.size(), "precondition: two queued rows to swap");
        final List<byte[]> reordered = new ArrayList<>();
        int seen = 0;
        for (final byte[] record : original) {
            if (new UnsafeBuffer(record).getInt(0) == MatchingEngineClusteredService.T_QUEUED_ORDER) {
                reordered.add(queued.get(1 - seen));
                seen++;
                continue;
            }
            reordered.add(record);
        }
        final IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> restore(reordered));
        assertTrue(thrown.getMessage().contains("is not after"),
            "the refusal must name the ordering rule, got: " + thrown.getMessage());
    }

    @Test
    void aCloseOnANonEmptyQueueLeavesNothingToRestore() {
        // Decision (b) at the snapshot seam rather than the ack: after the close there is no queue
        // left for a snapshot to carry, and the restored member agrees.
        final MatchingEngineClusteredService source = newLiveService();
        setPhase(source, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        queueOrder(source, 500L, 100 * PX);
        assertEquals(1, source.queueDepth());
        setPhase(source, MatchingEngineClusteredService.PHASE_CLOSED);
        assertEquals(0, source.queueDepth(), "the close cancelled the queue");

        final MatchingEngineClusteredService restored = restore(records(source, -1));
        assertEquals("CLOSED", restored.phaseName());
        assertEquals(0, restored.queueDepth());
        assertTrue(recordTypes(source).contains(MatchingEngineClusteredService.T_SESSION),
            "T_SESSION is written on every snapshot, empty queue or not");
    }

    // ----- helpers ---------------------------------------------------------------------------

    /** Sequence a phase command through the real ingress path. */
    private void setPhase(final MatchingEngineClusteredService service, final byte phase) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SESSION_CONTROL;
        e.side = phase;
        e.setClientOrderKey(9_000L + phase);
        apply(service, e);
    }

    /** Queue one BUY while PRE_OPEN; returns the ref it already holds. */
    private int queueOrder(final MatchingEngineClusteredService service, final long clientKey,
                           final long limitPx) {
        final InputEvent e = newOrder(InputEvent.SIDE_BUY, limitPx);
        e.setClientOrderKey(clientKey);
        apply(service, e);
        for (final long[] row : service.queuedOrderTuples()) {
            if (row[6] == clientKey) {
                return (int) row[0];
            }
        }
        throw new AssertionError("order with key " + clientKey + " was not queued");
    }

    /** Controls, a tick, and four resting bids at 100.00 — a stream with real content in it. */
    private MatchingEngineClusteredService newLiveService() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        apply(service, accountControl(ACCOUNT));
        apply(service, accountControl(ACCOUNT_TAKER));
        apply(service, securityControl(SECURITY));
        apply(service, priceTick(150 * PX));
        for (int i = 0; i < 4; i++) {
            apply(service, newOrder(InputEvent.SIDE_BUY, 100 * PX));
        }
        return service;
    }

    /** Serialize source, optionally dropping every record of {@code omitType} ({@code -1} = none). */
    private List<byte[]> records(final MatchingEngineClusteredService source, final int omitType) {
        final List<byte[]> out = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            if (buffer.getInt(offset) == omitType) {
                return;
            }
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            out.add(copy);
        });
        return out;
    }

    private Set<Integer> recordTypes(final MatchingEngineClusteredService source) {
        final Set<Integer> types = new LinkedHashSet<>();
        source.writeSnapshot((buffer, offset, length) -> types.add(buffer.getInt(offset)));
        return types;
    }

    private MatchingEngineClusteredService restore(final List<byte[]> records) {
        final MatchingEngineClusteredService target = new MatchingEngineClusteredService();
        target.initEngine();
        boolean done = false;
        for (final byte[] record : records) {
            done = target.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "the record stream must terminate with END");
        return target;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private InputEvent newOrder(final byte side, final long limitPx) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.side = side;
        e.accountId = ACCOUNT;
        e.securityId = SECURITY;
        e.qty = 10;
        e.limitPx = limitPx;
        return e;
    }

    private InputEvent priceTick(final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SECURITY;
        e.priceTicks = px;
        return e;
    }

    private InputEvent accountControl(final int accountId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(true);
        e.setControlVersion(1L);
        return e;
    }

    private InputEvent securityControl(final int securityId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SECURITY_CONTROL;
        e.securityId = securityId;
        e.setControlEnabled(true);
        e.setControlVersion(2L);
        return e;
    }
}
