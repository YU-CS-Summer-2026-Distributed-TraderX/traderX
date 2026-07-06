package finos.traderx.ordermatcher.risk;

import org.HdrHistogram.ConcurrentHistogram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gateway preliminary screening against local replica state only (FR-IMRG06), fail-closed. */
class GatewayReplicaStoreTest {
    private GatewayReplicaStore replicas;

    @BeforeEach
    void setUp() {
        replicas = new GatewayReplicaStore("22214,44044", "IBM,MSFT", 10_000,
            1_000_000_000_000L, 30_000L, 5_000L, 64, 64);
        replicas.seed();
        replicas.alignSecurityIds(ticker -> "IBM".equals(ticker) ? 0 : 1);
    }

    private RiskReason screen(int account, String ticker, int qty, String limit, long now) {
        return replicas.screen(account, ticker, qty,
            limit == null ? null : new BigDecimal(limit), false, now);
    }

    @Test
    void notReadyFailsClosed() {
        assertEquals(RiskReason.CONTROL_STATE_STALE, screen(22214, "IBM", 100, "100.000", 1_000L));
    }

    @Test
    void screensAgainstLocalReplicaState() {
        replicas.markReady();
        assertEquals(RiskReason.ACCEPTED, screen(22214, "IBM", 100, "100.000", 1_000L));
        assertEquals(RiskReason.UNKNOWN_ACCOUNT, screen(999, "IBM", 100, "100.000", 1_000L));
        assertEquals(RiskReason.UNKNOWN_SECURITY, screen(22214, "TSLA", 100, "100.000", 1_000L));
        assertEquals(RiskReason.ORDER_SIZE, screen(22214, "IBM", 20_000, "100.000", 1_000L));
        assertEquals(RiskReason.INVALID, screen(22214, "IBM", 100, null, 1_000L));

        replicas.applyAccount(22214, false);
        assertEquals(RiskReason.ACCOUNT_DISABLED, screen(22214, "IBM", 100, "100.000", 1_000L));
        replicas.applyAccount(22214, true);

        replicas.applyPolicy(2L, true);
        assertEquals(RiskReason.KILL_SWITCH, screen(22214, "IBM", 100, "100.000", 1_000L));
        replicas.applyPolicy(3L, false);

        replicas.applyRestriction("MSFT", true);
        assertEquals(RiskReason.RESTRICTED, screen(22214, "MSFT", 100, "100.000", 1_000L));
    }

    @Test
    void priceCollarAndFreshness() {
        replicas.markReady();
        replicas.recordPrice("IBM", 100_000_000L, 1_000L);
        // > 50% (5000 bps) away from the fresh market price
        assertEquals(RiskReason.PRICE_COLLAR, screen(22214, "IBM", 100, "200.000", 2_000L));
        assertEquals(RiskReason.ACCEPTED, screen(22214, "IBM", 100, "110.000", 2_000L));
        // market trades need a fresh price
        assertEquals(RiskReason.PRICE_STALE,
            replicas.screen(22214, "IBM", 100, null, true, 1_000L + 31_000L));
        assertEquals(RiskReason.PRICE_MISSING,
            replicas.screen(22214, "MSFT", 100, null, true, 1_000L));
    }

    @Test
    void screenLatencyP99StaysUnderGateway5usBudget() {
        // NFR-IMRG01 p99 CI gate, edge/Gateway side: same 5us budget as the BLP's authoritative
        // decideAndReserve (AllocationGateTest) -- screen() is a strict subset of those checks
        // (no reservation bookkeeping), so it should never be slower.
        replicas.markReady();
        replicas.recordPrice("IBM", 100_000_000L, 1_000L);
        BigDecimal limit = new BigDecimal("100.000"); // built once, outside the measured loop

        int warmup = 50_000;
        int measured = 200_000;
        for (int i = 0; i < warmup; i++) {
            replicas.screen(22214, "IBM", 100, limit, false, 2_000L);
        }

        ConcurrentHistogram histogram = new ConcurrentHistogram(60_000_000_000L, 3);
        for (int i = 0; i < measured; i++) {
            long start = System.nanoTime();
            replicas.screen(22214, "IBM", 100, limit, false, 2_000L);
            histogram.recordValue(System.nanoTime() - start);
        }

        long p99Nanos = histogram.getValueAtPercentile(99.0);
        assertTrue(p99Nanos <= 5_000L,
            "Gateway screen() p99 exceeded 5us gate (NFR-IMRG01): " + p99Nanos + "ns");
    }
}
