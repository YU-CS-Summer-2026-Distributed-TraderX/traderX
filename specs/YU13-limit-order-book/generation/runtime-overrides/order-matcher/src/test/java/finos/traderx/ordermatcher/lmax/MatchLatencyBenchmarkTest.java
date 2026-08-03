package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's own number (NFR-LOB01): per-order match-op latency of the crossing book,
 * measured directly around {@code onEvent} on the BLP thread with HdrHistogram, reported as
 * p50/p99/p99.9/p99.99/max in NANOSECONDS. This is the in-memory book operation only — no
 * transport, no consensus, no ring hop. The load is closed-loop back-to-back apply: the
 * generator never backs off, so the tail is not laundered by coordinated omission (each op is
 * timed individually at maximum pressure).
 *
 * Three op classes are reported separately, because the talk's honest framing needs each:
 * a resting insert (book add, no match), a full limit cross (both sides filled, paired trade
 * and position emission), and a market order (fill + remainder cancel).
 *
 * The pass gate is deliberately loose (p50 under 10us per op class on any hardware this runs
 * on) — the artifact is the printed histogram, captured into the bench log; regressions are
 * judged against recorded baselines, not a CI knife-edge.
 */
class MatchLatencyBenchmarkTest {
    private static final long CROSS_PX = 200_000_000L;
    private static final int WARMUP_CYCLES = 200_000;
    private static final int MEASURED_CYCLES = 250_000;

    private final InputEvent e = new InputEvent();
    private long seq = 1;
    private MatchingEngine engine;

    @Test
    void matchOpLatencyHistogramNanos() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(
            OutputEvent::newInstance, 1 << 16, new BlockingWaitStrategy());
        BlpRiskState risk = new BlpRiskState(64, 16, 16_384, 1024, Long.MAX_VALUE / 4,
            1_000_000, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, new RiskMetrics());
        risk.putAccount(42, true);
        for (int sec = 0; sec < 4; sec++) {
            risk.putSecurity(sec, true);
        }
        risk.putLimits(Integer.MAX_VALUE, Long.MAX_VALUE / 4);
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0,
            16_384, 1024, 8_192, risk);

        Histogram insertNs = new Histogram(3_600_000_000_000L, 3);
        Histogram crossNs = new Histogram(3_600_000_000_000L, 3);
        Histogram marketNs = new Histogram(3_600_000_000_000L, 3);

        int nextRef = 1;
        for (int sec = 0; sec < 4; sec++) {
            apply(tick(sec, 150_000_000L));
        }
        // Warm-up: same branch profile as the measured loop.
        nextRef = runCycles(nextRef, WARMUP_CYCLES, null, null, null);
        insertNs.reset();
        crossNs.reset();
        marketNs.reset();
        nextRef = runCycles(nextRef, MEASURED_CYCLES, insertNs, crossNs, marketNs);

        print("resting-insert", insertNs);
        print("limit-cross   ", crossNs);
        print("market-order  ", marketNs);

        assertTrue(insertNs.getValueAtPercentile(50.0) < 10_000, "resting insert p50 under 10us");
        assertTrue(crossNs.getValueAtPercentile(50.0) < 10_000, "limit cross p50 under 10us");
        assertTrue(marketNs.getValueAtPercentile(50.0) < 10_000, "market order p50 under 10us");
    }

    /** Level-neutral crossing cycle per security: rest 500 / cross 500 / rest 300 / market 400. */
    private int runCycles(int nextRef, int cycles, Histogram insertNs, Histogram crossNs,
                          Histogram marketNs) {
        for (int i = 0; i < cycles; i++) {
            int sec = i & 3;
            record(insertNs, order(nextRef++, sec, InputEvent.SIDE_SELL, 500, CROSS_PX));
            record(crossNs, order(nextRef++, sec, InputEvent.SIDE_BUY, 500, CROSS_PX));
            record(insertNs, order(nextRef++, sec, InputEvent.SIDE_SELL, 300, CROSS_PX));
            record(marketNs, order(nextRef++, sec, InputEvent.SIDE_BUY, 400, Px.NONE));
        }
        return nextRef;
    }

    private void record(Histogram histogram, InputEvent event) {
        final long start = System.nanoTime();
        apply(event);
        final long elapsed = System.nanoTime() - start;
        if (histogram != null) {
            histogram.recordValue(Math.min(elapsed, histogram.getHighestTrackableValue()));
        }
    }

    private void apply(InputEvent event) {
        event.seq = seq;
        event.eventTimeMillis = 1_750_000_000_000L;
        event.ingressNanos = System.nanoTime();
        engine.onEvent(event, seq++, true);
    }

    private InputEvent order(int ref, int sec, byte side, int qty, long limitPx) {
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.orderRef = ref;
        e.accountId = 42;
        e.securityId = sec;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        e.priceTicks = 0L;
        return e;
    }

    private InputEvent tick(int sec, long px) {
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.orderRef = 0;
        e.accountId = 0;
        e.securityId = sec;
        e.side = 0;
        e.qty = 0;
        e.limitPx = 0;
        e.priceTicks = px;
        return e;
    }

    private static void print(String label, Histogram h) {
        System.out.println(String.format(
            "MATCH-LATENCY %s count=%d p50=%dns p99=%dns p99.9=%dns p99.99=%dns max=%dns",
            label, h.getTotalCount(),
            h.getValueAtPercentile(50.0), h.getValueAtPercentile(99.0),
            h.getValueAtPercentile(99.9), h.getValueAtPercentile(99.99), h.getMaxValue()));
    }
}
