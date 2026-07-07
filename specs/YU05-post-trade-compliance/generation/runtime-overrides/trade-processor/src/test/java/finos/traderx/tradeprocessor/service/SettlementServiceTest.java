package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** YU05 (post-trade-compliance, FR-PTC02/06): the T+N sweep and manual override, in isolation. */
class SettlementServiceTest {

    @Test
    void sweepAdvancesDueProcessingTradesToSettled() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        Trade due = new Trade();
        due.setId("trd-09b-1");
        due.setState(TradeState.Processing);
        when(tradeRepository.findByStateAndSettlementDateLessThanEqual(eq(TradeState.Processing), any()))
            .thenReturn(List.of(due));

        SettlementService settlementService = new SettlementService(tradeRepository);
        settlementService.sweep();

        assertEquals(TradeState.Settled, due.getState());
        verify(tradeRepository, times(1)).saveAll(List.of(due));
        assertEquals(1, settlementService.sweptCount());
    }

    @Test
    void sweepIsANoOpWhenNothingIsDue() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findByStateAndSettlementDateLessThanEqual(eq(TradeState.Processing), any()))
            .thenReturn(List.of());

        SettlementService settlementService = new SettlementService(tradeRepository);
        settlementService.sweep();

        verify(tradeRepository, times(0)).saveAll(any());
        assertEquals(0, settlementService.sweptCount());
    }

    @Test
    void forceSettleTransitionsProcessingTradeImmediately() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        Trade trade = new Trade();
        trade.setId("trd-09b-2");
        trade.setState(TradeState.Processing);
        when(tradeRepository.findById("trd-09b-2")).thenReturn(Optional.of(trade));

        SettlementService settlementService = new SettlementService(tradeRepository);
        SettlementService.ForceResult result = settlementService.forceSettle("trd-09b-2");

        assertEquals(SettlementService.ForceResult.SETTLED, result);
        assertEquals(TradeState.Settled, trade.getState());
    }

    @Test
    void forceSettleOnAlreadySettledTradeIsANoOp() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        Trade trade = new Trade();
        trade.setId("trd-09b-3");
        trade.setState(TradeState.Settled);
        Date originalSettlementDate = new Date(0);
        trade.setSettlementDate(originalSettlementDate);
        when(tradeRepository.findById("trd-09b-3")).thenReturn(Optional.of(trade));

        SettlementService settlementService = new SettlementService(tradeRepository);
        SettlementService.ForceResult result = settlementService.forceSettle("trd-09b-3");

        assertEquals(SettlementService.ForceResult.ALREADY_SETTLED, result);
        assertEquals(originalSettlementDate, trade.getSettlementDate());
    }

    @Test
    void forceSettleOnUnknownTradeReturnsNotFound() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findById("missing")).thenReturn(Optional.empty());

        SettlementService settlementService = new SettlementService(tradeRepository);
        SettlementService.ForceResult result = settlementService.forceSettle("missing");

        assertEquals(SettlementService.ForceResult.NOT_FOUND, result);
    }
}
