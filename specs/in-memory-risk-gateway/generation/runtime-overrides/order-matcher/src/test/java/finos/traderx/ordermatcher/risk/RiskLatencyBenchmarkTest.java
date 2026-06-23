package finos.traderx.ordermatcher.risk;

import java.math.BigDecimal;
import java.util.List;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "riskLatencyBenchmark", matches = "true")
class RiskLatencyBenchmarkTest {
    @Test
    void reportsGatewayAndAuthoritativeDecisionLatency() {
        int iterations = Integer.getInteger("riskLatencyBenchmark.iterations", 20_000);
        GatewayReplicaStore gateway = new GatewayReplicaStore("22214", "IBM", 1_000_000,
            Long.MAX_VALUE / 4, 30_000L, 5_000L);
        gateway.bootstrap();
        BlpRiskState blp = state(iterations + 20_000);
        Histogram gatewayNs = new Histogram(10_000_000L, 3);
        Histogram blpNs = new Histogram(10_000_000L, 3);

        for (int i = 0; i < 5_000; i++) {
            gateway.screen(22214, "IBM", 1, BigDecimal.valueOf(100), false, 1_000L);
            blp.decideAndReserve(i + 1L, i + 1, 22214, 0, 1, 100_000_000L, 1_000L);
        }
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            gateway.screen(22214, "IBM", 1, BigDecimal.valueOf(100), false, 1_000L);
            gatewayNs.recordValue(System.nanoTime() - start);
            start = System.nanoTime();
            blp.decideAndReserve(10_000L + i, 10_000 + i, 22214, 0, 1, 100_000_000L, 1_000L);
            blpNs.recordValue(System.nanoTime() - start);
        }

        report("gateway_screen", gatewayNs);
        report("blp_decide_reserve", blpNs);
        assertTrue(gatewayNs.getValueAtPercentile(99.0) < 25_000L);
        assertTrue(blpNs.getValueAtPercentile(99.0) < 25_000L);
    }

    private static BlpRiskState state(int capacity) {
        GatewayReplicaStore.Snapshot snapshot = new GatewayReplicaStore.Snapshot(1L, 2L, 2L, 1L,
            true, List.of(new GatewayReplicaStore.AccountRecord(22214, true, 1L)),
            List.of(new GatewayReplicaStore.SecurityRecord(0, "IBM", true, false,
                100_000_000L, 1_000L, 2L)));
        BlpRiskState state = new BlpRiskState(16, 16, capacity, capacity * 2,
            Long.MAX_VALUE / 4, 1_000_000, Long.MAX_VALUE / 4, 30_000L, new RiskMetrics());
        state.bootstrap(snapshot);
        return state;
    }

    private static void report(String name, Histogram histogram) {
        System.out.printf("RISK_LATENCY name=%s p50_ns=%d p99_ns=%d p999_ns=%d max_ns=%d%n",
            name, histogram.getValueAtPercentile(50), histogram.getValueAtPercentile(99),
            histogram.getValueAtPercentile(99.9), histogram.getMaxValue());
    }
}
