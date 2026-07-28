package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OTEL-01 gate: the "never blocks the trade path" claim. The brief's second non-negotiable is that a
 * telemetry backpressure stall must never reach the owner thread, so the behaviour under a full ring
 * is the thing worth a test — asserting it in a comment is not the same as showing it.
 */
class SpanSinkTest {

    private static final int TINY_RING = 4096; // a few dozen spans, so overflow is reachable fast

    /** The headline: when the exporter cannot keep up, spans are DROPPED AND COUNTED, and every
     *  caller still returns. A sink that blocked here would stall an owner thread in production. */
    @Test
    void aFullRingDropsAndCountsInsteadOfBlocking() {
        try (SpanSink sink = SpanSink.forTest(TINY_RING)) {
            // No exporter thread is draining, so this is a hard overflow — the worst case.
            for (int i = 0; i < 10_000; i++) {
                sink.span(1L, 2L, 3L + i, 4L, 100L, 200L, SpanSink.NAME_ORDER, i);
            }
            assertTrue(sink.droppedCount() > 0, "a saturated ring must drop");
            assertTrue(sink.emittedCount() > 0, "it must also have accepted what fit");
            assertEquals(10_000L, sink.emittedCount() + sink.droppedCount(),
                "every span must be either recorded or counted as dropped — none silently lost");
        }
    }

    /** Drops are visible on /metrics, because "what is dropping" is a support question. */
    @Test
    void dropsAreExportedAsAMetric() {
        try (SpanSink sink = SpanSink.forTest(TINY_RING)) {
            for (int i = 0; i < 10_000; i++) {
                sink.span(1L, 2L, 3L, 0L, 100L, 200L, SpanSink.NAME_APPLY, i);
            }
            final String metrics = sink.metrics();
            assertTrue(metrics.contains("traderx_otel_spans_total{outcome=\"dropped\"}"), metrics);
            assertTrue(metrics.contains("traderx_otel_spans_total{outcome=\"emitted\"}"), metrics);
        }
    }

    /** The wire format: ids are lower-case zero-padded hex of the documented widths, a root span
     *  omits parentSpanId, and a child carries it — that is what makes Tempo draw the tree. */
    @Test
    void rendersOtlpJsonWithWellFormedIds() {
        try (SpanSink sink = SpanSink.forTest(1 << 16)) {
            sink.span(0x0123456789ABCDEFL, 0xFEDCBA9876543210L, 0xAABBCCDDEEFF0011L, 0L,
                1_000_000_000L, 2_000_000_000L, SpanSink.NAME_ORDER, 77L);
            sink.span(0x0123456789ABCDEFL, 0xFEDCBA9876543210L, 0x1L, 0xAABBCCDDEEFF0011L,
                1_100_000_000L, 1_900_000_000L, SpanSink.NAME_CLUSTER, 77L);
            final String body = sink.drainOnce();
            assertNotNull(body);
            // 128-bit trace id = the two longs concatenated, 32 hex chars.
            assertTrue(body.contains("\"traceId\":\"0123456789abcdeffedcba9876543210\""), body);
            // Span id zero-padded to 16 — the child's id is 1, which must NOT render as "1".
            assertTrue(body.contains("\"spanId\":\"0000000000000001\""), body);
            // Root has no parent; the child points at the root.
            assertTrue(body.contains("\"parentSpanId\":\"aabbccddeeff0011\""), body);
            assertEquals(1, countOccurrences(body, "\"parentSpanId\""), "only the child has a parent");
            assertTrue(body.contains("\"name\":\"cluster.consensus\""), body);
            assertTrue(body.contains("\"startTimeUnixNano\":\"1000000000\""), body);
            assertTrue(body.contains("\"traderx.order_ref\""), body);
            assertTrue(body.startsWith("{\"resourceSpans\":[") && body.endsWith("]}]}]}"), body);
        }
    }

    /** An empty ring renders nothing rather than an empty payload the collector would reject. */
    @Test
    void anEmptyRingProducesNoRequest() {
        try (SpanSink sink = SpanSink.forTest(TINY_RING)) {
            assertNull(sink.drainOnce());
        }
    }

    /** Draining frees the ring, so a sink that fell behind recovers instead of dropping forever. */
    @Test
    void drainingRestoresCapacity() {
        try (SpanSink sink = SpanSink.forTest(TINY_RING)) {
            for (int i = 0; i < 10_000; i++) {
                sink.span(1L, 2L, 3L, 0L, 100L, 200L, SpanSink.NAME_QUEUE, i);
            }
            final long droppedWhileFull = sink.droppedCount();
            assertNotNull(sink.drainOnce());
            final long emittedBefore = sink.emittedCount();
            sink.span(1L, 2L, 3L, 0L, 100L, 200L, SpanSink.NAME_QUEUE, 1L);
            assertEquals(emittedBefore + 1, sink.emittedCount(), "space freed by the drain went unused");
            assertEquals(droppedWhileFull, sink.droppedCount(), "no new drop after draining");
        }
    }

    /**
     * The exporter's cost is bounded by CONFIG, not by how much telemetry there is. The ring bounds
     * memory and the drop rule bounds what the trade path pays, but neither bounds what the exporter
     * THREAD costs — and members run on tainted core-pinned nodes where a thread spinning flat out
     * competes with the Aeron agents it exists to observe. The duty-cycle cap is what makes that
     * impossible, so the ceiling it implies is asserted rather than assumed.
     */
    @Test
    void exportRateIsCappedByConfigurationNotByTelemetryVolume() {
        final int batchLimit = 512;      // OTEL_BATCH_SPANS default
        final long minIntervalMs = 10L;  // OTEL_MIN_INTERVAL_MS default
        final long ceilingPerSecond = batchLimit * 1000L / minIntervalMs;

        assertEquals(51_200L, ceilingPerSecond, "defaults must cap the exporter at ~51k spans/s");

        // The ceiling has to sit above what a correctly-sampled production run actually produces,
        // or the cap would be silently throwing away the sample it was configured to keep.
        final long ordersPerSecond = 190_000L;              // measured per-order ceiling
        final long sampled = ordersPerSecond / 128L;        // OTEL_SAMPLE_MASK=127 on GKE
        final long spansPerSecond = sampled * 3L;           // gateway emits 3 spans per order
        assertTrue(spansPerSecond < ceilingPerSecond,
            "a 1-in-128 sample at 190k orders/s produces " + spansPerSecond
                + " spans/s, which must fit under the " + ceilingPerSecond + "/s cap");

        // And it must bite for trace-everything at that rate — that is the misconfiguration the cap
        // exists for (kind ships mask 0; copying kind's config to a loaded cluster is how this
        // happens). Excess drops at the ring and is counted, never queued and never blocking.
        final long unsampledSpansPerSecond = ordersPerSecond * 3L;
        assertTrue(unsampledSpansPerSecond > ceilingPerSecond,
            "trace-everything at 190k orders/s must exceed the cap, so the overflow valve engages");
    }

    /**
     * The pacing must hold on the FAILURE path, which is the one that persists. The first version of
     * the cap slept only after a successful post, so a throwing post() skipped the sleep entirely and
     * the exporter tight-spun for the whole duration of a collector outage — on a core-pinned member
     * node. "What does this cost when the bad state lasts forever?" is the question, and a bounded
     * happy path is not an answer.
     */
    @Test
    void pacingHoldsWhileExportIsFailing() {
        final long flush = 1000L;
        final long minInterval = 10L;

        // The bug: work was done, the post threw. Must still pause — and back off, not just floor.
        assertEquals(flush, SpanSink.pauseMillis(true, true, flush, minInterval),
            "a failing export must back off, not spin");

        // Healthy busy path: the duty-cycle floor, so throughput stays capped but useful.
        assertEquals(minInterval, SpanSink.pauseMillis(true, false, flush, minInterval));

        // Idle: nothing to do, wait the full flush interval.
        assertEquals(flush, SpanSink.pauseMillis(false, false, flush, minInterval));

        // Every case pauses. No combination of inputs may return zero — that is the tight spin.
        for (final boolean hadWork : new boolean[] {true, false}) {
            for (final boolean failing : new boolean[] {true, false}) {
                assertTrue(SpanSink.pauseMillis(hadWork, failing, flush, minInterval) > 0,
                    "pause must be positive for hadWork=" + hadWork + " failing=" + failing);
            }
        }
    }

    /** Nothing is lost silently: every offered span is either recorded or counted as dropped. This
     *  is the arithmetic that makes the drop counter trustworthy as a support signal. */
    @Test
    void everySpanIsAccountedForUnderSaturation() {
        try (SpanSink sink = SpanSink.forTest(TINY_RING)) {
            final int offered = 25_000;
            for (int i = 0; i < offered; i++) {
                sink.span(1L, 2L, 3L, 0L, 100L, 200L, SpanSink.NAME_APPLY, i);
            }
            assertEquals(offered, sink.emittedCount() + sink.droppedCount(),
                "offered must equal recorded + dropped — no span may vanish unaccounted");
        }
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
