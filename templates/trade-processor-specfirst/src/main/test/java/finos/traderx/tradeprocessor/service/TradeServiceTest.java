package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.TradeBookingResult;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.PositionRepository;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import finos.traderx.tradeprocessor.model.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for trade booking — no Spring context, no DB. Repositories and publishers are mocked.
 * The load-bearing behaviour is the position arithmetic: a Buy adds, a Sell subtracts, and repeated
 * trades accumulate onto the existing row. This is exactly the kind of signed-quantity logic where
 * a sign flip or a missed accumulation is silent in production but a one-line assertion here.
 */
@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

  @Mock private TradeRepository tradeRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private Publisher<Trade> tradePublisher;
  @Mock private Publisher<Position> positionPublisher;

  private TradeService service() {
    return new TradeService(tradeRepository, positionRepository, tradePublisher, positionPublisher);
  }

  private static TradeOrder order(int account, String sec, TradeSide side, int qty) {
    return new TradeOrder("ord", account, sec, side, qty);
  }

  private Position savedPosition() {
    ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void buy_opensNewLongPosition_whenNoneExists() {
    when(positionRepository.findByAccountIdAndSecurity(1, "AAPL")).thenReturn(null);

    TradeBookingResult result = service().processTrade(order(1, "AAPL", TradeSide.Buy, 100));

    assertThat(savedPosition().getQuantity()).isEqualTo(100);
    assertThat(result.getPosition().getQuantity()).isEqualTo(100);
  }

  @Test
  void sell_producesShortPosition_whenNoneExists() {
    when(positionRepository.findByAccountIdAndSecurity(1, "AAPL")).thenReturn(null);

    service().processTrade(order(1, "AAPL", TradeSide.Sell, 40));

    // No long guard in the baseline — a sell with no inventory goes short. Assert the sign, not a ban.
    assertThat(savedPosition().getQuantity()).isEqualTo(-40);
  }

  @Test
  void trades_accumulateOntoExistingPosition() {
    Position existing = new Position();
    existing.setAccountId(1);
    existing.setSecurity("AAPL");
    existing.setQuantity(100);
    when(positionRepository.findByAccountIdAndSecurity(1, "AAPL")).thenReturn(existing);

    service().processTrade(order(1, "AAPL", TradeSide.Sell, 30));

    assertThat(savedPosition().getQuantity()).isEqualTo(70);
  }

  @Test
  void bookedTrade_endsSettled() {
    when(positionRepository.findByAccountIdAndSecurity(1, "AAPL")).thenReturn(null);

    TradeBookingResult result = service().processTrade(order(1, "AAPL", TradeSide.Buy, 10));

    assertThat(result.getTrade().getState()).isEqualTo(TradeState.Settled);
    // saved twice: once New, once after the state walk to Settled.
    verify(tradeRepository, org.mockito.Mockito.times(2)).save(any(Trade.class));
  }

  @Test
  void publishesTradeAndPosition_toPerAccountTopics() throws Exception {
    when(positionRepository.findByAccountIdAndSecurity(7, "IBM")).thenReturn(null);

    service().processTrade(order(7, "IBM", TradeSide.Buy, 5));

    verify(tradePublisher).publish(eq("/accounts/7/trades"), any(Trade.class));
    verify(positionPublisher).publish(eq("/accounts/7/positions"), any(Position.class));
  }

  @Test
  void publishFailureIsBestEffort_bookingStillSucceeds() throws Exception {
    when(positionRepository.findByAccountIdAndSecurity(1, "AAPL")).thenReturn(null);
    org.mockito.Mockito.doThrow(new PubSubException("feed down"))
        .when(tradePublisher).publish(any(String.class), any(Trade.class));

    // Documents the seam: the baseline treats the outbound publish as best-effort — a feed outage
    // is logged, not propagated, and the trade is still booked/persisted. (A durable outbox is what
    // later YU states add on top of this.)
    TradeBookingResult result = service().processTrade(order(1, "AAPL", TradeSide.Buy, 100));

    assertThat(result.getPosition().getQuantity()).isEqualTo(100);
    verify(tradeRepository, org.mockito.Mockito.atLeastOnce()).save(any(Trade.class));
  }
}
