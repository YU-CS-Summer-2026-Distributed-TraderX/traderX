package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.ordermatcher.risk.RiskReason;

/**
 * Output-ring marshaller (LMAX-OUTPUT-DISRUPTOR.md A4): renders BLP output events into the
 * in-memory read model, completes gateway acks, and records true end-to-end latency
 * (now − ingressNanos) at the egress point.
 */
public final class MarshallerHandler implements EventHandler<OutputEvent> {
    private static final RiskReason[] RISK_REASONS = RiskReason.values();

    private final InMemoryOrderReadModel readModel;
    private final SymbolTable symbols;
    private final HotPathMetrics metrics;
    private volatile long marshalledSeq = -1;
    private volatile long orderUpdates;
    private volatile long tradesBooked;
    private volatile long positionsUpdated;

    // Event-time peak-throughput tracker (NFR-09B08): count trades booked per fixed
    // PEAK_WINDOW_NANOS window and publish the highest per-second rate observed. Single-writer —
    // only the marshaller's Disruptor consumer thread runs onEvent — so the window accumulators are
    // plain longs (no atomics/locks); only the published peak is volatile, for the scrape thread.
    // Allocation-free and measured at event time, so the peak is independent of the Prometheus
    // scrape interval (the scrape only ever samples an already-computed running max).
    private static final long PEAK_WINDOW_NANOS = 100_000_000L; // 100ms
    private long peakWindowStartNanos;
    private long peakWindowTrades;
    private volatile long peakTradesPerSecond;

    public MarshallerHandler(InMemoryOrderReadModel readModel, SymbolTable symbols, HotPathMetrics metrics) {
        this.readModel = readModel;
        this.symbols = symbols;
        this.metrics = metrics;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        long nowNanos = System.nanoTime();
        switch (e.kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED, OutputEvent.KIND_ORDER_REJECTED,
                 OutputEvent.KIND_ORDER_PARTIALLY_FILLED, OutputEvent.KIND_ORDER_FILLED,
                 OutputEvent.KIND_ORDER_CANCELED -> {
                orderUpdates++;
                readModel.apply(e, symbols);
            }
            case OutputEvent.KIND_ORDER_NOT_FOUND -> readModel.notFound(e.inputSeq);
            case OutputEvent.KIND_TRADE_ACCEPTED, OutputEvent.KIND_TRADE_REJECTED ->
                readModel.completeTradeAck(e.inputSeq, RISK_REASONS[e.riskReason]);
            case OutputEvent.KIND_TRADE_BOOKED -> {
                tradesBooked++;
                peakWindowTrades++;
                // An accepted market trade's decision ack completes on its booking; a fill's
                // TRADE_BOOKED carries an order inputSeq with no pending trade ack (no-op).
                readModel.completeTradeAck(e.inputSeq, RiskReason.ACCEPTED);
            }
            case OutputEvent.KIND_POSITION_UPDATED -> positionsUpdated++;
            default -> { /* ignore */ }
        }
        observePeakWindow(nowNanos);
        metrics.recordEgressLatency(nowNanos - e.ingressNanos);
        marshalledSeq = sequence;
    }

    /**
     * Roll the event-time peak-throughput window. Called once per output event on the marshaller
     * thread (single writer): when the current window has spanned at least PEAK_WINDOW_NANOS,
     * convert its trade count to a per-second rate, keep the running max, and start a fresh window.
     * Steady-path cost is one timestamp comparison; it reuses the egress nanoTime, so it adds no
     * syscall and no allocation to the hot path (preserves the no-GC budget, NFR-09B08).
     */
    private void observePeakWindow(long nowNanos) {
        if (peakWindowStartNanos == 0L) {
            peakWindowStartNanos = nowNanos;
            return;
        }
        long elapsedNanos = nowNanos - peakWindowStartNanos;
        if (elapsedNanos < PEAK_WINDOW_NANOS) {
            return;
        }
        long ratePerSecond = peakWindowTrades * 1_000_000_000L / elapsedNanos;
        if (ratePerSecond > peakTradesPerSecond) {
            peakTradesPerSecond = ratePerSecond;
        }
        peakWindowStartNanos = nowNanos;
        peakWindowTrades = 0L;
    }

    public long marshalledSeq() {
        return marshalledSeq;
    }

    public long orderUpdates() {
        return orderUpdates;
    }

    public long tradesBooked() {
        return tradesBooked;
    }

    public long positionsUpdated() {
        return positionsUpdated;
    }

    /** Highest trades-per-second observed over any {@link #PEAK_WINDOW_NANOS} window since start. */
    public long peakTradesPerSecond() {
        return peakTradesPerSecond;
    }
}
