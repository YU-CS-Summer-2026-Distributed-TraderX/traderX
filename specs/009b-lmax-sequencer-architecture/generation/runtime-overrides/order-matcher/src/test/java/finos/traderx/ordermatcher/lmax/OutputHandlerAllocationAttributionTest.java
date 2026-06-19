package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * On-demand ODL-05 allocation attribution. This is intentionally not a gate: run it when
 * the aggregate gate shows a residual allocation that needs explaining.
 *
 *     ./gradlew test --tests finos.traderx.ordermatcher.lmax.OutputHandlerAllocationAttributionTest \
 *       -DallocationAttribution=true
 */
class OutputHandlerAllocationAttributionTest {
    private static final int WARMUP = 1_000;
    private static final int ITERATIONS = 10_000;

    @Test
    void attributeOutputHandlerResidualAllocation() throws Exception {
        assumeTrue(Boolean.getBoolean("allocationAttribution"),
            "run with -DallocationAttribution=true");
        var threadMxBase = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMxBase instanceof com.sun.management.ThreadMXBean,
            "com.sun.management.ThreadMXBean unavailable");
        var threadMx = (com.sun.management.ThreadMXBean) threadMxBase;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(), "thread allocation accounting unavailable");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        SymbolTable symbols = symbolsWith("IBM");
        OutputEvent trade = tradeEvent(symbols);
        OutputEvent position = positionEvent(symbols);
        OutputValueCache values = new OutputValueCache();
        PositionUpdate positionPayload = new PositionUpdate();
        TradeOrder tradePayload = new TradeOrder();
        ProjectorHandler projectorTrade = newNoFlushProjector(symbols);
        ProjectorHandler projectorPosition = newNoFlushProjector(symbols);
        PositionUpdateHandler positionHandler = new PositionUpdateHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        NoopPublisher<TradeOrder> tradePublisher = new NoopPublisher<>();
        TradeSubmitHandler tradeSubmitHandler = new TradeSubmitHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        OutputEvent copyTarget = new OutputEvent();
        OutputExternalEdgeHandler externalEdge = new OutputExternalEdgeHandler(
            WARMUP + ITERATIONS + 16, new NoopOutputHandler());

        try {
            long priceCacheBytes = allocatedBytes(threadMx, () -> values.priceFor(position.averageCostBasisPx));
            long integerCacheBytes = allocatedBytes(threadMx, () -> values.integerFor(position.accountId));
            long dateReuseBytes = allocatedBytes(threadMx, () -> positionPayload.copyFromEvent(position, symbols, values));
            long positionHandlerBytes = allocatedBytes(threadMx, () -> positionHandler.onEvent(position, 0, true));
            long tradePayloadCopyBytes = allocatedBytes(threadMx, () -> tradePayload.copyFromEvent(trade, symbols, values));
            long tradePublisherBytes = allocatedBytes(threadMx, () -> tradePublisher.publish("/trades", tradePayload));
            long tradeSubmitHandlerBytes = allocatedBytes(threadMx, () -> tradeSubmitHandler.onEvent(trade, 0, true));
            long projectorTradeBytes = allocatedBytes(threadMx, () -> projectorTrade.onEvent(trade, 0, false));
            long projectorPositionBytes = allocatedBytes(threadMx, () -> projectorPosition.onEvent(position, 0, false));
            long eventCopyBytes = allocatedBytes(threadMx, () -> copyTarget.copyFrom(trade));
            long externalHandoffBytes = allocatedBytes(threadMx, () -> {
                try {
                    externalEdge.onEvent(trade, 0, true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            });

            System.out.printf(
                "ODL-05 allocation attribution bytes per %,d ops: " +
                    "OutputValueCache.priceFor=%d, OutputValueCache.integerFor=%d, " +
                    "PositionUpdate.copyFromEvent=%d, PositionUpdateHandler=%d, " +
                    "TradeOrder.copyFromEvent=%d, TradePublisher.publish=%d, TradeSubmitHandler=%d, " +
                    "ProjectorHandler(trade,no-flush)=%d, ProjectorHandler(position,no-flush)=%d, " +
                    "OutputEvent.copyFrom=%d, OutputExternalEdgeHandler=%d%n",
                ITERATIONS, priceCacheBytes, integerCacheBytes, dateReuseBytes, positionHandlerBytes,
                tradePayloadCopyBytes, tradePublisherBytes, tradeSubmitHandlerBytes, projectorTradeBytes,
                projectorPositionBytes, eventCopyBytes, externalHandoffBytes);
        } finally {
            externalEdge.close();
        }
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean threadMx, CheckedRunnable action)
        throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            action.run();
        }
        long threadId = Thread.currentThread().threadId();
        threadMx.getThreadAllocatedBytes(threadId);
        long before = threadMx.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < ITERATIONS; i++) {
            action.run();
        }
        return threadMx.getThreadAllocatedBytes(threadId) - before;
    }

    private static ProjectorHandler newNoFlushProjector(SymbolTable symbols) {
        return new ProjectorHandler(null, null, null, symbols, Integer.MAX_VALUE, new HotPathMetrics());
    }

    private static SymbolTable symbolsWith(String ticker) {
        SymbolTable symbols = new SymbolTable(16);
        symbols.idFor(ticker);
        return symbols;
    }

    private static OutputEvent tradeEvent(SymbolTable symbols) {
        OutputEvent e = new OutputEvent();
        e.kind = OutputEvent.KIND_TRADE_BOOKED;
        e.orderRef = 42;
        e.accountId = 22214;
        e.securityId = symbols.idFor("IBM");
        e.side = (byte) OrderSide.Buy.ordinal();
        e.quantity = 100;
        e.remainingQty = 75;
        e.status = RestingOrder.STATUS_PARTIALLY_FILLED;
        e.lastExecPx = Px.toTicks(new BigDecimal("101.125"));
        e.lastFillQty = 25;
        e.tradeQty = 25;
        e.tradeSeq = 7;
        e.tradePx = Px.toTicks(new BigDecimal("101.125"));
        e.createdAtMillis = 1_700_000_000_000L;
        e.updatedAtMillis = 1_700_000_000_000L;
        e.ingressNanos = System.nanoTime();
        return e;
    }

    private static OutputEvent positionEvent(SymbolTable symbols) {
        OutputEvent e = new OutputEvent();
        e.kind = OutputEvent.KIND_POSITION_UPDATED;
        e.accountId = 22214;
        e.securityId = symbols.idFor("IBM");
        e.positionQty = 175;
        e.averageCostBasisPx = Px.toTicks(new BigDecimal("101.125"));
        e.positionAvgCostTicks = e.averageCostBasisPx;
        e.updatedAtMillis = 1_700_000_000_000L;
        e.ingressNanos = System.nanoTime();
        return e;
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class NoopOutputHandler implements EventHandler<OutputEvent> {
        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {}
    }

    private static final class NoopPublisher<T> implements Publisher<T> {
        private final AtomicInteger published = new AtomicInteger();

        @Override
        public void publish(T message) throws PubSubException {
            published.incrementAndGet();
        }

        @Override
        public void publish(String topic, T message) throws PubSubException {
            published.addAndGet(topic.length() + System.identityHashCode(message));
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void connect() throws PubSubException {}

        @Override
        public void disconnect() throws PubSubException {}
    }
}
