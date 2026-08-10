package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * YU16 (FR-CDM22/23, ADR-057): Treasury booking semantics — fraction-of-par prices survive at
 * six decimals, average cost is face-weighted, and every validation failure lands as a
 * persisted-and-published {@code Rejected} trade with no position update.
 */
class TreasuryBookingTest {

  private static final String UST = "UST-20280630";
  private static final int ACCOUNT = 17017;

  private TradeRepository trades;
  private PositionRepository positions;
  private Publisher<Trade> tradePublisher;
  private Publisher<Position> positionPublisher;
  private InstrumentMetadataClient metadataClient;
  private TradeService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    trades = mock(TradeRepository.class);
    positions = mock(PositionRepository.class);
    tradePublisher = mock(Publisher.class);
    positionPublisher = mock(Publisher.class);
    metadataClient = mock(InstrumentMetadataClient.class);
    when(trades.findById(anyString())).thenReturn(Optional.empty());
    when(trades.save(any(Trade.class))).thenAnswer(call -> call.getArgument(0));
    when(positions.save(any(Position.class))).thenAnswer(call -> call.getArgument(0));
    service = new TradeService(trades, positions, tradePublisher, positionPublisher, 1, metadataClient, null);
  }

  private static InstrumentMetadata treasury() {
    InstrumentMetadata metadata = new InstrumentMetadata();
    metadata.setInstrumentKey(UST);
    metadata.setAssetClass("US_TREASURY");
    metadata.setSecurityType("Debt");
    InstrumentMetadata.DebtEconomics economics = new InstrumentMetadata.DebtEconomics();
    economics.setMaturityDate("2028-06-30");
    metadata.setDebtEconomics(economics);
    return metadata;
  }

  private static TradeOrder order(TradeSide side, int face, String fractionPrice) {
    TradeOrder order = new TradeOrder("ust-trd-1", ACCOUNT, UST, side, face);
    order.setPrice(new BigDecimal(fractionPrice));
    return order;
  }

  private void metadataResolves() {
    when(metadataClient.resolve(UST)).thenReturn(treasury());
    when(metadataClient.isMatured(any())).thenReturn(false);
  }

  @Test
  void aBondFillBooksAtSixDecimalsWithTheFractionIntact() {
    metadataResolves();
    when(positions.findByAccountIdAndSecurity(ACCOUNT, UST)).thenReturn(null);

    TradeBookingResult result = service.processTrade(order(TradeSide.Buy, 100_000, "0.998860"));

    assertThat(result.getTrade().getState()).isEqualTo(TradeState.Processing);
    assertThat(result.getTrade().getPrice()).isEqualByComparingTo(new BigDecimal("0.998860"));
    assertThat(result.getTrade().getPrice().scale()).isEqualTo(6);
    assertThat(result.getPosition().getQuantity()).isEqualTo(100_000);
    assertThat(result.getPosition().getAverageCostBasis()).isEqualByComparingTo(new BigDecimal("0.998860"));
  }

  @Test
  void aBuyGrowsTheFaceWeightedAverageAndASellPreservesIt() {
    metadataResolves();
    Position existing = new Position();
    existing.setAccountId(ACCOUNT);
    existing.setSecurity(UST);
    existing.setQuantity(100_000);
    existing.setAverageCostBasis(new BigDecimal("0.998780").setScale(6, RoundingMode.HALF_UP));
    when(positions.findByAccountIdAndSecurity(ACCOUNT, UST)).thenReturn(existing);

    // Buy 100,000 more at 0.999780: (0.998780 x 100k + 0.999780 x 100k) / 200k = 0.999280.
    TradeBookingResult grown = service.processTrade(order(TradeSide.Buy, 100_000, "0.999780"));
    assertThat(grown.getPosition().getQuantity()).isEqualTo(200_000);
    assertThat(grown.getPosition().getAverageCostBasis()).isEqualByComparingTo(new BigDecimal("0.999280"));

    // Sell half at any price: the average is preserved, never re-marked by the exit.
    when(trades.findById(anyString())).thenReturn(Optional.empty());
    TradeBookingResult reduced = service.processTrade(order(TradeSide.Sell, 100_000, "1.001000"));
    assertThat(reduced.getPosition().getQuantity()).isEqualTo(100_000);
    assertThat(reduced.getPosition().getAverageCostBasis()).isEqualByComparingTo(new BigDecimal("0.999280"));
  }

  @Test
  void unresolvableMetadataRejectsFailClosedWithTradePublishedAndNoPositionTouched() throws Exception {
    when(metadataClient.resolve(UST)).thenReturn(null);

    TradeBookingResult result = service.processTrade(order(TradeSide.Buy, 100_000, "0.998860"));

    assertThat(result.getTrade().getState()).isEqualTo(TradeState.Rejected);
    assertThat(result.getTrade().getRejectionReason()).isEqualTo(TradeService.MSG_METADATA_UNAVAILABLE);
    verify(trades).save(any(Trade.class));
    verify(positions, never()).save(any(Position.class));
    verify(tradePublisher).publish(eq("/accounts/" + ACCOUNT + "/trades"), any(Trade.class));
    verifyNoInteractions(positionPublisher);
  }

  @Test
  void faceBelowMinimumAndNonMultipleRejectWithTheExactMessages() {
    metadataResolves();

    assertThat(service.processTrade(order(TradeSide.Buy, 50, "0.998860")).getTrade().getRejectionReason())
        .isEqualTo(TradeService.MSG_FACE_MIN);
    assertThat(service.processTrade(order(TradeSide.Buy, 150, "0.998860")).getTrade().getRejectionReason())
        .isEqualTo(TradeService.MSG_FACE_MULTIPLE);
    verify(positions, never()).save(any(Position.class));
  }

  @Test
  void aMaturedTreasuryRejectsNewActivity() {
    when(metadataClient.resolve(UST)).thenReturn(treasury());
    when(metadataClient.isMatured(any())).thenReturn(true);

    TradeBookingResult result = service.processTrade(order(TradeSide.Buy, 100_000, "0.998860"));

    assertThat(result.getTrade().getState()).isEqualTo(TradeState.Rejected);
    assertThat(result.getTrade().getRejectionReason()).isEqualTo(TradeService.MSG_MATURED);
  }

  @Test
  void equityBookingsNeverResolveMetadata() {
    when(positions.findByAccountIdAndSecurity(anyInt(), anyString())).thenReturn(null);
    TradeOrder equity = new TradeOrder("eq-trd-1", ACCOUNT, "IBM", TradeSide.Buy, 100);
    equity.setPrice(new BigDecimal("136.250"));

    service.processTrade(equity);

    verifyNoInteractions(metadataClient);
  }
}
