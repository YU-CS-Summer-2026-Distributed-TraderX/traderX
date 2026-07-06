package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Output-ring NATS bridge for order subjects only. Direct account trade and position fan-out
 * are handled by dedicated output handlers; this bridge preserves the existing order streams.
 */
public final class NatsBridgeHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(NatsBridgeHandler.class);
    private static final String ALL_ORDERS_TOPIC = "/orders";
    // Cached enum tables so rendering an order event allocates no values() array per event (Tier 2-C).
    private static final OrderSide[] SIDES = OrderSide.values();
    private static final OrderStatus[] STATUSES = OrderStatus.values();
    private static final RiskReason[] RISK_REASONS = RiskReason.values();

    private final Publisher<OrderResponse> orderPublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;
    private final ReplicationRole replicationRole;

    public NatsBridgeHandler(Publisher<OrderResponse> orderPublisher, SymbolTable symbols,
                             InMemoryOrderReadModel readModel, ReplicationRole replicationRole) {
        this.orderPublisher = orderPublisher;
        this.symbols = symbols;
        this.readModel = readModel;
        this.replicationRole = replicationRole;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (readModel.isReplaying() || replicationRole.isFollower()) {
            return;   // recovery replay or follower: do not emit output
        }
        if (!e.publishNats || !OutputEvent.isOrderLifecycleKind(e.kind)) {
            return;
        }
        publishOrder(e);
    }

    private void publishOrder(OutputEvent e) {
        // Build the OrderResponse straight from the output-event fields. The marshaller already builds the
        // read-model OrderSnapshot, so the bridge no longer allocates a second OrderSnapshot + OrderRecord
        // per order event (state 009b Tier 2-C). Field-for-field identical to the prior snapshot→record→
        // response path, so the wire format is unchanged, plus riskReason (FR-IMRG44) so a live blotter
        // update surfaces why a BLP-rejected order was rejected, not just its terminal REJECTED status.
        RiskReason riskReason = RISK_REASONS[e.riskReason];
        OrderResponse payload = OrderResponse.from(
            OrderSnapshot.orderIdFor(e.orderRef), e.accountId, symbols.tickerFor(e.securityId), SIDES[e.side],
            e.quantity, e.remainingQty, Px.toBigDecimal(e.limitPx), STATUSES[e.status],
            Instant.ofEpochMilli(e.createdAtMillis), Instant.ofEpochMilli(e.updatedAtMillis),
            e.lastExecPx == Px.NONE ? null : Px.toBigDecimal(e.lastExecPx),
            e.lastFillQty == 0 ? null : e.lastFillQty, Px.toBigDecimal(e.marketPx),
            riskReason == RiskReason.ACCEPTED ? null : riskReason.name());
        String accountTopic = "/accounts/" + e.accountId + "/orders";
        try {
            orderPublisher.publish(accountTopic, payload);
            orderPublisher.publish(ALL_ORDERS_TOPIC, payload);
        } catch (PubSubException ex) {
            readModel.natsErrors().increment();
            log.warn("Unable to publish order update for {} on {}/{}", OrderSnapshot.orderIdFor(e.orderRef),
                accountTopic, ALL_ORDERS_TOPIC, ex);
        }
    }
}
