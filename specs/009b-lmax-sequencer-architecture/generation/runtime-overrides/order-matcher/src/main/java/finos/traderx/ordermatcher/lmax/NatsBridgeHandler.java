package finos.traderx.ordermatcher.lmax;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeSide;
import finos.traderx.ordermatcher.model.TradeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

import java.util.Date;

/**
 * Output-ring NATS publisher (FR-09B21): bridges BLP output events onto the exact 009
 * subjects with the exact 009 payload shapes, so every UI consumer keeps working unchanged.
 *  - order updates  -> {@code /accounts/{id}/orders} and {@code /orders} (OrderResponse)
 *  - booked trades  -> {@code /trades} (was trade-service) and {@code /accounts/{id}/trades}
 *                      (was trade-processor) — both carry the {@code Trade} with execution price
 *  - positions      -> {@code /accounts/{id}/positions} (Position) — formerly trade-processor
 *
 * securityId -> ticker and fixed-point -> decimal conversions happen here, at the edge, never
 * in the BLP (FR-09B25). JSON allocation is acceptable here: this handler is off the hot path.
 * Trade ids come from {@link OrderSnapshot#tradeIdFor} (the BLP-assigned trade number), so the
 * published id matches the row the projector writes.
 */
public final class NatsBridgeHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(NatsBridgeHandler.class);
    private static final String ALL_ORDERS_TOPIC = "/orders";
    private static final String ALL_TRADES_TOPIC = "/trades";

    private final Publisher<OrderResponse> orderPublisher;
    private final Publisher<Trade> tradePublisher;
    private final Publisher<Position> positionPublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;

    public NatsBridgeHandler(Publisher<OrderResponse> orderPublisher, Publisher<Trade> tradePublisher,
                             Publisher<Position> positionPublisher, SymbolTable symbols,
                             InMemoryOrderReadModel readModel) {
        this.orderPublisher = orderPublisher;
        this.tradePublisher = tradePublisher;
        this.positionPublisher = positionPublisher;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (!e.publishNats) {
            return;
        }
        switch (e.kind) {
            case OutputEvent.KIND_ORDER_UPDATE -> publishOrder(e);
            case OutputEvent.KIND_TRADE_BOOKED -> publishTrade(e);
            case OutputEvent.KIND_POSITION_UPDATED -> publishPosition(e);
            default -> { /* nothing to bridge */ }
        }
    }

    private void publishOrder(OutputEvent e) {
        // Build the payload from the event itself (handlers run in parallel; never read
        // sibling-handler state). Same OrderResponse contract as 009's publishOrderUpdate.
        OrderSnapshot snapshot = OrderSnapshot.fromEvent(e, symbols);
        OrderResponse payload = OrderResponse.from(snapshot.toRecord(), Px.toBigDecimal(e.marketPx));
        String accountTopic = "/accounts/" + e.accountId + "/orders";
        try {
            orderPublisher.publish(accountTopic, payload);
            orderPublisher.publish(ALL_ORDERS_TOPIC, payload);
        } catch (PubSubException ex) {
            readModel.natsErrors().increment();
            log.warn("Unable to publish order update for {} on {}/{}", snapshot.orderId,
                accountTopic, ALL_ORDERS_TOPIC, ex);
        }
    }

    private void publishTrade(OutputEvent e) {
        Trade trade = new Trade();
        trade.setId(OrderSnapshot.tradeIdFor(e.tradeSeq));
        trade.setAccountId(e.accountId);
        trade.setSecurity(symbols.tickerFor(e.securityId));
        trade.setSide(e.side == InputEvent.SIDE_BUY ? TradeSide.Buy : TradeSide.Sell);
        trade.setQuantity(e.tradeQty);
        trade.setPrice(Px.toDecimalOrZero(e.tradePx));   // stamped execution price (0.000 if no tick)
        trade.setState(TradeState.Settled);
        Date when = new Date(e.updatedAtMillis);
        trade.setCreated(when);
        trade.setUpdated(when);
        // Booking fused into the BLP now feeds both 009 trade subjects directly: the per-account
        // blotter stream (was trade-processor) and the global trade stream (was trade-service).
        String accountTopic = "/accounts/" + e.accountId + "/trades";
        try {
            tradePublisher.publish(accountTopic, trade);
            tradePublisher.publish(ALL_TRADES_TOPIC, trade);
        } catch (PubSubException ex) {
            readModel.natsErrors().increment();
            log.warn("Unable to publish trade {} on {}/{}", trade.getId(), accountTopic, ALL_TRADES_TOPIC, ex);
        }
    }

    private void publishPosition(OutputEvent e) {
        Position position = new Position();
        position.setAccountId(e.accountId);
        position.setSecurity(symbols.tickerFor(e.securityId));
        position.setQuantity(e.positionQty);
        position.setAverageCostBasis(Px.toDecimalOrZero(e.positionAvgCostTicks));   // weighted cost basis
        position.setUpdated(new Date(e.updatedAtMillis));
        String topic = "/accounts/" + e.accountId + "/positions";
        try {
            positionPublisher.publish(topic, position);
        } catch (PubSubException ex) {
            readModel.natsErrors().increment();
            log.warn("Unable to publish position {}:{} on {}", e.accountId,
                position.getSecurity(), topic, ex);
        }
    }
}
