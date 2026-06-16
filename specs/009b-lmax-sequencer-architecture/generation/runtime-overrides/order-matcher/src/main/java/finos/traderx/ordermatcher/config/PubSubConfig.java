package finos.traderx.ordermatcher.config;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.messaging.nats.NatsJSONPublisher;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.Trade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * State 009b: with booking + position-keeping fused into the BLP (FR-09B08/FR-09B10), the
 * order-matcher output ring now publishes trades and positions on the exact 009 subjects
 * (formerly trade-processor's job). These NATS publishers mirror the state-006 trade-processor
 * config (sender renamed) so {@code /accounts/{id}/trades} and {@code /accounts/{id}/positions}
 * payloads reach the trade-feed/UI unchanged (FR-09B21/FR-09B40).
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
    public Publisher<Trade> natsTradePublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        return natsPublisher(natsAddress);
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<Position> natsPositionPublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        return natsPublisher(natsAddress);
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<OrderResponse> noopOrderPublisher() {
        return noopPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<Trade> noopTradePublisher() {
        return noopPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NOOP)
    public Publisher<Position> noopPositionPublisher() {
        return noopPublisher();
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
}
