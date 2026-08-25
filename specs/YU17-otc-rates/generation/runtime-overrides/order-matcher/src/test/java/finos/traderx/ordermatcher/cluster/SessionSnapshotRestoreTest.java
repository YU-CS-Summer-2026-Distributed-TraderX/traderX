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
 * <p><b>Owed by the MINT CHIP, not writable against these sources</b> (they need
 * {@code T_SESSION = 14}, {@code T_QUEUED_ORDER = 15}, a {@code phase()} accessor and a
 * {@code queueDepth()}, none of which compile today), recorded here so the obligation is versioned
 * with its red half:
 * <ul>
 *   <li>decision (a): a service that restores a snapshot with no {@code T_SESSION} record — and a
 *       fresh one that restores nothing — both come up {@code OPEN};</li>
 *   <li>round trip: PRE_OPEN with N queued orders through {@code writeSnapshot} →
 *       {@code onSnapshotRecord}, phase preserved AND the queue restored with its INSERTION ORDER
 *       intact (§1.4: the order is the release order, so a set-equality assertion would miss the
 *       defect that matters);</li>
 *   <li>the same round trip with the {@code T_QUEUED_ORDER} rows filtered out — the FALSE arm
 *       below, pointed at the real record type — which must leave the restored queue provably
 *       empty, so the content assertion above is shown to discriminate;</li>
 *   <li>fail-closed: a queued row whose {@code orderRef >= nextOrderRef} refused (the T_ORDER
 *       rule), and out-of-order queued rows refused (the T_CONTRACT rule);</li>
 *   <li>decision (b): CLOSED applied to a service holding a non-empty queue leaves the queue empty
 *       and emits one CANCELED per entry.</li>
 * </ul>
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

    // ----- helpers ---------------------------------------------------------------------------

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
