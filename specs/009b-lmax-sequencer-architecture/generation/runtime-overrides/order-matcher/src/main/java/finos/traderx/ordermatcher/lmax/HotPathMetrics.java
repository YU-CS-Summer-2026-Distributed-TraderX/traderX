package finos.traderx.ordermatcher.lmax;

import org.HdrHistogram.ConcurrentHistogram;

/**
 * HdrHistogram-backed latency telemetry for the hot path (NFR-09B01/NFR-09B08). Latencies
 * are recorded in nanoseconds and rendered to Prometheus histogram families with fixed
 * bucket edges at scrape time — full distributions, never means only.
 */
public final class HotPathMetrics {
    private static final long HIGHEST_TRACKABLE_NS = 60_000_000_000L; // 60s

    private final ConcurrentHistogram journalNs = new ConcurrentHistogram(HIGHEST_TRACKABLE_NS, 3);
    private final ConcurrentHistogram blpEventNs = new ConcurrentHistogram(HIGHEST_TRACKABLE_NS, 3);
    private final ConcurrentHistogram matchNs = new ConcurrentHistogram(HIGHEST_TRACKABLE_NS, 3);
    private final ConcurrentHistogram egressNs = new ConcurrentHistogram(HIGHEST_TRACKABLE_NS, 3);

    public void recordJournalLatency(long nanos) {
        record(journalNs, nanos);
    }

    public void recordBlpEventLatency(long nanos) {
        record(blpEventNs, nanos);
    }

    /** Order-eligible-to-fill latency: state 009's match-latency family, now a real measurement. */
    public void recordMatchLatency(long nanos) {
        record(matchNs, nanos);
    }

    public void recordEgressLatency(long nanos) {
        record(egressNs, nanos);
    }

    private void record(ConcurrentHistogram histogram, long nanos) {
        if (nanos < 0) {
            return;
        }
        histogram.recordValue(Math.min(nanos, HIGHEST_TRACKABLE_NS));
    }

    /** Render a Prometheus cumulative histogram from an HdrHistogram in nanoseconds. */
    public static void renderHistogram(StringBuilder sb, String name, String help,
                                       ConcurrentHistogram histogram, double[] bucketSeconds) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" histogram\n");
        long total = histogram.getTotalCount();
        for (double edge : bucketSeconds) {
            long upperNs = (long) (edge * 1_000_000_000.0);
            long count = total == 0 ? 0 : histogram.getCountBetweenValues(0, Math.min(upperNs, HIGHEST_TRACKABLE_NS));
            sb.append(name).append("_bucket{le=\"").append(trimEdge(edge)).append("\"} ").append(count).append('\n');
        }
        sb.append(name).append("_bucket{le=\"+Inf\"} ").append(total).append('\n');
        double sumSeconds = total == 0 ? 0.0 : histogram.getMean() * total / 1_000_000_000.0;
        sb.append(name).append("_sum ").append(sumSeconds).append('\n');
        sb.append(name).append("_count ").append(total).append('\n');
    }

    private static String trimEdge(double edge) {
        if (edge == Math.floor(edge)) {
            return String.valueOf((long) edge);
        }
        return String.valueOf(edge);
    }

    public ConcurrentHistogram journalHistogram() {
        return journalNs;
    }

    public ConcurrentHistogram blpEventHistogram() {
        return blpEventNs;
    }

    public ConcurrentHistogram matchHistogram() {
        return matchNs;
    }

    public ConcurrentHistogram egressHistogram() {
        return egressNs;
    }
}
