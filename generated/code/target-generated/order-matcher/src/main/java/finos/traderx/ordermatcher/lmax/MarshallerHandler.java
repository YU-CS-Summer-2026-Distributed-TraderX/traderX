package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

/**
 * Output-ring marshaller (LMAX-OUTPUT-DISRUPTOR.md A4): renders BLP output events into the
 * in-memory read model, completes gateway acks, and records true end-to-end latency
 * (now − ingressNanos) at the egress point.
 */
public final class MarshallerHandler implements EventHandler<OutputEvent> {
    private static final long PEAK_WINDOW_NANOS = 100_000_000L;

    private final InMemoryOrderReadModel readModel;
    private final SymbolTable symbols;
    private final HotPathMetrics metrics;
    private volatile long marshalledSeq = -1;
    private volatile long orderUpdates;
    private volatile long tradesBooked;
    private volatile long positionsUpdated;
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
            case OutputEvent.KIND_TRADE_BOOKED -> {
                tradesBooked++;
                peakWindowTrades++;
            }
            case OutputEvent.KIND_POSITION_UPDATED -> positionsUpdated++;
            default -> { /* ignore */ }
        }
        observePeakWindow(nowNanos);
        metrics.recordEgressLatency(nowNanos - e.ingressNanos);
        marshalledSeq = sequence;
    }

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

    public long peakTradesPerSecond() {
        return peakTradesPerSecond;
    }
}
