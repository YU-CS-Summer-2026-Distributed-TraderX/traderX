package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlpRiskStateTest {
    private BlpRiskState state(long creditLimit) {
        GatewayReplicaStore.Snapshot snapshot = new GatewayReplicaStore.Snapshot(1L, 2L, 2L, 7L, true,
            List.of(new GatewayReplicaStore.AccountRecord(22214, true, 1L)),
            List.of(new GatewayReplicaStore.SecurityRecord(0, "IBM", true, false,
                100_000_000L, 1_000L, 2L)));
        BlpRiskState result = new BlpRiskState(16, 16, 128, 128, creditLimit,
            10_000, creditLimit, 30_000L, new RiskMetrics());
        result.bootstrap(snapshot);
        return result;
    }

    @Test
    void checkAndReserveIsAtomicAgainstAggregateHeadroom() {
        BlpRiskState state = state(1_500_000_000L);
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(11L, 1, 22214, 0, 10, 100_000_000L, 1_000L));
        assertEquals(1_000_000_000L, state.reservedNotional(22214));
        assertEquals(RiskReason.CREDIT_LIMIT,
            state.decideAndReserve(12L, 2, 22214, 0, 10, 100_000_000L, 1_000L));
    }

    @Test
    void duplicateDoesNotReserveTwice() {
        BlpRiskState state = state(5_000_000_000L);
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(99L, 1, 22214, 0, 10, 100_000_000L, 1_000L));
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(99L, 2, 22214, 0, 10, 100_000_000L, 1_000L));
        assertEquals(1_000_000_000L, state.reservedNotional(22214));
    }

    @Test
    void fillConvertsAndCancelReleasesReservationExactlyOnce() {
        BlpRiskState state = state(5_000_000_000L);
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(1L, 1, 22214, 0, 10, 100_000_000L, 1_000L));
        state.consume(22214, 1, 4, 100_000_000L);
        assertEquals(600_000_000L, state.reservedNotional(22214));
        state.release(22214, 1);
        state.release(22214, 1);
        assertEquals(0L, state.reservedNotional(22214));
    }

    @Test
    void entitlementRestrictionPositionAndDirectionalReservationsAreAuthoritative() {
        BlpRiskState state = state(5_000_000_000L);
        assertEquals(RiskReason.NOT_ENTITLED,
            state.decideAndReserve(200L, 77L, 1, 22214, 0, (byte) 0, 0,
                1, 100_000_000L, 1_000L));

        state.putEntitlement(77L, 22214, true);
        state.putRestriction(0, true);
        assertEquals(RiskReason.RESTRICTED,
            state.decideAndReserve(201L, 77L, 2, 22214, 0, (byte) 0, 0,
                1, 100_000_000L, 1_000L));

        state.putRestriction(0, false);
        state.putLimits(10, 5_000_000_000L);
        assertEquals(RiskReason.POSITION_LIMIT,
            state.decideAndReserve(202L, 77L, 3, 22214, 0, (byte) 0, 5,
                6, 100_000_000L, 1_000L));

        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(203L, 77L, 4, 22214, 0, (byte) 0, 0,
                3, 100_000_000L, 1_000L));
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(204L, 77L, 5, 22214, 0, (byte) 1, 0,
                2, 100_000_000L, 1_000L));
        assertEquals(300_000_000L, state.reservedBuyNotional(22214));
        assertEquals(200_000_000L, state.reservedSellNotional(22214));
        state.release(22214, 4);
        state.release(22214, 5);
        assertEquals(0L, state.reservedBuyNotional(22214));
        assertEquals(0L, state.reservedSellNotional(22214));
    }

    @Test
    void idempotencyRetentionAdvancesDeterministically() {
        GatewayReplicaStore.Snapshot snapshot = new GatewayReplicaStore.Snapshot(1L, 2L, 2L, 7L, true,
            List.of(new GatewayReplicaStore.AccountRecord(22214, true, 1L)),
            List.of(new GatewayReplicaStore.SecurityRecord(0, "IBM", true, false,
                100_000_000L, 1_000L, 2L)));
        BlpRiskState state = new BlpRiskState(16, 16, 16, 2, 10_000_000_000L,
            10_000, 10_000_000_000L, 30_000L, new RiskMetrics());
        state.bootstrap(snapshot);
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(1L, 1, 22214, 0, 1, 100_000_000L, 1_000L));
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(2L, 2, 22214, 0, 1, 100_000_000L, 1_000L));
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(3L, 3, 22214, 0, 1, 100_000_000L, 1_000L));
        assertEquals(1L, state.idempotencyFrontier());
        assertEquals(RiskReason.ACCEPTED,
            state.decideAndReserve(1L, 4, 22214, 0, 1, 100_000_000L, 1_000L));
        assertEquals(2L, state.idempotencyFrontier());
    }
}
