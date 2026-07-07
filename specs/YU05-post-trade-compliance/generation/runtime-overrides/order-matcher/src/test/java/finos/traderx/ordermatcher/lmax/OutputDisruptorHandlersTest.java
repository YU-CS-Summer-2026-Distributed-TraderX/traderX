package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputDisruptorHandlersTest {

    @Test
    void outputPublisherMapsOrderLifecycleKinds() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 16);
        OutputPublisher publisher = new OutputPublisher(ring);
        RestingOrder order = order(RestingOrder.STATUS_NEW, 100);

        publisher.emitOrderUpdate(order, 1, OutputEvent.FLAG_CREATE, true, Px.NONE, 100L);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, ring.get(0).kind);

        order.status = RestingOrder.STATUS_PARTIALLY_FILLED;
        publisher.emitOrderUpdate(order, 2, OutputEvent.FLAG_PARTIAL_FILL, true, Px.NONE, 100L);
        assertEquals(OutputEvent.KIND_ORDER_PARTIALLY_FILLED, ring.get(1).kind);

        order.status = RestingOrder.STATUS_FILLED;
        publisher.emitOrderUpdate(order, 3, OutputEvent.FLAG_FILL, true, Px.NONE, 100L);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, ring.get(2).kind);

        order.status = RestingOrder.STATUS_CANCELED;
        publisher.emitOrderUpdate(order, 4, OutputEvent.FLAG_CANCEL, true, Px.NONE, 100L);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, ring.get(3).kind);

        order.status = RestingOrder.STATUS_REJECTED;
        publisher.emitOrderUpdate(order, 5, OutputEvent.FLAG_REJECT, true, Px.NONE, 100L);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, ring.get(4).kind);
    }

    @Test
    void emitFillWithTradeAndPositionPublishesOrderUpdateTradeBookedAndPositionUpdatedBatch() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 16);
        OutputPublisher publisher = new OutputPublisher(ring);
        RestingOrder order = order(RestingOrder.STATUS_PARTIALLY_FILLED, 50);
        order.lastExecPx = Px.toTicks(new BigDecimal("101.125"));
        order.lastFillQty = 50;

        publisher.emitFillWithTradeAndPosition(order, 50, Px.toTicks(new BigDecimal("101.125")), 7,
            150, Px.toTicks(new BigDecimal("101.125")), 11, OutputEvent.FLAG_PARTIAL_FILL, Px.NONE, 100L);

        OutputEvent update = ring.get(0);
        assertEquals(OutputEvent.KIND_ORDER_PARTIALLY_FILLED, update.kind);
        assertEquals(0, update.tradeQty);
        OutputEvent trade = ring.get(1);
        assertEquals(OutputEvent.KIND_TRADE_BOOKED, trade.kind);
        assertEquals(50, trade.tradeQty);
        assertEquals(11, trade.inputSeq);
        OutputEvent position = ring.get(2);
        assertEquals(OutputEvent.KIND_POSITION_UPDATED, position.kind);
        assertEquals(150, position.positionQty);
        assertEquals(0, new BigDecimal("101.125").compareTo(Px.toBigDecimal(position.averageCostBasisPx)));
        assertEquals(11, position.inputSeq);
    }

    @Test
    void blpFillEmitsTradeBookedPlusPositionUpdated() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 16);
        MatchingEngine blp = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 1000, 16);

        InputEvent tick = new InputEvent();
        tick.seq = 1;
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = 0;
        tick.priceTicks = Px.toTicks(new BigDecimal("101.125"));
        tick.ingressNanos = System.nanoTime();
        tick.eventTimeMillis = 1_700_000_000_000L;
        blp.onEvent(tick, 1, true);

        InputEvent order = new InputEvent();
        order.seq = 2;
        order.type = InputEvent.TYPE_ORDER_NEW;
        order.orderRef = 42;
        order.accountId = 22214;
        order.securityId = 0;
        order.side = InputEvent.SIDE_BUY;
        order.qty = 100;
        order.limitPx = Px.toTicks(new BigDecimal("102.000"));
        order.ingressNanos = System.nanoTime();
        order.eventTimeMillis = 1_700_000_000_001L;
        blp.onEvent(order, 2, true);

        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, ring.get(0).kind);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, ring.get(1).kind);
        assertEquals(OutputEvent.KIND_TRADE_BOOKED, ring.get(2).kind);
        assertEquals(OutputEvent.KIND_POSITION_UPDATED, ring.get(3).kind);
        assertEquals(100, ring.get(3).positionQty);
        assertEquals(0, new BigDecimal("101.125").compareTo(Px.toBigDecimal(ring.get(3).averageCostBasisPx)));
    }

    @Test
    void natsBridgePublishesOnlyLifecycleEventsToOrderSubjects() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<OrderResponse> publisher = new RecordingPublisher<>();
        NatsBridgeHandler handler = new NatsBridgeHandler(publisher, symbols, readModel, primaryRole());

        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true), 0, true);
        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_TRADE_BOOKED, true), 1, true);
        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_ORDER_CANCELED, false), 2, true);

        assertEquals(List.of("/accounts/22214/orders", "/orders"), publisher.topics());
        assertEquals(0, readModel.natsErrors().sum());
    }

    @Test
    void natsBridgeFailuresIncrementOnlyNatsErrors() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<OrderResponse> publisher = new RecordingPublisher<>();
        publisher.failOnPublish = true;
        NatsBridgeHandler handler = new NatsBridgeHandler(publisher, symbols, readModel, primaryRole());

        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true), 0, true);

        assertEquals(1, readModel.natsErrors().sum());
        assertEquals(0, readModel.counterValue("reject"));
    }

    @Test
    void tradeSubmitPublishesTradeBookedOnlyToTrades() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<TradeOrder> publisher = new RecordingPublisher<>();
        TradeSubmitHandler handler = new TradeSubmitHandler(publisher, symbols, readModel);

        handler.onEvent(tradeEvent(symbols), 0, true);
        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true), 1, true);

        assertEquals(List.of("/trades"), publisher.topics());
        TradeOrder trade = publisher.messages.getFirst();
        // YU05 (post-trade-compliance, ADR-022): the trade's own deterministic id
        // (tradeIdFor(tradeSeq)), not the order's id — see TradeOrder.fromEvent.
        assertEquals("trd-09b-7", trade.getId());
        assertEquals(25, trade.getQuantity());
        assertEquals(0, new BigDecimal("101.125").compareTo(trade.getPrice()));
    }

    @Test
    void tradeSubmitFailureDoesNotIncrementBusinessRejectCounter() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<TradeOrder> publisher = new RecordingPublisher<>();
        publisher.failOnPublish = true;
        TradeSubmitHandler handler = new TradeSubmitHandler(publisher, symbols, readModel);

        handler.onEvent(tradeEvent(symbols), 0, true);

        assertEquals(1, readModel.tradeSubmitFailures().sum());
        assertEquals(0, readModel.counterValue("reject"));
    }

    @Test
    void accountTradePublishesTradeBookedOnlyToAccountTradeSubject() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<AccountTrade> publisher = new RecordingPublisher<>();
        AccountTradeHandler handler = new AccountTradeHandler(publisher, symbols, readModel, primaryRole());

        handler.onEvent(tradeEvent(symbols), 0, true);
        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true), 1, true);

        assertEquals(List.of("/accounts/22214/trades"), publisher.topics());
        AccountTrade trade = publisher.messages.getFirst();
        assertEquals("trd-09b-7", trade.getId());
        assertEquals(22214, trade.getAccountId());
        assertEquals("IBM", trade.getSecurity());
        assertEquals("Settled", trade.getState());
        assertEquals(25, trade.getQuantity());
        assertEquals(0, new BigDecimal("101.125").compareTo(trade.getPrice()));
        assertEquals(0, readModel.accountTradePublishFailures().sum());
    }

    @Test
    void accountTradeFailuresIncrementDedicatedCounter() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<AccountTrade> publisher = new RecordingPublisher<>();
        publisher.failOnPublish = true;
        AccountTradeHandler handler = new AccountTradeHandler(publisher, symbols, readModel, primaryRole());

        handler.onEvent(tradeEvent(symbols), 0, true);

        assertEquals(1, readModel.accountTradePublishFailures().sum());
        assertEquals(0, readModel.tradeSubmitFailures().sum());
    }

    @Test
    void directUiTradeAndPositionFanoutDoesNotRequireTradesSubject() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<AccountTrade> accountTrades = new RecordingPublisher<>();
        RecordingPublisher<PositionUpdate> positions = new RecordingPublisher<>();
        RecordingPublisher<TradeOrder> legacyTrades = new RecordingPublisher<>();
        AccountTradeHandler accountTradeHandler = new AccountTradeHandler(accountTrades, symbols, readModel, primaryRole());
        PositionUpdateHandler positionUpdateHandler = new PositionUpdateHandler(positions, symbols, readModel, primaryRole());

        accountTradeHandler.onEvent(tradeEvent(symbols), 0, true);
        positionUpdateHandler.onEvent(positionEvent(symbols), 1, true);

        assertEquals(List.of("/accounts/22214/trades"), accountTrades.topics());
        assertEquals(List.of("/accounts/22214/positions"), positions.topics());
        assertTrue(legacyTrades.topics().isEmpty());
        assertEquals(0, readModel.tradeSubmitFailures().sum());
    }

    @Test
    void positionUpdatePublishesOnlyPositionUpdatedToAccountPositionSubject() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<PositionUpdate> publisher = new RecordingPublisher<>();
        PositionUpdateHandler handler = new PositionUpdateHandler(publisher, symbols, readModel, primaryRole());

        handler.onEvent(positionEvent(symbols), 0, true);
        handler.onEvent(tradeEvent(symbols), 1, true);

        assertEquals(List.of("/accounts/22214/positions"), publisher.topics());
        PositionUpdate position = publisher.messages.getFirst();
        assertEquals(22214, position.getAccountId());
        assertEquals("IBM", position.getSecurity());
        assertEquals(175, position.getQuantity());
        assertEquals(0, new BigDecimal("101.125").compareTo(position.getAverageCostBasis()));
        assertTrue(position.getUpdated().getTime() > 0);
    }

    @Test
    void positionUpdateFailuresIncrementDedicatedCounter() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        RecordingPublisher<PositionUpdate> publisher = new RecordingPublisher<>();
        publisher.failOnPublish = true;
        PositionUpdateHandler handler = new PositionUpdateHandler(publisher, symbols, readModel, primaryRole());

        handler.onEvent(positionEvent(symbols), 0, true);

        assertEquals(1, readModel.positionPublishFailures().sum());
        assertEquals(0, readModel.natsErrors().sum());
    }

    @Test
    void marshallerCountsPositionUpdatedSeparately() {
        SymbolTable symbols = symbolsWith("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel();
        MarshallerHandler handler = new MarshallerHandler(readModel, symbols, new HotPathMetrics());

        handler.onEvent(orderEvent(symbols, OutputEvent.KIND_ORDER_ACCEPTED, true), 0, true);
        handler.onEvent(tradeEvent(symbols), 1, true);
        handler.onEvent(positionEvent(symbols), 2, true);

        assertEquals(1, handler.orderUpdates());
        assertEquals(1, handler.tradesBooked());
        assertEquals(1, handler.positionsUpdated());
        assertNull(readModel.get(9999));
    }

    private static SymbolTable symbolsWith(String ticker) {
        SymbolTable symbols = new SymbolTable(16);
        symbols.idFor(ticker);
        return symbols;
    }

    private static RestingOrder order(byte status, int remaining) {
        RestingOrder order = new RestingOrder();
        order.orderRef = 42;
        order.accountId = 22214;
        order.securityId = 0;
        order.side = (byte) OrderSide.Buy.ordinal();
        order.quantity = 100;
        order.remaining = remaining;
        order.limitPx = Px.toTicks(new BigDecimal("102.000"));
        order.status = status;
        order.lastExecPx = Px.NONE;
        order.createdAtMillis = 1_700_000_000_000L;
        order.updatedAtMillis = 1_700_000_000_000L;
        return order;
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
        e.updatedAtMillis = 1_700_000_000_000L;
        e.ingressNanos = System.nanoTime();
        return e;
    }

    private static ReplicationRole primaryRole() {
        ReplicationRole role = new ReplicationRole();
        role.set(ReplicationRole.Role.PRIMARY);
        return role;
    }

    private static final class RecordingPublisher<T> implements Publisher<T> {
        private final List<String> topics = new ArrayList<>();
        private final List<T> messages = new ArrayList<>();
        private boolean failOnPublish;

        @Override
        public void publish(T message) throws PubSubException {
            publish("/default", message);
        }

        @Override
        public void publish(String topic, T message) throws PubSubException {
            if (failOnPublish) {
                throw new PubSubException("publish failed");
            }
            topics.add(topic);
            messages.add(message);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void connect() throws PubSubException {}

        @Override
        public void disconnect() throws PubSubException {}

        private List<String> topics() {
            return topics;
        }
    }
}
