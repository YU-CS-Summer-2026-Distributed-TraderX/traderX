package finos.traderx.ordermatcher.lmax;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

/**
 * Optional Output-ring TradeBooked bridge for the unchanged 009 `/trades` subject.
 * Full Option B UI fan-out does not depend on this handler; enable it only for
 * compatibility, debug, or non-UI consumers.
 */
public final class TradeSubmitHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(TradeSubmitHandler.class);
    private static final String TRADES_TOPIC = "/trades";

    private final Publisher<TradeOrder> tradePublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;
    private final TradeOrder payload = new TradeOrder();
    private final OutputValueCache values = new OutputValueCache();

    public TradeSubmitHandler(Publisher<TradeOrder> tradePublisher, SymbolTable symbols,
                              InMemoryOrderReadModel readModel) {
        this.tradePublisher = tradePublisher;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (readModel.isReplaying()) {
            return;   // recovery replay: do not re-submit historical trades to the legacy endpoint
        }
        if (e.kind != OutputEvent.KIND_TRADE_BOOKED) {
            return;
        }
        payload.copyFromEvent(e, symbols, values);
        try {
            tradePublisher.publish(TRADES_TOPIC, payload);
        } catch (PubSubException ex) {
            recordFailure(e, ex);
        }
    }

    private void recordFailure(OutputEvent e, Exception ex) {
        readModel.tradeSubmitFailures().increment();
        log.warn("TradeBooked publish failed for order {} (qty {})", OrderSnapshot.orderIdFor(e.orderRef),
            e.tradeQty, ex);
    }
}
