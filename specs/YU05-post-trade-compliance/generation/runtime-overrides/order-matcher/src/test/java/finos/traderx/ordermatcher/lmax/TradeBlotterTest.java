package finos.traderx.ordermatcher.lmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-022): the blotter must (a) key trades by the deterministic
 * tradeSeq-derived id, (b) return entries in ascending tradeSeq order for forward reconciliation
 * pagination, (c) stay bounded via oldest-first eviction, and (d) be populated by the handler
 * regardless of replay state (the handler has no isReplaying() branch at all — see
 * TradeBlotterHandler's doc comment for why that is deliberate).
 */
class TradeBlotterTest {

    @Test
    void recordsAreKeyedByDeterministicIdAndReturnedInAscendingOrder() {
        TradeBlotter blotter = new TradeBlotter(10);
        blotter.record(record(1L, 22214, "IBM", 100));
        blotter.record(record(2L, 44044, "MSFT", 200));
        blotter.record(record(3L, 22214, "IBM", 50));

        List<TradeBlotter.TradeRecord> since0 = blotter.since(0L, 10);
        assertEquals(3, since0.size());
        assertEquals("trd-09b-1", since0.get(0).id());
        assertEquals("trd-09b-2", since0.get(1).id());
        assertEquals("trd-09b-3", since0.get(2).id());

        List<TradeBlotter.TradeRecord> since1 = blotter.since(1L, 10);
        assertEquals(2, since1.size());
        assertEquals("trd-09b-2", since1.get(0).id());
    }

    @Test
    void sincePaginationRespectsLimit() {
        TradeBlotter blotter = new TradeBlotter(100);
        for (long seq = 1; seq <= 10; seq++) {
            blotter.record(record(seq, 22214, "IBM", 100));
        }
        List<TradeBlotter.TradeRecord> page = blotter.since(0L, 3);
        assertEquals(3, page.size());
        assertEquals("trd-09b-1", page.get(0).id());
        assertEquals("trd-09b-3", page.get(2).id());
    }

    @Test
    void boundedCapacityEvictsOldestFirst() {
        TradeBlotter blotter = new TradeBlotter(3);
        for (long seq = 1; seq <= 5; seq++) {
            blotter.record(record(seq, 22214, "IBM", 100));
        }
        assertEquals(3, blotter.size());
        assertEquals(2, blotter.evictionCount());

        List<TradeBlotter.TradeRecord> remaining = blotter.since(0L, 10);
        assertEquals(3, remaining.size());
        assertEquals("trd-09b-3", remaining.get(0).id());
        assertEquals("trd-09b-5", remaining.get(2).id());
    }

    @Test
    void handlerCapturesTradeBookedEventsRegardlessOfReplayState() {
        TradeBlotter blotter = new TradeBlotter(10);
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        TradeBlotterHandler handler = new TradeBlotterHandler(blotter, symbols);

        OutputEvent booked = new OutputEvent();
        booked.kind = OutputEvent.KIND_TRADE_BOOKED;
        booked.tradeSeq = 42L;
        booked.accountId = 22214;
        booked.securityId = securityId;
        booked.side = 0;
        booked.tradeQty = 100;
        booked.tradePx = Px.toTicks(new BigDecimal("136.250"));
        booked.updatedAtMillis = 1_700_000_000_000L;
        handler.onEvent(booked, 0L, true);

        assertEquals(1, blotter.size());
        TradeBlotter.TradeRecord recorded = blotter.since(0L, 10).get(0);
        assertEquals("trd-09b-42", recorded.id());
        assertEquals("IBM", recorded.security());
        assertEquals(new BigDecimal("136.250"), recorded.price());

        // Non-trade-booked kinds must be ignored (this is the same handler regardless of the
        // engine's replay state, so there is no separate replay-path assertion needed here).
        OutputEvent accepted = new OutputEvent();
        accepted.kind = OutputEvent.KIND_ORDER_ACCEPTED;
        handler.onEvent(accepted, 1L, true);
        assertEquals(1, blotter.size());
    }

    private static TradeBlotter.TradeRecord record(long tradeSeq, int accountId, String security, int qty) {
        return new TradeBlotter.TradeRecord(
            OrderSnapshot.tradeIdFor(tradeSeq), tradeSeq, accountId, security, "Buy", qty,
            new BigDecimal("100.000"), 1_700_000_000_000L);
    }
}
