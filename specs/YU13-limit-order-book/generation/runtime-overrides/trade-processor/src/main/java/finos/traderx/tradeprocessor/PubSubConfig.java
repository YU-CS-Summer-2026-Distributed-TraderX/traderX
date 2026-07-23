package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Publisher;
import finos.traderx.messaging.Subscriber;
import finos.traderx.messaging.nats.NatsJSONPublisher;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.PriceTick;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.service.PriceHistoryStore;
import finos.traderx.tradeprocessor.service.PriceTickHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PubSubConfig {
  @Value("${nats.address}")
  private String natsAddress;

  @Bean
  public Publisher<Position> positionPublisher() {
    NatsJSONPublisher<Position> publisher = new NatsJSONPublisher<>();
    publisher.setServerAddress(natsAddress);
    publisher.setSender("trade-processor");
    return publisher;
  }

  @Bean
  public Publisher<Trade> tradePublisher() {
    NatsJSONPublisher<Trade> publisher = new NatsJSONPublisher<>();
    publisher.setServerAddress(natsAddress);
    publisher.setSender("trade-processor");
    return publisher;
  }

  @Bean
  public Subscriber<TradeOrder> tradeFeedHandler() {
    TradeFeedHandler handler = new TradeFeedHandler();
    handler.setDefaultTopic("/trades");
    handler.setServerAddress(natsAddress);
    handler.setClientId("trade-processor-subscriber");
    return handler;
  }

  // YU05 (post-trade-compliance, ADR-024): subscribes to price-publisher's existing pricing.*
  // wildcard subject to build the price history TCA (and YU06's EOD close) reads. This bean is
  // YU05's and MUST survive here: this file shadows YU05's copy in every descendant render
  // (dead-layer trap — dropping it silently killed the whole EOD price universe on YU15's GKE
  // tier: session close published instruments=0 because nothing subscribed to pricing.*).
  @Bean
  public Subscriber<PriceTick> priceTickHandler(PriceHistoryStore priceHistoryStore) {
    PriceTickHandler handler = new PriceTickHandler(priceHistoryStore);
    handler.setDefaultTopic("pricing.*");
    handler.setServerAddress(natsAddress);
    handler.setClientId("trade-processor-price-history-subscriber");
    return handler;
  }

  /**
   * YU13 order read model: consumes the cluster's leader-side {@code /orders} bridge and upserts the
   * {@code orderbook} projection. Idle on any state whose cluster does not publish {@code /orders}.
   */
  @Bean
  public Subscriber<OrderUpdate> orderFeedHandler() {
    OrderFeedHandler handler = new OrderFeedHandler();
    handler.setDefaultTopic("/orders");
    handler.setServerAddress(natsAddress);
    handler.setClientId("order-processor-subscriber");
    return handler;
  }
}
