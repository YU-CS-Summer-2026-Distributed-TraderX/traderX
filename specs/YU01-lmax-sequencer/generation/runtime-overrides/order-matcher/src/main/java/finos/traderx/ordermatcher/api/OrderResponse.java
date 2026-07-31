package finos.traderx.ordermatcher.api;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class OrderResponse {
    private String orderId;
    private int accountId;
    private String security;
    private OrderSide side;
    private int quantity;
    private int remainingQuantity;
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

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }

    public String getSecurity() {
        return security;
    }

    public void setSecurity(String security) {
        this.security = security;
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Integer getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(int remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(BigDecimal limitPrice) {
        this.limitPrice = limitPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public BigDecimal getLastExecutionPrice() {
        return lastExecutionPrice;
    }

    public void setLastExecutionPrice(BigDecimal lastExecutionPrice) {
        this.lastExecutionPrice = lastExecutionPrice;
    }

    public Integer getLastFillQuantity() {
        return lastFillQuantity;
    }

    public void setLastFillQuantity(Integer lastFillQuantity) {
        this.lastFillQuantity = lastFillQuantity;
    }

    public BigDecimal getMarketPrice() {
        return marketPrice;
    }

    public void setMarketPrice(BigDecimal marketPrice) {
        this.marketPrice = marketPrice;
    }
}
