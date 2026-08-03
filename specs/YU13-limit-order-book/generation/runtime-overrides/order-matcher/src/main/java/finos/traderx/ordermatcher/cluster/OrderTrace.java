package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;

/**
 * OTEL-01: trace identity and the head-sampling decision, DERIVED from a field the log already
 * carries rather than propagated through it.
 *
 * <h2>The consensus-boundary problem</h2>
 * A trace has to span gateway &rarr; sequence &rarr; consensus commit &rarr; apply &rarr; egress, but the gateway
 * and the members are different processes joined only by the replicated log. The obvious fix — put a
 * {@code traceparent} in the sequenced message — is the one thing we may not do: bytes in the log are
 * replicated state, so adding them is a schema change, a member roll, and a permanent determinism
 * risk taken on behalf of a debugging feature. (It is also a correctness hazard: a resend with a new
 * trace id would no longer be byte-identical to the original, so replay would stop reproducing.)
 *
 * <p><b>What we do instead: derive, don't carry.</b> Every order already carries a client idempotency
 * key through the log — {@code InputEvent.priceTicks}, set by the gateway from the client's ClOrdID
 * and read by the engine for duplicate suppression. It is business data that is already replicated,
 * already unique per order, and already identical on every member and on replay. Both sides run the
 * SAME pure function over it:
 * <pre>
 *   traceId       = splitmix64(key), splitmix64(key ^ TRACE_SALT)   (128-bit)
 *   sampled?      = (splitmix64(key ^ SAMPLE_SALT) &amp; mask) == 0
 *   clusterSpanId = splitmix64(key ^ CLUSTER_SALT)                  (the gateway's black-box span)
 * </pre>
 * so the member independently arrives at the same trace id, the same parent span id, and the same
 * sampling verdict as the gateway did — with <b>zero bytes added to the log, zero schema change, and
 * nothing new for the state machine to read.</b> Sampling is decided at ingress in the sense that
 * matters (it is a property of the order, fixed before it is offered); it simply needs no carriage.
 *
 * <p>The same argument extends one step further, and it is what makes error sampling possible here
 * at all: a REJECTED order is traced whatever the head verdict said ({@link #escalate}), because
 * "was it rejected" is likewise a committed, deterministic fact both sides read off the same ack —
 * so both sides escalate together and the trace stays whole. And because the trace id is derived
 * rather than generated, a LOG LINE can join the trace by computing it ({@link #traceIdHex})
 * instead of being handed it.
 *
 * <p><b>Why this is not "telemetry in replicated state".</b> The derivation is one-way and read-only:
 * it consumes a committed field and produces an id that is never written back, never encoded into an
 * {@code OutputEvent}, and never branched on by the engine. Delete this class and every member emits
 * byte-identical output. The relationship is exactly the one {@code GatewayLatencyDecomposition} and
 * {@link LeaderApplyLatency} already have with the apply path — a side effect, not an input.
 *
 * <h2>Key selection</h2>
 * {@code clientKey} is non-zero for NEW and REPLACE (the gateway hashes the ClOrdID, or the binary
 * path supplies a numeric key). CANCEL carries no client key but does carry the target
 * {@code orderRef}, which the member preserves unchanged — only NEW has its ref overwritten by the
 * sequenced generator. So both sides can agree on the key with the same two-line rule
 * ({@link #keyOf}), evaluated on data both sides see identically.
 *
 * <h2>Clock skew across hosts</h2>
 * The single-clock rule of LATENCY-01 still binds: a span's DURATION is always measured start-to-end
 * on one host's {@link System#nanoTime()} and is exact. Span START times, however, must be absolute
 * for OTLP, so each process anchors its monotonic clock to wall time once at startup
 * ({@link #epochNanos}). Gateway and member wall clocks differ by up to a few milliseconds, so a
 * child span may render slightly offset from its parent in the trace viewer. That is inherent to
 * distributed tracing and is why the µs-accurate numbers on the dashboard come from the histograms
 * ({@code /latency}), with traces used for structure and causality. Durations in the trace are
 * trustworthy; cross-host absolute offsets are not.
 */
public final class OrderTrace {

    // Distinct salts so trace id, span id and the sampling verdict are independent draws from the
    // same key — otherwise the low bits that pick the sample would also be the low bits of the id.
    private static final long TRACE_SALT = 0x5851F42D4C957F2DL;
    private static final long CLUSTER_SALT = 0x14057B7EF767814FL;
    private static final long SAMPLE_SALT = 0x2545F4914F6CDD1DL;

    /** Wall-clock anchor for this JVM: epochNanos = ANCHOR + nanoTime(). Read once; the pair is
     *  taken as close together as we can, and only the anchor's few-µs error is inherited. */
    private static final long ANCHOR = System.currentTimeMillis() * 1_000_000L - System.nanoTime();

    private OrderTrace() {
    }

    /** Monotonic {@code nanoTime} reading rendered as unix epoch nanos, which is what OTLP wants. */
    static long epochNanos(final long nanoTime) {
        return ANCHOR + nanoTime;
    }

    /**
     * The correlation key both sides derive from. NEW/REPLACE use the client idempotency key;
     * CANCEL (no client key) falls back to its target orderRef, which survives the log unchanged.
     * Returns 0 when neither is available — such an order is simply never sampled.
     */
    static long keyOf(final long clientKey, final int orderRef) {
        if (clientKey != 0L) {
            return clientKey;
        }
        return orderRef > 0 ? mix(orderRef) : 0L;
    }

    /** Head-sampling verdict: a deterministic property of the order, so gateway and member agree
     *  without exchanging anything. {@code mask} is a power-of-two-minus-one (127 = 1 in 128);
     *  0 traces every order. */
    static boolean sampled(final long key, final int mask) {
        return key != 0L && (mix(key ^ SAMPLE_SALT) & mask) == 0L;
    }

    /**
     * OUTCOME ESCALATION — trace this order regardless of {@link #sampled}, because of how it
     * turned out. The second half of the sampling story, and the reason there is no collector-side
     * tail_sampling processor here.
     *
     * <h2>Why not a tail processor</h2>
     * A tail sampler can only choose among spans it RECEIVED. The head verdict above is decided
     * before the order is offered, and an unsampled order emits nothing at all — no span leaves
     * either process, so there is nothing at the collector to keep. Pointing a
     * {@code tail_sampling} processor at this pipeline would only re-filter the 1-in-128 that was
     * already sampled: it would look like error-sampling and would never surface a single reject
     * that head sampling had dropped. The only way to have the rejects is to decide at the head.
     *
     * <h2>Why this can be decided at the head without carriage</h2>
     * "How it turned out" is the committed ack kind, and that is not a wall-clock or host-local
     * fact — it is the deterministic output of the replicated state machine. The member produces
     * it; the gateway reads the very same byte off the egress ack. So both sides evaluate this
     * predicate on identical input and reach the same verdict, exactly as they do for
     * {@link #sampled} — no traceparent, no new field, nothing added to the log. The emission
     * points on both sides sit AFTER the decision is known (the gateway closes its spans on the
     * committed ack; the member emits after apply and drain), so escalating costs no ordering
     * change, only the timestamps that must now be recorded for every order rather than the
     * sampled fraction.
     *
     * <h2>What deliberately cannot be escalated: "slow"</h2>
     * A slow order is the other thing a supporter wants, and it is out of reach BY THE SAME RULE
     * that makes rejects reachable. Latency is per-host and non-deterministic: the gateway's view
     * of slow (client residence) and the leader's view (apply duration) are different numbers on
     * different clocks, and members do not agree with each other either. Escalating on it would
     * have one side emit and the other not — a half-trace, which is worse than no trace. The
     * µs-accurate tail belongs to the {@code /latency} histograms, which is where this design has
     * always said to look for timing.
     *
     * @param ackKind the committed order-lifecycle ack kind ({@code OutputEvent.KIND_*}); 0 when
     *                no direct ack was produced, which never escalates.
     */
    static boolean escalate(final byte ackKind) {
        return ackKind == OutputEvent.KIND_ORDER_REJECTED
            || ackKind == OutputEvent.KIND_ORDER_NOT_FOUND;
    }

    /**
     * The 32-char lower-case hex W3C trace id, for a LOG LINE to carry — the whole of the
     * log/trace correlation story.
     *
     * <p>Because the id is DERIVED from the idempotency key, a log line does not need to be handed
     * trace context by anything: any code holding the key can compute the identical id the spans
     * were emitted under. There is no MDC to populate, no log pattern to extend and no extra field
     * to thread through — which matters here because this tier has no logging framework at all, it
     * writes to {@code System.out}.
     *
     * <p>Allocates a String, so it is for cold paths only — the reject line, not the order path.
     */
    static String traceIdHex(final long key) {
        final char[] out = new char[32];
        hex16(traceIdHi(key), out, 0);
        hex16(traceIdLo(key), out, 16);
        return new String(out);
    }

    private static void hex16(final long v, final char[] out, final int offset) {
        for (int i = 0; i < 16; i++) {
            out[offset + i] = Character.forDigit((int) ((v >>> (60 - (i * 4))) & 0xF), 16);
        }
    }

    /** High 64 bits of the W3C trace id. */
    static long traceIdHi(final long key) {
        return nonZero(mix(key));
    }

    /** Low 64 bits of the W3C trace id. */
    static long traceIdLo(final long key) {
        return nonZero(mix(key ^ TRACE_SALT));
    }

    /**
     * The gateway's "cluster black box" span id — the one span both sides can name without talking.
     * The gateway emits it as the span covering offer&rarr;committed-ack; the member emits its commit and
     * apply spans as children of it. That is the whole consensus-boundary join.
     */
    static long clusterSpanId(final long key) {
        return nonZero(mix(key ^ CLUSTER_SALT));
    }

    /** Span id for any other span in this order's trace, distinguished by an ordinal. */
    static long spanId(final long key, final int ordinal) {
        return nonZero(mix(key + (0x9E3779B97F4A7C15L * (ordinal + 1))));
    }

    /** splitmix64 finalizer — a good avalanche in a handful of ALU ops, no allocation, no state. */
    static long mix(final long input) {
        long z = input + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** W3C forbids an all-zero trace or span id; the odds are 2^-64 but the check is one branch. */
    private static long nonZero(final long v) {
        return v == 0L ? 1L : v;
    }
}
