package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output-ring NATS bridge for order subjects only. Direct account trade and position fan-out
 * are handled by dedicated output handlers; this bridge preserves the existing order streams.
 */
public final class NatsBridgeHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(NatsBridgeHandler.class);
    private static final String ALL_ORDERS_TOPIC = "/orders";

    private final Publisher<OrderResponse> orderPublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;

    public NatsBridgeHandler(Publisher<OrderResponse> orderPublisher, SymbolTable symbols,
                             InMemoryOrderReadModel readModel) {
        this.orderPublisher = orderPublisher;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (readModel.isReplaying()) {
            return;   // recovery replay: do not re-broadcast historical events to NATS
        }
        if (!e.publishNats || !OutputEvent.isOrderLifecycleKind(e.kind)) {
            return;
        }
        publishOrder(e);
    }

    private void publishOrder(OutputEvent e) {
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
}
