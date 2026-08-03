package finos.traderx.ordermatcher.lmax;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ODL-05 characterization gate: output handlers run in the matcher JVM and are part of
 * the no-GC hardening boundary. This test deliberately measures the real handler code
 * with no-op publishers/repositories so the result is handler-local allocation, not
 * JSON/NATS serialization or database driver allocation.
 *
 * Every independent output-ring handler should stay allocation-free or bounded to tiny
 * framework overhead after warm-up.
 */
class OutputHandlerAllocationGateTest {
    // 500 was not enough to reach the steady state this test's name claims to measure: a JIT
    // event landed inside the measured window and charged a one-off ~576 bytes to whichever
    // handler happened to be measuring at the time — it moved between PositionUpdateHandler and
    // TradeSubmitHandler across consecutive runs, which is how it was identified as an artifact
    // rather than a per-event cost. At 20k it is gone and repeat runs agree. Budgets below are
    // unchanged; this makes the measurement valid, it does not make the gate easier.
    private static final int WARMUP = 20_000;
    private static final int ITERATIONS = 1_000;
    private static final int VARIED_EVENTS = 128;

    @Test
    void outputHandlersCurrentlyAllocateInSteadyState() {
        var threadMxBase = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMxBase instanceof com.sun.management.ThreadMXBean,
            "com.sun.management.ThreadMXBean unavailable");
        var threadMx = (com.sun.management.ThreadMXBean) threadMxBase;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(), "thread allocation accounting unavailable");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        SymbolTable symbols = symbolsWith("IBM");
        OutputEvent order = orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true);
        OutputEvent trade = tradeEvent(symbols);
        OutputEvent position = positionEvent(symbols);

        MarshallerHandler marshaller = new MarshallerHandler(new InMemoryOrderReadModel(), symbols,
            new HotPathMetrics());
        NatsBridgeHandler natsBridge = new NatsBridgeHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        AccountTradeHandler accountTrade = new AccountTradeHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        PositionUpdateHandler positionUpdate = new PositionUpdateHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        TradeSubmitHandler tradeSubmit = new TradeSubmitHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        ProjectorHandler projectorOrder = newNoFlushProjector(symbols);
        ProjectorHandler projectorTrade = newNoFlushProjector(symbols);
        ProjectorHandler projectorPosition = newNoFlushProjector(symbols);
        long marshallerBytes = allocatedBytes(threadMx, e -> marshaller.onEvent(e, 0, true), order);
        long natsBridgeBytes = allocatedBytes(threadMx, e -> natsBridge.onEvent(e, 0, true), order);
        long accountTradeBytes = allocatedBytes(threadMx, e -> accountTrade.onEvent(e, 0, true), trade);
        long positionUpdateBytes = allocatedBytes(threadMx, e -> positionUpdate.onEvent(e, 0, true), position);
        long tradeSubmitBytes = allocatedBytes(threadMx, e -> tradeSubmit.onEvent(e, 0, true), trade);
        long projectorOrderBytes = allocatedBytes(threadMx, e -> projectorOrder.onEvent(e, 0, false), order);
        long projectorTradeBytes = allocatedBytes(threadMx, e -> projectorTrade.onEvent(e, 0, false), trade);
        long projectorPositionBytes = allocatedBytes(threadMx, e -> projectorPosition.onEvent(e, 0, false), position);

        System.out.printf(
            "ODL-05 output handler allocation bytes per %,d events: " +
                "MarshallerHandler=%d, NatsBridgeHandler=%d, AccountTradeHandler=%d, " +
                "PositionUpdateHandler=%d, TradeSubmitHandler=%d, ProjectorHandler(order)=%d, " +
                "ProjectorHandler(trade)=%d, ProjectorHandler(position)=%d%n",
            ITERATIONS, marshallerBytes, natsBridgeBytes, accountTradeBytes, positionUpdateBytes,
            tradeSubmitBytes, projectorOrderBytes, projectorTradeBytes, projectorPositionBytes);

        assertAll(
            () -> assertBudget("MarshallerHandler", marshallerBytes, 0),
            () -> assertBudget("NatsBridgeHandler", natsBridgeBytes, 0),
            () -> assertBudget("AccountTradeHandler", accountTradeBytes, 0),
            () -> assertBudget("PositionUpdateHandler", positionUpdateBytes, 512),
            () -> assertBudget("TradeSubmitHandler", tradeSubmitBytes, 512),
            () -> assertBudget("ProjectorHandler order path", projectorOrderBytes, 512),
            () -> assertBudget("ProjectorHandler trade path", projectorTradeBytes, 512),
            () -> assertBudget("ProjectorHandler position path", projectorPositionBytes, 512)
        );
    }

    @Test
    void variedOutputHandlersRemainAllocationBoundedAfterWarmup() {
        var threadMxBase = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMxBase instanceof com.sun.management.ThreadMXBean,
            "com.sun.management.ThreadMXBean unavailable");
        var threadMx = (com.sun.management.ThreadMXBean) threadMxBase;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(), "thread allocation accounting unavailable");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        SymbolTable symbols = symbolsWith("IBM");
        OutputEvent[] orders = variedEvents(symbols, OutputEvent.KIND_ORDER_ACCEPTED);
        OutputEvent[] trades = variedEvents(symbols, OutputEvent.KIND_TRADE_BOOKED);
        OutputEvent[] positions = variedEvents(symbols, OutputEvent.KIND_POSITION_UPDATED);

        NatsBridgeHandler natsBridge = new NatsBridgeHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        AccountTradeHandler accountTrade = new AccountTradeHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        PositionUpdateHandler positionUpdate = new PositionUpdateHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        TradeSubmitHandler tradeSubmit = new TradeSubmitHandler(new NoopPublisher<>(), symbols,
            new InMemoryOrderReadModel());
        ProjectorHandler projectorOrder = newNoFlushProjector(symbols);
        ProjectorHandler projectorTrade = newNoFlushProjector(symbols);
        ProjectorHandler projectorPosition = newNoFlushProjector(symbols);
        long natsBridgeBytes = allocatedBytes(threadMx, e -> natsBridge.onEvent(e, 0, true), orders);
        long accountTradeBytes = allocatedBytes(threadMx, e -> accountTrade.onEvent(e, 0, true), trades);
        long positionUpdateBytes = allocatedBytes(threadMx, e -> positionUpdate.onEvent(e, 0, true), positions);
        long tradeSubmitBytes = allocatedBytes(threadMx, e -> tradeSubmit.onEvent(e, 0, true), trades);
        long projectorOrderBytes = allocatedBytes(threadMx, e -> projectorOrder.onEvent(e, 0, false), orders);
        long projectorTradeBytes = allocatedBytes(threadMx, e -> projectorTrade.onEvent(e, 0, false), trades);
        long projectorPositionBytes = allocatedBytes(threadMx, e -> projectorPosition.onEvent(e, 0, false), positions);

        System.out.printf(
            "ODL-05 varied output allocation bytes per %,d events (%d-value set): " +
                "NatsBridgeHandler=%d, AccountTradeHandler=%d, " +
                "PositionUpdateHandler=%d, TradeSubmitHandler=%d, ProjectorHandler(order)=%d, " +
                "ProjectorHandler(trade)=%d, ProjectorHandler(position)=%d%n",
            ITERATIONS, VARIED_EVENTS, natsBridgeBytes, accountTradeBytes,
            positionUpdateBytes, tradeSubmitBytes, projectorOrderBytes, projectorTradeBytes,
            projectorPositionBytes);

        assertAll(
            () -> assertBudget("varied NatsBridgeHandler", natsBridgeBytes, 512),
            () -> assertBudget("varied AccountTradeHandler", accountTradeBytes, 512),
            () -> assertBudget("varied PositionUpdateHandler", positionUpdateBytes, 512),
            () -> assertBudget("varied TradeSubmitHandler", tradeSubmitBytes, 512),
            () -> assertBudget("varied ProjectorHandler order path", projectorOrderBytes, 512),
            () -> assertBudget("varied ProjectorHandler trade path", projectorTradeBytes, 512),
            () -> assertBudget("varied ProjectorHandler position path", projectorPositionBytes, 512)
        );
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean threadMx, Consumer<OutputEvent> handler,
                                       OutputEvent event) {
        for (int i = 0; i < WARMUP; i++) {
            handler.accept(event);
        }
        long threadId = Thread.currentThread().threadId();
        threadMx.getThreadAllocatedBytes(threadId);
        long before = threadMx.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < ITERATIONS; i++) {
            handler.accept(event);
        }
        return threadMx.getThreadAllocatedBytes(threadId) - before;
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean threadMx, Consumer<OutputEvent> handler,
                                       OutputEvent[] events) {
        for (int i = 0; i < WARMUP; i++) {
            handler.accept(events[i % events.length]);
        }
        long threadId = Thread.currentThread().threadId();
        threadMx.getThreadAllocatedBytes(threadId);
        long before = threadMx.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < ITERATIONS; i++) {
            handler.accept(events[i % events.length]);
        }
        return threadMx.getThreadAllocatedBytes(threadId) - before;
    }

    private static void assertBudget(String handler, long bytes, long maxBytes) {
        assertTrue(bytes <= maxBytes, () -> handler + " allocated " + bytes + " bytes, budget " + maxBytes);
    }

    private static ProjectorHandler newNoFlushProjector(SymbolTable symbols) {
        return new ProjectorHandler(null, null, null, symbols, Integer.MAX_VALUE, new HotPathMetrics());
    }

    private static SymbolTable symbolsWith(String ticker) {
        SymbolTable symbols = new SymbolTable(16);
        symbols.idFor(ticker);
        return symbols;
    }

    private static OutputEvent orderEvent(SymbolTable symbols, byte kind, boolean publishNats) {
        OutputEvent e = new OutputEvent();
        e.kind = kind;
        e.flags = kind == OutputEvent.KIND_ORDER_ACCEPTED ? OutputEvent.FLAG_CREATE : 0;
        e.publishNats = publishNats;
        e.orderRef = 42;
        e.accountId = 22214;
        e.securityId = symbols.idFor("IBM");
        e.side = (byte) OrderSide.Buy.ordinal();
        e.quantity = 100;
        e.remainingQty = 100;
        e.limitPx = Px.toTicks(new BigDecimal("102.000"));
        e.status = RestingOrder.STATUS_NEW;
        e.lastExecPx = Px.NONE;
        e.createdAtMillis = 1_700_000_000_000L;
        e.updatedAtMillis = 1_700_000_000_000L;
        e.marketPx = Px.NONE;
        e.ingressNanos = System.nanoTime();
        return e;
    }

    private static OutputEvent tradeEvent(SymbolTable symbols) {
        OutputEvent e = orderEvent(symbols, OutputEvent.KIND_TRADE_BOOKED, false);
        e.remainingQty = 75;
        e.status = RestingOrder.STATUS_PARTIALLY_FILLED;
        e.lastExecPx = Px.toTicks(new BigDecimal("101.125"));
        e.lastFillQty = 25;
        e.tradeQty = 25;
        e.tradeSeq = 7;
        e.tradePx = Px.toTicks(new BigDecimal("101.125"));
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

    private static OutputEvent[] variedEvents(SymbolTable symbols, byte kind) {
        String[] tickers = {"IBM", "MSFT", "AAPL", "JPM", "GS", "NVDA", "META", "BAC"};
        for (String ticker : tickers) {
            symbols.idFor(ticker);
        }
        OutputEvent[] events = new OutputEvent[VARIED_EVENTS];
        for (int i = 0; i < events.length; i++) {
            String ticker = tickers[i & (tickers.length - 1)];
            OutputEvent e;
            if (kind == OutputEvent.KIND_POSITION_UPDATED) {
                e = positionEvent(symbols);
            } else if (kind == OutputEvent.KIND_TRADE_BOOKED) {
                e = tradeEvent(symbols);
            } else {
                e = orderEvent(symbols, kind, true);
            }
            e.orderRef = 1_000 + i;
            e.accountId = 10_000 + (i & 63);
            e.securityId = symbols.idFor(ticker);
            e.quantity = 100 + i;
            e.remainingQty = Math.max(0, e.quantity - 25);
            e.limitPx = Px.toTicks(BigDecimal.valueOf(100_000L + i, 3));
            e.lastExecPx = Px.toTicks(BigDecimal.valueOf(99_000L + i, 3));
            e.lastFillQty = 25 + (i & 7);
            e.tradeQty = e.lastFillQty;
            e.tradeSeq = 10_000L + i;
            e.tradePx = e.lastExecPx;
            e.positionQty = 50 + i;
            e.averageCostBasisPx = e.lastExecPx;
            e.positionAvgCostTicks = e.averageCostBasisPx;
            e.createdAtMillis = 1_700_000_000_000L + i;
            e.updatedAtMillis = e.createdAtMillis;
            events[i] = e;
        }
        return events;
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
