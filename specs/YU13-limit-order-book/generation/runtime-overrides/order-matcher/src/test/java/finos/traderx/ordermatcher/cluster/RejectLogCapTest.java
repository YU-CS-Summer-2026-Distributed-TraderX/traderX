package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OTEL-01 follow-up gate: the reject log line is BOUNDED.
 *
 * <p>Why this is worth a test at all. Every other best-effort side channel in this design has an
 * overflow valve — a span hits a full ring and is dropped and counted, the exporter's duty cycle is
 * capped so an outage costs ~1 attempt/s. {@code System.out} has none: a line goes to the node's
 * disk and on to promtail/Loki with nothing in between that can refuse it. A reject storm is a real
 * state of this system (a 30s bench once had the engine reject 296,000 orders on CREDIT_LIMIT while
 * every request was answered 2xx), and at those rates an unbounded reject line makes the telemetry
 * the outage — the exact failure the OTEL-01 result doc found twice already, one level further in.
 *
 * <p>The decision is asserted directly rather than inferred from wall-clock timing, for the same
 * reason {@code SpanSink.pauseMillis} is a pure function: a cap that is tested by sleeping is tested
 * by luck.
 */
class RejectLogCapTest {

    private final AtomicLong window = new AtomicLong();
    private final AtomicInteger count = new AtomicInteger();

    private int allowedOutOf(final int attempts, final long atMillis, final int perSecond) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (ClusterGatewayMain.allowRejectLog(atMillis, perSecond, window, count)) {
                allowed++;
            }
        }
        return allowed;
    }

    /** A storm inside one second prints the budget and nothing more. */
    @Test
    void aStormWithinOneSecondIsCappedAtTheBudget() {
        assertEquals(20, allowedOutOf(100_000, 1_700_000_000_000L, 20),
            "an unbounded reject line is how telemetry becomes the outage");
    }

    /** The budget refills — a cap that latches would hide every reject after the first storm, which
     *  is worse than the storm. */
    @Test
    void theBudgetRefillsOnTheNextSecond() {
        assertEquals(20, allowedOutOf(100, 1_700_000_000_000L, 20));
        assertEquals(20, allowedOutOf(100, 1_700_000_001_000L, 20));
        assertEquals(20, allowedOutOf(100, 1_700_000_002_000L, 20));
    }

    /** The ordinary case — a handful of rejects a second — is never suppressed, so the feature is
     *  not quietly off in the only conditions anyone will demo it under. */
    @Test
    void ordinaryRejectRatesArePrintedInFull() {
        for (int second = 0; second < 10; second++) {
            assertEquals(3, allowedOutOf(3, 1_700_000_000_000L + (second * 1000L), 20));
        }
    }

    /** No input returns "unbounded". A budget of 0 must actually mean silence, not wrap to allow. */
    @Test
    void aZeroBudgetPrintsNothing() {
        assertEquals(0, allowedOutOf(50, 1_700_000_000_000L, 0));
    }

    /** Millisecond offsets inside the same second share one budget — the window is the second, not
     *  the timestamp. */
    @Test
    void millisWithinTheSameSecondShareOneBudget() {
        assertTrue(ClusterGatewayMain.allowRejectLog(1_700_000_000_001L, 2, window, count));
        assertTrue(ClusterGatewayMain.allowRejectLog(1_700_000_000_500L, 2, window, count));
        assertFalse(ClusterGatewayMain.allowRejectLog(1_700_000_000_999L, 2, window, count),
            "the third line in the same second must be refused");
        assertTrue(ClusterGatewayMain.allowRejectLog(1_700_000_001_000L, 2, window, count),
            "the next second must open a fresh budget");
    }

    /**
     * The ClOrdID on the reject line is client-supplied and lands in a log a supporter reads. A
     * newline in it would forge a whole second log line — including a fake `trace=` token pointing
     * anywhere the attacker likes — so it must not survive to the line.
     */
    @Test
    void aClientSuppliedIdCannotForgeASecondLogLine() {
        final String forged = "ok\nORDER-REJECT trace=" + "a".repeat(32) + " clordid=NOT-REAL";
        final String safe = ClusterGatewayMain.safeForLog(forged);
        assertFalse(safe.contains("\n"), "a newline reached the log line: " + safe);
        assertFalse(safe.contains("\r"), "a carriage return reached the log line: " + safe);
        assertTrue(safe.length() <= 64, "the field must stay bounded, got " + safe.length());
        assertEquals("-", ClusterGatewayMain.safeForLog(null), "a missing id renders as a dash");
        assertEquals("-", ClusterGatewayMain.safeForLog(""));
        assertEquals("ORD-123_x", ClusterGatewayMain.safeForLog("ORD-123_x"),
            "an ordinary client order id must pass through untouched");
    }

    /** Concurrent submit threads share the process-wide budget without exceeding it. The window roll
     *  races benignly by design; what must NOT happen is the cap being multiplied by thread count. */
    @Test
    void concurrentSubmittersShareOneBudget() throws Exception {
        final int perSecond = 50;
        final AtomicInteger allowed = new AtomicInteger();
        final Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 5_000; i++) {
                    if (ClusterGatewayMain.allowRejectLog(1_700_000_000_000L, perSecond,
                            window, count)) {
                        allowed.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }
        assertEquals(perSecond, allowed.get(),
            "the budget is process-wide, not per-thread");
    }
}
