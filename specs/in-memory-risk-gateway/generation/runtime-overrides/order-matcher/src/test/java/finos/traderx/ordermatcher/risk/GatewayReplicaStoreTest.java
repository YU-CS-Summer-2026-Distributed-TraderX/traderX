package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayReplicaStoreTest {
    private GatewayReplicaStore store;

    @BeforeEach
    void setUp() {
        store = new GatewayReplicaStore("22214,44044", "IBM,MSFT", 10_000,
            1_000_000_000_000L, 1_000L, 5_000L);
        store.bootstrap();
    }

    @Test
    void screensAgainstLocalAccountAndSecurityReplicas() {
        assertEquals(RiskReason.ACCEPTED,
            store.screen(22214, "ibm", 10, new BigDecimal("100.000"), false, 10L));
        assertEquals(RiskReason.UNKNOWN_ACCOUNT,
            store.screen(99999, "IBM", 10, new BigDecimal("100.000"), false, 10L));
        assertEquals(RiskReason.UNKNOWN_SECURITY,
            store.screen(22214, "NOPE", 10, new BigDecimal("100.000"), false, 10L));
    }

    @Test
    void marketTradeRequiresFreshLocalPrice() {
        assertEquals(RiskReason.PRICE_MISSING,
            store.screen(22214, "IBM", 10, null, true, 100L));
        store.recordPrice("IBM", 100_000_000L, 100L);
        assertEquals(RiskReason.ACCEPTED,
            store.screen(22214, "IBM", 10, null, true, 500L));
        assertEquals(RiskReason.PRICE_STALE,
            store.screen(22214, "IBM", 10, null, true, 1_101L));
    }

    @Test
    void detectsVersionGapAndFailsClosed() {
        long current = store.sourceVersion();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> store.applyAccount(1L, current + 2L, 22214, false));
        assertTrue(failure.getMessage().contains("control version gap"));
        assertFalse(store.ready());
        assertEquals(RiskReason.CONTROL_STATE_STALE,
            store.screen(22214, "IBM", 1, BigDecimal.ONE, false, 1L));
    }

    @Test
    void appliesOrderedControlAndPublishesWatermarkedSnapshot() {
        long next = store.sourceVersion() + 1L;
        store.applyAccount(1L, next, 22214, false);
        assertEquals(RiskReason.ACCOUNT_DISABLED,
            store.screen(22214, "IBM", 1, BigDecimal.ONE, false, 1L));
        GatewayReplicaStore.Snapshot snapshot = store.snapshot();
        assertEquals(next, snapshot.watermark());
        assertEquals(next, snapshot.highWatermark());
        assertTrue(snapshot.ready());
    }

    @Test
    void snapshotIsNotReadyUntilWatermarkReachesObservedHead() {
        GatewayReplicaStore.Snapshot snapshot = store.snapshot();
        store.installSnapshot(new GatewayReplicaStore.Snapshot(2L, 10L, 12L, 3L, true,
            snapshot.accounts(), snapshot.securities()));
        assertFalse(store.ready());
        assertEquals(RiskReason.CONTROL_STATE_STALE,
            store.screen(22214, "IBM", 1, BigDecimal.ONE, false, 1L));
    }

    @Test
    void externalEntitlementSnapshotFailsClosedForUnauthorizedPrincipal() {
        GatewayReplicaStore.Snapshot current = store.snapshot();
        store.beginExternalBootstrap();
        store.installAccountSnapshot(2L, 5L, 5L, current.accounts(), List.of(
            new GatewayReplicaStore.EntitlementRecord("alice", 22214, true, 5L)));
        store.installSecuritySnapshot(2L, 2L, 2L, current.securities());
        store.installRiskSnapshot(2L, 0L, 0L, 1L, false, Map.of());
        assertEquals(RiskReason.ACCEPTED,
            store.screen("alice", 22214, "IBM", 1, BigDecimal.ONE, false, 1L));
        assertEquals(RiskReason.NOT_ENTITLED,
            store.screen("mallory", 22214, "IBM", 1, BigDecimal.ONE, false, 1L));
    }

    @Test
    void riskGapAndEpochChangeFailClosedUntilAtomicRebootstrap() {
        GatewayReplicaStore.Snapshot current = store.snapshot();
        store.beginExternalBootstrap();
        store.installAccountSnapshot(2L, 5L, 5L, current.accounts(), List.of(
            new GatewayReplicaStore.EntitlementRecord("alice", 22214, true, 5L)));
        store.installSecuritySnapshot(3L, 2L, 2L, current.securities());
        store.installRiskSnapshot(4L, 7L, 7L, 9L, false, Map.of());
        assertTrue(store.ready());

        assertThrows(IllegalArgumentException.class,
            () -> store.applyExternalRestriction(4L, 9L, "IBM", true));
        assertFalse(store.ready());

        store.installRiskSnapshot(5L, 10L, 10L, 10L, false, Map.of("IBM", true));
        assertTrue(store.ready());
        assertEquals(RiskReason.RESTRICTED,
            store.screen("alice", 22214, "IBM", 1, BigDecimal.ONE, false, 1L));
    }

    @Test
    void disconnectedControlFeedFailsClosedOnlyAfterConfiguredDeadline() {
        GatewayReplicaStore.Snapshot current = store.snapshot();
        store.beginExternalBootstrap();
        store.installAccountSnapshot(2L, 1L, 1L, current.accounts(), List.of(
            new GatewayReplicaStore.EntitlementRecord("alice", 22214, true, 1L)));
        store.installSecuritySnapshot(3L, 1L, 1L, current.securities());
        store.installRiskSnapshot(4L, 0L, 0L, 1L, false, Map.of());
        store.markControlConnected(1_000L);
        store.markControlDisconnected(1_000L);
        assertTrue(store.admissionReady(30_999L));
        assertFalse(store.admissionReady(31_001L));
        store.markControlConnected(31_002L);
        assertTrue(store.admissionReady(31_002L));
    }
}
