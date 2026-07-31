package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output-ring NATS bridge for order subjects only. Direct account trade and position fan-out
 * are handled by dedicated output handlers; this bridge preserves the existing order streams.
 */
public final class NatsBridgeHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(NatsBridgeHandler.class);
    private static final String ALL_ORDERS_TOPIC = "/orders";
    private static final OrderStatus[] STATUSES = OrderStatus.values();
    private static final OrderSide[] SIDES = OrderSide.values();

    private final Publisher<OrderResponse> orderPublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;
    private final OrderResponse payload = new OrderResponse();
    private final AccountTopicCache orderTopics = new AccountTopicCache("/orders");
    private final OutputValueCache values = new OutputValueCache();

    public NatsBridgeHandler(Publisher<OrderResponse> orderPublisher, SymbolTable symbols,
                             InMemoryOrderReadModel readModel) {
        this.orderPublisher = orderPublisher;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (!e.publishNats || !OutputEvent.isOrderLifecycleKind(e.kind)) {
            return;
        }
        publishOrder(e);
    }

    private void publishOrder(OutputEvent e) {
        payload.setOrderId(values.orderIdFor(e.orderRef));
        payload.setAccountId(e.accountId);
        payload.setSecurity(symbols.tickerFor(e.securityId));
        payload.setSide(SIDES[e.side]);
        payload.setQuantity(e.quantity);
        payload.setRemainingQuantity(e.remainingQty);
        payload.setLimitPrice(values.priceFor(e.limitPx));
        payload.setStatus(STATUSES[e.status]);
        payload.setCreatedAt(values.instantFor(e.createdAtMillis));
        payload.setUpdatedAt(values.instantFor(e.updatedAtMillis));
        payload.setLastExecutionPrice(values.priceFor(e.lastExecPx));
        payload.setLastFillQuantity(e.lastFillQty == 0 ? null : e.lastFillQty);
        payload.setMarketPrice(values.priceFor(e.marketPx));
        String accountTopic = orderTopics.topicFor(e.accountId);
        try {
            orderPublisher.publish(accountTopic, payload);
            orderPublisher.publish(ALL_ORDERS_TOPIC, payload);
        } catch (PubSubException ex) {
            readModel.natsErrors().increment();
            log.warn("Unable to publish order update for {} on {}/{}", payload.getOrderId(),
                accountTopic, ALL_ORDERS_TOPIC, ex);
        }
    }
}
