package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authoritative BLP risk pipeline: ordered rejection precedence (FR-IMRG12), reserve/consume/
 * release exactly-once and never negative (FR-IMRG13/16), idempotent retries (FR-IMRG14), and
 * snapshot-tuple restore parity (FR-IMRG21/22).
 */
class BlpRiskStateTest {
    private static final int ACCT = 22214;
    private static final int SEC = 3;
    private static final long PX = 100_000_000L;   // 100.000 in ticks
    private static final long CREDIT = 1_000_000_000_000L;

    private BlpRiskState risk;
    private TestReservation order;

    private static final class TestReservation implements ReservationHolder {
        long notional;
        int qty;

        @Override
        public long reservedNotional() {
            return notional;
        }

        @Override
        public int reservedQty() {
            return qty;
        }

        @Override
        public void setReservation(long notional, int qty) {
            this.notional = notional;
            this.qty = qty;
        }
    }

    @BeforeEach
    void setUp() {
        risk = new BlpRiskState(64, 16, 1024, 128, CREDIT, 10_000, CREDIT, 30_000L, new RiskMetrics());
        risk.putAccount(ACCT, true);
        risk.putSecurity(SEC, true);
        risk.putLimits(50_000, CREDIT);
        order = new TestReservation();
    }

    private RiskReason decide(long key, int qty, long px) {
        return risk.decideAndReserve(key, 0L, 7, ACCT, SEC, (byte) 0, 0, qty, px, 1_000L, order);
    }

    @Test
    void acceptReservesExactExposure() {
        assertEquals(RiskReason.ACCEPTED, decide(11L, 100, PX));
        assertEquals(100L * PX, risk.reservedNotional(ACCT));
        assertEquals(100L * PX, order.reservedNotional());
        assertEquals(100, order.reservedQty());
    }

    @Test
    void rejectionPrecedenceIsStable() {
        risk.putPolicy(2L, true);
        assertEquals(RiskReason.KILL_SWITCH, decide(1L, 100, PX));
        risk.putPolicy(3L, false);
        assertEquals(RiskReason.UNKNOWN_ACCOUNT,
            risk.decideAndReserve(2L, 0L, 7, 999, SEC, (byte) 0, 0, 100, PX, 1_000L, order));
        risk.putAccount(ACCT, false);
        assertEquals(RiskReason.ACCOUNT_DISABLED, decide(3L, 100, PX));
        risk.putAccount(ACCT, true);
        assertEquals(RiskReason.UNKNOWN_SECURITY,
            risk.decideAndReserve(4L, 0L, 7, ACCT, 15, (byte) 0, 0, 100, PX, 1_000L, order));
        risk.putRestriction(SEC, true);
        assertEquals(RiskReason.RESTRICTED, decide(5L, 100, PX));
        risk.putRestriction(SEC, false);
        assertEquals(RiskReason.ORDER_SIZE, decide(6L, 20_000, PX));
        assertEquals(RiskReason.PRICE_MISSING, decide(7L, 100, 0L));
        assertEquals(0L, risk.reservedNotional(ACCT));   // nothing above reserved anything
    }

    @Test
    void staleSequencedPriceRejects() {
        risk.onPrice(SEC, PX, 1_000L);
        assertEquals(RiskReason.PRICE_STALE, risk.decideAndReserve(8L, 0L, 7, ACCT, SEC, (byte) 0,
            0, 100, PX, 1_000L + 31_000L, order));
        assertEquals(RiskReason.ACCEPTED, risk.decideAndReserve(9L, 0L, 7, ACCT, SEC, (byte) 0,
            0, 100, PX, 1_000L + 10_000L, order));
    }

    @Test
    void creditLimitCountsReservedPlusExecuted() {
        // credit = 1e12; each 90-lot at 100.000 = 9e9; 111 fit, the 112th trips the limit.
        for (int i = 0; i < 111; i++) {
            TestReservation r = new TestReservation();
            assertEquals(RiskReason.ACCEPTED, risk.decideAndReserve(100L + i, 0L, 7, ACCT, SEC,
                (byte) 0, 0, 90, PX, 1_000L, r));
        }
        assertEquals(RiskReason.CREDIT_LIMIT, decide(999L, 90, PX));
    }

    @Test
    void positionLimitProjectsReservedExposure() {
        risk.putLimits(150, CREDIT);
        assertEquals(RiskReason.ACCEPTED, decide(21L, 100, PX));
        // projected = reservedBuy(100) + 100 > 150
        assertEquals(RiskReason.POSITION_LIMIT, decide(22L, 100, PX));
    }

    @Test
    void consumeConvertsAndReleaseFreesExactlyOnce() {
        assertEquals(RiskReason.ACCEPTED, decide(31L, 100, PX));
        risk.consume(ACCT, SEC, (byte) 0, order, 40, PX);
        assertEquals(60L * PX, risk.reservedNotional(ACCT));
        assertEquals(40L * PX, risk.executedNotional(ACCT));
        assertEquals(60, order.reservedQty());
        risk.release(ACCT, SEC, (byte) 0, order);
        assertEquals(0L, risk.reservedNotional(ACCT));
        assertEquals(0, order.reservedQty());
        // double release must not go negative
        risk.release(ACCT, SEC, (byte) 0, order);
        assertEquals(0L, risk.reservedNotional(ACCT));
    }

    @Test
    void duplicateKeyReturnsOriginalDecisionWithoutSecondReservation() {
        assertEquals(RiskReason.ACCEPTED, decide(41L, 100, PX));
        long reserved = risk.reservedNotional(ACCT);
        TestReservation second = new TestReservation();
        assertEquals(RiskReason.ACCEPTED, risk.decideAndReserve(41L, 0L, 8, ACCT, SEC, (byte) 0,
            0, 100, PX, 1_000L, second));
        assertTrue(risk.duplicateReplay());
        assertEquals(reserved, risk.reservedNotional(ACCT));
        assertEquals(0, second.reservedQty());
        assertEquals(7, risk.existingOrderRef(41L));
    }

    @Test
    void keylessCommandsAreDecidedButNotReplayMapped() {
        assertEquals(RiskReason.ACCEPTED, decide(0L, 100, PX));
        TestReservation second = new TestReservation();
        assertEquals(RiskReason.ACCEPTED, risk.decideAndReserve(0L, 0L, 8, ACCT, SEC, (byte) 0,
            0, 100, PX, 1_000L, second));
        assertEquals(200L * PX, risk.reservedNotional(ACCT));   // both reserved: no dedupe on key 0
    }

    @Test
    void marketTradeExecutesWithinCredit() {
        assertEquals(RiskReason.ACCEPTED, risk.decideMarketTrade(51L, 0L, ACCT, SEC, (byte) 0,
            0, 100, PX, 1_000L));
        assertEquals(100L * PX, risk.executedNotional(ACCT));
        assertEquals(RiskReason.PRICE_MISSING, risk.decideMarketTrade(52L, 0L, ACCT, SEC, (byte) 0,
            0, 100, 0L, 1_000L));
    }

    @Test
    void snapshotTuplesRestoreIdenticalDecisionState() {
        risk.putPolicy(9L, false);
        risk.onPrice(SEC, PX, 500L);
        assertEquals(RiskReason.ACCEPTED, decide(61L, 100, PX));
        assertEquals(RiskReason.ACCEPTED, risk.decideMarketTrade(62L, 0L, ACCT, SEC, (byte) 0,
            0, 50, PX, 1_000L));

        BlpRiskState restored = new BlpRiskState(64, 16, 1024, 128, CREDIT, 10_000, CREDIT,
            30_000L, new RiskMetrics());
        restored.bootstrapPolicy(risk.policyTuple());
        for (long[] a : risk.accountTuples()) {
            restored.bootstrapAccount((int) a[0], a[1] != 0, a[2]);
        }
        for (long[] s : risk.securityTuples()) {
            restored.bootstrapSecurity((int) s[0], s[1] != 0, s[2] != 0, s[3], s[4]);
        }
        for (long[] k : risk.idempotencyTuples()) {
            restored.bootstrapIdempotency(k[0], (int) k[1], (byte) k[2]);
        }
        // the open order's reservation is restored from the order row, not the aggregates
        restored.reaccumulateReservation(ACCT, SEC, (byte) 0, order.reservedNotional(), order.reservedQty());

        assertEquals(risk.policyVersion(), restored.policyVersion());
        assertEquals(risk.reservedNotional(ACCT), restored.reservedNotional(ACCT));
        assertEquals(risk.executedNotional(ACCT), restored.executedNotional(ACCT));
        // a retry of the pre-snapshot key replays the original decision on the restored state
        TestReservation retry = new TestReservation();
        assertEquals(RiskReason.ACCEPTED, restored.decideAndReserve(61L, 0L, 9, ACCT, SEC, (byte) 0,
            0, 100, PX, 1_000L, retry));
        assertTrue(restored.duplicateReplay());
        assertEquals(0, retry.reservedQty());
        List<long[]> originalIdempotency = risk.idempotencyTuples();
        assertEquals(originalIdempotency.size(), restored.idempotencyTuples().size());
    }

    // ----- why a live order's reservation cannot be released without cancelling it -------------
    // issues/resolved/orphaned-children-hold-risk-capacity-nobody-releases.md asks whether the
    // capacity an ORPHANED child holds can be handed back while the child keeps resting — the one
    // option that respects the 2026-08-21 decision not to auto-cancel orphans. It cannot, and these
    // two tests are the measurement that closed it, kept because the throwaway probe that first took
    // it is gone and a decision resting on a number nobody can re-run is not a record. They pin the
    // CURRENT coupling, which is correct: a reservation and its live order are one thing. If either
    // stops holding, that issue's reasoning has expired and needs re-deciding, not re-deriving.

    @Test
    void aReservationReleasedWhileItsOrderIsStillLiveLosesThatOrdersFillEntirely() {
        assertEquals(RiskReason.ACCEPTED, decide(71L, 100, PX));
        assertEquals(100L * PX, risk.reservedNotional(ACCT));

        // The tempting move: hand the capacity back, leave the order resting in the book.
        risk.release(ACCT, SEC, (byte) 0, order);
        assertEquals(0L, risk.reservedNotional(ACCT));

        // ...and now that still-live order fills, for real, all 100 of it.
        risk.consume(ACCT, SEC, (byte) 0, order, 100, PX);

        // Nothing is booked. consume() opens `if (reservedQty <= 0) return;` — its exactly-once
        // guard — and release() already zeroed the holder. The credit gate reads
        // executedNotional + reservedNotional, so releasing a live order's reservation does not
        // trade a leak for nothing: it trades a bounded, conservative over-hold for an unbounded
        // UNDER-count of exposure the account really took on.
        assertEquals(0L, risk.executedNotional(ACCT),
            "a fill worth " + (100L * PX) + " booked nothing: release() and consume() are coupled "
                + "through the holder, so capacity cannot be released from an order left live");
    }

    @Test
    void anAggregateOnlyReleaseIsRebuiltFromTheOrderAtSnapshotRestore() {
        assertEquals(RiskReason.ACCEPTED, decide(72L, 100, PX));

        // The other tempting move: decrement the account aggregate only and leave the holder
        // intact, so a later fill still books. Restore rebuilds the aggregates FROM the holders
        // (bootstrapOrder -> reaccumulateReservation for every open order), so the capacity comes
        // straight back — an aggregate-only release survives exactly until the next restore, which
        // also settles the question that issue left open: the reservation of an orphaned child
        // survives snapshot restore and failover, because risk never knew it had a parent.
        BlpRiskState restored = new BlpRiskState(64, 16, 1024, 128, CREDIT, 10_000, CREDIT,
            30_000L, new RiskMetrics());
        restored.reaccumulateReservation(ACCT, SEC, (byte) 0, order.reservedNotional(),
            order.reservedQty());

        assertEquals(100L * PX, restored.reservedNotional(ACCT),
            "the aggregate is rebuilt from the order's own reservation, so decrementing the "
                + "aggregate alone is undone by the next snapshot restore");
    }

    // ----- YU14: contract-multiplier-aware notional (FR-LEO03) -------------------------------

    private static final int OPT = 5;   // a second security carrying multiplier 100

    private void enableOption() {
        risk.putSecurity(OPT, true);
        risk.putContractMultiplier(OPT, 100L);
    }

    @Test
    void multiplierScalesReservedNotional() {
        enableOption();
        assertEquals(RiskReason.ACCEPTED,
            risk.decideAndReserve(101L, 0L, 7, ACCT, OPT, (byte) 0, 0, 10, PX, 1_000L, order));
        assertEquals(10L * PX * 100L, risk.reservedNotional(ACCT),
            "an option reservation is qty x price x multiplier");
        assertEquals(10L * PX * 100L, order.reservedNotional());
    }

    @Test
    void multipliedNotionalCapFiresWhereUnmultipliedWouldPass() {
        enableOption();
        // qty 200 x 100.000: 2e10 as an equity (passes the 1e12 cap), 2e12 as a 100-multiplier
        // option (rejects). The identical order on the multiplier-1 security is the control.
        assertEquals(RiskReason.ACCEPTED, decide(102L, 200, PX));
        assertEquals(RiskReason.ORDER_NOTIONAL,
            risk.decideAndReserve(103L, 0L, 8, ACCT, OPT, (byte) 0, 0, 200, PX, 1_000L, order));
    }

    @Test
    void multiplierScalesCreditConsumption() {
        enableOption();
        // Credit 1e12; each option order reserves 10 x 1e8 x 100 = 1e11. Ten fill the line
        // exactly; the 11th exceeds it. The same flow un-multiplied would sit at 1% utilization.
        for (int i = 0; i < 10; i++) {
            assertEquals(RiskReason.ACCEPTED, risk.decideAndReserve(110L + i, 0L, 7, ACCT, OPT,
                (byte) 0, 0, 10, PX, 1_000L, new TestReservation()));
        }
        assertEquals(RiskReason.CREDIT_LIMIT, risk.decideAndReserve(120L, 0L, 7, ACCT, OPT,
            (byte) 0, 0, 10, PX, 1_000L, new TestReservation()));
    }

    @Test
    void consumeAccumulatesMultipliedExecutedExposure() {
        enableOption();
        assertEquals(RiskReason.ACCEPTED,
            risk.decideAndReserve(130L, 0L, 7, ACCT, OPT, (byte) 0, 0, 10, PX, 1_000L, order));
        risk.consume(ACCT, OPT, (byte) 0, order, 4, PX);
        assertEquals(4L * PX * 100L, risk.executedNotional(ACCT),
            "executed exposure accumulates at the multiplied notional");
        assertEquals(6L * PX * 100L, risk.reservedNotional(ACCT),
            "the remaining reservation is released pro rata at the multiplied notional");
        assertEquals(6, order.reservedQty());
    }

    @Test
    void concentrationAppliesMultiplier() {
        enableOption();
        // Concentration cap 5e10: projected 10 contracts x 100.000 x 100 = 1e11 exceeds it;
        // the same projection un-multiplied (1e9) is 2% of the cap.
        risk.putLimits(50_000, 50_000_000_000L);
        assertEquals(RiskReason.CONCENTRATION_LIMIT, risk.decideAndReserve(140L, 0L, 7, ACCT, OPT,
            (byte) 0, 0, 10, PX, 1_000L, order));
        assertEquals(RiskReason.ACCEPTED, decide(141L, 10, PX));
    }

    @Test
    void multiplierDefaultsToOneWhenNeverSet() {
        assertEquals(0L, risk.contractMultiplier(SEC), "raw storage: never set");
        assertEquals(RiskReason.ACCEPTED, decide(150L, 100, PX));
        assertEquals(100L * PX, risk.reservedNotional(ACCT),
            "an unset multiplier behaves as 1 - equity behavior is bit-identical");
    }
}
