package finos.traderx.ordermatcher.config;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

@Component("lmaxRecovery")
public class LmaxRecoveryHealthIndicator implements HealthIndicator {
    private final LmaxEngine engine;

    public LmaxRecoveryHealthIndicator(LmaxEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        Health.Builder builder;
        String recoveryError = engine.recoveryError();
        if (recoveryError != null) {
            builder = Health.down();
        } else if (engine.recoveryReady()) {
            builder = Health.up();
        } else {
            builder = Health.status(Status.OUT_OF_SERVICE);
        }

        builder
            .withDetail("status", engine.recoveryStatus())
            .withDetail("mode", engine.recoveryMode())
            .withDetail("journalPath", engine.journalPath())
            .withDetail("projectedSeq", engine.projectedSeq())
            .withDetail("inputPublishedSeq", engine.inputPublishedSeq())
            .withDetail("gatingSeq", engine.gatingSeq())
            .withDetail("projectorQueueDepth", engine.projectorQueueDepth())
            .withDetail("projectorDbEnabled", engine.projectorDbEnabled())
            .withDetail("warmup", "deferred");

        if (recoveryError != null) {
            builder.withDetail("recoveryError", recoveryError);
        }

        return builder.build();
    }
}
