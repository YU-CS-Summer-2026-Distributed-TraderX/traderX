package finos.traderx.ordermatcher.cluster;

import io.aeron.cluster.service.ClusterClock;
import org.agrona.concurrent.OffsetEpochNanoClock;

import java.util.concurrent.TimeUnit;

/**
 * Nanosecond cluster clock for the LATENCY-02 decomposition ({@code CLUSTER_CLOCK=nanos}).
 *
 * <p>Aeron ships {@link io.aeron.cluster.NanosecondClusterClock}, but it reads the wall clock via
 * {@code HighResolutionClock.epochNanos()}, which calls {@code Instant.now()} and therefore
 * <b>allocates once per sequenced message</b> — on the ConsensusModule's hot path, and again on the
 * apply thread at the far end of the interval. The no-GC allocation gate (NGC-01) catches the second
 * one. Allocating in order to measure latency is self-defeating: the GC jitter lands in the very
 * histogram the run exists to produce.
 *
 * <p>{@link OffsetEpochNanoClock} instead holds an offset between the wall clock and
 * {@code System.nanoTime()} and adds, so a reading is arithmetic and allocation-free (it re-samples
 * hourly by default, which does allocate — off the measurement's timescale).
 *
 * <p>The instance is <b>static and shared on purpose</b>. Both ends of the commit interval — the
 * sequencing timestamp the ConsensusModule stamps, and the read taken at apply — resolve to this one
 * object, in one JVM. They cannot disagree by a calibration offset, because there is only one
 * calibration. That is what makes the subtraction a single-clock interval rather than a difference
 * of two clocks that merely claim the same epoch.
 */
public final class NanosClusterClock implements ClusterClock {

    private static final OffsetEpochNanoClock CLOCK = new OffsetEpochNanoClock();

    /** The same reading the cluster stamps into the log — use this at the far end of an interval. */
    public static long epochNanos() {
        return CLOCK.nanoTime();
    }

    public TimeUnit timeUnit() {
        return TimeUnit.NANOSECONDS;
    }

    public long time() {
        return CLOCK.nanoTime();
    }

    public long timeMillis() {
        return CLOCK.nanoTime() / 1_000_000L;
    }

    public long timeMicros() {
        return CLOCK.nanoTime() / 1_000L;
    }

    public long timeNanos() {
        return CLOCK.nanoTime();
    }

    public long convertToNanos(final long time) {
        return time;
    }
}
