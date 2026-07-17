package finos.traderx.ordermatcher.config;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** YU11 transport, Archive, shadow, and fast-witness telemetry. */
@Component
public final class ReplicationMetrics implements MeterBinder {
    private final LmaxEngine engine;

    public ReplicationMetrics(LmaxEngine engine) {
        this.engine = engine;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        String transport = engine.replicationTransportName();
        String failover = engine.failoverModeName();
        gauge(registry, "traderx.blp.replication.enabled", engine,
            e -> yes(e.replicationEnabled()), transport, failover);
        gauge(registry, "traderx.blp.replication.primary", engine,
            e -> yes(e.replicationPrimary()), transport, failover);
        gauge(registry, "traderx.blp.replication.healthy", engine,
            e -> yes(e.replicationHealthy()), transport, failover);
        gauge(registry, "traderx.blp.replication.connected", engine,
            e -> yes(e.replicationConnected()), transport, failover);
        gauge(registry, "traderx.blp.replication.degraded", engine,
            e -> yes(e.replicationDegraded()), transport, failover);
        gauge(registry, "traderx.blp.replication.leader.epoch", engine,
            e -> e.leaderEpoch(), transport, failover);
        gauge(registry, "traderx.blp.replication.journaled.input.seq", engine,
            e -> e.journaledSeq(), transport, failover);
        gauge(registry, "traderx.blp.replication.follower.acked.input.seq", engine,
            e -> e.followerAckedInputSeq(), transport, failover);
        gauge(registry, "traderx.blp.replication.follower.received.input.seq", engine,
            e -> e.followerReceivedInputSeq(), transport, failover);
        gauge(registry, "traderx.blp.replication.follower.durable.input.seq", engine,
            e -> e.followerDurableAckedInputSeq(), transport, failover);
        gauge(registry, "traderx.blp.replication.control.session.ready", engine,
            e -> yes(e.controlSessionReady()), transport, failover);
        gauge(registry, "traderx.blp.replication.peer.stale", engine,
            e -> yes(e.peerStale()), transport, failover);
        gauge(registry, "traderx.blp.replication.peer.heartbeat.age.millis", engine,
            e -> e.peerHeartbeatAgeMillis(), transport, failover);
        gauge(registry, "traderx.blp.replication.archive.replay.active", engine,
            e -> yes(e.archiveReplayActive()), transport, failover);
        gauge(registry, "traderx.blp.replication.archive.replay.merged", engine,
            e -> yes(e.archiveReplayMerged()), transport, failover);
        gauge(registry, "traderx.blp.replication.shadow.mismatches", engine,
            e -> e.shadowMismatchCount(), transport, failover);
        gauge(registry, "traderx.blp.fast.witness.held", engine,
            e -> yes(e.witnessHeld()), transport, failover);
        gauge(registry, "traderx.blp.fast.witness.revision", engine,
            e -> e.witnessRevision(), transport, failover);
        gauge(registry, "traderx.blp.fast.witness.epoch", engine,
            e -> e.witnessEpoch(), transport, failover);
        gauge(registry, "traderx.blp.fast.failover.detection.to.claim.millis", engine,
            e -> e.failoverDetectionToClaimMillis(), transport, failover);
        gauge(registry, "traderx.blp.fast.failover.claim.to.admission.millis", engine,
            e -> e.failoverClaimToAdmissionMillis(), transport, failover);
        gauge(registry, "traderx.blp.fast.failover.total.millis", engine,
            e -> e.failoverTotalMillis(), transport, failover);

        counter(registry, "traderx.blp.replication.offer.failures", engine,
            e -> e.replicationOfferFailureCount(), transport, failover);
        counter(registry, "traderx.blp.replication.invalid.frames", engine,
            e -> e.replicationInvalidFrameCount(), transport, failover);
        counter(registry, "traderx.blp.fast.witness.claim.attempts", engine,
            e -> e.witnessClaimAttemptCount(), transport, failover);
        counter(registry, "traderx.blp.fast.witness.claim.conflicts", engine,
            e -> e.witnessClaimConflictCount(), transport, failover);
        counter(registry, "traderx.blp.fast.witness.ambiguous.operations", engine,
            e -> e.witnessAmbiguousOperationCount(), transport, failover);
        counter(registry, "traderx.blp.fast.witness.lost.claims", engine,
            e -> e.witnessLostClaimCount(), transport, failover);
    }

    private static void gauge(MeterRegistry registry, String name, LmaxEngine engine,
                              java.util.function.ToDoubleFunction<LmaxEngine> value,
                              String transport, String failover) {
        Gauge.builder(name, engine, value)
            .tags("transport", transport, "failover_mode", failover)
            .register(registry);
    }

    private static void counter(MeterRegistry registry, String name, LmaxEngine engine,
                                java.util.function.ToDoubleFunction<LmaxEngine> value,
                                String transport, String failover) {
        FunctionCounter.builder(name, engine, value)
            .tags("transport", transport, "failover_mode", failover)
            .register(registry);
    }

    private static double yes(boolean value) { return value ? 1.0 : 0.0; }
}
