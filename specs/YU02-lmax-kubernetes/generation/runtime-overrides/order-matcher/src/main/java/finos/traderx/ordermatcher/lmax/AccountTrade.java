package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 009-compatible `/accounts/{accountId}/trades` payload emitted directly from TradeBooked.
 */
public final class AccountTrade {
    private String id;
    private Integer accountId;
    private String security;
    private OrderSide side;
    private String state;
    private Integer quantity;
    private BigDecimal price;
    private Date created;
    private Date updated;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price == null ? null : price.setScale(3, RoundingMode.HALF_UP);
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }

    public static AccountTrade fromEvent(OutputEvent e, SymbolTable symbols) {
        AccountTrade payload = new AccountTrade();
        payload.setId(e.tradeSeq > 0 ? OrderSnapshot.tradeIdFor(e.tradeSeq) : OrderSnapshot.orderIdFor(e.orderRef));
        payload.setAccountId(e.accountId);
        payload.setSecurity(symbols.tickerFor(e.securityId));
        payload.setSide(OrderSide.values()[e.side]);
        payload.setState("Settled");
        payload.setQuantity(e.tradeQty);
        payload.setPrice(Px.toBigDecimal(e.tradePx != Px.NONE ? e.tradePx : e.lastExecPx));
        payload.setCreated(new Date(e.updatedAtMillis));
        payload.setUpdated(new Date(e.updatedAtMillis));
        return payload;
    }
}
