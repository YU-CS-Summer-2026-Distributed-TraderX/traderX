package finos.traderx.ordermatcher.reporting;

import finos.traderx.ordermatcher.lmax.OrderSnapshot;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SymbolTable;
import finos.traderx.ordermatcher.model.OrderSide;
import java.math.BigDecimal;

/**
 * YU05 (post-trade-compliance, ADR-023, FR-PTC20): one flat audit-trail record per order/trade
 * lifecycle event, exactly as it happened — sourced from journal replay via {@link
 * AuditLogHandler}, never from the MariaDB projection. {@code inputSeq} is the sequenced input
 * command's global sequence number, which is what makes a report reproducible byte-for-byte
 * (same journal range in ⇒ same records out, always).
 */
public record AuditRecord(String kind, long inputSeq, String orderId, String tradeId, int accountId,
                           String security, String side, int quantity, BigDecimal price,
                           long timestampMillis) {

    public static AuditRecord fromEvent(OutputEvent e, SymbolTable symbols) {
        boolean isTrade = e.kind == OutputEvent.KIND_TRADE_BOOKED;
        String tradeId = isTrade ? OrderSnapshot.tradeIdFor(e.tradeSeq) : null;
        int qty = isTrade ? e.tradeQty : e.quantity;
        long px = isTrade ? e.tradePx : e.limitPx;
        return new AuditRecord(
            kindName(e.kind),
            e.inputSeq,
            OrderSnapshot.orderIdFor(e.orderRef),
            tradeId,
            e.accountId,
            symbols.tickerFor(e.securityId),
            OrderSide.values()[e.side].name(),
            qty,
            Px.toBigDecimal(px),
            e.updatedAtMillis);
    }

    private static String kindName(byte kind) {
        return switch (kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED -> "ORDER_ACCEPTED";
            case OutputEvent.KIND_ORDER_REJECTED -> "ORDER_REJECTED";
            case OutputEvent.KIND_ORDER_PARTIALLY_FILLED -> "ORDER_PARTIALLY_FILLED";
            case OutputEvent.KIND_ORDER_FILLED -> "ORDER_FILLED";
            case OutputEvent.KIND_ORDER_CANCELED -> "ORDER_CANCELED";
            case OutputEvent.KIND_TRADE_BOOKED -> "TRADE_BOOKED";
            default -> "UNKNOWN_" + kind;
        };
    }
}
