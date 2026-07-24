package finos.traderx.ordermatcher.cluster;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * LATENCY-01, Phase B: side-channel split of the gateway's "cluster black box" measured on the
 * LEADER's clock, so the dominant hop from Phase A (the 3.4ms black box = 95% of the gateway's
 * residence) can be attributed to sequence/consensus-commit vs apply — the split that decides
 * whether the cheapest lever is config (idle strategy / core pinning), placement (RDMA/DPDK), or the
 * architectural consensus-model redesign. Like Phase A this is a METRIC: it never touches the
 * replicated log or an OutputEvent (only {@code onSessionMessage} reads already-present values), so
 * it is not a determinism change.
 *
 * <p><b>Single-clock rule.</b> Both segments start and end on the SAME leader host:
 * <pre>
 *   commit   t_sequenced -&gt; t_applied   {@code System.currentTimeMillis()} at apply MINUS the
 *                                        {@code onSessionMessage} cluster {@code timestamp} (the
 *                                        ConsensusModule's epoch-millis clock, stamped when the entry
 *                                        was sequenced/appended). Both epoch-millis on the leader, so
 *                                        the subtraction is valid. This is the consensus commit
 *                                        round-trip (sequenced -&gt; replicated -&gt; quorum ack -&gt; committed
 *                                        -&gt; delivered to the service). Resolution is 1ms — coarse, but
 *                                        the black box is ~3.4ms, so it resolves "consensus dominates"
 *                                        vs "consensus is ~0" decisively.
 *   apply    t_applied   -&gt; t_emitted   {@code System.nanoTime()} span around {@code engine.onEvent}
 *                                        + {@code drainOutputs} — the match/apply + OutputEvent emit.
 * </pre>
 * The leader-internal total (commit + apply) subtracted from the gateway's black box leaves the Aeron
 * transport both ways (ingress-in + egress-out) + the ingress-arrival&rarr;sequenced bit — a single
 * residual number instead of a buried one.
 *
 * <p>Created only when {@code LATENCY_DECOMP=1}; else null (zero overhead). Recorded on the apply
 * agent thread (single writer), but {@link ConcurrentHistogram} is used for a consistent copy under
 * the /latency reader. Read the LEADER member's {@code /latency} — followers apply the same log but
 * their commit-to-apply delay is not the gateway's round-trip.
 */
public final class LeaderApplyLatency {

    private static final long LOWEST_NS = 1L;
    private static final long HIGHEST_NS = 60_000_000_000L;
    private static final int SIG_DIGITS = 3;

    private final ConcurrentHistogram commit = newHist(); // ns (from ms*1e6); dump() reports µs
    private final ConcurrentHistogram apply = newHist();   // ns

    LeaderApplyLatency() { // package-private: env factory + unit test
    }

    static LeaderApplyLatency fromEnvOrNull() {
        final String on = System.getenv("LATENCY_DECOMP");
        if (on == null || on.isEmpty() || on.equals("0")) {
            return null;
        }
        return new LeaderApplyLatency();
    }

    private static ConcurrentHistogram newHist() {
        return new ConcurrentHistogram(LOWEST_NS, HIGHEST_NS, SIG_DIGITS);
    }

    /**
     * commit round-trip on the MILLISECOND cluster clock: leader epoch-millis at apply minus the
     * sequencing cluster timestamp. 1ms quantum.
     *
     * <p><b>LATENCY-02 fix — zero is a sample, not a miss.</b> This used to drop {@code ms == 0},
     * i.e. every commit that finished inside the same millisecond it was sequenced in. Once
     * {@code lowpark} pulled the true commit under 1ms, that filter censored the fast majority and
     * left only the samples that happened to straddle a millisecond boundary — all of which read
     * exactly 1. The result was a histogram that was "a flat 1000µs from p50 to p99.9", which looked
     * like a timer quantum and was in fact the filter. Recording the zeros makes the mean an unbiased
     * estimate of the true commit (ms-truncated differencing is unbiased under uniform phase) and
     * makes the censorship self-evident: commit count now equals apply count.
     *
     * <p>Still only a 1ms quantum per sample — for the real distribution use {@code CLUSTER_CLOCK=nanos}
     * and {@link #recordCommitNanos(long)}.
     */
    void recordCommitMillis(final long applyMillis, final long sequencedMillis) {
        final long ms = applyMillis - sequencedMillis;
        if (ms >= 0 && ms < 60_000) {
            commit.recordValue(ms * 1_000_000L); // ms -> ns, so dump()'s ns/1000 yields µs
        }
    }

    /** commit round-trip on the NANOSECOND cluster clock ({@code CLUSTER_CLOCK=nanos}): the real
     *  distribution, no quantum. Both ends are {@code HighResolutionClock.epochNanos()} on the leader. */
    void recordCommitNanos(final long ns) {
        if (ns >= 0 && ns <= HIGHEST_NS) {
            commit.recordValue(ns);
        }
    }

    /** apply/match + emit span, nanoseconds. */
    void recordApplyNanos(final long ns) {
        if (ns > 0 && ns <= HIGHEST_NS) {
            apply.recordValue(ns);
        }
    }

    void reset() {
        commit.reset();
        apply.reset();
    }

    /** Prometheus-ish dump in MICROSECONDS, mirroring the gateway /latency shape. */
    String dump() {
        final StringBuilder sb = new StringBuilder(1024);
        appendSegment(sb, "commit", commit);
        appendSegment(sb, "apply", apply);
        return sb.toString();
    }

    private static void appendSegment(final StringBuilder sb, final String seg, final ConcurrentHistogram h) {
        final Histogram snap = h.copy();
        // The mean is load-bearing on the ms clock: individual samples are quantized to 0/1/2ms, but
        // ms-truncated differencing is unbiased, so the mean recovers the true commit even when every
        // percentile reads a whole millisecond (LATENCY-02).
        line(sb, seg, "mean", (long) snap.getMean());
        line(sb, seg, "p50", snap.getValueAtPercentile(50.0));
        line(sb, seg, "p99", snap.getValueAtPercentile(99.0));
        line(sb, seg, "p999", snap.getValueAtPercentile(99.9));
        line(sb, seg, "max", snap.getMaxValue());
        sb.append("traderx_leader_latency_count{segment=\"").append(seg).append("\"} ")
            .append(snap.getTotalCount()).append('\n');
    }

    private static void line(final StringBuilder sb, final String seg, final String pct, final long ns) {
        sb.append("traderx_leader_latency_us{segment=\"").append(seg).append("\",pct=\"").append(pct)
            .append("\"} ").append(String.format("%.3f", ns / 1000.0)).append('\n');
    }
}
