package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-022, FR-PTC02/08): a redelivered {@code TradeOrder} (now
 * carrying the BLP's deterministic id) must book exactly once, and a freshly booked trade must
 * start {@code Processing} with a real T+N settlement date — not the old "always Settled
 * instantly" shortcut.
 */
class TradeServiceIdempotencyTest {
    private TradeRepository tradeRepository;
    private PositionRepository positionRepository;
    private TradeService tradeService;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        positionRepository = mock(PositionRepository.class);
        Publisher<Trade> tradePublisher = mock(Publisher.class);
        Publisher<Position> positionPublisher = mock(Publisher.class);
        when(tradeRepository.findAllById(any())).thenReturn(List.of());
        // YU16: trailing nulls engage the documented seam - no transaction template (body runs
        // directly) and no metadata client (Treasury bookings would fail closed; none occur here).
        tradeService = new TradeService(tradeRepository, positionRepository, tradePublisher, positionPublisher, 1, null, null);
    }

    private TradeOrder order(String id) {
        TradeOrder order = new TradeOrder(id, 22214, "IBM", TradeSide.Buy, 100);
        order.setPrice(new BigDecimal("136.250"));
        return order;
    }

    @Test
    void firstBookingStartsProcessingWithSettlementDateSet() {
        when(tradeRepository.findById("trd-09b-1")).thenReturn(Optional.empty());
        when(positionRepository.findByAccountIdAndSecurity(22214, "IBM")).thenReturn(null);

        TradeBookingResult result = tradeService.processTrade(order("trd-09b-1"));

        assertEquals(TradeState.Processing, result.getTrade().getState());
        assertNotNull(result.getTrade().getSettlementDate());
        verify(tradeRepository, times(1)).save(any());
        verify(positionRepository, times(1)).save(any());
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        Trade existingTrade = new Trade();
        existingTrade.setId("trd-09b-2");
        existingTrade.setAccountId(22214);
        existingTrade.setSecurity("IBM");
        existingTrade.setState(TradeState.Processing);
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.of(existingTrade));
        when(positionRepository.findByAccountIdAndSecurity(22214, "IBM")).thenReturn(new Position());

        TradeBookingResult result = tradeService.processTrade(order("trd-09b-2"));

        assertEquals("trd-09b-2", result.getTrade().getId());
        verify(tradeRepository, times(0)).save(any());
        verify(positionRepository, times(0)).save(any());
    }

    @Test
    void batchDropsAlreadyBookedOrdersEntirely() {
        Trade existingTrade = new Trade();
        existingTrade.setId("trd-09b-3");
        when(tradeRepository.findAllById(any())).thenReturn(List.of(existingTrade));
        when(positionRepository.findByAccountIdAndSecurity(22214, "IBM")).thenReturn(null);

        List<TradeBookingResult> results = tradeService.processTrades(
            List.of(order("trd-09b-3"), order("trd-09b-4")));

        assertEquals(1, results.size());
        assertEquals("trd-09b-4", results.get(0).getTrade().getId());
    }

    @Test
    void plusBusinessDaysSkipsWeekends() {
        // Friday 2026-07-10 + 1 business day = Monday 2026-07-13.
        LocalDate friday = LocalDate.of(2026, 7, 10);
        assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek());
        Date fridayDate = Date.from(friday.atStartOfDay(ZoneOffset.UTC).toInstant());

        Date settlement = TradeService.plusBusinessDays(fridayDate, 1);

        LocalDate settlementDate = Instant.ofEpochMilli(settlement.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        assertEquals(LocalDate.of(2026, 7, 13), settlementDate);
    }
}
