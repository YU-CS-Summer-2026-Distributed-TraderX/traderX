package finos.traderx.ordermatcher.risk;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.ReplicationRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Durable-feed orchestration contract (FR-IMRG05/34 and NFR-IMRG-ISOLATION). */
class ReplicaBootstrapTest {
    private GatewayReplicaStore replicas;
    private LmaxEngine engine;
    private ControlFeedSubscriber<AccountDelta> accountFeed;
    private ControlFeedSubscriber<SecurityDelta> securityFeed;
    private ReplicaBootstrap bootstrap;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        replicas = new GatewayReplicaStore("22214", "IBM", 10_000,
            1_000_000_000_000L, 30_000L, 5_000L, 64, 64);
        replicas.seed();
        replicas.alignSecurityIds(ticker -> "IBM".equals(ticker) ? 0 : 1);
        engine = mock(LmaxEngine.class);
        accountFeed = mock(ControlFeedSubscriber.class);
        securityFeed = mock(ControlFeedSubscriber.class);
        bootstrap = new ReplicaBootstrap(replicas, engine, new ReplicationRole(), true, true,
            accountFeed, securityFeed);
    }

    @Test
    void frImrg05ReadinessRequiresBothIndependentFeedWatermarks() {
        when(accountFeed.watermark()).thenReturn(17L);
        when(securityFeed.watermark()).thenReturn(29L);
        when(accountFeed.isReady()).thenReturn(true);
        when(securityFeed.isReady()).thenReturn(false, true);

        bootstrap.updateReadiness();
        assertFalse(replicas.ready());
        bootstrap.updateReadiness();
        assertTrue(replicas.ready());

        StringBuilder metrics = new StringBuilder();
        replicas.metrics().render(metrics, replicas.ready());
        assertTrue(metrics.toString().contains(
            "traderx_replica_source_watermark{source=\"account\"} 17"));
        assertTrue(metrics.toString().contains(
            "traderx_replica_source_watermark{source=\"security\"} 29"));
    }

    @Test
    void frImrg34AccountQuarantineRevokesReadinessWithoutTouchingSecurityFeed() {
        replicas.markReady();

        bootstrap.onQuarantine("account");

        assertFalse(replicas.ready());
        verifyNoInteractions(securityFeed);
        StringBuilder metrics = new StringBuilder();
        replicas.metrics().render(metrics, replicas.ready());
        assertTrue(metrics.toString().contains(
            "traderx_replica_quarantine_total{source=\"account\",reason=\"gap_or_epoch_mismatch\"} 1"));
    }

    @Test
    void frImrg05DeltaHandlersUpdateReplicaAndSequenceTheSameFactIntoBlp() {
        bootstrap.applyAccountDelta(new AccountDelta(44044, "Account B"), 101L);
        bootstrap.applySecurityDelta(new SecurityDelta("MSFT", "Microsoft"), 202L);

        verify(engine).submitAccountControl(44044, true, 3L);
        verify(engine).submitSecurityControl("MSFT", true, 4L);
        GatewayReplicaStore.Snapshot snapshot = replicas.snapshot();
        assertEquals(101L, snapshot.accounts().stream()
            .filter(account -> account.accountId() == 44044).findFirst().orElseThrow().sourceVersion());
        assertEquals(202L, snapshot.securities().stream()
            .filter(security -> security.ticker().equals("MSFT")).findFirst().orElseThrow().sourceVersion());
    }
}
