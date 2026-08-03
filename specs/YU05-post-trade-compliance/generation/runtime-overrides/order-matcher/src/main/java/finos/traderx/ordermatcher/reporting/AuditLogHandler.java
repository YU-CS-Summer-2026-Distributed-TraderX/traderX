package finos.traderx.ordermatcher.reporting;

import com.lmax.disruptor.EventHandler;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.SymbolTable;
import java.util.List;

/**
 * YU05 (post-trade-compliance, ADR-023, FR-PTC20/21): captures every reportable output event
 * (order accept/reject/partial-fill/fill/cancel, trade booked) into {@code sink}, filtered to an
 * {@code [fromSeq, toSeq]} input-sequence window. Used only against an offline shadow replay (see
 * {@code LmaxEngine.generateRegulatoryReport}) — never wired into the live output ring, since
 * regulatory reports are generated on demand from the journal, not accumulated live.
 */
public final class AuditLogHandler implements EventHandler<OutputEvent> {
    private final List<AuditRecord> sink;
    private final SymbolTable symbols;
    private final long fromSeq;
    private final long toSeq;

    public AuditLogHandler(List<AuditRecord> sink, SymbolTable symbols, long fromSeq, long toSeq) {
        this.sink = sink;
        this.symbols = symbols;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (e.inputSeq < fromSeq || (toSeq > 0 && e.inputSeq > toSeq)) {
            return;
        }
        if (!isReportableKind(e.kind)) {
            return;
        }
        sink.add(AuditRecord.fromEvent(e, symbols));
    }

    private static boolean isReportableKind(byte kind) {
        return kind == OutputEvent.KIND_ORDER_ACCEPTED
            || kind == OutputEvent.KIND_ORDER_REJECTED
            || kind == OutputEvent.KIND_ORDER_PARTIALLY_FILLED
            || kind == OutputEvent.KIND_ORDER_FILLED
            || kind == OutputEvent.KIND_ORDER_CANCELED
            || kind == OutputEvent.KIND_TRADE_BOOKED;
    }
}
