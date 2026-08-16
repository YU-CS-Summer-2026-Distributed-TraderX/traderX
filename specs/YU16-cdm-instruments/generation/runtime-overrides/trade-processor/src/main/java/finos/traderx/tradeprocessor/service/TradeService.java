package finos.traderx.tradeprocessor.service;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.InstrumentMetadata;
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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TradeService {
  private static final Logger log = LoggerFactory.getLogger(TradeService.class);

  // YU16 (ADR-057/FR-CDM15): six decimals everywhere a price is held. A bond price is a
  // fraction of par - 0.998860 at three decimals is 0.999, a $114 error on 100,000 face.
  // For equities six decimals is a pure widening of the inherited three.
  private static final int PRICE_SCALE = 6;

  // YU16 (FR-CDM23): the canonical BOND routing discriminator. The prefix routes; the resolved
  // metadata must still confirm Debt before a bond booking. Widened from Treasury-only when
  // corporates arrived: this one predicate drives face-amount validation, the face-based
  // averaging rule in newAverage, AND metadata resolution, so leaving it Treasury-only meant a
  // corporate silently skipped all three - including a stated rule the system did not enforce.
  private static final String[] BOND_KEY_PREFIXES = { "UST-", "CORP-" };
  static final String MSG_FACE_MIN = "Bond quantity must be at least 100.";
  static final String MSG_FACE_MULTIPLE = "Bond quantity must be a multiple of 100.";
  static final String MSG_METADATA_UNAVAILABLE = "Bond reference metadata is unavailable";
  static final String MSG_MATURED = "Bond has matured; no new activity is accepted.";

  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final Publisher<Trade> tradePublisher;
  private final Publisher<Position> positionPublisher;
  private final int settlementTPlusDays;
  // YU16: both may be null when constructed outside Spring (the inherited YU02 reflective unit
  // test fills trailing constructor params with null). A null template runs the body directly;
  // a null client fails every Treasury booking closed - equity paths are unaffected.
  @Nullable private final InstrumentMetadataClient metadataClient;
  @Nullable private final TransactionTemplate transactionTemplate;

  public TradeService(
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      Publisher<Trade> tradePublisher,
      Publisher<Position> positionPublisher,
      @Value("${settlement.t-plus-days:1}") int settlementTPlusDays,
      @Nullable InstrumentMetadataClient metadataClient,
      @Nullable TransactionTemplate transactionTemplate) {
    this.tradeRepository = tradeRepository;
    this.positionRepository = positionRepository;
    this.tradePublisher = tradePublisher;
    this.positionPublisher = positionPublisher;
    this.settlementTPlusDays = settlementTPlusDays;
    this.metadataClient = metadataClient;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * YU05 (post-trade-compliance, FR-PTC08): {@code order.getId()} is now the BLP's deterministic
   * trade id (ADR-022), so a redelivered NATS message (ack timeout, operator replay) carries the
   * SAME id as the original — book once, ignore the rest, instead of double-booking a position.
   *
   * <p>YU16 (FR-CDM24): Treasury metadata is resolved BEFORE the database transaction opens —
   * a hung reference-data call must not hold a connection and row locks — so the transaction is
   * template-driven rather than annotation-driven. Non-Treasury orders do no lookup at all.
   */
  public TradeBookingResult processTrade(TradeOrder order) {
    log.debug("Trade order received: {}", order);

    InstrumentMetadata metadata = resolveIfBond(order.getSecurity());
    return inTransaction(() -> {
      Optional<Trade> existing = tradeRepository.findById(order.getId());
      if (existing.isPresent()) {
        log.info("Duplicate trade delivery ignored (already booked): id={}", order.getId());
        Trade existingTrade = existing.get();
        Position existingPosition = positionRepository.findByAccountIdAndSecurity(
            existingTrade.getAccountId(), existingTrade.getSecurity());
        return new TradeBookingResult(existingTrade, existingPosition);
      }

      String rejection = bondRejection(order, metadata);
      if (rejection != null) {
        TradeBookingResult rejected = bookRejected(order, rejection);
        publishTradeOnly(order.getAccountId(), rejected);
        return rejected;
      }

      Position position = positionRepository.findByAccountIdAndSecurity(order.getAccountId(), order.getSecurity());
      if (position == null) {
        position = newFlatPosition(order);
      }

      Trade trade = bookTrade(order, position);

      tradeRepository.save(trade);
      positionRepository.save(position);

      TradeBookingResult result = new TradeBookingResult(trade, position);
      log.debug("Trade Processing complete: {}", result);

      publish(order.getAccountId(), result);

      return result;
    });
  }

  /**
   * Books a batch of trades in a single transaction. Positions are looked up once per unique
   * (accountId, security) pair and updated in memory across the whole batch, so the whole batch
   * costs O(unique positions) SELECTs instead of O(orders) — and Hibernate batches the resulting
   * saveAll() inserts into multi-row statements (see spring.jpa.properties.hibernate.jdbc.batch_size).
   *
   * <p>YU05 (FR-PTC08): orders whose id already exists are dropped from the batch entirely (no
   * position mutation, no re-publish) — same idempotency guarantee as {@link #processTrade}.
   *
   * <p>YU16: Treasury metadata for every distinct {@code UST-} security in the batch is resolved
   * before the transaction; a failing Treasury order books Rejected (trade published, position
   * untouched) without poisoning the rest of the batch.
   */
  public List<TradeBookingResult> processTrades(List<TradeOrder> orders) {
    Map<String, InstrumentMetadata> metadataBySecurity = new HashMap<>();
    for (TradeOrder order : orders) {
      if (isBondKey(order.getSecurity()) && !metadataBySecurity.containsKey(order.getSecurity())) {
        metadataBySecurity.put(order.getSecurity(), resolveIfBond(order.getSecurity()));
      }
    }

    return inTransaction(() -> {
      Set<String> alreadyBooked = new HashSet<>();
      for (Trade existing : tradeRepository.findAllById(orders.stream().map(TradeOrder::getId).toList())) {
        alreadyBooked.add(existing.getId());
      }

      Map<String, Position> positionsByKey = new HashMap<>();
      List<Trade> trades = new ArrayList<>(orders.size());
      List<TradeBookingResult> results = new ArrayList<>(orders.size());
      List<TradeBookingResult> rejectedResults = new ArrayList<>();

      for (TradeOrder order : orders) {
        if (alreadyBooked.contains(order.getId())) {
          log.info("Duplicate trade delivery ignored (already booked): id={}", order.getId());
          continue;
        }

        String rejection = bondRejection(order, metadataBySecurity.get(order.getSecurity()));
        if (rejection != null) {
          TradeBookingResult rejected = bookRejected(order, rejection);
          results.add(rejected);
          rejectedResults.add(rejected);
          continue;
        }

        String key = order.getAccountId() + "|" + order.getSecurity();
        Position position = positionsByKey.get(key);
        if (position == null) {
          position = positionRepository.findByAccountIdAndSecurity(order.getAccountId(), order.getSecurity());
          if (position == null) {
            position = newFlatPosition(order);
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
        if (rejectedResults.contains(result)) {
          publishTradeOnly(result.getTrade().getAccountId(), result);
        } else {
          publish(result.getTrade().getAccountId(), result);
        }
      }

      log.debug("Batch trade processing complete: {} trades, {} positions", trades.size(), positionsByKey.size());
      return results;
    });
  }

  /** Computes the booked (not-yet-settled) trade and mutates {@code position} in place. Does not persist. */
  private Trade bookTrade(TradeOrder order, Position position) {
    Trade trade = newTradeFrom(order);
    // YU05 (post-trade-compliance, FR-PTC02): booking and settlement are no longer the same
    // instant — a trade is Processing until SettlementService's T+N sweep (or a manual force
    // override) advances it to Settled.
    trade.setState(TradeState.Processing);
    trade.setSettlementDate(plusBusinessDays(trade.getCreated(), settlementTPlusDays));

    BigDecimal executionPrice = trade.getPrice();
    int oldQuantity = position.getQuantity() == null ? 0 : position.getQuantity();
    BigDecimal oldAverage = position.getAverageCostBasis() == null
        ? zero()
        : position.getAverageCostBasis().setScale(PRICE_SCALE, RoundingMode.HALF_UP);

    int signedQuantity = ((order.getSide() == TradeSide.Buy) ? 1 : -1) * trade.getQuantity();
    int newQuantity = oldQuantity + signedQuantity;
    position.setQuantity(newQuantity);
    position.setAverageCostBasis(
        newAverage(order.getSecurity(), oldQuantity, oldAverage, newQuantity, executionPrice));
    position.setUpdated(trade.getUpdated());

    return trade;
  }

  /**
   * YU16 (FR-CDM22): Treasury average cost is FACE-weighted — growing the position re-weights
   * {@code (oldAvg x oldFace + price x addedFace) / newFace}, reducing it preserves the average,
   * flat resets it, and a flip through zero starts the new direction at the execution price.
   * Equities keep the inherited signed-notional moving average unchanged.
   */
  private BigDecimal newAverage(
      String security, int oldQuantity, BigDecimal oldAverage, int newQuantity, BigDecimal executionPrice) {
    if (newQuantity == 0) {
      return zero();
    }
    if (isBondKey(security)) {
      boolean flipped = (oldQuantity > 0 && newQuantity < 0) || (oldQuantity < 0 && newQuantity > 0);
      if (flipped) {
        return executionPrice;
      }
      int oldFace = Math.abs(oldQuantity);
      int newFace = Math.abs(newQuantity);
      if (newFace < oldFace) {
        return oldAverage;
      }
      BigDecimal grown = oldAverage.multiply(BigDecimal.valueOf(oldFace))
          .add(executionPrice.multiply(BigDecimal.valueOf(newFace - oldFace)));
      return grown.divide(BigDecimal.valueOf(newFace), PRICE_SCALE, RoundingMode.HALF_UP);
    }
    BigDecimal oldNotional = oldAverage.multiply(BigDecimal.valueOf(oldQuantity));
    BigDecimal tradeNotional = executionPrice.multiply(BigDecimal.valueOf(newQuantity - oldQuantity));
    return oldNotional.add(tradeNotional)
        .divide(BigDecimal.valueOf(newQuantity), PRICE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * YU16 (FR-CDM23): the fail-closed rejection reason for a BOND order, or {@code null} for a
   * bookable one. Non-bond securities always pass — they never resolved metadata.
   */
  private String bondRejection(TradeOrder order, @Nullable InstrumentMetadata metadata) {
    if (!isBondKey(order.getSecurity())) {
      return null;
    }
    if (metadata == null || !metadata.isBond()) {
      return MSG_METADATA_UNAVAILABLE;
    }
    if (metadataClient != null && metadataClient.isMatured(metadata)) {
      return MSG_MATURED;
    }
    int face = order.getQuantity() == null ? 0 : order.getQuantity();
    if (face < 100) {
      return MSG_FACE_MIN;
    }
    if (face % 100 != 0) {
      return MSG_FACE_MULTIPLE;
    }
    return null;
  }

  private TradeBookingResult bookRejected(TradeOrder order, String reason) {
    Trade trade = newTradeFrom(order);
    trade.setState(TradeState.Rejected);
    trade.setRejectionReason(reason);
    tradeRepository.save(trade);
    log.warn("Bond booking rejected (fail closed): id={} security={} reason={}",
        order.getId(), order.getSecurity(), reason);
    // No position mutation and no position message - the existing (possibly absent) position is
    // returned untouched so callers keep their result shape.
    Position untouched = positionRepository.findByAccountIdAndSecurity(order.getAccountId(), order.getSecurity());
    return new TradeBookingResult(trade, untouched);
  }

  private Trade newTradeFrom(TradeOrder order) {
    Trade trade = new Trade();
    trade.setId(order.getId());
    trade.setAccountId(order.getAccountId());
    trade.setSecurity(order.getSecurity());
    trade.setSide(order.getSide());
    trade.setQuantity(order.getQuantity());
    trade.setPrice((order.getPrice() == null ? BigDecimal.ZERO : order.getPrice())
        .setScale(PRICE_SCALE, RoundingMode.HALF_UP));
    trade.setSourceOrderId(order.getSourceOrderId());
    Date now = new Date();
    trade.setCreated(now);
    trade.setUpdated(now);
    return trade;
  }

  private Position newFlatPosition(TradeOrder order) {
    Position position = new Position();
    position.setAccountId(order.getAccountId());
    position.setSecurity(order.getSecurity());
    position.setQuantity(0);
    position.setAverageCostBasis(zero());
    return position;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean isBondKey(String security) {
    if (security == null) {
      return false;
    }
    for (String prefix : BOND_KEY_PREFIXES) {
      if (security.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private InstrumentMetadata resolveIfBond(String security) {
    if (!isBondKey(security) || metadataClient == null) {
      return null;
    }
    return metadataClient.resolve(security);
  }

  /** YU16: template-driven so metadata resolution stays outside; null template = test seam. */
  private <T> T inTransaction(Supplier<T> body) {
    if (transactionTemplate == null) {
      return body.get();
    }
    return transactionTemplate.execute(status -> body.get());
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

  /** YU16 (FR-CDM23): a rejected trade is published; a position message never follows it. */
  private void publishTradeOnly(Integer accountId, TradeBookingResult result) {
    try {
      tradePublisher.publish("/accounts/" + accountId + "/trades", result.getTrade());
    } catch (PubSubException exc) {
      log.error("Error publishing rejected trade for account {}", accountId, exc);
    }
  }
}
