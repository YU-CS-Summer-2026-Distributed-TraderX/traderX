package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output-ring PositionUpdated bridge. Publishes the UI's existing account position subject
 * directly from the output disruptor.
 */
public final class PositionUpdateHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(PositionUpdateHandler.class);

    private final Publisher<PositionUpdate> positionPublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;
    private final ReplicationRole replicationRole;

    public PositionUpdateHandler(Publisher<PositionUpdate> positionPublisher, SymbolTable symbols,
                                 InMemoryOrderReadModel readModel, ReplicationRole replicationRole) {
        this.positionPublisher = positionPublisher;
        this.symbols = symbols;
        this.readModel = readModel;
        this.replicationRole = replicationRole;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (readModel.isReplaying() || replicationRole.isFollower()) {
            return;   // recovery replay or follower: do not emit output
        }
        if (e.kind != OutputEvent.KIND_POSITION_UPDATED) {
            return;
        }
        PositionUpdate payload = PositionUpdate.fromEvent(e, symbols);
        String accountTopic = "/accounts/" + e.accountId + "/positions";
        try {
            positionPublisher.publish(accountTopic, payload);
        } catch (PubSubException ex) {
            readModel.positionPublishFailures().increment();
            log.warn("PositionUpdated publish failed for account {} security {}", e.accountId,
                payload.getSecurity(), ex);
        }
    }
}
