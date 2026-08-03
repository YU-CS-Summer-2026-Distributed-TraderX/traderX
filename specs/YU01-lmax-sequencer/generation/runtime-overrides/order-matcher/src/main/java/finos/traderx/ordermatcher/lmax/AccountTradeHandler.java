package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output-ring account-trade bridge. Owns direct UI fan-out for `/accounts/{accountId}/trades`.
 */
public final class AccountTradeHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(AccountTradeHandler.class);

    private final Publisher<AccountTrade> accountTradePublisher;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;
    private final AccountTrade payload = new AccountTrade();
    private final AccountTopicCache topics = new AccountTopicCache("/trades");
    private final OutputValueCache values = new OutputValueCache();

    public AccountTradeHandler(Publisher<AccountTrade> accountTradePublisher, SymbolTable symbols,
                               InMemoryOrderReadModel readModel) {
        this.accountTradePublisher = accountTradePublisher;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (readModel.isReplaying()) {
            return;   // recovery replay: do not re-broadcast historical trades
        }
        if (e.kind != OutputEvent.KIND_TRADE_BOOKED) {
            return;
        }
        payload.copyFromEvent(e, symbols, values);
        String accountTopic = topics.topicFor(e.accountId);
        try {
            accountTradePublisher.publish(accountTopic, payload);
        } catch (PubSubException ex) {
            readModel.accountTradePublishFailures().increment();
            log.warn("Account trade publish failed for account {} order {}", e.accountId,
                OrderSnapshot.orderIdFor(e.orderRef), ex);
        }
    }
}
