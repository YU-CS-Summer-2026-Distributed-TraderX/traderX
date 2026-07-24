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
    void dropsNonPositiveCommitAndApply() {
        final LeaderApplyLatency m = new LeaderApplyLatency();
        m.recordCommitMillis(1_000L, 1_000L); // 0ms -> dropped
        m.recordCommitMillis(1_000L, 1_005L); // negative -> dropped
        m.recordApplyNanos(0L);               // dropped
        m.recordApplyNanos(-3L);              // dropped
        m.recordCommitMillis(1_002L, 1_000L); // 2ms kept
        assertTrue(m.dump().contains("segment=\"commit\"} 1"), () -> m.dump());
        assertTrue(m.dump().contains("segment=\"apply\"} 0"), () -> m.dump());
    }

    private static double us(final String dump, final String seg, final String pct) {
        final String key = "segment=\"" + seg + "\",pct=\"" + pct + "\"} ";
        final int i = dump.indexOf(key);
        assertTrue(i >= 0, () -> "missing " + key + " in:\n" + dump);
        final int start = i + key.length();
        return Double.parseDouble(dump.substring(start, dump.indexOf('\n', start)).trim());
    }
}
