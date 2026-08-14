package finos.traderx.ordermatcher.risk;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import static org.mockito.Mockito.never;
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
    void nfrImrgIsolationRebootstrapsOnlyTheQuarantinedSource() throws Exception {
        ReplicaBootstrap.BootstrapProgress progress =
            bootstrap.bootstrapPendingFeeds(false, true);

        verify(accountFeed).bootstrapOnce();
        verify(securityFeed, never()).bootstrapOnce();
        assertTrue(progress.accountBootstrapped());
        assertTrue(progress.securityBootstrapped());
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

    /**
     * The "bootstrap complete" line is logged on a CHANGE, not on a tick. updateReadiness() runs
     * from the ~1s monitor loop, so an unconditional INFO there was one identical line per second
     * per pod -- 86,400 a day, burying the warn lines beside it.
     *
     * Asserted on the real appender rather than on a flag, because the thing that matters is what
     * an operator sees. The re-arm is the half worth guarding: get it wrong and the RECOVERY line
     * -- the one someone is actually waiting for after a quarantine -- never prints again.
     */
    @Test
    void bootstrapCompleteLogsOnChangeNotOnEveryTick() {
        final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReplicaBootstrap.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(accountFeed.isReady()).thenReturn(true);
            when(securityFeed.isReady()).thenReturn(true);
            when(accountFeed.watermark()).thenReturn(17L);
            when(securityFeed.watermark()).thenReturn(29L);

            bootstrap.updateReadiness();
            bootstrap.updateReadiness();
            bootstrap.updateReadiness();
            assertEquals(1, completeLines(appender),
                "three ticks at an unchanged watermark must log once, not three times");

            // A watermark moved: that is a new fact, so it prints.
            when(securityFeed.watermark()).thenReturn(30L);
            bootstrap.updateReadiness();
            assertEquals(2, completeLines(appender), "a watermark change must re-log");

            // Quarantine, then recovery at the SAME watermarks. The recovery line must print --
            // this is the case a naive "log once ever" throttle silently loses.
            when(accountFeed.isReady()).thenReturn(false);
            bootstrap.updateReadiness();
            assertEquals(2, completeLines(appender), "losing readiness logs no completion line");
            when(accountFeed.isReady()).thenReturn(true);
            bootstrap.updateReadiness();
            assertEquals(3, completeLines(appender),
                "recovery after a quarantine must log even at unchanged watermarks");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long completeLines(final ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("Risk replica bootstrap complete"))
            .count();
    }

}
