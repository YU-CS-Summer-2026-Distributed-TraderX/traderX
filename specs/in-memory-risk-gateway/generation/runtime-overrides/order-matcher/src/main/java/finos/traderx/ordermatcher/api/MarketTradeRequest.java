package finos.traderx.ordermatcher.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import finos.traderx.ordermatcher.model.OrderSide;

/**
 * Market trade submitted by trade-service (the validating gateway edge) for sequencing as a
 * TRADE_NEW event (FR-09B08). Field shape matches trade-service's {@code TradeOrder} so the
 * forward is a straight pass-through.
 */
public class MarketTradeRequest {
    private String clientOrderId;
    @JsonAlias("accountID")
    private Integer accountId;
    private String security;
    private OrderSide side;
    private Integer quantity;

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public void setAccountId(Integer accountId) {
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

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
