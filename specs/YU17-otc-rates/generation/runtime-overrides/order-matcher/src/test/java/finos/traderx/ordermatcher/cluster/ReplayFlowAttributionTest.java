package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU17 (ADR-072): replayed tape flow is attributable and excludable at the source.
 *
 * <p>WHY THIS TEST EXISTS, AND WHY OFF-RIG. `scripts/proofs/lib-consensus-readings.sh` sets one
 * admission test for any new consensus reading: <i>name a counter the new writer does not advance,
 * and show it standing still on a live rig while that writer runs.</i> The rig half is
 * `scripts/proofs/yu17-replay-attribution.sh`. This is the half a rig cannot decide:
 *
 * <ul>
 *   <li>ANTI-VACUITY. On a rig, "the operator counter did not move while the replay ran" is also
 *       satisfied by a counter wired to a constant. Here both halves are driven in one test, so a
 *       counter that never moves fails as loudly as one that moves too much.</li>
 *   <li>THE RESTORE. Format 9 exists because these counters must survive a snapshot; a member that
 *       restored a zero would report an operator count inflated by the epoch's whole replayed
 *       flow. Exercising that in situ means restarting a member and inspecting a subtraction —
 *       the divergence class, where a wrong answer is indistinguishable from lag. Off-rig the
 *       round trip is a function call.</li>
 *   <li>THE REJECTED ORDER. A refused order consumes a ref BEFORE any verdict, which is the exact
 *       property `assert_no_orders_sequenced` is built on. It has to hold for replayed flow too or
 *       the two halves stop summing to the global.</li>
 * </ul>
 */
class ReplayFlowAttributionTest {
    private static final long PX = 1_000_000L;
    private static final int OPERATOR = 11;
    private static final int OPERATOR_TAKER = 12;
    private static final int REPLAY = InputEvent.REPLAY_ACCOUNT_BASE + 1;
    private static final int REPLAY_TAKER = InputEvent.REPLAY_ACCOUNT_BASE + 2;
    private static final int SECURITY = 1;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingress = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    // ----- the admission test, both halves ---------------------------------------------------

    @Test
    void replayedOrdersAdvanceTheGlobalGeneratorAndNotTheOperatorOne() {
        final MatchingEngineClusteredService s = live();
        final long globalBefore = s.nextOrderRef();
        final long operatorBefore = s.operatorOrderRefs();

        for (int i = 0; i < 5; i++) {
            apply(s, order(REPLAY, InputEvent.SIDE_BUY, 100 * PX, 10));
        }

        assertEquals(5, s.nextOrderRef() - globalBefore,
            "five order-shaped commands were sequenced, so the GLOBAL generator moved by five");
        assertEquals(0, s.operatorOrderRefs() - operatorBefore,
            "…and none of them were the operator's. This is the reading lib-consensus-readings.sh"
                + " brackets its own work with; if it moves here, every proof that uses it is"
                + " measuring the tape replay instead of its own orders.");
        assertEquals(5, s.externalOrderRefs(), "the halves must sum to the global");
    }

    @Test
    void anOperatorOrderStillMovesTheOperatorCounterWhileReplayFlowIsRunning() {
        // ANTI-VACUITY for the test above, and it is not theoretical: `assertEquals(0, delta)` is
        // satisfied by a counter that is wired to a constant, and that counter would silently make
        // every proof's ref bracket unfalsifiable rather than merely wrong.
        final MatchingEngineClusteredService s = live();
        final long operatorBefore = s.operatorOrderRefs();

        apply(s, order(REPLAY, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, order(OPERATOR, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, order(REPLAY, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, order(OPERATOR, InputEvent.SIDE_BUY, 100 * PX, 10));

        assertEquals(2, s.operatorOrderRefs() - operatorBefore,
            "exactly the two operator orders, interleaved with replayed flow throughout");
    }

    @Test
    void aRejectedReplayedOrderStillConsumesAReplayedRef() {
        // Refs are issued on apply, BEFORE any verdict — the property assert_no_orders_sequenced
        // is built on. A disabled account's order is refused and still consumes one, and it must
        // be counted on the SAME side as the accepted ones or the halves stop summing.
        final MatchingEngineClusteredService s = live();
        apply(s, accountControl(REPLAY, false));
        final long globalBefore = s.nextOrderRef();
        final long externalBefore = s.externalOrderRefs();
        final long operatorBefore = s.operatorOrderRefs();

        apply(s, order(REPLAY, InputEvent.SIDE_BUY, 100 * PX, 10));

        assertEquals(1, s.nextOrderRef() - globalBefore, "a refused order is still sequenced");
        assertEquals(1, s.externalOrderRefs() - externalBefore, "…and it was still replayed flow");
        assertEquals(0, s.operatorOrderRefs() - operatorBefore, "…and still not the operator's");
    }

    // ----- trades -----------------------------------------------------------------------------

    @Test
    void aReplayedCrossIsExcludedFromTheOperatorTradeCount() {
        final MatchingEngineClusteredService s = live();
        final long tradesBefore = s.engine().tradeCounter();
        final long operatorBefore = operatorTrades(s);

        apply(s, order(REPLAY, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, order(REPLAY_TAKER, InputEvent.SIDE_SELL, 100 * PX, 10));

        assertEquals(2, s.engine().tradeCounter() - tradesBefore,
            "one match, two legs — the convention traderx_cluster_trades counts on");
        assertEquals(0, operatorTrades(s) - operatorBefore,
            "both legs were replayed flow, so assert_order_effects' trade delta must not see them");
    }

    @Test
    void anOperatorLegOfAMixedCrossIsStillTheOperatorS() {
        // The honest half of the split: when replayed flow hits an operator's resting order, ONE
        // leg is the operator's and it is counted. Attributing both to the replay would tell a
        // proof its order never traded when it did.
        final MatchingEngineClusteredService s = live();
        final long operatorBefore = operatorTrades(s);

        apply(s, order(OPERATOR, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, order(REPLAY, InputEvent.SIDE_SELL, 100 * PX, 10));

        assertEquals(2, s.engine().tradeCounter() - tradeCounterAt(s, 2), "one match, two legs");
        assertEquals(1, operatorTrades(s) - operatorBefore,
            "exactly the operator's own leg — not both, and not neither");
    }

    // ----- the band shadows --------------------------------------------------------------------

    @Test
    void bandMovementCausedByReplayedFlowIsExcludedFromTheOperatorBandCounters() {
        final MatchingEngineClusteredService s = live();
        // Rest an operator order inside the band anchored at the 150 reference, then move the
        // reference far enough that a replayed order at the new level cannot fit the old band.
        apply(s, order(OPERATOR, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, priceTick(500 * PX));
        final long reanchorsBefore = s.engine().bandReanchors();
        final long operatorReanchorsBefore = operatorReanchors(s);
        final long strandedBefore = s.engine().bandStrandedCancels();
        final long operatorStrandedBefore = operatorStranded(s);

        apply(s, order(REPLAY, InputEvent.SIDE_BUY, 500 * PX, 10));

        assertEquals(1, s.engine().bandReanchors() - reanchorsBefore,
            "the replayed order was outside the standing band and inside a band centred on the"
                + " reference, so ADR-066 re-anchored — if this is 0 the scenario did not fire and"
                + " the assertion below proves nothing");
        assertEquals(0, operatorReanchors(s) - operatorReanchorsBefore,
            "…and yu17-band-follows-market's EXACT delta must not count it");
        assertTrue(s.engine().bandStrandedCancels() - strandedBefore >= 1,
            "the operator's 100.00 bid could not survive a band centred on 500.00");
        assertEquals(0, operatorStranded(s) - operatorStrandedBefore,
            "the strand was CAUSED by replayed flow, so it is not the operator scenario's movement");
    }

    // ----- the restore, which is why format 9 exists ---------------------------------------------

    @Test
    void bothReplayedHalvesSurviveASnapshotRestore() {
        final MatchingEngineClusteredService s = live();
        apply(s, order(REPLAY, InputEvent.SIDE_BUY, 100 * PX, 10));
        apply(s, order(REPLAY_TAKER, InputEvent.SIDE_SELL, 100 * PX, 10));
        apply(s, order(OPERATOR, InputEvent.SIDE_BUY, 90 * PX, 10));

        final MatchingEngineClusteredService restored = restore(s);

        assertEquals(s.externalOrderRefs(), restored.externalOrderRefs(),
            "a member that restored zero here would report an operator ref count inflated by the"
                + " whole epoch's replayed flow, and the three-member quiesce would never agree");
        assertEquals(s.operatorOrderRefs(), restored.operatorOrderRefs());
        assertEquals(s.engine().externalTradeLegs(), restored.engine().externalTradeLegs());
        assertEquals(s.engine().tradeCounter() - s.engine().externalTradeLegs(),
            restored.engine().tradeCounter() - restored.engine().externalTradeLegs(),
            "the operator trade count is a subtraction; both terms have to survive or it lies");
        assertTrue(s.externalOrderRefs() > 0 && s.engine().externalTradeLegs() > 0,
            "anti-vacuity: 0 == 0 would pass every assertion above on a build that stores nothing");
    }

    @Test
    void aHeaderClaimingMoreReplayedRefsThanWereIssuedIsRefused() {
        // Fail closed. The operator counter is a subtraction, so this corruption does not produce
        // a missing reading — it produces a NEGATIVE one, which reads as "no order of mine was
        // sequenced" and would let a proof pass having measured a corrupt snapshot.
        final MatchingEngineClusteredService target = new MatchingEngineClusteredService();
        target.initEngine();
        final UnsafeBuffer header = new UnsafeBuffer(new byte[68]);
        header.putInt(0, MatchingEngineClusteredService.T_HEADER);
        header.putInt(4, MatchingEngineClusteredService.SNAPSHOT_FORMAT);
        header.putLong(8, 10L);    // nextOrderRef: 9 refs issued
        header.putLong(52, 9L);    // externalOrderRefs: exactly the 9, still legal
        target.onSnapshotRecord(header, 0);

        final MatchingEngineClusteredService overflowed = new MatchingEngineClusteredService();
        overflowed.initEngine();
        header.putLong(52, 10L);   // one more than were ever issued
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> overflowed.onSnapshotRecord(header, 0));
        assertTrue(thrown.getMessage().contains("externalOrderRefs"),
            "the refusal must name the field, got: " + thrown.getMessage());
    }

    @Test
    void theAccountRangeIsTheTagAndItIsDisjointFromTheSeededFixtures() {
        // The seeded proof accounts (scripts/yu15/seed-proof-fixtures.sh) are all five digits. If
        // REPLAY_ACCOUNT_BASE ever dropped to five, an operator's own orders would be attributed
        // to the tape and every proof's bracket would silently under-count.
        for (final int seeded : new int[] { 22214, 52355, 42422, 62654, 11413, 10031, 44044 }) {
            assertTrue(!InputEvent.isReplayFlow(seeded),
                "seeded fixture account " + seeded + " must not read as replayed flow");
        }
        assertTrue(InputEvent.isReplayFlow(InputEvent.REPLAY_ACCOUNT_BASE));
        assertTrue(!InputEvent.isReplayFlow(InputEvent.REPLAY_ACCOUNT_BASE - 1));
    }

    // ----- helpers ---------------------------------------------------------------------------

    private long operatorTrades(final MatchingEngineClusteredService s) {
        return s.engine().tradeCounter() - s.engine().externalTradeLegs();
    }

    private long operatorReanchors(final MatchingEngineClusteredService s) {
        return s.engine().bandReanchors() - s.engine().externalBandReanchors();
    }

    private long operatorStranded(final MatchingEngineClusteredService s) {
        return s.engine().bandStrandedCancels() - s.engine().externalBandStrandedCancels();
    }

    /** The trade counter as it was `back` legs ago — the mixed-cross arm needs the value before
     *  its own two orders, and reading it after the resting one has already been applied is the
     *  reading-taken-too-late version of the same mistake. */
    private long tradeCounterAt(final MatchingEngineClusteredService s, final int back) {
        return s.engine().tradeCounter() - back;
    }

    private MatchingEngineClusteredService live() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        apply(s, accountControl(OPERATOR, true));
        apply(s, accountControl(OPERATOR_TAKER, true));
        apply(s, accountControl(REPLAY, true));
        apply(s, accountControl(REPLAY_TAKER, true));
        apply(s, securityControl(SECURITY, true));
        apply(s, priceTick(150 * PX));
        return s;
    }

    private MatchingEngineClusteredService restore(final MatchingEngineClusteredService source) {
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        final MatchingEngineClusteredService restored = new MatchingEngineClusteredService();
        restored.initEngine();
        boolean done = false;
        for (final byte[] record : records) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "snapshot record stream must terminate with END");
        return restored;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingress, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingress, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private InputEvent order(final int accountId, final byte side, final long limitPx, final int qty) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.side = side;
        e.accountId = accountId;
        e.securityId = SECURITY;
        e.qty = qty;
        e.limitPx = limitPx;
        e.priceTicks = 0L;
        return e;
    }

    private InputEvent accountControl(final int accountId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(enabled);
        e.setControlVersion(++timestamp);
        return e;
    }

    private InputEvent securityControl(final int securityId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SECURITY_CONTROL;
        e.securityId = securityId;
        e.setControlEnabled(enabled);
        e.setControlVersion(++timestamp);
        return e;
    }

    private InputEvent priceTick(final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SECURITY;
        e.priceTicks = px;
        return e;
    }
}
