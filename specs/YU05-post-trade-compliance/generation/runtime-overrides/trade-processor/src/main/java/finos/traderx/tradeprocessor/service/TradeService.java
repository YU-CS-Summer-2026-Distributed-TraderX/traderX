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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeService {
  private static final Logger log = LoggerFactory.getLogger(TradeService.class);

  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final Publisher<Trade> tradePublisher;
  private final Publisher<Position> positionPublisher;
  private final int settlementTPlusDays;

  public TradeService(
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      Publisher<Trade> tradePublisher,
      Publisher<Position> positionPublisher,
      @Value("${settlement.t-plus-days:1}") int settlementTPlusDays) {
    this.tradeRepository = tradeRepository;
    this.positionRepository = positionRepository;
    this.tradePublisher = tradePublisher;
    this.positionPublisher = positionPublisher;
    this.settlementTPlusDays = settlementTPlusDays;
  }

  /**
   * YU05 (post-trade-compliance, FR-PTC08): {@code order.getId()} is now the BLP's deterministic
   * trade id (ADR-022), so a redelivered NATS message (ack timeout, operator replay) carries the
   * SAME id as the original — book once, ignore the rest, instead of double-booking a position.
   */
  @Transactional
  public TradeBookingResult processTrade(TradeOrder order) {
    log.debug("Trade order received: {}", order);

    Optional<Trade> existing = tradeRepository.findById(order.getId());
    if (existing.isPresent()) {
      log.info("Duplicate trade delivery ignored (already booked): id={}", order.getId());
      Trade existingTrade = existing.get();
      Position existingPosition = positionRepository.findByAccountIdAndSecurity(
          existingTrade.getAccountId(), existingTrade.getSecurity());
      return new TradeBookingResult(existingTrade, existingPosition);
    }

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
   *
   * <p>YU05 (FR-PTC08): orders whose id already exists are dropped from the batch entirely (no
   * position mutation, no re-publish) — same idempotency guarantee as {@link #processTrade}.
   */
  @Transactional
  public List<TradeBookingResult> processTrades(List<TradeOrder> orders) {
    Set<String> alreadyBooked = new HashSet<>();
    for (Trade existing : tradeRepository.findAllById(orders.stream().map(TradeOrder::getId).toList())) {
      alreadyBooked.add(existing.getId());
    }

    Map<String, Position> positionsByKey = new HashMap<>();
    List<Trade> trades = new ArrayList<>(orders.size());
    List<TradeBookingResult> results = new ArrayList<>(orders.size());

    for (TradeOrder order : orders) {
      if (alreadyBooked.contains(order.getId())) {
        log.info("Duplicate trade delivery ignored (already booked): id={}", order.getId());
        continue;
      }

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

  /** Computes the booked (not-yet-settled) trade and mutates {@code position} in place. Does not persist. */
  private Trade bookTrade(TradeOrder order, Position position) {
    Trade trade = new Trade();
    trade.setId(order.getId());
    trade.setAccountId(order.getAccountId());
    trade.setSecurity(order.getSecurity());
    trade.setSide(order.getSide());
    trade.setQuantity(order.getQuantity());
    BigDecimal executionPrice = (order.getPrice() == null ? BigDecimal.ZERO : order.getPrice()).setScale(3, RoundingMode.HALF_UP);
    trade.setPrice(executionPrice);
    Date now = new Date();
    trade.setCreated(now);
    trade.setUpdated(now);
    // YU05 (post-trade-compliance, FR-PTC02): booking and settlement are no longer the same
    // instant — a trade is Processing until SettlementService's T+N sweep (or a manual force
    // override) advances it to Settled.
    trade.setState(TradeState.Processing);
    trade.setSettlementDate(plusBusinessDays(now, settlementTPlusDays));

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
    position.setUpdated(now);

    return trade;
  }

  /** Adds {@code days} business days (Mon-Fri) to {@code from}, skipping weekends. */
  static Date plusBusinessDays(Date from, int days) {
    LocalDate date = Instant.ofEpochMilli(from.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
    int added = 0;
    while (added < days) {
      date = date.plusDays(1);
      if (date.getDayOfWeek().getValue() < 6) {
        added++;
      }
    }
    return Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
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
