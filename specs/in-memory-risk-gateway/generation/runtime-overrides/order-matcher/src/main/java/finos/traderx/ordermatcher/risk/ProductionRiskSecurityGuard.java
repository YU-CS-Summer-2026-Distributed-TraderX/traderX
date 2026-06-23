package finos.traderx.ordermatcher.risk;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fail-fast production preflight for sensitive journal/snapshot/control transport. */
@Component
public final class ProductionRiskSecurityGuard {
    private final String profile;
    private final String natsAddress;
    private final boolean atRestProtected;

    public ProductionRiskSecurityGuard(@Value("${runtime.profile:demo}") String profile,
        @Value("${nats.address:nats://localhost:4222}") String natsAddress,
        @Value("${risk.data-at-rest-protected:false}") boolean atRestProtected) {
        this.profile = profile;
        this.natsAddress = natsAddress;
        this.atRestProtected = atRestProtected;
    }

    @PostConstruct
    void verify() {
        if (!"production".equalsIgnoreCase(profile)) return;
        if (!atRestProtected) {
            throw new IllegalStateException("production requires encrypted storage for risk snapshots, "
                + "journals, and retained control data (risk.data-at-rest-protected=true)");
        }
        if (!natsAddress.startsWith("tls://")) {
            throw new IllegalStateException("production risk control transport requires tls:// NATS");
        }
        if (!natsAddress.contains("@")) {
            throw new IllegalStateException("production risk control transport requires an authenticated identity");
        }
    }
}
