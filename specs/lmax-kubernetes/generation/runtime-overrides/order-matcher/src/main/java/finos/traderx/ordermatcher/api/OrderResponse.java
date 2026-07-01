package finos.traderx.ordermatcher.api;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class OrderResponse {
    private String orderId;
    private Integer accountId;
    private String security;
    private OrderSide side;
    private Integer quantity;
    private Integer remainingQuantity;
    private BigDecimal limitPrice;
    private OrderStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private BigDecimal lastExecutionPrice;
    private Integer lastFillQuantity;
    private BigDecimal marketPrice;

    public static OrderResponse from(OrderRecord order, BigDecimal marketPrice) {
        OrderResponse response = new OrderResponse();
        response.orderId = order.getOrderId();
        response.accountId = order.getAccountId();
        response.security = order.getSecurity();
        response.side = order.getSide();
        response.quantity = order.getQuantity();
        response.remainingQuantity = order.getRemainingQuantity();
        response.limitPrice = order.getLimitPrice();
        response.status = order.getStatus();
        response.createdAt = order.getCreatedAt();
        response.updatedAt = order.getUpdatedAt();
        response.lastExecutionPrice = order.getLastExecutionPrice();
        response.lastFillQuantity = order.getLastFillQuantity();
        response.marketPrice = marketPrice;
        return response;
    }

    /**
     * Build directly from already-rendered edge fields, so the output-ring NATS bridge can avoid the
     * intermediate OrderSnapshot + OrderRecord allocation per event (state 009b Tier 2-C). Takes only
     * model/java types — the lmax hot-path types do the rendering at the call site — so this stays a
     * leaf with no dependency back on the lmax package. Field-for-field identical to building via
     * {@link #from(OrderRecord, BigDecimal)}, so the wire format is unchanged.
     */
    public static OrderResponse from(String orderId, Integer accountId, String security, OrderSide side,
                                     Integer quantity, Integer remainingQuantity, BigDecimal limitPrice,
                                     OrderStatus status, Instant createdAt, Instant updatedAt,
                                     BigDecimal lastExecutionPrice, Integer lastFillQuantity,
                                     BigDecimal marketPrice) {
        OrderResponse response = new OrderResponse();
        response.orderId = orderId;
        response.accountId = accountId;
        response.security = security;
        response.side = side;
        response.quantity = quantity;
        response.remainingQuantity = remainingQuantity;
        response.limitPrice = limitPrice;
        response.status = status;
        response.createdAt = createdAt;
        response.updatedAt = updatedAt;
        response.lastExecutionPrice = lastExecutionPrice;
        response.lastFillQuantity = lastFillQuantity;
        response.marketPrice = marketPrice;
        return response;
    }

    public String getOrderId() {
        return orderId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public String getSecurity() {
        return security;
    }

    public OrderSide getSide() {
        return side;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getRemainingQuantity() {
        return remainingQuantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getLastExecutionPrice() {
        return lastExecutionPrice;
    }

    public Integer getLastFillQuantity() {
        return lastFillQuantity;
    }

    public BigDecimal getMarketPrice() {
        return marketPrice;
    }
}
