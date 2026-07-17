package finos.traderx.ordermatcher.config;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/** Bounded YU11 replication/witness state in the readiness health group. */
@Component("replication")
public final class ReplicationHealthIndicator implements HealthIndicator {
    private final LmaxEngine engine;

    public ReplicationHealthIndicator(LmaxEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        Health.Builder result = engine.replicationHealthy()
            ? Health.up() : Health.status(Status.OUT_OF_SERVICE);
        return result
            .withDetail("enabled", engine.replicationEnabled())
            .withDetail("transport", engine.replicationTransportName())
            .withDetail("ackMode", engine.replicationAckModeName())
            .withDetail("failurePolicy", engine.replicationFailurePolicyName())
            .withDetail("failoverMode", engine.failoverModeName())
            .withDetail("primary", engine.replicationPrimary())
            .withDetail("leaderEpoch", engine.leaderEpoch())
            .withDetail("connected", engine.replicationConnected())
            .withDetail("degraded", engine.replicationDegraded())
            .withDetail("controlSessionReady", engine.controlSessionReady())
            .withDetail("peerStale", engine.peerStale())
            .withDetail("peerHeartbeatAgeMs", engine.peerHeartbeatAgeMillis())
            .withDetail("journaledSeq", engine.journaledSeq())
            .withDetail("followerAckedInputSeq", engine.followerAckedInputSeq())
            .withDetail("followerReceivedInputSeq", engine.followerReceivedInputSeq())
            .withDetail("archiveReplayActive", engine.archiveReplayActive())
            .withDetail("archiveReplayMerged", engine.archiveReplayMerged())
            .withDetail("witnessHeld", engine.witnessHeld())
            .withDetail("witnessRevision", engine.witnessRevision())
            .withDetail("witnessEpoch", engine.witnessEpoch())
            .build();
    }
}
