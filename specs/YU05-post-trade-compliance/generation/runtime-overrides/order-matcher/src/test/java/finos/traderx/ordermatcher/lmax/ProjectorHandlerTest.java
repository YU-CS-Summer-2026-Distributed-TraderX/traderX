package finos.traderx.ordermatcher.lmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeState;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * YU05 (post-trade-compliance, FR-PTC02/06): {@code ProjectorHandler} is the actual live TRADES
 * writer (not trade-processor — {@code output.legacy-trades.enabled} is false by default, so
 * trade-processor's NATS-driven booking path never fires against the deployed configuration).
 * This test proves the fix landed in the process that matters: a booked trade must start
 * Processing with a real T+N settlement date, not Settled-on-arrival.
 */
class ProjectorHandlerTest {

    @Test
    void bookedTradeStartsProcessingWithSettlementDateSet() {
        SymbolTable symbols = symbolsWith("IBM");
        ProjectorHandler handler = newHandler(symbols);

        Trade trade = handler.toTrade(tradeEvent(symbols.idFor("IBM")));

        assertEquals(TradeState.Processing, trade.getState());
        assertNotNull(trade.getSettlementDate());
        assertTrue(trade.getSettlementDate().after(trade.getCreated()));
    }

    @Test
    void settlementDateSkipsWeekends() {
        SymbolTable symbols = symbolsWith("IBM");
        ProjectorHandler handler = newHandler(symbols);

        // Friday 2026-07-10 -> updatedAtMillis is that instant; +1 business day = Monday 2026-07-13.
        LocalDate friday = LocalDate.of(2026, 7, 10);
        assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek());
        long fridayMillis = friday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

        OutputEvent e = tradeEvent(symbols.idFor("IBM"));
        e.updatedAtMillis = fridayMillis;
        Trade trade = handler.toTrade(e);

        LocalDate settlementDate = Instant.ofEpochMilli(trade.getSettlementDate().getTime())
            .atZone(ZoneOffset.UTC).toLocalDate();
        assertEquals(LocalDate.of(2026, 7, 13), settlementDate);
    }

    @Test
    void deterministicIdStillUsedForTheLiveWritePath() {
        SymbolTable symbols = symbolsWith("IBM");
        ProjectorHandler handler = newHandler(symbols);

        OutputEvent e = tradeEvent(symbols.idFor("IBM"));
        e.tradeSeq = 99L;
        Trade trade = handler.toTrade(e);

        assertEquals("trd-09b-99", trade.getId());
    }

    private static SymbolTable symbolsWith(String ticker) {
        SymbolTable symbols = new SymbolTable(16);
        symbols.idFor(ticker);
        return symbols;
    }

    /** {@code toTrade} never touches the DB, but the constructor wires a transaction manager
     *  that requires a non-null DataSource — stub just enough for that to succeed. */
    private static ProjectorHandler newHandler(SymbolTable symbols) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.getDataSource()).thenReturn(mock(DataSource.class));
        return new ProjectorHandler(jdbcTemplate, symbols, 500, 1_000_000, new HotPathMetrics(), 1);
    }

    private static OutputEvent tradeEvent(int securityId) {
        OutputEvent e = new OutputEvent();
        e.kind = OutputEvent.KIND_TRADE_BOOKED;
        e.orderRef = 42;
        e.accountId = 22214;
        e.securityId = securityId;
        e.side = (byte) OrderSide.Buy.ordinal();
        e.tradeQty = 100;
        e.tradeSeq = 7L;
        e.tradePx = Px.toTicks(new BigDecimal("101.125"));
        e.updatedAtMillis = 1_700_000_000_000L;
        return e;
    }
}
