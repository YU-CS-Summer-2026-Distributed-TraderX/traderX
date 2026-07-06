package finos.traderx.tradeprocessor.service;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeBookingResult;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.PositionRepository;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeService {
  private static final Logger log = LoggerFactory.getLogger(TradeService.class);

  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final Publisher<Trade> tradePublisher;
  private final Publisher<Position> positionPublisher;

  public TradeService(
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      Publisher<Trade> tradePublisher,
      Publisher<Position> positionPublisher) {
    this.tradeRepository = tradeRepository;
    this.positionRepository = positionRepository;
    this.tradePublisher = tradePublisher;
    this.positionPublisher = positionPublisher;
  }

  @Transactional
  public TradeBookingResult processTrade(TradeOrder order) {
    log.debug("Trade order received: {}", order);

    Position position = positionRepository.findByAccountIdAndSecurity(order.getAccountId(), order.getSecurity());
    if (position == null) {
      position = new Position();
      position.setAccountId(order.getAccountId());
      position.setSecurity(order.getSecurity());
      position.setQuantity(0);
      position.setAverageCostBasis(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
    }

    Trade trade = bookTrade(order, position);

    tradeRepository.save(trade);
    positionRepository.save(position);

    TradeBookingResult result = new TradeBookingResult(trade, position);
    log.debug("Trade Processing complete: {}", result);

    publish(order.getAccountId(), result);

    return result;
  }

  /**
   * Books a batch of trades in a single transaction. Positions are looked up once per unique
   * (accountId, security) pair and updated in memory across the whole batch, so the whole batch
   * costs O(unique positions) SELECTs instead of O(orders) — and Hibernate batches the resulting
   * saveAll() inserts into multi-row statements (see spring.jpa.properties.hibernate.jdbc.batch_size).
   */
  @Transactional
  public List<TradeBookingResult> processTrades(List<TradeOrder> orders) {
    Map<String, Position> positionsByKey = new HashMap<>();
    List<Trade> trades = new ArrayList<>(orders.size());
    List<TradeBookingResult> results = new ArrayList<>(orders.size());

    for (TradeOrder order : orders) {
      String key = order.getAccountId() + "|" + order.getSecurity();
      Position position = positionsByKey.get(key);
      if (position == null) {
        position = positionRepository.findByAccountIdAndSecurity(order.getAccountId(), order.getSecurity());
        if (position == null) {
          position = new Position();
          position.setAccountId(order.getAccountId());
          position.setSecurity(order.getSecurity());
          position.setQuantity(0);
          position.setAverageCostBasis(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
        }
        positionsByKey.put(key, position);
      }

      Trade trade = bookTrade(order, position);
      trades.add(trade);
      results.add(new TradeBookingResult(trade, position));
    }

    tradeRepository.saveAll(trades);
    positionRepository.saveAll(positionsByKey.values());

    for (TradeBookingResult result : results) {
      publish(result.getTrade().getAccountId(), result);
    }

    log.debug("Batch trade processing complete: {} trades, {} positions", trades.size(), positionsByKey.size());
    return results;
  }

  /** Computes the settled trade and mutates {@code position} in place. Does not persist. */
  private Trade bookTrade(TradeOrder order, Position position) {
    Trade trade = new Trade();
    trade.setId(UUID.randomUUID().toString());
    trade.setAccountId(order.getAccountId());
    trade.setSecurity(order.getSecurity());
    trade.setSide(order.getSide());
    trade.setQuantity(order.getQuantity());
    BigDecimal executionPrice = (order.getPrice() == null ? BigDecimal.ZERO : order.getPrice()).setScale(3, RoundingMode.HALF_UP);
    trade.setPrice(executionPrice);
    trade.setCreated(new Date());
    trade.setUpdated(new Date());
    // Booking is synchronous and always settles immediately in this model — write the final
    // state directly instead of persisting New/Processing/Settled as separate DB round trips.
    trade.setState(TradeState.Settled);

    int oldQuantity = position.getQuantity() == null ? 0 : position.getQuantity();
    BigDecimal oldAverage = position.getAverageCostBasis() == null
        ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
        : position.getAverageCostBasis().setScale(3, RoundingMode.HALF_UP);

    int signedQuantity = ((order.getSide() == TradeSide.Buy) ? 1 : -1) * trade.getQuantity();
    int newQuantity = oldQuantity + signedQuantity;
    position.setQuantity(newQuantity);

    BigDecimal oldNotional = oldAverage.multiply(BigDecimal.valueOf(oldQuantity));
    BigDecimal tradeNotional = executionPrice.multiply(BigDecimal.valueOf(signedQuantity));
    BigDecimal newNotional = oldNotional.add(tradeNotional);
    BigDecimal newAverage = newQuantity == 0
        ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
        : newNotional.divide(BigDecimal.valueOf(newQuantity), 3, RoundingMode.HALF_UP);
    position.setAverageCostBasis(newAverage);
    position.setUpdated(new Date());

    return trade;
  }

  private void publish(Integer accountId, TradeBookingResult result) {
    try {
      tradePublisher.publish("/accounts/" + accountId + "/trades", result.getTrade());
      positionPublisher.publish("/accounts/" + accountId + "/positions", result.getPosition());
    } catch (PubSubException exc) {
      log.error("Error publishing trade for account {}", accountId, exc);
    }
  }
}
