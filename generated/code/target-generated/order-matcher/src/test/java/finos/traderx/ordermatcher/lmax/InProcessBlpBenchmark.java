package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.HdrHistogram.ConcurrentHistogram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Pure in-process benchmark for the LMAX hot path around {@link LmaxEngine#executeNewOrder}:
 * producer claim/write/publish into the input ring, single-threaded BLP matching, and
 * output-ring consumption by an in-process probe. HTTP, JSON, NATS, JPA/DB, projector,
 * and journal I/O are all removed so we can measure the matcher core rather than the
 * whole TraderX platform.
 */
public final class InProcessBlpBenchmark {
    private static final byte SIDE_BUY = InputEvent.SIDE_BUY;
    private static final long EVENT_TIME_BASE = 1_750_000_000_000L;
    private static final long MARKET_PX = 100_000_000L;   // 100.000
    private static final long LIMIT_PX = 200_000_000L;    // always in the money for buys
    private static final int ORDER_QTY = 500;             // fills fully (< threshold 1000)
    private static final DecimalFormat DF = new DecimalFormat("0.00",
        DecimalFormatSymbols.getInstance(Locale.US));

    private InProcessBlpBenchmark() {}

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config = BenchmarkConfig.load();
        BenchmarkResult result = run(config);
        String report = render(config, result);
        writeReport(config.outFile(), report);
        System.out.println(report);
        System.out.println("[blp] wrote " + config.outFile().toAbsolutePath());
    }

    static BenchmarkResult run(BenchmarkConfig config) throws Exception {
        HotPathMetrics metrics = new HotPathMetrics();
        Disruptor<OutputEvent> outputDisruptor = new Disruptor<>(OutputEvent::newInstance,
            config.outputRingSize(), DaemonThreadFactory.INSTANCE, ProducerType.SINGLE,
            new YieldingWaitStrategy());
        OutputProbe probe = new OutputProbe(metrics);
        outputDisruptor.handleEventsWith(probe);
        outputDisruptor.start();

        int poolSize = Math.toIntExact(Math.min(Integer.MAX_VALUE - 4096L,
            config.warmupOrders() + config.measureOrders() + 4096L));
        MatchingEngine blp = new MatchingEngine(new OutputPublisher(outputDisruptor.getRingBuffer()),
            metrics, Math.max(16, config.securities() * 2), 1000, poolSize, 8192);
        Disruptor<InputEvent> inputDisruptor = new Disruptor<>(InputEvent::newInstance,
            config.inputRingSize(), DaemonThreadFactory.INSTANCE, ProducerType.MULTI,
            new YieldingWaitStrategy());
        inputDisruptor.handleEventsWith(blp);
        inputDisruptor.start();
        RingBuffer<InputEvent> inputRing = inputDisruptor.getRingBuffer();

        try {
            primePrices(inputRing, config.securities());
            awaitDrained(inputRing, outputDisruptor.getRingBuffer(), blp, probe);

            long nextOrderRef = 1;
            nextOrderRef = submitOrders(inputRing, nextOrderRef, config.warmupOrders(), config.securities());
            awaitTradeCount(probe::tradeBookedCount, config.warmupOrders(), "warmup trades");
            awaitDrained(inputRing, outputDisruptor.getRingBuffer(), blp, probe);

            metrics.blpEventHistogram().reset();
            metrics.matchHistogram().reset();
            metrics.egressHistogram().reset();
            metrics.journalHistogram().reset();
            probe.beginMeasurement();

            long eventsBefore = blp.eventsProcessed();
            long autoFillAttemptsBefore = blp.autoFillAttempts();
            long autoFillSuccessBefore = blp.autoFillSuccess();
            long backpressureBefore = metrics.backpressureWaits();
            long outputBefore = probe.outputEvents();
            long startNanos = System.nanoTime();
            nextOrderRef = submitOrders(inputRing, nextOrderRef, config.measureOrders(), config.securities());
            awaitTradeCount(probe::tradeBookedCount, config.measureOrders(), "measured trades");
            awaitDrained(inputRing, outputDisruptor.getRingBuffer(), blp, probe);
            long elapsedNanos = System.nanoTime() - startNanos;

            return new BenchmarkResult(
                config.measureOrders(),
                elapsedNanos,
                blp.eventsProcessed() - eventsBefore,
                blp.autoFillAttempts() - autoFillAttemptsBefore,
                blp.autoFillSuccess() - autoFillSuccessBefore,
                metrics.backpressureWaits() - backpressureBefore,
                probe.tradeBookedCount(),
                probe.outputEvents() - outputBefore,
                probe.peakTradesPerSecond(),
                percentileMicros(metrics.blpEventHistogram(), 50),
                percentileMicros(metrics.blpEventHistogram(), 95),
                percentileMicros(metrics.blpEventHistogram(), 99),
                maxMicros(metrics.blpEventHistogram()),
                percentileMicros(metrics.matchHistogram(), 50),
                percentileMicros(metrics.matchHistogram(), 95),
                percentileMicros(metrics.matchHistogram(), 99),
                maxMicros(metrics.matchHistogram()),
                percentileMicros(metrics.egressHistogram(), 50),
                percentileMicros(metrics.egressHistogram(), 95),
                percentileMicros(metrics.egressHistogram(), 99),
                maxMicros(metrics.egressHistogram())
            );
        } finally {
            inputDisruptor.shutdown(30, TimeUnit.SECONDS);
            outputDisruptor.shutdown(30, TimeUnit.SECONDS);
        }
    }

    private static void primePrices(RingBuffer<InputEvent> ring, int securities) {
        for (int securityId = 0; securityId < securities; securityId++) {
            publish(ring, InputEvent.TYPE_PRICE_TICK, 0, 0, securityId, (byte) 0, 0, 0L, MARKET_PX, 0);
        }
    }

    private static long submitOrders(RingBuffer<InputEvent> ring, long nextOrderRef, long orders, int securities) {
        for (long i = 0; i < orders; i++) {
            int securityId = (int) (i % securities);
            publish(ring, InputEvent.TYPE_ORDER_NEW, (int) nextOrderRef++, 42422, securityId,
                SIDE_BUY, ORDER_QTY, LIMIT_PX, 0L, i + 1);
        }
        return nextOrderRef;
    }

    private static void publish(RingBuffer<InputEvent> ring, byte type, int orderRef, int accountId,
                                int securityId, byte side, int qty, long limitPx, long priceTicks, long seqNo) {
        long seq = ring.next();
        try {
            InputEvent e = ring.get(seq);
            e.seq = seq;
            e.type = type;
            e.orderRef = orderRef;
            e.accountId = accountId;
            e.securityId = securityId;
            e.side = side;
            e.qty = qty;
            e.limitPx = limitPx;
            e.priceTicks = priceTicks;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = EVENT_TIME_BASE + seqNo;
        } finally {
            ring.publish(seq);
        }
    }

    private static void awaitDrained(RingBuffer<InputEvent> input, RingBuffer<OutputEvent> output,
                                     MatchingEngine blp, OutputProbe probe) {
        awaitSeq(blp::blpSeq, input.getCursor(), "BLP to consume the input ring");
        awaitSeq(probe::lastSeq, output.getCursor(), "output probe to consume the output ring");
    }

    private static void awaitTradeCount(LongSupplier actual, long target, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (actual.getAsLong() < target) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timed out waiting for " + what);
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitSeq(LongSupplier actual, long target, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (actual.getAsLong() < target) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timed out waiting for " + what);
            }
            Thread.onSpinWait();
        }
    }

    private static double percentileMicros(ConcurrentHistogram histogram, double percentile) {
        return histogram.getTotalCount() == 0 ? 0.0 : histogram.getValueAtPercentile(percentile) / 1_000.0;
    }

    private static double maxMicros(ConcurrentHistogram histogram) {
        return histogram.getTotalCount() == 0 ? 0.0 : histogram.getMaxValue() / 1_000.0;
    }

    private static String render(BenchmarkConfig config, BenchmarkResult result) {
        double elapsedSeconds = result.elapsedNanos() / 1_000_000_000.0;
        double ordersPerSecond = result.orders() / elapsedSeconds;
        double outputEventsPerSecond = result.outputEvents() / elapsedSeconds;
        StringBuilder sb = new StringBuilder(2048);
        sb.append("TraderX pure in-process BLP benchmark\n");
        sb.append("=====================================\n");
        sb.append("generated       : ").append(Instant.now()).append('\n');
        sb.append("scope           : input-ring -> BLP -> output-ring only (no HTTP, JSON, NATS, DB, projector, or journal I/O)\n");
        sb.append("module          : generated/code/target-generated/order-matcher\n");
        sb.append("command         : ./gradlew --no-daemon blpBenchmark\n");
        sb.append("warmup orders   : ").append(config.warmupOrders()).append('\n');
        sb.append("measured orders : ").append(result.orders()).append('\n');
        sb.append("securities      : ").append(config.securities()).append('\n');
        sb.append("ring sizes      : input=").append(config.inputRingSize())
            .append(" output=").append(config.outputRingSize()).append('\n');
        sb.append('\n');
        sb.append("Results\n");
        sb.append("-------\n");
        sb.append("elapsed seconds           : ").append(DF.format(elapsedSeconds)).append('\n');
        sb.append("orders/sec sustained      : ").append(DF.format(ordersPerSecond)).append('\n');
        sb.append("trade-booked/sec peak100ms: ").append(DF.format(result.peakTradesPerSecond())).append('\n');
        sb.append("output-events/sec         : ").append(DF.format(outputEventsPerSecond)).append('\n');
        sb.append("BLP events processed      : ").append(result.blpEvents()).append('\n');
        sb.append("auto-fill attempts        : ").append(result.autoFillAttempts()).append('\n');
        sb.append("auto-fill success         : ").append(result.autoFillSuccess()).append('\n');
        sb.append("trade-booked events       : ").append(result.tradeBooked()).append('\n');
        sb.append("output events             : ").append(result.outputEvents()).append('\n');
        sb.append("input backpressure waits  : ").append(result.backpressureWaits()).append('\n');
        sb.append('\n');
        sb.append("Latency (microseconds)\n");
        sb.append("----------------------\n");
        sb.append("BLP event    p50=").append(DF.format(result.blpP50Micros()))
            .append(" p95=").append(DF.format(result.blpP95Micros()))
            .append(" p99=").append(DF.format(result.blpP99Micros()))
            .append(" max=").append(DF.format(result.blpMaxMicros())).append('\n');
        sb.append("Match        p50=").append(DF.format(result.matchP50Micros()))
            .append(" p95=").append(DF.format(result.matchP95Micros()))
            .append(" p99=").append(DF.format(result.matchP99Micros()))
            .append(" max=").append(DF.format(result.matchMaxMicros())).append('\n');
        sb.append("Egress probe p50=").append(DF.format(result.egressP50Micros()))
            .append(" p95=").append(DF.format(result.egressP95Micros()))
            .append(" p99=").append(DF.format(result.egressP99Micros()))
            .append(" max=").append(DF.format(result.egressMaxMicros())).append('\n');
        return sb.toString();
    }

    private static void writeReport(Path outFile, String report) throws IOException {
        Files.createDirectories(outFile.toAbsolutePath().getParent());
        Files.writeString(outFile, report, StandardCharsets.UTF_8);
    }

    record BenchmarkConfig(long warmupOrders, long measureOrders, int securities,
                           int inputRingSize, int outputRingSize, Path outFile) {
        static BenchmarkConfig load() {
            long warmupOrders = positiveLong("blp.bench.warmupOrders", 250_000);
            long measureOrders = positiveLong("blp.bench.measureOrders", 2_000_000);
            int securities = positiveInt("blp.bench.securities", 4);
            int inputRingSize = normalizeRingSize(positiveInt("blp.bench.inputRingSize", 65_536));
            int outputRingSize = normalizeRingSize(positiveInt("blp.bench.outputRingSize", 65_536));
            String out = System.getProperty("blp.bench.out",
                "build/reports/benchmarks/blp-benchmark-latest.txt");
            return new BenchmarkConfig(warmupOrders, measureOrders, securities, inputRingSize,
                outputRingSize, Path.of(out));
        }

        private static long positiveLong(String key, long fallback) {
            long value = Long.getLong(key, fallback);
            return value > 0 ? value : fallback;
        }

        private static int positiveInt(String key, int fallback) {
            int value = Integer.getInteger(key, fallback);
            return value > 0 ? value : fallback;
        }

        private static int normalizeRingSize(int candidate) {
            int n = 1;
            while (n < candidate) {
                n <<= 1;
            }
            return Math.max(1024, n);
        }
    }

    record BenchmarkResult(long orders, long elapsedNanos, long blpEvents, long autoFillAttempts,
                           long autoFillSuccess, long backpressureWaits, long tradeBooked,
                           long outputEvents, double peakTradesPerSecond,
                           double blpP50Micros, double blpP95Micros, double blpP99Micros,
                           double blpMaxMicros, double matchP50Micros, double matchP95Micros,
                           double matchP99Micros, double matchMaxMicros, double egressP50Micros,
                           double egressP95Micros, double egressP99Micros, double egressMaxMicros) {}

    /**
     * Output-edge stand-in: counts results and records egress latency without any JSON,
     * network, or persistence side effects.
     */
    static final class OutputProbe implements EventHandler<OutputEvent> {
        private final HotPathMetrics metrics;
        private final AtomicLong tradesBaseline = new AtomicLong();
        private final AtomicLong outputsBaseline = new AtomicLong();
        private volatile long tradeBookedTotal;
        private volatile long outputTotal;
        private volatile long lastSeq = -1;
        private volatile long peakTradesPerSecond;
        private long windowStartNanos;
        private long windowTrades;

        OutputProbe(HotPathMetrics metrics) {
            this.metrics = metrics;
        }

        void beginMeasurement() {
            tradesBaseline.set(tradeBookedTotal);
            outputsBaseline.set(outputTotal);
            peakTradesPerSecond = 0;
            windowStartNanos = System.nanoTime();
            windowTrades = 0;
        }

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            outputTotal++;
            long now = System.nanoTime();
            metrics.recordEgressLatency(now - event.ingressNanos);
            if (event.kind == OutputEvent.KIND_TRADE_BOOKED) {
                tradeBookedTotal++;
                windowTrades++;
                long elapsed = now - windowStartNanos;
                if (elapsed >= TimeUnit.MILLISECONDS.toNanos(100)) {
                    long rate = Math.round(windowTrades * 1_000_000_000.0 / elapsed);
                    if (rate > peakTradesPerSecond) {
                        peakTradesPerSecond = rate;
                    }
                    windowStartNanos = now;
                    windowTrades = 0;
                }
            }
            lastSeq = sequence;
        }

        long tradeBookedCount() {
            return tradeBookedTotal - tradesBaseline.get();
        }

        long outputEvents() {
            return outputTotal - outputsBaseline.get();
        }

        long lastSeq() {
            return lastSeq;
        }

        long peakTradesPerSecond() {
            return peakTradesPerSecond;
        }
    }
}
