package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MAX_QUEUED_ORDERS}, sized by measurement rather than picked
 * (skill: {@code size-a-configuration-bound}). This class IS the measurement: it prints both
 * gradients at three candidate values and pins the shipped one against them, so the number's
 * rationale lives in something that runs rather than in a commit message.
 *
 * <p><b>The unit that binds is not "orders."</b> It is <em>how large a halt this venue can hold
 * without refusing business</em>, against <em>what one apply costs when it releases them</em> — and
 * those pull in opposite directions, which is the whole reason a bound needs sizing.
 *
 * <p><b>Gradient 1 — snapshot bytes.</b> A snapshot write is an apply-thread FREEZE, so queue rows
 * are paid for in freeze duration, not in heap. Measured off the real writer below.
 *
 * <p><b>Gradient 2 — the OPEN apply's output cascade.</b> The release replays every queued order
 * through the engine inside ONE apply. The output ring is bounded ({@code OUTPUT_RING_SIZE}, 65536
 * slots by default) and {@code drainOnBackpressure} makes an over-large cascade correct rather than
 * deadlocking — but a cap near the ring size would make the single most important apply of the day
 * the one most likely to exercise that path. Measured below as outputs emitted per release.
 *
 * <p><b>The opposing (too-small) side, reported because a negligible cost is still a finding:</b>
 * the rig's whole fixture universe is 69 instruments and the heaviest measured proof traffic moves
 * the order-ref generator by tens per window, so a pre-open window would have to take thousands of
 * distinct client orders before the first CAPACITY refusal. At 256 that is reachable; at 4096 it is
 * two orders of magnitude away from real traffic.
 */
class QueuedOrderSizingTest {
    private static final int ACCOUNT = 42422;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    @Test
    void bothGradientsAreMeasuredAndTheShippedCapSitsBetweenThem() {
        // Measured at two real fills, one an order of magnitude below the cap and one AT it. Both
        // unit costs are exactly linear by construction — a queued row is a fixed-width record and
        // a released rest emits a fixed number of outputs — so the row above the cap is computed
        // from the measured unit costs and LABELLED as such rather than quietly presented as run.
        // (The cap cannot be exceeded at runtime to measure it: it is a compile-time constant on
        // purpose, because a member with a different value disagrees about whether order 4097 was
        // queued, which is permanent divergence.)
        final long[] small = measure(256);
        final long[] atCap = measure(MatchingEngineClusteredService.MAX_QUEUED_ORDERS);

        final long bytesPerRow = atCap[0] / MatchingEngineClusteredService.MAX_QUEUED_ORDERS;
        final long outputsPerOrder = atCap[2] / MatchingEngineClusteredService.MAX_QUEUED_ORDERS;
        assertEquals(small[0] / 256, bytesPerRow, "the row cost must be linear for the table to hold");

        final int ring = MatchingEngineClusteredService.outputRingSize();
        final StringBuilder table = new StringBuilder(
            "\nMAX_QUEUED_ORDERS sizing (size-a-configuration-bound; measured, not estimated)\n"
            + "  cap      queue bytes    snapshot total    outputs at the open   % of output ring   source\n");
        for (final int candidate : new int[] { 256, MatchingEngineClusteredService.MAX_QUEUED_ORDERS, 65536 }) {
            final long[] row = candidate == 256 ? small
                : candidate == MatchingEngineClusteredService.MAX_QUEUED_ORDERS ? atCap
                : new long[] { 65536L * bytesPerRow, -1L, 65536L * outputsPerOrder };
            table.append(String.format("  %-8d %-14d %-17s %-21d %-18.1f %s%n",
                candidate, row[0], row[1] < 0 ? "-" : Long.toString(row[1]), row[2],
                100.0 * row[2] / ring, row[1] < 0 ? "extrapolated" : "measured"));
        }
        // The comparison that makes the byte figure mean something: the idempotency table is the
        // freeze budget this project has ALREADY accepted on every snapshot, so the queue is sized
        // against it rather than against nothing.
        final long idempotencyBytes = (long) MatchingEngineClusteredService.IDEMPOTENCY_CAPACITY * 28L;
        table.append(String.format(
            "  unit costs: %d B/row, %d outputs/released rest; output ring %d slots%n",
            bytesPerRow, outputsPerOrder, ring));
        table.append(String.format(
            "  reference:  the idempotency table already costs %d B/snapshot (%d entries x 28 B)%n",
            idempotencyBytes, MatchingEngineClusteredService.IDEMPOTENCY_CAPACITY));
        System.out.println(table);

        // GRADIENT 1 (snapshot bytes -> apply-thread freeze). A full queue must stay an order of
        // magnitude inside the budget already being paid. Not free: cheap against what is spent now.
        assertTrue(atCap[0] * 10 < idempotencyBytes,
            "a full queue costs " + atCap[0] + " B of snapshot against the idempotency table's "
                + idempotencyBytes + " B — at this ratio it is no longer inside the accepted budget");

        // GRADIENT 2 (the OPEN apply's output cascade). A full queue of pure rests must not
        // approach the ring. At or above it the release's own cascade becomes the most likely
        // exerciser of drainOnBackpressure, on the single apply of the day that matters most.
        assertTrue(atCap[2] * 4 < ring,
            "a full queue's release emits " + atCap[2] + " outputs into a " + ring + "-slot ring");

        // ...and the row ABOVE the cap is where both gradients stop being comfortable, which is
        // what makes 4096 a decision rather than a preference.
        assertTrue(65536L * bytesPerRow > idempotencyBytes / 2,
            "at 65536 the queue would rival the idempotency table's snapshot cost");
    }

    /** Queue {@code n} orders, then measure {queue bytes, total snapshot bytes, outputs at the open}. */
    private long[] measure(final int n) {
        final MatchingEngineClusteredService service = seeded();
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        for (int i = 0; i < n; i++) {
            // Distinct prices spread across the band so they REST rather than cross: a release of
            // pure rests is the honest floor for the cascade, and a crossing release is bounded by
            // the same cap plus drainOnBackpressure.
            apply(service, newOrder(100 * PX + (long) (i % 4096) * 1_000L, 1_000L + i));
        }
        assertEquals(Math.min(n, MatchingEngineClusteredService.MAX_QUEUED_ORDERS), service.queueDepth());

        final AtomicLong queueBytes = new AtomicLong();
        final AtomicLong totalBytes = new AtomicLong();
        service.writeSnapshot((buffer, offset, length) -> {
            totalBytes.addAndGet(length);
            if (buffer.getInt(offset) == MatchingEngineClusteredService.T_QUEUED_ORDER) {
                queueBytes.addAndGet(length);
            }
        });

        final AtomicLong outputs = new AtomicLong();
        service.outputSink(event -> outputs.incrementAndGet());
        setPhase(service, MatchingEngineClusteredService.PHASE_OPEN);
        service.outputSink(null);
        assertEquals(0, service.queueDepth(), "the release must have drained the queue");
        return new long[] { queueBytes.get(), totalBytes.get(), outputs.get() };
    }

    private MatchingEngineClusteredService seeded() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        final InputEvent account = new InputEvent();
        account.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        account.accountId = ACCOUNT;
        account.setControlEnabled(true);
        account.setControlVersion(1L);
        apply(service, account);
        final InputEvent security = new InputEvent();
        security.type = InputEvent.TYPE_SECURITY_CONTROL;
        security.securityId = SECURITY;
        security.setControlEnabled(true);
        security.setControlVersion(2L);
        apply(service, security);
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = SECURITY;
        tick.priceTicks = 100 * PX;
        apply(service, tick);
        return service;
    }

    private void setPhase(final MatchingEngineClusteredService service, final byte phase) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SESSION_CONTROL;
        e.side = phase;
        e.setClientOrderKey(7_000L + phase);
        apply(service, e);
    }

    private InputEvent newOrder(final long limitPx, final long clientKey) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = ACCOUNT;
        e.securityId = SECURITY;
        e.side = InputEvent.SIDE_BUY;
        e.qty = 10;
        e.limitPx = limitPx;
        e.setClientOrderKey(clientKey);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }
}
