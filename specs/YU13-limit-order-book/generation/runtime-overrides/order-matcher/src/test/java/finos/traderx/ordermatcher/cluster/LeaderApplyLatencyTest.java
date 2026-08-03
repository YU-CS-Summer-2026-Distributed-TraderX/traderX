package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The leader split turns two different units into one µs dump: the commit segment from an epoch-ms
 * delta (ms*1000) and the apply segment from a nanoTime delta (ns/1000). A wrong conversion or a
 * missed non-positive guard would make the Phase B attribution lie, so pin both.
 */
class LeaderApplyLatencyTest {

    @Test
    void commitMsAndApplyNsBothReportMicroseconds() {
        final LeaderApplyLatency m = new LeaderApplyLatency();
        for (int i = 0; i < 500; i++) {
            m.recordCommitMillis(1_000_003L, 1_000_000L); // 3ms commit -> 3000µs
            m.recordApplyNanos(700L);                     // 700ns apply  -> 0.700µs
        }
        final String d = m.dump();
        assertEquals(3000.0, us(d, "commit", "p50"), 1.0, () -> d);
        assertEquals(0.700, us(d, "apply", "p50"), 0.05, () -> d);
        assertTrue(d.contains("segment=\"commit\"} 500"), () -> d);
    }

    @Test
    void dropsNegativeCommitAndNonPositiveApply() {
        final LeaderApplyLatency m = new LeaderApplyLatency();
        m.recordCommitMillis(1_000L, 1_000L); // 0ms -> KEPT: a same-millisecond commit is a sample
        m.recordCommitMillis(1_000L, 1_005L); // negative -> dropped
        m.recordApplyNanos(0L);               // dropped
        m.recordApplyNanos(-3L);              // dropped
        m.recordCommitMillis(1_002L, 1_000L); // 2ms kept
        assertTrue(m.dump().contains("segment=\"commit\"} 2"), () -> m.dump());
        assertTrue(m.dump().contains("segment=\"apply\"} 0"), () -> m.dump());
    }

    /**
     * LATENCY-02 regression: the shape that made the post-lowpark commit read "a flat 1000µs from p50
     * to p99.9". Simulate a TRUE commit of 200µs on the 1ms cluster clock — the ms-delta is 1 for the
     * ~20% of samples that straddle a millisecond boundary and 0 for the rest. Dropping the zeros
     * leaves nothing but 1s, so every percentile reads exactly 1000µs and the number looks like a
     * timer quantum. Keeping them makes the MEAN recover the true 200µs.
     */
    @Test
    void subMillisecondCommitIsNotAFlatMillisecond() {
        final LeaderApplyLatency m = new LeaderApplyLatency();
        for (int i = 0; i < 1000; i++) {
            m.recordCommitMillis(i < 200 ? 1_001L : 1_000L, 1_000L); // 200 straddle, 800 same-ms
        }
        final String d = m.dump();
        assertEquals(200.0, us(d, "commit", "mean"), 1.0, () -> d);   // the truth, recovered
        assertEquals(0.0, us(d, "commit", "p50"), 0.001, () -> d);    // NOT a flat millisecond
        assertTrue(d.contains("segment=\"commit\"} 1000"), () -> d);  // nothing censored
    }

    @Test
    void nanosCommitNeedsNoQuantum() {
        final LeaderApplyLatency m = new LeaderApplyLatency();
        for (int i = 0; i < 1000; i++) {
            m.recordCommitNanos(180_000L + i * 100L); // 180–280µs, a real distribution
        }
        final String d = m.dump();
        assertEquals(230.0, us(d, "commit", "mean"), 2.0, () -> d);
        assertEquals(230.0, us(d, "commit", "p50"), 2.0, () -> d);
        assertTrue(us(d, "commit", "p999") > us(d, "commit", "p50"), () -> d); // a tail exists
    }

    private static double us(final String dump, final String seg, final String pct) {
        final String key = "segment=\"" + seg + "\",pct=\"" + pct + "\"} ";
        final int i = dump.indexOf(key);
        assertTrue(i >= 0, () -> "missing " + key + " in:\n" + dump);
        final int start = i + key.length();
        return Double.parseDouble(dump.substring(start, dump.indexOf('\n', start)).trim());
    }
}
