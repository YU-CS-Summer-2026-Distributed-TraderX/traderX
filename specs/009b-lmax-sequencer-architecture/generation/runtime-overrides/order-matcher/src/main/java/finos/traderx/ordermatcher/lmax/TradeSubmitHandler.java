package finos.traderx.ordermatcher.lmax;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

/**
 * Output-ring TradeBooked bridge. Publishes the unchanged 009 `/trades` payload so the
 * existing trade-processor consumes the fill and continues producing account trade and
 * position subjects. JSON allocation and security/price rendering happen here, at the
 * edge, never in the BLP.
 */
public final class TradeSubmitHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(TradeSubmitHandler.class);
    private static final String TRADES_TOPIC = "/trades";

    private final Publisher<TradeOrder> tradePublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;

    public TradeSubmitHandler(Publisher<TradeOrder> tradePublisher, SymbolTable symbols,
                              InMemoryOrderReadModel readModel) {
        this.tradePublisher = tradePublisher;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (e.kind != OutputEvent.KIND_TRADE_BOOKED) {
            return;
        }
        TradeOrder payload = TradeOrder.fromEvent(e, symbols);
        try {
            tradePublisher.publish(TRADES_TOPIC, payload);
        } catch (PubSubException ex) {
            recordFailure(e, ex);
        }
    }

    private void recordFailure(OutputEvent e, Exception ex) {
        readModel.tradeSubmitFailures().increment();
        readModel.increment("reject");
        log.warn("TradeBooked publish failed for order {} (qty {})", OrderSnapshot.orderIdFor(e.orderRef),
            e.tradeQty, ex);
    }
}
