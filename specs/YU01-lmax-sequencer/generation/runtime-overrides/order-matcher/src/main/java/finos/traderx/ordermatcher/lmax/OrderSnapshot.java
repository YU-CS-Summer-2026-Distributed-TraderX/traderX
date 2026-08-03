package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-order read-model state used by the output-ring marshaller. It keeps matcher-path
 * fields in primitive form and renders BigDecimal/Instant values only when crossing back
 * to REST/JPA edge types (FR-09B25).
 */
public final class OrderSnapshot {
    private static final OrderStatus[] STATUSES = OrderStatus.values();
    private static final OrderSide[] SIDES = OrderSide.values();

    public final int orderRef;
    public volatile String orderId;
    public volatile int accountId;
    public volatile String security;
    public volatile OrderSide side;
    public volatile int quantity;
    public volatile int remainingQuantity;
    public volatile long limitPx;
    public volatile OrderStatus status;
    public volatile long createdAtMillis;
    public volatile long updatedAtMillis;
    public volatile long lastExecPx;
    public volatile int lastFillQuantity;

    private OrderSnapshot(int orderRef) {
        this.orderRef = orderRef;
    }

    private OrderSnapshot(int orderRef, String orderId, int accountId, String security, OrderSide side,
                          int quantity, int remainingQuantity, long limitPx, OrderStatus status,
                          long createdAtMillis, long updatedAtMillis, long lastExecPx, int lastFillQuantity) {
        this(orderRef);
        this.orderId = orderId;
        this.accountId = accountId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.remainingQuantity = remainingQuantity;
        this.limitPx = limitPx;
        this.status = status;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
        this.lastExecPx = lastExecPx;
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
        return new OrderSnapshot(e.orderRef).updateFromEvent(e, symbols);
    }

    public OrderSnapshot updateFromEvent(OutputEvent e, SymbolTable symbols) {
        if (orderId == null) {
            orderId = orderIdFor(e.orderRef);
        }
        accountId = e.accountId;
        security = symbols.tickerFor(e.securityId);
        side = SIDES[e.side];
        quantity = e.quantity;
        remainingQuantity = e.remainingQty;
        limitPx = e.limitPx;
        status = STATUSES[e.status];
        createdAtMillis = e.createdAtMillis;
        updatedAtMillis = e.updatedAtMillis;
        lastExecPx = e.lastExecPx;
        lastFillQuantity = e.lastFillQty;
        return this;
    }

    public static OrderSnapshot fromRecord(int orderRef, OrderRecord record) {
        return new OrderSnapshot(
            orderRef,
            record.getOrderId(),
            record.getAccountId() == null ? 0 : record.getAccountId(),
            record.getSecurity(),
            record.getSide(),
            record.getQuantity() == null ? 0 : record.getQuantity(),
            record.getRemainingQuantity() == null ? 0 : record.getRemainingQuantity(),
            Px.toTicks(record.getLimitPrice()),
            record.getStatus(),
            record.getCreatedAt() == null ? 0 : record.getCreatedAt().toEpochMilli(),
            record.getUpdatedAt() == null ? 0 : record.getUpdatedAt().toEpochMilli(),
            Px.toTicks(record.getLastExecutionPrice()),
            record.getLastFillQuantity() == null ? 0 : record.getLastFillQuantity()
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
        record.setLimitPrice(Px.toBigDecimal(limitPx));
        record.setStatus(status);
        record.setCreatedAt(Instant.ofEpochMilli(createdAtMillis));
        record.setUpdatedAt(Instant.ofEpochMilli(updatedAtMillis));
        record.setLastExecutionPrice(lastExecPx == Px.NONE ? null : Px.toBigDecimal(lastExecPx));
        record.setLastFillQuantity(lastFillQuantity == 0 ? null : lastFillQuantity);
        return record;
    }
}
