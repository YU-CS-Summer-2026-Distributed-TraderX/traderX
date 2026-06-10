package finos.traderx.ordermatcher.lmax;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

/**
 * Output-ring NATS publisher (FR-09B21): bridges BLP order updates onto the exact 009
 * subjects (`/accounts/{accountId}/orders` and `/orders`) with the exact 009 payload shape
 * (OrderResponse), so every UI consumer keeps working unchanged. securityId -> ticker and
 * fixed-point -> decimal conversions happen here, at the edge, never in the BLP
 * (FR-09B25). JSON allocation is acceptable here: this handler is off the hot path.
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
        if (e.kind != OutputEvent.KIND_ORDER_UPDATE || !e.publishNats) {
            return;
        }
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
}
