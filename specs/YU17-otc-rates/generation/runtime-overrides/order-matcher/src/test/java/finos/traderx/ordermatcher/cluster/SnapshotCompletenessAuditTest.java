package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format-8 snapshot completeness audit, as a behavioural test
 * (skill: {@code traderx-snapshot-completeness-audit}, sections 5 and 7).
 *
 * <p><b>Why a byte-identity check alone is not the audit.</b> Writing a snapshot, restoring it and
 * re-writing it catches a LOAD gap — state written but not read back. It cannot catch a CAPTURE
 * gap, because a state item missing from the writer is missing from both streams and they compare
 * equal. So this class does both, in order:
 *
 * <ol>
 *   <li><b>Load fidelity</b>: the re-written stream is byte-identical, record for record;</li>
 *   <li><b>Capture fidelity</b>: the SAME follow-on commands are driven into the source and the
 *       restored member, and every future-output generator and admission dependency is compared
 *       afterwards. A state item the writer omits but a later decision READS shows up here as a
 *       divergence, which is the only way that class of gap is observable at all.</li>
 * </ol>
 *
 * <p>The source state is deliberately rich enough that every record type format 8 can emit is in
 * the stream — asserted, so this never degrades into a round trip of an empty machine.
 */
class SnapshotCompletenessAuditTest {
    private static final int ACCOUNT = 42422;
    private static final int ACCOUNT2 = 22214;
    private static final long PX = 1_000_000L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    @Test
    void everyRecordTypeFormatEightCanEmitIsInARichSnapshot() {
        // THE ANTI-VACUITY GUARD for both arms below, and the audit's own "enumerate from mutation
        // surfaces" step made mechanical: if a future change adds a record type and no fixture
        // produces it, this fails rather than the round trips silently narrowing.
        final Set<Integer> types = recordTypes(rich());
        for (final int required : new int[] {
                MatchingEngineClusteredService.T_HEADER,
                MatchingEngineClusteredService.T_POLICY,
                MatchingEngineClusteredService.T_ACCOUNT,
                MatchingEngineClusteredService.T_SECURITY,
                MatchingEngineClusteredService.T_SYMBOL,
                MatchingEngineClusteredService.T_IDEMPOTENCY,
                MatchingEngineClusteredService.T_CONTRACT,
                MatchingEngineClusteredService.T_FX_RATE,
                MatchingEngineClusteredService.T_POSITION,
                MatchingEngineClusteredService.T_PRICE,
                MatchingEngineClusteredService.T_BOOK,
                MatchingEngineClusteredService.T_SESSION,
                MatchingEngineClusteredService.T_QUEUED_ORDER,
                MatchingEngineClusteredService.T_ORDER,
                MatchingEngineClusteredService.T_END }) {
            assertTrue(types.contains(required),
                "record type " + required + " is absent from the rich fixture; types seen: " + types);
        }
        assertEquals(15, types.size(),
            "a record type exists that this audit does not produce — extend the fixture rather than"
                + " this number, or the round trips below stop covering it. Types seen: " + types);
    }

    @Test
    void loadFidelity_theRestoredMemberReWritesAByteIdenticalStream() {
        final MatchingEngineClusteredService source = rich();
        final List<byte[]> first = records(source);
        final MatchingEngineClusteredService restored = restore(first);
        final List<byte[]> second = records(restored);

        assertEquals(first.size(), second.size(), "record COUNT changed across the round trip");
        for (int i = 0; i < first.size(); i++) {
            assertArrayEquals(first.get(i), second.get(i),
                "record " + i + " (type " + new UnsafeBuffer(first.get(i)).getInt(0)
                    + ") differs after restore");
        }
    }

    @Test
    void captureFidelity_theSameCommandsAfterRecoveryReachTheSameState() {
        // The audit's section 7 acceptance: commands before AND after the boundary, recovery, then
        // identical follow-on traffic. Anything the writer omits that a later decision reads
        // diverges here — that is what makes this a CAPTURE check and not another load check.
        final MatchingEngineClusteredService source = rich();
        final MatchingEngineClusteredService restored = restore(records(source));

        for (final MatchingEngineClusteredService service : new MatchingEngineClusteredService[] { source, restored }) {
            final long ts = timestamp;
            timestamp = ts;   // identical event-carried time on both, so nothing diverges on a clock
            followOn(service);
            timestamp = ts;
        }

        assertEquals(describe(source), describe(restored),
            "a divergence here names a state item the snapshot does not carry");
        // ...and the digests agree, which is the assertion the live proofs make across members.
        assertEquals(source.engine().recoveryDigest(), restored.engine().recoveryDigest());
    }

    @Test
    void aHaltedQueueReleasesIDENTICALLYAfterARecovery() {
        // yu17-halt-survives-failover's claim at the seam where it is decided: a new leader IS a
        // member that restored and replayed. Snapshot mid-halt, restore, then OPEN on BOTH — the
        // release must produce the same trades, the same refs and the same book.
        final MatchingEngineClusteredService source = rich();
        assertEquals("PRE_OPEN", source.phaseName(), "the fixture must be snapshotted mid-halt");
        assertTrue(source.queueDepth() > 0, "...with a non-empty queue, or this proves nothing");

        final MatchingEngineClusteredService restored = restore(records(source));
        assertEquals(source.queueDepth(), restored.queueDepth());

        final long ts = timestamp;
        setPhase(source, MatchingEngineClusteredService.PHASE_OPEN);
        timestamp = ts;
        setPhase(restored, MatchingEngineClusteredService.PHASE_OPEN);

        assertEquals(0, source.queueDepth());
        assertEquals(0, restored.queueDepth());
        assertEquals(source.engine().tradeCounter(), restored.engine().tradeCounter(),
            "the release booked different trades on the two members");
        assertEquals(source.nextOrderRef(), restored.nextOrderRef(),
            "the release issued different refs — it must issue NONE");
        assertEquals(describe(source), describe(restored));
        assertEquals(source.engine().recoveryDigest(), restored.engine().recoveryDigest());
    }

    // ----- fixtures ---------------------------------------------------------------------------

    /**
     * A service holding every kind of state format 8 can carry: two accounts, three securities on
     * three DIFFERENT derived grids (a bond on the category grid, a penny name on the map's fine
     * grid, an equity on the global grid), prices, a booked trade and its positions, idempotency
     * entries, an OTC contract, an FX rate, resting and terminal orders — and, at the end, a halt
     * with a non-empty queue.
     */
    private MatchingEngineClusteredService rich() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        register(service, "UST-20280630");     // id 0: category grid, tick 1
        register(service, "FNMA");             // id 1: map grid at ~$1.12, tick 10
        register(service, "IBM");              // id 2: global grid at ~$150, tick 1000
        for (final int account : new int[] { ACCOUNT, ACCOUNT2 }) {
            final InputEvent e = new InputEvent();
            e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            e.accountId = account;
            e.setControlEnabled(true);
            e.setControlVersion(1L);
            apply(service, e);
        }
        for (int sec = 0; sec < 3; sec++) {
            final InputEvent e = new InputEvent();
            e.type = InputEvent.TYPE_SECURITY_CONTROL;
            e.securityId = sec;
            e.setControlEnabled(true);
            e.setControlVersion(2L);
            apply(service, e);
        }
        final InputEvent policy = new InputEvent();
        policy.type = InputEvent.TYPE_POLICY_CONTROL;
        policy.qty = 1_000_000;
        policy.limitPx = Long.MAX_VALUE / 8;
        policy.setControlVersion(3L);
        apply(service, policy);

        tick(service, 0, 990_000L);            // a bond below par
        tick(service, 1, 1_120_000L);          // FNMA
        tick(service, 2, 150 * PX);            // IBM

        final InputEvent fx = new InputEvent();
        fx.type = InputEvent.TYPE_FX_RATE;
        fx.securityId = SwapConventions.currencyIndexOf("EUR");
        fx.limitPx = 1_084_200L;
        apply(service, fx);

        final InputEvent swap = new InputEvent();
        swap.type = InputEvent.TYPE_SWAP_BOOK;
        swap.accountId = ACCOUNT;
        swap.side = InputEvent.SWAP_RECEIVE_FIXED;
        swap.qty = 1_000_000;
        swap.limitPx = 42_000L;
        swap.securityId = SwapConventions.indexOf("USD-SOFR-1Y-ACT360");
        swap.setSwapDates(20_000, 21_000);
        swap.setClientOrderKey(0L);
        apply(service, swap);

        // A crossed pair on IBM: trade counter, positions, terminal (filled) order rows.
        apply(service, order(ACCOUNT, 2, InputEvent.SIDE_BUY, 150 * PX, 10, 5_001L));
        apply(service, order(ACCOUNT2, 2, InputEvent.SIDE_SELL, 150 * PX, 10, 5_002L));
        // Resting orders on all three grids, so T_BOOK carries three different ticks.
        apply(service, order(ACCOUNT, 0, InputEvent.SIDE_BUY, 989_999L, 10, 5_003L));
        apply(service, order(ACCOUNT, 1, InputEvent.SIDE_BUY, 1_119_990L, 10, 5_004L));
        apply(service, order(ACCOUNT, 2, InputEvent.SIDE_BUY, 149 * PX, 10, 5_005L));

        // ...and a halt holding a queue.
        setPhase(service, MatchingEngineClusteredService.PHASE_PRE_OPEN);
        apply(service, order(ACCOUNT2, 2, InputEvent.SIDE_SELL, 149 * PX, 10, 6_001L));
        apply(service, order(ACCOUNT, 1, InputEvent.SIDE_BUY, 1_119_980L, 10, 6_002L));
        return service;
    }

    /** Traffic AFTER the boundary, driven identically into both members. Every kind of decision
     *  that reads snapshotted state: a cancel, a re-price, a tick, a booking, and an open. */
    private void followOn(final MatchingEngineClusteredService service) {
        setPhase(service, MatchingEngineClusteredService.PHASE_OPEN);
        tick(service, 1, 1_300_000L);
        apply(service, order(ACCOUNT2, 1, InputEvent.SIDE_SELL, 1_119_990L, 5, 7_001L));
        apply(service, order(ACCOUNT, 0, InputEvent.SIDE_BUY, 989_999L, 10, 5_003L)); // idempotent retry
        final InputEvent swap = new InputEvent();
        swap.type = InputEvent.TYPE_SWAP_BOOK;
        swap.accountId = ACCOUNT2;
        swap.side = InputEvent.SWAP_PAY_FIXED;
        swap.qty = 500_000;
        swap.limitPx = 41_000L;
        swap.securityId = SwapConventions.indexOf("USD-SOFR-1Y-ACT360");
        swap.setSwapDates(20_100, 21_100);
        swap.setClientOrderKey(0L);
        apply(service, swap);
        setPhase(service, MatchingEngineClusteredService.PHASE_CLOSED);
    }

    /** Every future-output generator and admission dependency this build holds, as one string. */
    private static String describe(final MatchingEngineClusteredService s) {
        final StringBuilder sb = new StringBuilder();
        sb.append("phase=").append(s.phaseName())
          .append(" queueDepth=").append(s.queueDepth())
          .append(" nextOrderRef=").append(s.nextOrderRef())
          .append(" applied=").append(s.appliedSeq())
          .append(" trades=").append(s.engine().tradeCounter())
          .append(" contracts=").append(s.contractCount())
          .append(" symbols=").append(s.symbolCount())
          .append(" digest=").append(s.engine().recoveryDigest());
        for (final long[] row : s.queuedOrderTuples()) {
            sb.append(" q").append(java.util.Arrays.toString(row));
        }
        for (final long[] book : s.engine().bookBaseTuples()) {
            sb.append(" b").append(java.util.Arrays.toString(book));
        }
        for (final long[] position : s.engine().positionTuples()) {
            sb.append(" p").append(java.util.Arrays.toString(position));
        }
        for (final long[] price : s.engine().priceTuples()) {
            sb.append(" x").append(java.util.Arrays.toString(price));
        }
        for (final long[] contract : s.contractTuples()) {
            sb.append(" c").append(java.util.Arrays.toString(contract));
        }
        for (int sec = 0; sec < s.symbolCount(); sec++) {
            sb.append(" t").append(sec).append('=').append(s.engine().bookTickPxOf(sec));
        }
        return sb.toString();
    }

    // ----- harness ----------------------------------------------------------------------------

    private void register(final MatchingEngineClusteredService service, final String ticker) {
        codec.encodeSymbolRegister(symbolBuffer, 0, ++timestamp, ticker);
        service.onSessionMessage(null, ++timestamp, symbolBuffer, 0,
            AeronReplicationCodec.SYMBOL_BYTES, null);
    }

    private void setPhase(final MatchingEngineClusteredService service, final byte phase) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SESSION_CONTROL;
        e.side = phase;
        e.setClientOrderKey(8_000L + phase);
        apply(service, e);
    }

    private void tick(final MatchingEngineClusteredService service, final int securityId, final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = securityId;
        e.priceTicks = px;
        apply(service, e);
    }

    private InputEvent order(final int account, final int securityId, final byte side,
                             final long limitPx, final int qty, final long key) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = account;
        e.securityId = securityId;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        e.setClientOrderKey(key);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private List<byte[]> records(final MatchingEngineClusteredService service) {
        final List<byte[]> out = new ArrayList<>();
        service.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            out.add(copy);
        });
        return out;
    }

    private Set<Integer> recordTypes(final MatchingEngineClusteredService service) {
        final Set<Integer> types = new LinkedHashSet<>();
        service.writeSnapshot((buffer, offset, length) -> types.add(buffer.getInt(offset)));
        return types;
    }

    private MatchingEngineClusteredService restore(final List<byte[]> stream) {
        final MatchingEngineClusteredService target = new MatchingEngineClusteredService();
        target.initEngine();
        boolean done = false;
        for (final byte[] record : stream) {
            done = target.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "the record stream must terminate with END");
        return target;
    }
}
