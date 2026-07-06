package finos.traderx.ordermatcher.service;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Samples the live matcher counters every 100ms and exports rolling current/peak throughput
 * gauges for the running TraderX service.
 */
@Component
public final class LiveThroughputService implements InitializingBean, DisposableBean {
    private static final long SAMPLE_PERIOD_MS = 100L;

    private final LmaxEngine engine;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "live-throughput-sampler");
        thread.setDaemon(true);
        return thread;
    });

    private final ThroughputMetricSet input = new ThroughputMetricSet();
    private final ThroughputMetricSet blp = new ThroughputMetricSet();
    private final ThroughputMetricSet output = new ThroughputMetricSet();
    private final ThroughputMetricSet trades = new ThroughputMetricSet();

    public LiveThroughputService(LmaxEngine engine) {
        this.engine = engine;
    }

    @Override
    public void afterPropertiesSet() {
        sampler.scheduleAtFixedRate(this::sampleSafely, SAMPLE_PERIOD_MS, SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        sampler.shutdownNow();
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (Exception ignored) {
            // Keep the matcher alive even if telemetry sampling misbehaves.
        }
    }

    private void sample() {
        long nowNanos = System.nanoTime();
        long inputTotal = Math.max(0L, engine.inputPublishedSeq() + 1L);
        long blpTotal = engine.blp() == null ? 0L : engine.blp().eventsProcessed();
        long outputTotal = engine.orderUpdatesOut() + engine.tradesBookedOut() + engine.positionsUpdatedOut();
        long tradesTotal = engine.tradesBookedOut();
        input.observe(inputTotal, nowNanos);
        blp.observe(blpTotal, nowNanos);
        output.observe(outputTotal, nowNanos);
        trades.observe(tradesTotal, nowNanos);
    }

    public void appendPrometheusMetrics(StringBuilder sb) {
        appendGauge(sb, "traderx_input_events_per_second_current",
            "Current input-ring throughput, sampled in-process over rolling 100ms windows.",
            input.currentPerSecond());
        appendGauge(sb, "traderx_input_events_per_second_peak",
            "Peak input-ring throughput since matcher start, sampled in-process over rolling 100ms windows.",
            input.peakPerSecond());
        appendGauge(sb, "traderx_blp_events_per_second_current",
            "Current BLP throughput, sampled in-process over rolling 100ms windows.",
            blp.currentPerSecond());
        appendGauge(sb, "traderx_blp_events_per_second_peak",
            "Peak BLP throughput since matcher start, sampled in-process over rolling 100ms windows.",
            blp.peakPerSecond());
        appendGauge(sb, "traderx_output_events_per_second_current",
            "Current output-ring throughput, sampled in-process over rolling 100ms windows.",
            output.currentPerSecond());
        appendGauge(sb, "traderx_output_events_per_second_peak",
            "Peak output-ring throughput since matcher start, sampled in-process over rolling 100ms windows.",
            output.peakPerSecond());
        appendGauge(sb, "traderx_trades_per_second_current",
            "Current booked-trade throughput, sampled in-process over rolling 100ms windows.",
            trades.currentPerSecond());
        appendGauge(sb, "traderx_trades_per_second_peak",
            "Peak booked-trade throughput since matcher start, sampled in-process over rolling 100ms windows.",
            trades.peakPerSecond());
    }

    private static void appendGauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
