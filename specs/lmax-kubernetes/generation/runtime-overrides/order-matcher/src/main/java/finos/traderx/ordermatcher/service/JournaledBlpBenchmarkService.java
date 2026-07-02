package finos.traderx.ordermatcher.service;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.Journaler;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.ReplicatorStub;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * Isolated benchmark for the lmax-kubernetes hot-path topology: input ring → [journaler,
 * replicator] → BLP → output ring. Unlike the theoretical ceiling from BlpBenchmarkService
 * (no journaling), this harness places the Journaler on the ring barrier so every batch
 * flushes + fsyncs before the BLP can advance — the actual latency tax paid on lmax-kubernetes.
 *
 * <p>The {@code simulatedRttMs} parameter adds a spin-wait every {@code batchRecords} events on
 * the replicator thread, modelling the NATS JetStream round-trip on the HA branch. Spinning at
 * the same cadence as the journaler's fsyncs (not at Disruptor endOfBatch, which fires once per
 * giant parallel-consumer batch) reveals the correct {@code batch_size / RTT} ceiling in-process
 * without needing a running cluster. Zero (default) uses a {@link ReplicatorStub} loopback so the
 * journaler cost is measured in isolation.
 *
 * <p>Triggered via:
 * <pre>
 *   POST /system/benchmarks/journaled-blp/run?warmupOrders=250000&measuredOrders=2000000
 *   GET  /system/benchmarks/journaled-blp
 * </pre>
 *
 * <p>Prometheus metrics are exported at {@code /metrics} with the prefix
 * {@code traderx_benchmark_journaled_blp_}.
 */
@Component
public final class JournaledBlpBenchmarkService implements DisposableBean {
    private static final long SAMPLE_PERIOD_MS = 100L;
    private static final int BATCH_CLAIM = 1024;
    private static final int ACCOUNT_ID = 22214;
    private static final int SECURITY_ID = 1;
    private static final byte SIDE_BUY = InputEvent.SIDE_BUY;
    private static final int ORDER_QTY = 500;
    private static final long PRICE_TICKS = Px.toTicks(new BigDecimal("99.500"));
    private static final long LIMIT_TICKS = Px.toTicks(new BigDecimal("100.000"));

    private final int defaultWarmupOrders;
    private final int defaultMeasuredOrders;
    private final int defaultRingSize;
    private final int defaultBatchRecords;
    private final int defaultSimulatedRttMs;
    private final int fillFullThreshold;
    private final int maxSecurities;
    private final int defaultBookPoolSize;
    private final int defaultPositionCapacity;
    private final String defaultWaitStrategy;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "journaled-blp-benchmark-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "journaled-blp-benchmark-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final Object monitor = new Object();

    private volatile BenchmarkRun lastRun = BenchmarkRun.idle();

    public JournaledBlpBenchmarkService(
        @Value("${benchmark.journaled-blp.warmup-orders:250000}") int defaultWarmupOrders,
        @Value("${benchmark.journaled-blp.measured-orders:2000000}") int defaultMeasuredOrders,
        @Value("${benchmark.journaled-blp.ring-size:65536}") int defaultRingSize,
        @Value("${benchmark.journaled-blp.wait-strategy:yielding}") String defaultWaitStrategy,
        @Value("${benchmark.journaled-blp.batch-records:1024}") int defaultBatchRecords,
        @Value("${benchmark.journaled-blp.simulated-rtt-ms:0}") int defaultSimulatedRttMs,
        @Value("${order.matcher.fill-full-threshold:1000}") int fillFullThreshold,
        @Value("${blp.books.max-securities:4096}") int maxSecurities,
        @Value("${blp.book.pool-size:65536}") int defaultBookPoolSize,
        @Value("${blp.positions.capacity:8192}") int defaultPositionCapacity
    ) {
        this.defaultWarmupOrders = defaultWarmupOrders;
        this.defaultMeasuredOrders = defaultMeasuredOrders;
        this.defaultRingSize = defaultRingSize;
        this.defaultWaitStrategy = defaultWaitStrategy;
        this.defaultBatchRecords = defaultBatchRecords;
        this.defaultSimulatedRttMs = defaultSimulatedRttMs;
        this.fillFullThreshold = fillFullThreshold;
        this.maxSecurities = maxSecurities;
        this.defaultBookPoolSize = defaultBookPoolSize;
        this.defaultPositionCapacity = defaultPositionCapacity;
    }

    @Override
    public void destroy() {
        runner.shutdownNow();
        sampler.shutdownNow();
    }

    public Map<String, Object> startRun(Integer warmupOrders, Integer measuredOrders,
                                        Integer ringSize, String waitStrategy,
                                        Integer batchRecords, Integer simulatedRttMs) {
        synchronized (monitor) {
            if (lastRun.running) {
                return lastRun.snapshot();
            }
            BenchmarkRun run = new BenchmarkRun(
                positiveOrDefault(warmupOrders, defaultWarmupOrders),
                positiveOrDefault(measuredOrders, defaultMeasuredOrders),
                normalizeRingSize(positiveOrDefault(ringSize, defaultRingSize)),
                waitStrategy == null || waitStrategy.isBlank() ? defaultWaitStrategy : waitStrategy.trim().toLowerCase(Locale.ROOT),
                positiveOrDefault(batchRecords, defaultBatchRecords),
                nonNegativeOrDefault(simulatedRttMs, defaultSimulatedRttMs)
            );
            lastRun = run;
            runner.submit(() -> execute(run));
            return run.snapshot();
        }
    }

    public Map<String, Object> status() {
        return lastRun.snapshot();
    }

    public void appendPrometheusMetrics(StringBuilder sb) {
        BenchmarkRun run = lastRun;
        appendGauge(sb, "traderx_benchmark_journaled_blp_running",
            "1 while the journaled BLP benchmark is running, otherwise 0.",
            run.running ? 1 : 0);
        appendGauge(sb, "traderx_benchmark_journaled_input_events_per_second_current",
            "Current journaled-benchmark input throughput over rolling 100ms windows.",
            run.input.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_input_events_per_second_peak",
            "Peak journaled-benchmark input throughput over rolling 100ms windows.",
            run.input.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_blp_events_per_second_current",
            "Current journaled-benchmark BLP throughput over rolling 100ms windows.",
            run.blp.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_blp_events_per_second_peak",
            "Peak journaled-benchmark BLP throughput over rolling 100ms windows.",
            run.blp.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_output_events_per_second_current",
            "Current journaled-benchmark output throughput over rolling 100ms windows.",
            run.output.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_output_events_per_second_peak",
            "Peak journaled-benchmark output throughput over rolling 100ms windows.",
            run.output.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_trades_per_second_current",
            "Current journaled-benchmark booked-trade throughput over rolling 100ms windows.",
            run.trades.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_trades_per_second_peak",
            "Peak journaled-benchmark booked-trade throughput over rolling 100ms windows.",
            run.trades.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_journaled_blp_last_sustained_orders_per_second",
            "Last completed journaled-benchmark sustained order throughput across the measured phase.",
            run.lastSustainedOrdersPerSecond);
        appendGauge(sb, "traderx_benchmark_journaled_blp_last_duration_millis",
            "Last completed journaled-benchmark measured-phase duration in milliseconds.",
            run.lastDurationMillis);
        appendGauge(sb, "traderx_benchmark_journaled_blp_last_measured_orders_total",
            "Measured orders in the last completed journaled-benchmark run.",
            run.measuredOrders);
        appendGauge(sb, "traderx_benchmark_journaled_blp_last_warmup_orders_total",
            "Warmup orders in the last completed journaled-benchmark run.",
            run.warmupOrders);
        appendGauge(sb, "traderx_benchmark_journaled_blp_last_simulated_rtt_ms",
            "Simulated replication RTT used in the last completed journaled-benchmark run (0 = journaling only, no replication gate).",
            run.simulatedRttMs);
        appendGauge(sb, "traderx_benchmark_journaled_blp_last_batch_records",
            "Journal batch-coalescing depth used in the last completed journaled-benchmark run.",
            run.batchRecords);
    }

    private void execute(BenchmarkRun run) {
        ScheduledFuture<?> future = null;
        try (BenchmarkHarness harness = new BenchmarkHarness(run.ringSize, run.waitStrategy,
            run.batchRecords, run.simulatedRttMs,
            fillFullThreshold, maxSecurities, defaultBookPoolSize, defaultPositionCapacity)) {
            run.startedAt = Instant.now();
            run.phase = "warming_up";
            harness.publishPriceTick();
            future = sampler.scheduleAtFixedRate(() -> sample(run, harness),
                SAMPLE_PERIOD_MS, SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS);

            harness.publishOrders(run.warmupOrders);
            harness.awaitTrades(run.warmupOrders);

            run.phase = "measuring";
            run.beginMeasurement(harness);
            long startedNanos = System.nanoTime();
            harness.publishOrders(run.measuredOrders);
            harness.awaitTrades(run.tradeBaseline + run.measuredOrders);
            long elapsedNanos = Math.max(1L, System.nanoTime() - startedNanos);

            run.lastSustainedOrdersPerSecond = run.measuredOrders * 1_000_000_000L / elapsedNanos;
            run.lastDurationMillis = elapsedNanos / 1_000_000L;
            run.success = true;
            run.phase = "complete";
        } catch (Exception ex) {
            run.success = false;
            run.phase = "failed";
            run.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        } finally {
            if (future != null) {
                future.cancel(true);
            }
            run.running = false;
            run.finishedAt = Instant.now();
        }
    }

    private void sample(BenchmarkRun run, BenchmarkHarness harness) {
        if (!run.measuring) {
            return;
        }
        long nowNanos = System.nanoTime();
        run.input.observe(harness.inputTotal() - run.inputBaseline, nowNanos);
        run.blp.observe(harness.blpTotal() - run.blpBaseline, nowNanos);
        run.output.observe(harness.outputTotal() - run.outputBaseline, nowNanos);
        run.trades.observe(harness.tradesTotal() - run.tradeBaseline, nowNanos);
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private static int normalizeRingSize(int requested) {
        int size = Math.max(1024, Integer.highestOneBit(requested));
        return size == requested ? requested : size * (requested > size ? 2 : 1);
    }

    private static WaitStrategy waitStrategy(String name) {
        return switch (name == null ? "yielding" : name) {
            case "busyspin" -> new BusySpinWaitStrategy();
            case "sleeping" -> new SleepingWaitStrategy();
            case "blocking" -> new BlockingWaitStrategy();
            default -> new YieldingWaitStrategy();
        };
    }

    private static void appendGauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    // ---- BenchmarkRun -----------------------------------------------------------------------

    private static final class BenchmarkRun {
        final int warmupOrders;
        final int measuredOrders;
        final int ringSize;
        final String waitStrategy;
        final int batchRecords;
        final int simulatedRttMs;
        final ThroughputMetricSet input = new ThroughputMetricSet();
        final ThroughputMetricSet blp = new ThroughputMetricSet();
        final ThroughputMetricSet output = new ThroughputMetricSet();
        final ThroughputMetricSet trades = new ThroughputMetricSet();
        final Instant requestedAt = Instant.now();
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile boolean running = true;
        volatile boolean measuring;
        volatile boolean success;
        volatile String phase = "queued";
        volatile String error = "";
        volatile long lastSustainedOrdersPerSecond;
        volatile long lastDurationMillis;
        volatile long inputBaseline;
        volatile long blpBaseline;
        volatile long outputBaseline;
        volatile long tradeBaseline;

        BenchmarkRun(int warmupOrders, int measuredOrders, int ringSize, String waitStrategy,
                     int batchRecords, int simulatedRttMs) {
            this.warmupOrders = warmupOrders;
            this.measuredOrders = measuredOrders;
            this.ringSize = ringSize;
            this.waitStrategy = waitStrategy;
            this.batchRecords = batchRecords;
            this.simulatedRttMs = simulatedRttMs;
        }

        static BenchmarkRun idle() {
            BenchmarkRun run = new BenchmarkRun(0, 0, 0, "yielding", 0, 0);
            run.running = false;
            run.phase = "idle";
            run.success = false;
            return run;
        }

        void beginMeasurement(BenchmarkHarness harness) {
            long nowNanos = System.nanoTime();
            inputBaseline = harness.inputTotal();
            blpBaseline = harness.blpTotal();
            outputBaseline = harness.outputTotal();
            tradeBaseline = harness.tradesTotal();
            input.reset(0L, nowNanos);
            blp.reset(0L, nowNanos);
            output.reset(0L, nowNanos);
            trades.reset(0L, nowNanos);
            measuring = true;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("running", running);
            payload.put("success", success);
            payload.put("phase", phase);
            payload.put("requestedAt", requestedAt);
            payload.put("startedAt", startedAt);
            payload.put("finishedAt", finishedAt);
            payload.put("warmupOrders", warmupOrders);
            payload.put("measuredOrders", measuredOrders);
            payload.put("ringSize", ringSize);
            payload.put("waitStrategy", waitStrategy);
            payload.put("batchRecords", batchRecords);
            payload.put("simulatedRttMs", simulatedRttMs);
            payload.put("lastSustainedOrdersPerSecond", lastSustainedOrdersPerSecond);
            payload.put("inputCurrent", input.currentPerSecond());
            payload.put("inputPeak", input.peakPerSecond());
            payload.put("blpCurrent", blp.currentPerSecond());
            payload.put("blpPeak", blp.peakPerSecond());
            payload.put("outputCurrent", output.currentPerSecond());
            payload.put("outputPeak", output.peakPerSecond());
            payload.put("tradesCurrent", trades.currentPerSecond());
            payload.put("tradesPeak", trades.peakPerSecond());
            payload.put("error", error);
            return payload;
        }
    }

    // ---- BenchmarkHarness -------------------------------------------------------------------

    private static final class BenchmarkHarness implements AutoCloseable {
        private final Disruptor<InputEvent> inputDisruptor;
        private final RingBuffer<InputEvent> inputRing;
        private final Disruptor<OutputEvent> outputDisruptor;
        private final MatchingEngine matchingEngine;
        private final BenchmarkOutputHandler outputHandler;
        private final Path journalTempDir;
        private int nextOrderRef = 1;

        private BenchmarkHarness(int ringSize, String waitStrategy, int batchRecords, int simulatedRttMs,
                                 int fillFullThreshold, int maxSecurities, int bookPoolSize, int positionCapacity) {
            // Journal writes to a temp directory; cleaned up on close so we never pollute the real journal.
            try {
                journalTempDir = Files.createTempDirectory("journaled-blp-bench-");
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot create benchmark journal temp dir", ex);
            }

            outputDisruptor = new Disruptor<>(OutputEvent::newInstance, ringSize,
                runnableThreadFactory("journaled-bench-output"), ProducerType.SINGLE, waitStrategy(waitStrategy));
            outputHandler = new BenchmarkOutputHandler();
            outputDisruptor.handleEventsWith(outputHandler);
            RingBuffer<OutputEvent> outputRing = outputDisruptor.start();

            matchingEngine = new MatchingEngine(new OutputPublisher(outputRing), new HotPathMetrics(),
                Math.max(16, maxSecurities), fillFullThreshold, Math.max(bookPoolSize, ringSize), positionCapacity);

            Journaler journaler = new Journaler(true, journalTempDir, new HotPathMetrics(), batchRecords);

            inputDisruptor = new Disruptor<>(InputEvent::newInstance, ringSize,
                runnableThreadFactory("journaled-bench-input"), ProducerType.SINGLE, waitStrategy(waitStrategy));

            if (simulatedRttMs > 0) {
                // Simulate the NATS replication RTT — models the HA branch topology.
                // Spins every batchRecords events (matching the journaler's fsync cadence) so the
                // ceiling is correctly batch_size / RTT rather than invisible to the Journaler.
                inputDisruptor.handleEventsWith(journaler, new SimulatedReplicator(simulatedRttMs, batchRecords))
                    .then(matchingEngine);
            } else {
                // No replication gate — journaling cost only. Mirrors lmax-kubernetes (not HA).
                inputDisruptor.handleEventsWith(journaler, new ReplicatorStub()).then(matchingEngine);
            }
            inputRing = inputDisruptor.start();
        }

        private void publishPriceTick() {
            long seq = inputRing.next();
            try {
                InputEvent event = inputRing.get(seq);
                event.seq = seq;
                event.type = InputEvent.TYPE_PRICE_TICK;
                event.orderRef = 0;
                event.accountId = ACCOUNT_ID;
                event.securityId = SECURITY_ID;
                event.side = SIDE_BUY;
                event.qty = 0;
                event.limitPx = LIMIT_TICKS;
                event.priceTicks = PRICE_TICKS;
                event.ingressNanos = System.nanoTime();
                event.eventTimeMillis = System.currentTimeMillis();
            } finally {
                inputRing.publish(seq);
            }
        }

        private void publishOrders(int totalOrders) {
            int remaining = totalOrders;
            while (remaining > 0) {
                int batch = Math.min(remaining, BATCH_CLAIM);
                long hi = inputRing.next(batch);
                long lo = hi - (batch - 1L);
                long nowNanos = System.nanoTime();
                long nowMillis = System.currentTimeMillis();
                for (int i = 0; i < batch; i++) {
                    long seq = lo + i;
                    InputEvent event = inputRing.get(seq);
                    event.seq = seq;
                    event.type = InputEvent.TYPE_ORDER_NEW;
                    event.orderRef = nextOrderRef++;
                    event.accountId = ACCOUNT_ID;
                    event.securityId = SECURITY_ID;
                    event.side = SIDE_BUY;
                    event.qty = ORDER_QTY;
                    event.limitPx = LIMIT_TICKS;
                    event.priceTicks = 0L;
                    event.ingressNanos = nowNanos;
                    event.eventTimeMillis = nowMillis;
                }
                inputRing.publish(lo, hi);
                remaining -= batch;
            }
        }

        private void awaitTrades(long targetTrades) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (outputHandler.tradesBooked() < targetTrades) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new IllegalStateException("journaled benchmark timed out waiting for booked trades");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }

        long inputTotal() {
            return Math.max(0L, inputRing.getCursor() + 1L);
        }

        long blpTotal() {
            return matchingEngine.eventsProcessed();
        }

        long outputTotal() {
            return outputHandler.outputEvents();
        }

        long tradesTotal() {
            return outputHandler.tradesBooked();
        }

        @Override
        public void close() {
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
            // Clean up the temp journal directory.
            try (Stream<Path> paths = Files.walk(journalTempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }

        private static java.util.concurrent.ThreadFactory runnableThreadFactory(String prefix) {
            return runnable -> {
                Thread thread = new Thread(runnable, prefix);
                thread.setDaemon(true);
                return thread;
            };
        }
    }

    // ---- SimulatedReplicator ----------------------------------------------------------------

    /**
     * In-process stand-in for {@link finos.traderx.ordermatcher.lmax.NatsJournalReplicator}.
     * Spins for {@code rttNanos} after every {@code batchRecords} events (or at Disruptor
     * endOfBatch if the ring drains sooner). This mirrors the journaler's fsync cadence: one
     * replication ACK per journal batch, giving the correct {@code batch_size/RTT} ceiling.
     *
     * <p>Spinning only at Disruptor's endOfBatch is incorrect when the SimulatedReplicator
     * runs as a parallel consumer — it races ahead of the Journaler and sees one giant batch
     * per ring-drain, making the RTT penalty invisible. Spinning every batchRecords events
     * models the per-fsync ACK correctly.
     */
    private static final class SimulatedReplicator implements EventHandler<InputEvent> {
        private final long rttNanos;
        private final int batchRecords;
        private int count = 0;

        SimulatedReplicator(int rttMs, int batchRecords) {
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

    // ---- BenchmarkOutputHandler -------------------------------------------------------------

    private static final class BenchmarkOutputHandler implements EventHandler<OutputEvent> {
        private volatile long outputEvents;
        private volatile long tradesBooked;

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            outputEvents++;
            if (event.kind == OutputEvent.KIND_TRADE_BOOKED) {
                tradesBooked++;
            }
        }

        long outputEvents() {
            return outputEvents;
        }

        long tradesBooked() {
            return tradesBooked;
        }
    }
}
