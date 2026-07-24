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

    /** commit round-trip: leader epoch-millis at apply minus the sequencing cluster timestamp (ms).
     *  Stored as microseconds so the dump is one unit; 1ms resolution. Negatives/zero dropped. */
    void recordCommitMillis(final long applyMillis, final long sequencedMillis) {
        final long ms = applyMillis - sequencedMillis;
        if (ms > 0 && ms < 60_000) {
            commit.recordValue(ms * 1_000_000L); // ms -> ns, so dump()'s ns/1000 yields µs
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
