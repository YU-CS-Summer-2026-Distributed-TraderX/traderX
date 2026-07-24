package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one non-trivial thing this holder does is turn nanosecond deltas into a percentile dump in
 * MICROSECONDS with the segments the decomposition table expects — a broken unit conversion or a
 * dropped segment would make the whole deliverable lie. This proves the record→dump path, the
 * non-positive-delta guard, reset(), and that a mask=0 samples every order.
 */
class GatewayLatencyDecompositionTest {

    @Test
    void dumpReportsPercentilesInMicroseconds() {
        final GatewayLatencyDecomposition m = new GatewayLatencyDecomposition(0);
        // 1000 orders each: decode 1µs, cluster 4ms — the shape we expect (cluster dominates).
        for (int i = 0; i < 1000; i++) {
            m.recordDecode(1_000);       // 1µs
            m.recordCluster(4_000_000);  // 4ms
        }
        final String dump = m.dump();
        // decode ~1µs, cluster ~4000µs — right unit (µs), right magnitude. HdrHistogram reports the
        // bucket's top value (3 sig figs), so range-check rather than match an exact string.
        assertEquals(1.0, us(dump, "decode", "p50"), 0.05, () -> "decode p50 ~1µs:\n" + dump);
        assertEquals(4000.0, us(dump, "cluster", "p50"), 50.0, () -> "cluster p50 ~4000µs:\n" + dump);
        assertTrue(dump.contains("segment=\"cluster\"} 1000"), () -> "cluster count == 1000:\n" + dump);
    }

    /** Pull the µs value out of a {@code segment=...,pct=...} N} dump line. */
    private static double us(final String dump, final String seg, final String pct) {
        final String key = "segment=\"" + seg + "\",pct=\"" + pct + "\"} ";
        final int i = dump.indexOf(key);
        assertTrue(i >= 0, () -> "missing " + key + " in:\n" + dump);
        final int start = i + key.length();
        return Double.parseDouble(dump.substring(start, dump.indexOf('\n', start)).trim());
    }

    @Test
    void dropsNonPositiveDeltasAndResetsClean() {
        final GatewayLatencyDecomposition m = new GatewayLatencyDecomposition(0);
        m.recordQueue(0);   // clock clamp — must be dropped, not thrown
        m.recordQueue(-5);  // paused-thread negative delta — dropped
        m.recordQueue(500);
        assertTrue(m.dump().contains("segment=\"queue\"} 1"), "only the one positive sample counts");
        m.reset();
        assertTrue(m.dump().contains("segment=\"queue\"} 0"), "reset zeros the histograms");
    }

    @Test
    void maskZeroSamplesEveryOrder() {
        final GatewayLatencyDecomposition m = new GatewayLatencyDecomposition(0);
        for (int i = 0; i < 64; i++) {
            assertTrue(m.sample(), "mask 0 => (x & 0)==0 always true");
        }
    }

    @Test
    void envFactoryOffByDefault() {
        // LATENCY_DECOMP unset in the test JVM => the whole facility is null (zero-overhead arm).
        assertEquals(null, GatewayLatencyDecomposition.fromEnvOrNull());
        assertFalse(false); // marker: the "without instrumentation" observer-effect arm is just this null
    }
}
