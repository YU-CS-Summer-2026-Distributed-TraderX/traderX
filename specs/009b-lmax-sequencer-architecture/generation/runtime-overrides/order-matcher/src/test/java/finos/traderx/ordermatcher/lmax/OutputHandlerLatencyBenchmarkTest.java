package finos.traderx.ordermatcher.lmax;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Local ODL-01/ODL-05 benchmark. This is intentionally a Gradle-invoked microbenchmark,
 * not a correctness gate: run `./gradlew outputLatencyBenchmark` and compare the printed
 * percentiles before/after each output-handler change.
 */
class OutputHandlerLatencyBenchmarkTest {
    private static final int WARMUP = 20_000;

    @Test
    void outputHandlerLatencyPercentiles() {
        assumeTrue(Boolean.getBoolean("outputLatencyBenchmark"),
            "run with ./gradlew outputLatencyBenchmark");

        int iterations = Integer.getInteger("outputLatencyBenchmark.iterations", 200_000);
        SymbolTable symbols = symbolsWith("IBM");
        OutputEvent order = orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true);
        OutputEvent trade = tradeEvent(symbols);
        OutputEvent position = positionEvent(symbols);

        NoopPublisher<OrderResponse> orderPublisher = new NoopPublisher<>();
        NoopPublisher<AccountTrade> accountTradePublisher = new NoopPublisher<>();
        NoopPublisher<PositionUpdate> positionPublisher = new NoopPublisher<>();
        NoopPublisher<TradeOrder> tradePublisher = new NoopPublisher<>();

        NatsBridgeHandler natsBridge = new NatsBridgeHandler(orderPublisher, symbols, new InMemoryOrderReadModel());
        AccountTradeHandler accountTrade = new AccountTradeHandler(accountTradePublisher, symbols,
            new InMemoryOrderReadModel());
        PositionUpdateHandler positionUpdate = new PositionUpdateHandler(positionPublisher, symbols,
            new InMemoryOrderReadModel());
        TradeSubmitHandler tradeSubmit = new TradeSubmitHandler(tradePublisher, symbols, new InMemoryOrderReadModel());
        ProjectorHandler projectorTrade = newNoFlushProjector(symbols);
        ProjectorHandler projectorPosition = newNoFlushProjector(symbols);
        OutputExternalEdgeHandler externalEdge = new OutputExternalEdgeHandler(
            WARMUP + iterations + 16, new NoopOutputHandler());

        System.out.printf("ODL output handler latency benchmark: iterations=%d warmup=%d%n", iterations, WARMUP);
        try {
            report("OutputExternalEdgeHandler", measure(iterations, e -> {
                try {
                    externalEdge.onEvent(e, 0, true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }, trade));
            report("NatsBridgeHandler(order)", measure(iterations, e -> natsBridge.onEvent(e, 0, true), order));
            report("AccountTradeHandler", measure(iterations, e -> accountTrade.onEvent(e, 0, true), trade));
            report("PositionUpdateHandler", measure(iterations, e -> positionUpdate.onEvent(e, 0, true), position));
            report("TradeSubmitHandler", measure(iterations, e -> tradeSubmit.onEvent(e, 0, true), trade));
            report("ProjectorHandler(trade,no-flush)", measure(iterations, e -> projectorTrade.onEvent(e, 0, false), trade));
            report("ProjectorHandler(position,no-flush)",
                measure(iterations, e -> projectorPosition.onEvent(e, 0, false), position));
        } finally {
            externalEdge.close();
        }
    }

    private static long[] measure(int iterations, Consumer<OutputEvent> handler, OutputEvent event) {
        for (int i = 0; i < WARMUP; i++) {
            handler.accept(event);
        }
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long before = System.nanoTime();
            handler.accept(event);
            samples[i] = System.nanoTime() - before;
        }
        Arrays.sort(samples);
        return samples;
    }

    private static void report(String name, long[] sortedSamples) {
        long p50 = percentile(sortedSamples, 50);
        long p95 = percentile(sortedSamples, 95);
        long p99 = percentile(sortedSamples, 99);
        long max = sortedSamples[sortedSamples.length - 1];
        System.out.printf("%-34s p50=%d ns p95=%d ns p99=%d ns max=%d ns%n", name, p50, p95, p99, max);
    }

    private static long percentile(long[] sortedSamples, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedSamples.length) - 1;
        return sortedSamples[Math.max(0, Math.min(index, sortedSamples.length - 1))];
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

    private static final class NoopOutputHandler implements com.lmax.disruptor.EventHandler<OutputEvent> {
        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {}
    }

    private static final class NoopPublisher<T> implements Publisher<T> {
        private final AtomicLong published = new AtomicLong();

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
