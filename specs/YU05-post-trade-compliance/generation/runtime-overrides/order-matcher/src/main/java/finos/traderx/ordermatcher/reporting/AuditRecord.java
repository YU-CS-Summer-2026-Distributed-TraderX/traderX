package finos.traderx.ordermatcher.reporting;

import finos.traderx.ordermatcher.lmax.OrderSnapshot;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import finos.traderx.ordermatcher.lmax.SymbolTable;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.risk.RiskReason;
import java.math.BigDecimal;

/**
 * YU05 (post-trade-compliance, ADR-023, FR-PTC20): one flat audit-trail record per order/trade
 * lifecycle event, exactly as it happened — sourced from journal replay via {@link
 * AuditLogHandler}, never from the MariaDB projection. {@code inputSeq} is the sequenced input
 * command's global sequence number, which is what makes a report reproducible byte-for-byte
 * (same journal range in ⇒ same records out, always).
 *
 * <p>{@code riskReason} carries WHY, not just what: without it the record says an order was
 * refused and never whether the risk gate refused an unknown account or the collar refused a good
 * one, which had to be inferred from accepted/rejected price ranges. It is present on every record
 * and never null — {@code RiskReason} ordinal 0 is {@code ACCEPTED} and the publisher sets 0 on the
 * accepted paths, so a non-rejection record renders the engine's own byte, not a placeholder. It is
 * not gated on {@code ORDER_REJECTED}: a cancel carries a reason too ({@code SELF_TRADE_PREVENTED}).
 *
 * <p>Field names mirror the cluster tier's {@code ClusterRecon.AuditRow} exactly — the two move
 * together or a proof written against one stops reading the other.
 */
public record AuditRecord(String kind, long inputSeq, String orderId, String tradeId, int accountId,
                           String security, String side, int quantity, BigDecimal price,
                           long timestampMillis, String riskReason) {

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
            e.updatedAtMillis,
            reasonName(e.riskReason));
    }

    /** Bounds-checked: the ordinal comes off a replayed event, and an unknown byte from a later
     *  build must render as one odd column, not throw and blank the whole report. */
    private static String reasonName(byte reason) {
        RiskReason[] all = RiskReason.values();
        return reason >= 0 && reason < all.length ? all[reason].name() : "UNKNOWN_" + reason;
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
