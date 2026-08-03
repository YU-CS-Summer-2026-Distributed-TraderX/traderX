package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.math.BigDecimal;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-024, FR-PTC30-32): slippage sign convention must always mean
 * "worse than benchmark is positive" regardless of side — a buy paying more, or a sell receiving
 * less, both count as positive (costly) slippage.
 */
class TcaServiceTest {

    @Test
    void buyExecutingAboveBenchmarkHasPositiveSlippage() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        PriceHistoryStore priceHistory = new PriceHistoryStore(1000);
        long execMillis = 1_000_000L;
        priceHistory.record("IBM", new BigDecimal("100.000"), execMillis - 60_000);

        Trade trade = trade("trd-1", "IBM", TradeSide.Buy, new BigDecimal("101.000"), execMillis);
        when(tradeRepository.findById("trd-1")).thenReturn(Optional.of(trade));

        TcaService tca = new TcaService(tradeRepository, priceHistory, 5);
        TcaService.TcaReport report = tca.computeForTrade("trd-1");

        assertEquals(new BigDecimal("100.000"), report.benchmarkPrice());
        assertTrue(report.slippageBps().signum() > 0, "buy paying more than benchmark should be positive slippage");
    }

    @Test
    void sellExecutingBelowBenchmarkHasPositiveSlippage() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        PriceHistoryStore priceHistory = new PriceHistoryStore(1000);
        long execMillis = 1_000_000L;
        priceHistory.record("IBM", new BigDecimal("100.000"), execMillis - 60_000);

        Trade trade = trade("trd-2", "IBM", TradeSide.Sell, new BigDecimal("99.000"), execMillis);
        when(tradeRepository.findById("trd-2")).thenReturn(Optional.of(trade));

        TcaService tca = new TcaService(tradeRepository, priceHistory, 5);
        TcaService.TcaReport report = tca.computeForTrade("trd-2");

        assertTrue(report.slippageBps().signum() > 0, "sell receiving less than benchmark should be positive slippage");
    }

    @Test
    void sellExecutingAboveBenchmarkHasNegativeSlippage() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        PriceHistoryStore priceHistory = new PriceHistoryStore(1000);
        long execMillis = 1_000_000L;
        priceHistory.record("IBM", new BigDecimal("100.000"), execMillis - 60_000);

        Trade trade = trade("trd-3", "IBM", TradeSide.Sell, new BigDecimal("101.000"), execMillis);
        when(tradeRepository.findById("trd-3")).thenReturn(Optional.of(trade));

        TcaService tca = new TcaService(tradeRepository, priceHistory, 5);
        TcaService.TcaReport report = tca.computeForTrade("trd-3");

        assertTrue(report.slippageBps().signum() < 0, "sell receiving more than benchmark should be negative (favorable) slippage");
    }

    @Test
    void noPriceHistoryYieldsNullBenchmarkAndSlippageNotAFabricatedZero() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        PriceHistoryStore priceHistory = new PriceHistoryStore(1000); // never fed for MSFT

        Trade trade = trade("trd-4", "MSFT", TradeSide.Buy, new BigDecimal("300.000"), 1_000_000L);
        when(tradeRepository.findById("trd-4")).thenReturn(Optional.of(trade));

        TcaService tca = new TcaService(tradeRepository, priceHistory, 5);
        TcaService.TcaReport report = tca.computeForTrade("trd-4");

        assertNull(report.benchmarkPrice());
        assertNull(report.slippageBps());
    }

    @Test
    void unknownTradeThrows() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        when(tradeRepository.findById("missing")).thenReturn(Optional.empty());
        TcaService tca = new TcaService(tradeRepository, new PriceHistoryStore(1000), 5);

        assertThrows(NoSuchElementException.class, () -> tca.computeForTrade("missing"));
    }

    private static Trade trade(String id, String security, TradeSide side, BigDecimal price, long createdMillis) {
        Trade trade = new Trade();
        trade.setId(id);
        trade.setAccountId(22214);
        trade.setSecurity(security);
        trade.setSide(side);
        trade.setQuantity(100);
        trade.setPrice(price);
        trade.setCreated(new Date(createdMillis));
        return trade;
    }
}
