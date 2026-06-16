package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable order snapshot used by the in-memory read-model and the output edges. Built
 * from output events (or from DB rows at bootstrap); renders the hot path's primitive
 * representation back to the external types (String id, BigDecimal prices, Instant times)
 * exactly once, at the edge (FR-09B25).
 */
public final class OrderSnapshot {
    private static final OrderStatus[] STATUSES = OrderStatus.values();
    private static final OrderSide[] SIDES = OrderSide.values();

    public final int orderRef;
    public final String orderId;
    public final Integer accountId;
    public final String security;
    public final OrderSide side;
    public final Integer quantity;
    public final Integer remainingQuantity;
    public final BigDecimal limitPrice;
    public final OrderStatus status;
    public final Instant createdAt;
    public final Instant updatedAt;
    public final BigDecimal lastExecutionPrice;
    public final Integer lastFillQuantity;

    private OrderSnapshot(int orderRef, String orderId, Integer accountId, String security, OrderSide side,
                          Integer quantity, Integer remainingQuantity, BigDecimal limitPrice, OrderStatus status,
                          Instant createdAt, Instant updatedAt, BigDecimal lastExecutionPrice, Integer lastFillQuantity) {
        this.orderRef = orderRef;
        this.orderId = orderId;
        this.accountId = accountId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.remainingQuantity = remainingQuantity;
        this.limitPrice = limitPrice;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastExecutionPrice = lastExecutionPrice;
        this.lastFillQuantity = lastFillQuantity;
    }

    public static String orderIdFor(int orderRef) {
        // 009 id scheme preserved verbatim; the numeric part is the order reference.
        return String.format("ord-013-%04d", orderRef);
    }

    /**
     * Deterministic booked-trade id from the BLP's global trade number, shared by the
     * projector (DB row id) and the NATS bridge (published id) so both agree exactly — the
     * single-id-per-trade property 009 got from one UUID (FR-09B22/FR-09B23 idempotency).
     */
    public static String tradeIdFor(long tradeSeq) {
        return "trd-09b-" + tradeSeq;
    }

    /** Inverse of {@link #tradeIdFor}: the trade number for our id scheme, or -1 if foreign. */
    public static long tradeSeqFromId(String tradeId) {
        if (tradeId == null || !tradeId.startsWith("trd-09b-")) {
            return -1;
        }
        try {
            return Long.parseLong(tradeId.substring("trd-09b-".length()));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public static OrderSnapshot fromEvent(OutputEvent e, SymbolTable symbols) {
        return new OrderSnapshot(
            e.orderRef,
            orderIdFor(e.orderRef),
            e.accountId,
            symbols.tickerFor(e.securityId),
            SIDES[e.side],
            e.quantity,
            e.remainingQty,
            Px.toBigDecimal(e.limitPx),
            STATUSES[e.status],
            Instant.ofEpochMilli(e.createdAtMillis),
            Instant.ofEpochMilli(e.updatedAtMillis),
            e.lastExecPx == Px.NONE ? null : Px.toBigDecimal(e.lastExecPx),
            e.lastFillQty == 0 ? null : e.lastFillQty
        );
    }

    public static OrderSnapshot fromRecord(int orderRef, OrderRecord record) {
        return new OrderSnapshot(
            orderRef,
            record.getOrderId(),
            record.getAccountId(),
            record.getSecurity(),
            record.getSide(),
            record.getQuantity(),
            record.getRemainingQuantity(),
            record.getLimitPrice(),
            record.getStatus(),
            record.getCreatedAt(),
            record.getUpdatedAt(),
            record.getLastExecutionPrice(),
            record.getLastFillQuantity()
        );
    }

    public boolean isOpen() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    /** Render to the JPA entity shape (used for projection and OrderResponse building). */
    public OrderRecord toRecord() {
        OrderRecord record = new OrderRecord();
        record.setOrderId(orderId);
        record.setAccountId(accountId);
        record.setSecurity(security);
        record.setSide(side);
        record.setQuantity(quantity);
        record.setRemainingQuantity(remainingQuantity);
        record.setLimitPrice(limitPrice);
        record.setStatus(status);
        record.setCreatedAt(createdAt);
        record.setUpdatedAt(updatedAt);
        record.setLastExecutionPrice(lastExecutionPrice);
        record.setLastFillQuantity(lastFillQuantity);
        return record;
    }
}
