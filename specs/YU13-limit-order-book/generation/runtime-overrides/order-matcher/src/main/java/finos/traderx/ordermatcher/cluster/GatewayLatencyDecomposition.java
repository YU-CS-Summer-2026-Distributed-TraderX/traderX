package finos.traderx.ordermatcher.cluster;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.util.concurrent.ThreadLocalRandom;

/**
 * LATENCY-01, Phase A: side-channel decomposition of a binary order's residence time inside ONE
 * gateway, split into the four hops the brief names. It is a METRIC — like the {@code /proc} profiling
 * used in the throughput campaign — and touches NOTHING on the replicated path: no nanoTime read enters
 * the sequenced log or an OutputEvent, so it is not a determinism change (no member roll, no format bump).
 *
 * <p><b>The single-clock rule.</b> Client, gateway and the three members are different hosts whose wall
 * clocks differ by milliseconds — the same order of magnitude as the thing being measured — so a
 * timestamp taken on one host may never be subtracted from one taken on another. Every segment here
 * starts and ends on the SAME gateway JVM's {@link System#nanoTime()} (one monotonic source), even when
 * the two reads happen on different <i>threads</i> of that JVM (connection thread vs the single cluster
 * owner thread): same clock, so the subtraction is valid. Cross-host segments (client&harr;gateway wire,
 * Aeron transport) are never measured here — they are derived by subtracting these single-clock
 * intervals from an independently measured envelope (client RTT, or Phase B's leader-internal total).
 *
 * <p><b>The five segments</b> (all gateway-clock), per the brief's t_recv/t_decoded/t_offer/t_egress/t_reply:
 * <pre>
 *   decode   t_recv   -&gt; t_decoded   bytes-in to just-before-submit (flyweight decode; binary NEW
 *                                     carries a pre-resolved securityId so there is no symbol lookup)
 *   queue    t_decoded-&gt; t_offer     owner-thread queue wait: submit-thread enqueue to the owner
 *                                     actually offering into the log (the pipelining FIFO)
 *   cluster  t_offer  -&gt; t_egress    THE BLACK BOX: ingress-out + sequence + consensus commit + apply +
 *                                     egress-back, measured with one clock (owner thread both ends)
 *   reply    t_egress -&gt; t_reply     ack encode + buffered socket write + flush
 *   total    t_recv   -&gt; t_reply     the gateway's whole residence time; the sum-check anchor
 * </pre>
 * At large sample counts, {@code median(total) &asymp; median(decode)+median(queue)+median(cluster)+median(reply)}
 * (medians are additive; the tiny residual is future-wakeup slack between the owner completing an order
 * and the submit thread returning). Percentiles do NOT sum in general — the tail rows are marginal
 * distributions, and {@code total} is the authoritative wire-to-wire-minus-client-wire number.
 *
 * <p><b>Sampling</b> is per-order via {@link ThreadLocalRandom} (no shared counter &rarr; no cache-line
 * contention on the hot path, so the observer effect stays a nanoTime read on the sampled fraction only).
 * The mask is {@code LATENCY_SAMPLE_MASK} (default 127 = 1/128); set 0 to record every order for the
 * best tails, or a larger power-of-two-minus-one to thin further. The whole facility is created only
 * when {@code LATENCY_DECOMP=1}; otherwise the gateway holds a null reference and pays nothing — which
 * is also the "without instrumentation" arm of the mandated observer-effect check.
 *
 * <p>All six writers (many connection threads on decode/reply/total, the single owner thread on
 * queue/cluster) record concurrently, so every segment is a {@link ConcurrentHistogram} (lock-free
 * concurrent record + a consistent {@link ConcurrentHistogram#copy() copy} for the /latency reader).
 */
public final class GatewayLatencyDecomposition {

    // 1ns .. 60s, 3 significant figures — fine enough for a sub-µs decode and a multi-ms cluster hop
    // in the same run, a few hundred KB each.
    private static final long LOWEST_NS = 1L;
    private static final long HIGHEST_NS = 60_000_000_000L;
    private static final int SIG_DIGITS = 3;

    private final ConcurrentHistogram decode = newHist();
    private final ConcurrentHistogram queue = newHist();
    private final ConcurrentHistogram cluster = newHist();
    private final ConcurrentHistogram reply = newHist();
    private final ConcurrentHistogram total = newHist();

    private final int sampleMask;

    GatewayLatencyDecomposition(final int sampleMask) { // package-private: env factory + unit test
        this.sampleMask = sampleMask;
    }

    /** Build from env, or null when {@code LATENCY_DECOMP} is unset/0 (the zero-overhead / observer-off arm). */
    static GatewayLatencyDecomposition fromEnvOrNull() {
        final String on = System.getenv("LATENCY_DECOMP");
        if (on == null || on.isEmpty() || on.equals("0")) {
            return null;
        }
        final String maskEnv = System.getenv("LATENCY_SAMPLE_MASK");
        int mask = 127;
        if (maskEnv != null && !maskEnv.isEmpty()) {
            mask = Integer.parseInt(maskEnv.trim());
        }
        return new GatewayLatencyDecomposition(mask);
    }

    private static ConcurrentHistogram newHist() {
        return new ConcurrentHistogram(LOWEST_NS, HIGHEST_NS, SIG_DIGITS);
    }

    /** True for the sampled fraction of orders. Called at most once per order per side (acceptor,
     *  gateway) and the boolean reused for that order's segments, so a sampled order is coherent. */
    boolean sample() {
        return (ThreadLocalRandom.current().nextInt() & sampleMask) == 0;
    }

    void recordDecode(final long ns) {
        record(decode, ns);
    }

    void recordQueue(final long ns) {
        record(queue, ns);
    }

    void recordCluster(final long ns) {
        record(cluster, ns);
    }

    void recordReply(final long ns) {
        record(reply, ns);
    }

    void recordTotal(final long ns) {
        record(total, ns);
    }

    private static void record(final ConcurrentHistogram h, final long ns) {
        // nanoTime is monotonic per JVM but a paused thread can still yield 0 or a negative delta on
        // clamp; drop those rather than let HdrHistogram throw on a hot path.
        if (ns > 0 && ns <= HIGHEST_NS) {
            h.recordValue(ns);
        }
    }

    /** Zero every segment — call after warmup so the reported window is warm-JIT only. */
    void reset() {
        decode.reset();
        queue.reset();
        cluster.reset();
        reply.reset();
        total.reset();
    }

    /** Prometheus-ish text dump: one line per (segment, percentile) in MICROSECONDS, plus per-segment
     *  count. Read once at the end of the measured window; {@link ConcurrentHistogram#copy()} gives a
     *  point-in-time snapshot safe to read while recorders keep writing. */
    String dump() {
        final StringBuilder sb = new StringBuilder(2048);
        appendSegment(sb, "decode", decode);
        appendSegment(sb, "queue", queue);
        appendSegment(sb, "cluster", cluster);
        appendSegment(sb, "reply", reply);
        appendSegment(sb, "total", total);
        sb.append("traderx_gateway_latency_sample_mask ").append(sampleMask).append('\n');
        return sb.toString();
    }

    private static void appendSegment(final StringBuilder sb, final String seg, final ConcurrentHistogram h) {
        final Histogram snap = h.copy();
        line(sb, seg, "p50", snap.getValueAtPercentile(50.0));
        line(sb, seg, "p99", snap.getValueAtPercentile(99.0));
        line(sb, seg, "p999", snap.getValueAtPercentile(99.9));
        line(sb, seg, "max", snap.getMaxValue());
        sb.append("traderx_gateway_latency_count{segment=\"").append(seg).append("\"} ")
            .append(snap.getTotalCount()).append('\n');
    }

    private static void line(final StringBuilder sb, final String seg, final String pct, final long ns) {
        // report microseconds (the campaign talks in µs/ms); keep 3 decimals so a sub-µs decode is visible
        sb.append("traderx_gateway_latency_us{segment=\"").append(seg).append("\",pct=\"").append(pct)
            .append("\"} ").append(String.format("%.3f", ns / 1000.0)).append('\n');
    }
}
