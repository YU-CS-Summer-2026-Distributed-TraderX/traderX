package finos.traderx.ordermatcher.config;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.messaging.nats.NatsJSONPublisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.lmax.AccountTrade;
import finos.traderx.ordermatcher.lmax.PositionUpdate;
import finos.traderx.ordermatcher.lmax.TradeOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Output-side publishers for state 009b. Orders stay on the existing `/orders` subjects,
 * direct account trades and positions use dedicated UI payloads, and the legacy `/trades`
 * payload stays available behind the optional compatibility handler.
 */
@Configuration
public class PubSubConfig {
    private static final String NATS = "nats";
    private static final String NOOP = "noop";

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<OrderResponse> natsOrderPublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        return natsPublisher(natsAddress);
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<TradeOrder> natsTradePublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        return natsPublisher(natsAddress);
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<AccountTrade> natsAccountTradePublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        return natsPublisher(natsAddress);
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<PositionUpdate> natsPositionPublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        return natsPublisher(natsAddress);
    }

    private static <T> NatsJSONPublisher<T> natsPublisher(String natsAddress) {
        NatsJSONPublisher<T> publisher = new NatsJSONPublisher<>();
        publisher.setServerAddress(natsAddress);
        publisher.setSender("order-matcher");
        return publisher;
    }

    private static <T> Publisher<T> noopPublisher() {
        return new Publisher<>() {
            @Override
            public void publish(T message) throws PubSubException {}

            @Override
            public void publish(String topic, T message) throws PubSubException {}

            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public void connect() throws PubSubException {}

            @Override
            public void disconnect() throws PubSubException {}
        };
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<OrderResponse> noopOrderPublisher() {
        return noopPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<TradeOrder> noopTradePublisher() {
        return noopPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<AccountTrade> noopAccountTradePublisher() {
        return noopPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<PositionUpdate> noopPositionPublisher() {
        return noopPublisher();
    }
}
