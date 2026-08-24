package finos.traderx.ordermatcher.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SymbolTable;
import finos.traderx.ordermatcher.risk.RiskReason;
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

    /**
     * The whole point of carrying a reason: two refusals with genuinely DIFFERENT causes must read
     * differently. A check that renders the same string for both would pass while proving nothing,
     * which is the exact defect this column exists to fix — so the assertion is that they DIFFER,
     * not merely that each is non-empty.
     */
    @Test
    void twoRejectionsWithDifferentCausesRenderDifferently() {
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        List<AuditRecord> sink = new ArrayList<>();
        AuditLogHandler handler = new AuditLogHandler(sink, symbols, 0L, 0L);

        handler.onEvent(rejection(1L, securityId, RiskReason.UNKNOWN_ACCOUNT), 0, true);
        handler.onEvent(rejection(2L, securityId, RiskReason.PRICE_COLLAR), 1, true);
        handler.onEvent(orderEvent(OutputEvent.KIND_ORDER_ACCEPTED, 3L, securityId), 2, true);

        assertEquals("UNKNOWN_ACCOUNT", sink.get(0).riskReason());
        assertEquals("PRICE_COLLAR", sink.get(1).riskReason());
        assertNotEquals(sink.get(0).riskReason(), sink.get(1).riskReason(),
            "the risk gate refusing an unknown account and the collar refusing a good one are "
                + "the two causes this column exists to tell apart");
        // Ordinal 0 IS RiskReason.ACCEPTED, so a non-rejection carries the engine's own byte
        // rather than a blank or a synthesized NONE.
        assertEquals("ACCEPTED", sink.get(2).riskReason());
    }

    /**
     * A reason appended by a later build decodes here as an out-of-range ordinal. It must render
     * as one odd column, never throw — the report is produced in one pass, so an exception would
     * blank every row in the range over one byte of one of them.
     */
    @Test
    void anUnknownReasonOrdinalIsNamedOpaquelyNotFatal() {
        SymbolTable symbols = new SymbolTable(16);
        int securityId = symbols.idFor("IBM");
        List<AuditRecord> sink = new ArrayList<>();
        AuditLogHandler handler = new AuditLogHandler(sink, symbols, 0L, 0L);

        OutputEvent fromALaterBuild = orderEvent(OutputEvent.KIND_ORDER_REJECTED, 1L, securityId);
        fromALaterBuild.riskReason = (byte) (RiskReason.values().length + 3);
        handler.onEvent(fromALaterBuild, 0, true);
        OutputEvent corrupt = orderEvent(OutputEvent.KIND_ORDER_REJECTED, 2L, securityId);
        corrupt.riskReason = (byte) -1;
        handler.onEvent(corrupt, 1, true);

        assertEquals(2, sink.size());
        assertTrue(sink.get(0).riskReason().startsWith("UNKNOWN_"), sink.get(0).riskReason());
        assertTrue(sink.get(1).riskReason().startsWith("UNKNOWN_"), sink.get(1).riskReason());
    }

    private static OutputEvent rejection(long inputSeq, int securityId, RiskReason reason) {
        OutputEvent e = orderEvent(OutputEvent.KIND_ORDER_REJECTED, inputSeq, securityId);
        e.riskReason = (byte) reason.ordinal();
        return e;
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
