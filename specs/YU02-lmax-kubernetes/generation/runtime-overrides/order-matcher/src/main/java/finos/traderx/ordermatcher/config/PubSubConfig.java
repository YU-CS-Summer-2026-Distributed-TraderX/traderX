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
 *
 * <p>Lever 3 (throughput): the NATS bridge handlers are gating consumers on the output ring, so a
 * synchronous publish on them backpressures the BLP (and, via the gateway ack, ingress). By default
 * the nats publishers are wrapped in {@link AsyncPublisher}, which moves the serialize+publish off
 * the ring thread (non-gating, identical wire format). Set {@code order.matcher.publisher.async=false}
 * to restore the synchronous path (A/B), or {@code order.matcher.publisher=noop} to drop NATS entirely.
 */
@Configuration
public class PubSubConfig {
    private static final String NATS = "nats";
    private static final String NOOP = "noop";

    @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}")
    private String natsAddress;

    @Value("${order.matcher.publisher.async:true}")
    private boolean async;

    @Value("${order.matcher.publisher.async-capacity:65536}")
    private int asyncCapacity;

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<OrderResponse> natsOrderPublisher() {
        return maybeAsync(this.<OrderResponse>natsPublisher(), "order");
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<TradeOrder> natsTradePublisher() {
        return maybeAsync(this.<TradeOrder>natsPublisher(), "trade");
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<AccountTrade> natsAccountTradePublisher() {
        return maybeAsync(this.<AccountTrade>natsPublisher(), "accountTrade");
    }

    @Bean
    @ConditionalOnProperty(name = "order.matcher.publisher", havingValue = NATS, matchIfMissing = true)
    public Publisher<PositionUpdate> natsPositionPublisher() {
        return maybeAsync(this.<PositionUpdate>natsPublisher(), "position");
    }

    private <T> NatsJSONPublisher<T> natsPublisher() {
        NatsJSONPublisher<T> publisher = new NatsJSONPublisher<>();
        publisher.setServerAddress(natsAddress);
        publisher.setSender("order-matcher");
        return publisher;
    }

    /** Wrap a nats publisher in the non-gating async drain unless async is disabled (A/B knob). */
    private <T> Publisher<T> maybeAsync(Publisher<T> delegate, String name) {
        return async ? new AsyncPublisher<>(delegate, name, asyncCapacity) : delegate;
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
