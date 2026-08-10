package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeBookingResult;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.repository.PositionRepository;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * Position math in {@link TradeService}, with no Spring context, no database and no broker.
 *
 * <p><b>Why this exists.</b> This layer moved the inherited {@code TradeProcessorApplicationTests}
 * into {@code TradeProcessorContextIT} and tagged it {@code integration}, because the context
 * cannot start without a real broker. That left the unit tier of trade-processor with *no*
 * discoverable test: the {@code test} task excludes the {@code integration} tag, so its filter
 * matched nothing and Gradle failed the build outright with "No tests found for given includes".
 * That was invisible until the YU publish wiring first let a compile preflight run against a YU02
 * tree. See {@code issues/HANDOFF-issue-yu02-trade-processor-zero-tests.md}.
 *
 * <p>The assertions below are deliberately about arithmetic that any later layer must preserve —
 * signed quantity and the notional-weighted average — rather than about incidental details, since
 * this file is inherited by every descendant state and YU05 carries its own {@code TradeService}.
 */
class TradeServiceBookingTest {

  private static final String SECURITY = "AAPL";
  private static final int ACCOUNT = 22214;

  private Position booked;

  private TradeService serviceReturning(Position existing) {
    TradeRepository trades = mock(TradeRepository.class);
    PositionRepository positions = mock(PositionRepository.class);
    @SuppressWarnings("unchecked")
    Publisher<Trade> tradePublisher = mock(Publisher.class);
    @SuppressWarnings("unchecked")
    Publisher<Position> positionPublisher = mock(Publisher.class);

    when(positions.findByAccountIdAndSecurity(anyInt(), anyString())).thenReturn(existing);
    when(trades.save(any(Trade.class))).thenAnswer(call -> call.getArgument(0));
    when(positions.save(any(Position.class))).thenAnswer(call -> {
      booked = call.getArgument(0);
      return booked;
    });

    return construct(trades, positions, tradePublisher, positionPublisher);
  }

  /**
   * Builds a {@link TradeService} through whichever constructor the operative layer declares.
   *
   * <p>This file lives in the YU02 layer and is inherited by every descendant, but descendants
   * override {@code TradeService} itself and may widen its constructor — YU05 appends
   * {@code @Value("${settlement.t-plus-days:1}") int settlementTPlusDays}. A hardcoded
   * {@code new TradeService(a, b, c, d)} therefore compiles at YU02..YU04 and breaks the build from
   * YU05 up, which is exactly what it did. The four collaborators are positional and stable; any
   * trailing settings parameter is filled with its documented default, since none of them
   * participate in the position arithmetic asserted here.
   */
  private static TradeService construct(Object... collaborators) {
    Constructor<?>[] declared = TradeService.class.getDeclaredConstructors();
    assertThat(declared)
        .as("TradeService is expected to declare exactly one constructor")
        .hasSize(1);

    Constructor<?> ctor = declared[0];
    Class<?>[] types = ctor.getParameterTypes();
    assertThat(types.length)
        .as("TradeService constructor should still start with its four collaborators")
        .isGreaterThanOrEqualTo(collaborators.length);

    Object[] args = new Object[types.length];
    System.arraycopy(collaborators, 0, args, 0, collaborators.length);
    for (int i = collaborators.length; i < types.length; i++) {
      args[i] = defaultFor(types[i]);
    }

    try {
      return (TradeService) ctor.newInstance(args);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("could not construct TradeService", failure);
    }
  }

  private static Object defaultFor(Class<?> type) {
    if (type == int.class || type == Integer.class) {
      return 1; // settlement.t-plus-days default at YU05
    }
    if (type == long.class || type == Long.class) {
      return 1L;
    }
    if (type == boolean.class || type == Boolean.class) {
      return Boolean.FALSE;
    }
    return null;
  }

  private static TradeOrder order(TradeSide side, int quantity, String price) {
    TradeOrder order = new TradeOrder();
    order.setAccountId(ACCOUNT);
    order.setSecurity(SECURITY);
    order.setSide(side);
    order.setQuantity(quantity);
    order.setPrice(new BigDecimal(price));
    return order;
  }

  @Test
  void buyIntoAFlatBookSetsQuantityAndCostBasisToTheExecutionPrice() {
    TradeBookingResult result =
        serviceReturning(null).processTrade(order(TradeSide.Buy, 100, "10.000"));

    assertThat(result.getPosition().getQuantity()).isEqualTo(100);
    assertThat(result.getPosition().getAverageCostBasis())
        .isEqualByComparingTo(new BigDecimal("10.000"));
    // Deliberately NOT asserting the trade's lifecycle state. YU02 books straight to Settled, but
    // YU05 introduces T+N settlement and books to Processing — a real behaviour change, not a
    // regression, and this file is inherited by both. YU16 widens the price scale to 6 (ADR-057:
    // a bond price is a fraction of par, and three decimals on a fraction is one decimal of
    // percentage), so the rounding invariant at this layer is six decimals.
    assertThat(result.getTrade().getPrice().scale()).isEqualTo(6);
  }

  @Test
  void aSecondBuyAtADifferentPriceMovesTheAverageNotTheLastPrice() {
    Position existing = position(100, "10.000");

    TradeBookingResult result =
        serviceReturning(existing).processTrade(order(TradeSide.Buy, 100, "20.000"));

    // (100 * 10.000 + 100 * 20.000) / 200
    assertThat(result.getPosition().getQuantity()).isEqualTo(200);
    assertThat(result.getPosition().getAverageCostBasis())
        .isEqualByComparingTo(new BigDecimal("15.000"));
  }

  @Test
  void sellIsSignedNegativeAndFlatteningTheBookZeroesTheCostBasis() {
    Position existing = position(100, "10.000");

    TradeBookingResult result =
        serviceReturning(existing).processTrade(order(TradeSide.Sell, 100, "12.000"));

    assertThat(result.getPosition().getQuantity()).isZero();
    // A flat book has no basis to average; guarding this is what stops a divide-by-zero.
    assertThat(result.getPosition().getAverageCostBasis())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void aNullOrderPriceBooksAtZeroRatherThanThrowing() {
    TradeOrder order = order(TradeSide.Buy, 10, "0.000");
    order.setPrice(null);

    TradeBookingResult result = serviceReturning(null).processTrade(order);

    assertThat(result.getTrade().getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.getPosition().getQuantity()).isEqualTo(10);
  }

  @Test
  void theSavedPositionIsTheSameInstanceReturnedInTheResult() {
    TradeBookingResult result =
        serviceReturning(null).processTrade(order(TradeSide.Buy, 5, "3.000"));

    // Guards against a future refactor persisting one object and publishing another.
    assertThat(booked).isSameAs(result.getPosition());
  }

  private static Position position(int quantity, String averageCostBasis) {
    Position position = new Position();
    position.setAccountId(ACCOUNT);
    position.setSecurity(SECURITY);
    position.setQuantity(quantity);
    position.setAverageCostBasis(new BigDecimal(averageCostBasis).setScale(3, RoundingMode.HALF_UP));
    return position;
  }
}
