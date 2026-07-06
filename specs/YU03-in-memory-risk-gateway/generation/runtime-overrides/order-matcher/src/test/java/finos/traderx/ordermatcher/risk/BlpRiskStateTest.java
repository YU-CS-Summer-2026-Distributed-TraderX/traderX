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
}
