package finos.traderx.ordermatcher.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SymbolTable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * YU05 (post-trade-compliance, ADR-023, FR-PTC20/21): the audit log must capture every
 * order/trade lifecycle kind, filter strictly by the requested input-sequence range, and ignore
 * kinds that aren't part of the regulatory report (e.g. position updates).
 */
class AuditLogHandlerTest {

    @Test
    void capturesAllReportableKindsWithinRange() {
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        List<AuditRecord> sink = new ArrayList<>();
        AuditLogHandler handler = new AuditLogHandler(sink, symbols, 0L, 0L);

        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 1L, securityId), 0, true);
        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_REJECTED, 2L, securityId), 1, true);
        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_CANCELED, 3L, securityId), 2, true);
        handler.onEvent(tradeEvent(4L, securityId, 42L), 3, true);
        handler.onEvent(positionEvent(5L, securityId), 4, true); // not reportable, must be ignored

        assertEquals(4, sink.size());
        assertEquals("ORDER_ACCEPTED", sink.get(0).kind());
        assertEquals("ORDER_REJECTED", sink.get(1).kind());
        assertEquals("ORDER_CANCELED", sink.get(2).kind());
        assertEquals("TRADE_BOOKED", sink.get(3).kind());
        assertEquals("trd-09b-42", sink.get(3).tradeId());
    }

    @Test
    void filtersStrictlyByInputSeqRange() {
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        List<AuditRecord> sink = new ArrayList<>();
        AuditLogHandler handler = new AuditLogHandler(sink, symbols, 2L, 4L);

        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 1L, securityId), 0, true); // below range
        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 2L, securityId), 1, true); // in range
        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 4L, securityId), 2, true); // in range (inclusive)
        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 5L, securityId), 3, true); // above range

        assertEquals(2, sink.size());
        assertTrue(sink.stream().allMatch(r -> r.inputSeq() >= 2L && r.inputSeq() <= 4L));
    }

    @Test
    void toSeqZeroMeansUnboundedToTheEnd() {
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        List<AuditRecord> sink = new ArrayList<>();
        AuditLogHandler handler = new AuditLogHandler(sink, symbols, 0L, 0L);

        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 1_000_000L, securityId), 0, true);

        assertEquals(1, sink.size());
    }

    @Test
    void frPtc21SameReplayAndRangeProducesIdenticalRecords() {
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        List<OutputEvent> replay = List.of(
            orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 1L, securityId),
            orderEvent(OutputEvent.KIND_ORDER_REJECTED, 2L, securityId),
            tradeEvent(3L, securityId, 42L),
            positionEvent(4L, securityId),
            orderEvent(OutputEvent.KIND_ORDER_CANCELED, 5L, securityId));

        List<AuditRecord> first = replay(replay, symbols, 2L, 5L);
        List<AuditRecord> second = replay(replay, symbols, 2L, 5L);

        assertEquals(first, second);
        assertEquals(List.of("ORDER_REJECTED", "TRADE_BOOKED", "ORDER_CANCELED"),
            first.stream().map(AuditRecord::kind).toList());
    }

    private static List<AuditRecord> replay(List<OutputEvent> events, SymbolTable symbols,
                                             long fromSeq, long toSeq) {
        List<AuditRecord> sink = new ArrayList<>();
        AuditLogHandler handler = new AuditLogHandler(sink, symbols, fromSeq, toSeq);
        long outputSequence = 0L;
        for (OutputEvent event : events) {
            handler.onEvent(event, outputSequence++, outputSequence == events.size());
        }
        return List.copyOf(sink);
    }

    private static OutputEvent orderEvent(byte kind, long inputSeq, int securityId) {
        OutputEvent e = new OutputEvent();
        e.kind = kind;
        e.inputSeq = inputSeq;
        e.orderRef = 42;
        e.accountId = 22214;
        e.securityId = securityId;
        e.side = 0;
        e.quantity = 100;
        e.limitPx = Px.toTicks(new BigDecimal("100.000"));
        e.updatedAtMillis = 1_700_000_000_000L;
        return e;
    }

    private static OutputEvent tradeEvent(long inputSeq, int securityId, long tradeSeq) {
        OutputEvent e = orderEvent(OutputEvent.KIND_TRADE_BOOKED, inputSeq, securityId);
        e.tradeSeq = tradeSeq;
        e.tradeQty = 100;
        e.tradePx = Px.toTicks(new BigDecimal("100.000"));
        return e;
    }

    private static OutputEvent positionEvent(long inputSeq, int securityId) {
        OutputEvent e = new OutputEvent();
        e.kind = OutputEvent.KIND_POSITION_UPDATED;
        e.inputSeq = inputSeq;
        e.accountId = 22214;
        e.securityId = securityId;
        return e;
    }
}
