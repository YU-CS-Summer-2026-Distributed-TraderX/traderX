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
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
import finos.traderx.ordermatcher.lmax.Px;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Pure in-process benchmark mode for the matcher core, exposed through the running service so
 * Grafana can display the true BLP ceiling separately from the full platform path.
 */
@Component
public final class BlpBenchmarkService implements DisposableBean {
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
    private final int defaultBookPoolSize;
    private final int defaultPositionCapacity;
    private final int fillFullThreshold;
    private final int maxSecurities;
    private final String defaultWaitStrategy;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "blp-benchmark-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "blp-benchmark-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final Object monitor = new Object();

    private volatile BenchmarkRun lastRun = BenchmarkRun.idle();

    public BlpBenchmarkService(
        @Value("${benchmark.blp.warmup-orders:250000}") int defaultWarmupOrders,
        @Value("${benchmark.blp.measured-orders:2000000}") int defaultMeasuredOrders,
        @Value("${benchmark.blp.ring-size:65536}") int defaultRingSize,
        @Value("${benchmark.blp.wait-strategy:yielding}") String defaultWaitStrategy,
        @Value("${blp.book.pool-size:65536}") int defaultBookPoolSize,
        @Value("${blp.positions.capacity:8192}") int defaultPositionCapacity,
        @Value("${order.matcher.fill-full-threshold:1000}") int fillFullThreshold,
        @Value("${blp.books.max-securities:4096}") int maxSecurities
    ) {
        this.defaultWarmupOrders = defaultWarmupOrders;
        this.defaultMeasuredOrders = defaultMeasuredOrders;
        this.defaultRingSize = defaultRingSize;
        this.defaultWaitStrategy = defaultWaitStrategy;
        this.defaultBookPoolSize = defaultBookPoolSize;
        this.defaultPositionCapacity = defaultPositionCapacity;
        this.fillFullThreshold = fillFullThreshold;
        this.maxSecurities = maxSecurities;
    }

    @Override
    public void destroy() {
        runner.shutdownNow();
        sampler.shutdownNow();
    }

    public Map<String, Object> startRun(Integer warmupOrders, Integer measuredOrders,
                                        Integer ringSize, String waitStrategy) {
        synchronized (monitor) {
            if (lastRun.running) {
                return lastRun.snapshot();
            }
            BenchmarkRun run = new BenchmarkRun(
                positiveOrDefault(warmupOrders, defaultWarmupOrders),
                positiveOrDefault(measuredOrders, defaultMeasuredOrders),
                normalizeRingSize(positiveOrDefault(ringSize, defaultRingSize)),
                waitStrategy == null || waitStrategy.isBlank() ? defaultWaitStrategy : waitStrategy.trim().toLowerCase(Locale.ROOT)
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
        appendGauge(sb, "traderx_benchmark_blp_running",
            "1 while the pure in-process BLP benchmark is running, otherwise 0.",
            run.running ? 1 : 0);
        appendGauge(sb, "traderx_benchmark_input_events_per_second_current",
            "Current benchmark input throughput over rolling 100ms windows.",
            run.input.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_input_events_per_second_peak",
            "Peak benchmark input throughput over rolling 100ms windows.",
            run.input.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_blp_events_per_second_current",
            "Current benchmark BLP throughput over rolling 100ms windows.",
            run.blp.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_blp_events_per_second_peak",
            "Peak benchmark BLP throughput over rolling 100ms windows.",
            run.blp.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_output_events_per_second_current",
            "Current benchmark output throughput over rolling 100ms windows.",
            run.output.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_output_events_per_second_peak",
            "Peak benchmark output throughput over rolling 100ms windows.",
            run.output.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_trades_per_second_current",
            "Current benchmark booked-trade throughput over rolling 100ms windows.",
            run.trades.currentPerSecond());
        appendGauge(sb, "traderx_benchmark_trades_per_second_peak",
            "Peak benchmark booked-trade throughput over rolling 100ms windows.",
            run.trades.peakPerSecond());
        appendGauge(sb, "traderx_benchmark_blp_last_sustained_orders_per_second",
            "Last completed benchmark sustained order throughput across the measured phase.",
            run.lastSustainedOrdersPerSecond);
        appendGauge(sb, "traderx_benchmark_blp_last_duration_millis",
            "Last completed benchmark measured-phase duration in milliseconds.",
            run.lastDurationMillis);
        appendGauge(sb, "traderx_benchmark_blp_last_measured_orders_total",
            "Measured orders in the last completed benchmark run.",
            run.measuredOrders);
        appendGauge(sb, "traderx_benchmark_blp_last_warmup_orders_total",
            "Warmup orders in the last completed benchmark run.",
            run.warmupOrders);
    }

    private void execute(BenchmarkRun run) {
        ScheduledFuture<?> future = null;
        try (BenchmarkHarness harness = new BenchmarkHarness(run.ringSize, run.waitStrategy,
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

    private static final class BenchmarkRun {
        private final int warmupOrders;
        private final int measuredOrders;
        private final int ringSize;
        private final String waitStrategy;
        private final ThroughputMetricSet input = new ThroughputMetricSet();
        private final ThroughputMetricSet blp = new ThroughputMetricSet();
        private final ThroughputMetricSet output = new ThroughputMetricSet();
        private final ThroughputMetricSet trades = new ThroughputMetricSet();
        private final Instant requestedAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile boolean running = true;
        private volatile boolean measuring;
        private volatile boolean success;
        private volatile String phase = "queued";
        private volatile String error = "";
        private volatile long lastSustainedOrdersPerSecond;
        private volatile long lastDurationMillis;
        private volatile long inputBaseline;
        private volatile long blpBaseline;
        private volatile long outputBaseline;
        private volatile long tradeBaseline;

        private BenchmarkRun(int warmupOrders, int measuredOrders, int ringSize, String waitStrategy) {
            this.warmupOrders = warmupOrders;
            this.measuredOrders = measuredOrders;
            this.ringSize = ringSize;
            this.waitStrategy = waitStrategy;
        }

        private static BenchmarkRun idle() {
            BenchmarkRun run = new BenchmarkRun(0, 0, 0, "yielding");
            run.running = false;
            run.phase = "idle";
            run.success = false;
            return run;
        }

        private void beginMeasurement(BenchmarkHarness harness) {
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

        private Map<String, Object> snapshot() {
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

    private static final class BenchmarkHarness implements AutoCloseable {
        private final Disruptor<InputEvent> inputDisruptor;
        private final RingBuffer<InputEvent> inputRing;
        private final Disruptor<OutputEvent> outputDisruptor;
        private final MatchingEngine matchingEngine;
        private final BenchmarkOutputHandler outputHandler;
        private int nextOrderRef = 1;

        private BenchmarkHarness(int ringSize, String waitStrategy, int fillFullThreshold,
                                 int maxSecurities, int bookPoolSize, int positionCapacity) {
            outputDisruptor = new Disruptor<>(OutputEvent::newInstance, ringSize,
                runnableThreadFactory("blp-benchmark-output"), ProducerType.SINGLE, waitStrategy(waitStrategy));
            outputHandler = new BenchmarkOutputHandler();
            outputDisruptor.handleEventsWith(outputHandler);
            RingBuffer<OutputEvent> outputRing = outputDisruptor.start();

            matchingEngine = new MatchingEngine(new OutputPublisher(outputRing), HotPathMetrics.noop(),
                Math.max(16, maxSecurities), fillFullThreshold, Math.max(bookPoolSize, ringSize), positionCapacity);
            inputDisruptor = new Disruptor<>(InputEvent::newInstance, ringSize,
                runnableThreadFactory("blp-benchmark-input"), ProducerType.SINGLE, waitStrategy(waitStrategy));
            inputDisruptor.handleEventsWith(matchingEngine);
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
            long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (outputHandler.tradesBooked() < targetTrades) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new IllegalStateException("benchmark timed out waiting for booked trades");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }

        private long inputTotal() {
            return Math.max(0L, inputRing.getCursor() + 1L);
        }

        private long blpTotal() {
            return matchingEngine.eventsProcessed();
        }

        private long outputTotal() {
            return outputHandler.outputEvents();
        }

        private long tradesTotal() {
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
        }

        private static java.util.concurrent.ThreadFactory runnableThreadFactory(String prefix) {
            return runnable -> {
                Thread thread = new Thread(runnable, prefix);
                thread.setDaemon(true);
                return thread;
            };
        }
    }

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

        private long outputEvents() {
            return outputEvents;
        }

        private long tradesBooked() {
            return tradesBooked;
        }
    }
}
