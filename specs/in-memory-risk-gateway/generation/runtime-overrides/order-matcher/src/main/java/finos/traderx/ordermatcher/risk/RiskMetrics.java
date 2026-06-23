package finos.traderx.ordermatcher.risk;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded-cardinality replica/Gateway metrics; no account/security identifiers are labels. */
public final class RiskMetrics {
    private final LongAdder[] gatewayRejects = counters();
    private final LongAdder[] authoritativeDecisions = counters();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder gaps = new LongAdder();
    private final LongAdder mismatches = new LongAdder();
    private final AtomicLong policyVersion = new AtomicLong();
    private final AtomicLong sourceVersion = new AtomicLong();
    private final AtomicLong highWatermark = new AtomicLong();

    private static LongAdder[] counters() {
        LongAdder[] result = new LongAdder[RiskReason.values().length];
        for (int i = 0; i < result.length; i++) result[i] = new LongAdder();
        return result;
    }

    public void gatewayRejected(RiskReason reason) { gatewayRejects[reason.ordinal()].increment(); }
    public void decided(RiskReason reason) { authoritativeDecisions[reason.ordinal()].increment(); }
    public void duplicate() { duplicates.increment(); }
    public void gap() { gaps.increment(); }
    public void mismatch() { mismatches.increment(); }
    public void policyVersion(long value) { policyVersion.set(value); }
    public void sourceVersion(long value) { sourceVersion.set(value); }
    public void highWatermark(long value) { highWatermark.set(value); }

    public void render(StringBuilder sb, boolean ready) {
        sb.append("# TYPE traderx_replica_ready gauge\ntraderx_replica_ready{replica=\"gateway\"} ")
            .append(ready ? 1 : 0).append('\n');
        sb.append("# TYPE traderx_replica_source_version gauge\ntraderx_replica_source_version{replica=\"gateway\"} ")
            .append(sourceVersion.get()).append('\n');
        sb.append("# TYPE traderx_replica_high_watermark gauge\ntraderx_replica_high_watermark{replica=\"gateway\"} ")
            .append(highWatermark.get()).append('\n');
        sb.append("# TYPE traderx_replica_lag gauge\ntraderx_replica_lag{replica=\"gateway\"} ")
            .append(Math.max(0L, highWatermark.get() - sourceVersion.get())).append('\n');
        sb.append("# TYPE traderx_replica_gap_total counter\ntraderx_replica_gap_total{replica=\"gateway\"} ")
            .append(gaps.sum()).append('\n');
        sb.append("# TYPE traderx_risk_policy_version gauge\ntraderx_risk_policy_version ")
            .append(policyVersion.get()).append('\n');
        sb.append("# TYPE traderx_gateway_rejections_total counter\n");
        for (RiskReason reason : RiskReason.values()) {
            if (reason == RiskReason.ACCEPTED) continue;
            sb.append("traderx_gateway_rejections_total{reason=\"").append(reason.name().toLowerCase())
                .append("\"} ").append(gatewayRejects[reason.ordinal()].sum()).append('\n');
        }
        sb.append("# TYPE traderx_risk_decisions_total counter\n");
        for (RiskReason reason : RiskReason.values()) {
            sb.append("traderx_risk_decisions_total{reason=\"").append(reason.name().toLowerCase())
                .append("\"} ").append(authoritativeDecisions[reason.ordinal()].sum()).append('\n');
        }
        sb.append("# TYPE traderx_idempotency_duplicate_total counter\ntraderx_idempotency_duplicate_total ")
            .append(duplicates.sum()).append('\n');
        sb.append("# TYPE traderx_gateway_blp_mismatch_total counter\ntraderx_gateway_blp_mismatch_total{reason=\"decision\"} ")
            .append(mismatches.sum()).append('\n');
    }
}
