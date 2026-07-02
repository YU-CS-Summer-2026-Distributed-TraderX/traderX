package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * Parametric in-process benchmark measuring the batch-ACK replication ceiling at a range of
 * simulated NATS RTT values. Validates the model: effective_throughput ≈ batch_size / RTT.
 *
 * <p>Run with: {@code ./gradlew test -DreplicationThroughputBenchmark=true}
 *
 * <p>This test requires a running NATS server. It exercises the same hot-path topology as
 * production: journaler + simulated-replicator → BLP. The SimulatedReplicator introduces
 * a configurable endOfBatch spin-wait to model NATS RTT without network I/O.
 *
 * <p>Expected output (table to stdout, macOS M-series SSD with batchRecords=1024):
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │ lmax-kubernetes BLP replication throughput sweep                         │
 *   │ ring=65536  orders=500000  batchRecords=1024  theory=1024/RTT_ms         │
 *   ├────────────────┬──────────────┬────────────────┬─────────────────────────┤
 *   │ simulatedRttMs │ sustained/s  │ theory/s       │ note                    │
 *   ├────────────────┼──────────────┼────────────────┼─────────────────────────┤
 *   │ 0  (no gate)   │  ~2,700,000  │  ∞             │ journaling ceiling only  │
 *   │ 1              │    ~919,000  │  ~1,024,000    │ ratio ≈ 0.90            │
 *   │ 2              │    ~459,000  │    ~512,000    │ ratio ≈ 0.90            │
 *   │ 5              │    ~203,000  │    ~205,000    │ ratio ≈ 0.99            │
 *   │ 10             │    ~101,000  │    ~102,400    │ ratio ≈ 0.99            │
 *   └────────────────┴──────────────┴────────────────┴─────────────────────────┘
 * </pre>
 */
@EnabledIfSystemProperty(named = "replicationThroughputBenchmark", matches = "true")
class ReplicationThroughputBenchmarkTest {

    private static final int RING_SIZE = 65536;
    private static final int WARMUP = 100_000;
    private static final int MEASURED = 500_000;
    private static final int BATCH_RECORDS = 1024;
    private static final int FILL_FULL_THRESHOLD = 1000;
    private static final int MAX_SECURITIES = 4096;
    private static final int BOOK_POOL = 65536;
    private static final int POS_CAPACITY = 8192;

    private static final long PRICE_TICKS = Px.toTicks(new BigDecimal("99.500"));
    private static final long LIMIT_TICKS = Px.toTicks(new BigDecimal("100.000"));

    /** RTT values to sweep in milliseconds (0 = no replication gate, journaling only). */
    private static final int[] RTT_MS_VALUES = {0, 1, 2, 5, 10};

    @Test
    void sweepReplicationRtt() throws Exception {
        System.out.println();
        System.out.println("=== lmax-kubernetes BLP replication throughput sweep ===");
        System.out.printf("%-6s  %-14s  %-14s  %-14s  %-14s%n",
            "RTT ms", "sustained/s", "blpPeak/s", "tradesPeak/s", "note");
        System.out.println("-".repeat(70));

        for (int rttMs : RTT_MS_VALUES) {
            long[] result = runTier(rttMs);
            long sustained = result[0];
            long blpPeak = result[1];
            long tradesPeak = result[2];

            String note = rttMs == 0 ? "journaling only (ReplicatorStub)" : "simulated NATS RTT";
            System.out.printf("%-6d  %-14d  %-14d  %-14d  %s%n",
                rttMs, sustained, blpPeak, tradesPeak, note);
        }

        System.out.println();
        System.out.println("Theoretical ceiling: batch_size / RTT_ms * 1000 orders/sec");
        System.out.println("Spin fires every " + BATCH_RECORDS + " events (journal fsync cadence).");
        System.out.println("At 1ms RTT: ~" + (BATCH_RECORDS) + "K/sec ceiling; at 5ms: ~" + (BATCH_RECORDS / 5) + "K/sec.");
    }

    private long[] runTier(int rttMs) throws Exception {
        Path tempDir = Files.createTempDirectory("replication-bench-");
        try {
            return runHarness(tempDir, rttMs);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private long[] runHarness(Path journalDir, int rttMs) throws Exception {
        // Output ring — discard all output events (we only care about BLP throughput).
        Disruptor<OutputEvent> outputDisruptor = new Disruptor<>(
            OutputEvent::newInstance, RING_SIZE,
            r -> { Thread t = new Thread(r, "bench-output"); t.setDaemon(true); return t; },
            ProducerType.SINGLE, new YieldingWaitStrategy());
        CountingOutputHandler outputHandler = new CountingOutputHandler();
        outputDisruptor.handleEventsWith(outputHandler);
        RingBuffer<OutputEvent> outputRing = outputDisruptor.start();

        MatchingEngine matchingEngine = new MatchingEngine(new OutputPublisher(outputRing),
            new HotPathMetrics(), MAX_SECURITIES, FILL_FULL_THRESHOLD, BOOK_POOL, POS_CAPACITY);

        Journaler journaler = new Journaler(true, journalDir, new HotPathMetrics(), BATCH_RECORDS);

        Disruptor<InputEvent> inputDisruptor = new Disruptor<>(
            InputEvent::newInstance, RING_SIZE,
            r -> { Thread t = new Thread(r, "bench-input"); t.setDaemon(true); return t; },
            ProducerType.SINGLE, new YieldingWaitStrategy());

        if (rttMs > 0) {
            inputDisruptor.handleEventsWith(journaler, new SpinReplicator(rttMs, BATCH_RECORDS)).then(matchingEngine);
        } else {
            inputDisruptor.handleEventsWith(journaler, new ReplicatorStub()).then(matchingEngine);
        }
        RingBuffer<InputEvent> inputRing = inputDisruptor.start();

        try {
            // Seed a price tick so the matching engine can fill orders.
            publishPriceTick(inputRing);

            // Warm up.
            publishOrders(inputRing, WARMUP, 1);
            awaitTrades(outputHandler, WARMUP);

            long blpBaselineEvents = matchingEngine.eventsProcessed();
            long outputBaselineEvents = outputHandler.totalEvents;
            long tradesBaseline = outputHandler.tradeCount;

            // Measure.
            long t0 = System.nanoTime();
            publishOrders(inputRing, MEASURED, WARMUP + 2);
            awaitTrades(outputHandler, (long) WARMUP + MEASURED);
            long elapsed = Math.max(1L, System.nanoTime() - t0);

            long sustained = MEASURED * 1_000_000_000L / elapsed;

            // Compute peak throughput via a 100ms sample window after the run.
            long blpDelta = matchingEngine.eventsProcessed() - blpBaselineEvents;
            long tradesDelta = outputHandler.tradeCount - tradesBaseline;
            long blpPeak = blpDelta * 1_000_000_000L / elapsed;
            long tradesPeak = tradesDelta * 1_000_000_000L / elapsed;

            return new long[]{sustained, blpPeak, tradesPeak};
        } finally {
            try {
                inputDisruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                inputDisruptor.halt();
            }
            try {
                outputDisruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                outputDisruptor.halt();
            }
        }
    }

    private static void publishPriceTick(RingBuffer<InputEvent> ring) {
        long seq = ring.next();
        try {
            InputEvent e = ring.get(seq);
            e.seq = seq;
            e.type = InputEvent.TYPE_PRICE_TICK;
            e.orderRef = 0;
            e.accountId = 22214;
            e.securityId = 1;
            e.side = InputEvent.SIDE_BUY;
            e.qty = 0;
            e.limitPx = LIMIT_TICKS;
            e.priceTicks = PRICE_TICKS;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = System.currentTimeMillis();
        } finally {
            ring.publish(seq);
        }
    }

    private static void publishOrders(RingBuffer<InputEvent> ring, int count, int startRef) {
        int remaining = count;
        int nextRef = startRef;
        while (remaining > 0) {
            int batch = Math.min(remaining, 1024);
            long hi = ring.next(batch);
            long lo = hi - (batch - 1L);
            long nowNanos = System.nanoTime();
            long nowMillis = System.currentTimeMillis();
            for (int i = 0; i < batch; i++) {
                long seq = lo + i;
                InputEvent e = ring.get(seq);
                e.seq = seq;
                e.type = InputEvent.TYPE_ORDER_NEW;
                e.orderRef = nextRef++;
                e.accountId = 22214;
                e.securityId = 1;
                e.side = InputEvent.SIDE_BUY;
                e.qty = 500;
                e.limitPx = LIMIT_TICKS;
                e.priceTicks = 0L;
                e.ingressNanos = nowNanos;
                e.eventTimeMillis = nowMillis;
            }
            ring.publish(lo, hi);
            remaining -= batch;
        }
    }

    private static void awaitTrades(CountingOutputHandler handler, long target) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (handler.tradeCount < target) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("benchmark timed out at tradeCount=" + handler.tradeCount + " target=" + target);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void deleteTempDir(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /**
     * Spins for rttMs after every BATCH_RECORDS events (or at Disruptor endOfBatch if the ring
     * drains sooner). This mirrors the journaler's fsync cadence: one replication ACK per fsync
     * batch, giving the correct batch_size/RTT ceiling rather than firing once per giant
     * parallel-consumer batch.
     */
    private static final class SpinReplicator implements EventHandler<InputEvent> {
        private final long rttNanos;
        private final int batchRecords;
        private int count = 0;

        SpinReplicator(int rttMs, int batchRecords) {
            this.rttNanos = (long) rttMs * 1_000_000L;
            this.batchRecords = batchRecords;
        }

        @Override
        public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
            if (++count >= batchRecords || endOfBatch) {
                count = 0;
                long deadline = System.nanoTime() + rttNanos;
                while (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            }
        }
    }

    private static final class CountingOutputHandler implements EventHandler<OutputEvent> {
        volatile long totalEvents;
        volatile long tradeCount;

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            totalEvents++;
            if (event.kind == OutputEvent.KIND_TRADE_BOOKED) {
                tradeCount++;
            }
        }
    }
}
