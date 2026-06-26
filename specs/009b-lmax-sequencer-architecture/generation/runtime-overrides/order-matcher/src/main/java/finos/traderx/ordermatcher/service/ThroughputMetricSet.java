package finos.traderx.ordermatcher.service;

/**
 * Rolling throughput gauge over sampled cumulative counters.
 */
public final class ThroughputMetricSet {
    private long lastTotal = -1;
    private long lastSampleNanos;
    private volatile long currentPerSecond;
    private volatile long peakPerSecond;

    public void reset(long total, long nowNanos) {
        lastTotal = total;
        lastSampleNanos = nowNanos;
        currentPerSecond = 0;
        peakPerSecond = 0;
    }

    public void observe(long total, long nowNanos) {
        if (lastTotal < 0 || lastSampleNanos == 0L) {
            reset(total, nowNanos);
            return;
        }
        long deltaTotal = Math.max(0L, total - lastTotal);
        long elapsedNanos = nowNanos - lastSampleNanos;
        if (elapsedNanos <= 0L) {
            return;
        }
        long rate = deltaTotal * 1_000_000_000L / elapsedNanos;
        currentPerSecond = rate;
        if (rate > peakPerSecond) {
            peakPerSecond = rate;
        }
        lastTotal = total;
        lastSampleNanos = nowNanos;
    }

    public void clearCurrent() {
        currentPerSecond = 0L;
    }

    public long currentPerSecond() {
        return currentPerSecond;
    }

    public long peakPerSecond() {
        return peakPerSecond;
    }
}
