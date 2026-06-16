package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

/**
 * Output-ring marshaller (LMAX-OUTPUT-DISRUPTOR.md A4): renders BLP output events into the
 * in-memory read model, completes gateway acks, and records true end-to-end latency
 * (now − ingressNanos) at the egress point.
 */
public final class MarshallerHandler implements EventHandler<OutputEvent> {
    private final InMemoryOrderReadModel readModel;
    private final SymbolTable symbols;
    private final HotPathMetrics metrics;
    private volatile long marshalledSeq = -1;
    private volatile long orderUpdates;
    private volatile long tradesBooked;
    private volatile long positionsUpdated;

    public MarshallerHandler(InMemoryOrderReadModel readModel, SymbolTable symbols, HotPathMetrics metrics) {
        this.readModel = readModel;
        this.symbols = symbols;
        this.metrics = metrics;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        switch (e.kind) {
            case OutputEvent.KIND_ORDER_UPDATE -> {
                orderUpdates++;
                readModel.apply(e, symbols);
            }
            case OutputEvent.KIND_ORDER_NOT_FOUND -> readModel.notFound(e.inputSeq);
            case OutputEvent.KIND_TRADE_BOOKED -> tradesBooked++;
            case OutputEvent.KIND_POSITION_UPDATED -> positionsUpdated++;
            default -> { /* ignore */ }
        }
        metrics.recordEgressLatency(System.nanoTime() - e.ingressNanos);
        marshalledSeq = sequence;
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
}
