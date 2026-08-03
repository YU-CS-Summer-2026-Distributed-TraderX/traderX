package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 009-compatible `/accounts/{accountId}/trades` payload emitted directly from TradeBooked.
 */
public final class AccountTrade {
    private static final OrderSide[] SIDES = OrderSide.values();

    private String id;
    private int accountId;
    private String security;
    private OrderSide side;
    private String state;
    private int quantity;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
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
        payload.copyFromEvent(e, symbols);
        return payload;
    }

    void copyFromEvent(OutputEvent e, SymbolTable symbols, OutputValueCache values) {
        setId(e.tradeSeq > 0 ? values.tradeIdFor(e.tradeSeq) : values.orderIdFor(e.orderRef));
        setAccountId(e.accountId);
        setSecurity(symbols.tickerFor(e.securityId));
        setSide(SIDES[e.side]);
        setState("Settled");
        setQuantity(e.tradeQty);
        price = values.priceFor(e.tradePx != Px.NONE ? e.tradePx : e.lastExecPx);
        if (created == null) {
            created = new Date(e.updatedAtMillis);
        } else {
            created.setTime(e.updatedAtMillis);
        }
        if (updated == null) {
            updated = new Date(e.updatedAtMillis);
        } else {
            updated.setTime(e.updatedAtMillis);
        }
    }

    void copyFromEvent(OutputEvent e, SymbolTable symbols) {
        copyFromEvent(e, symbols, new OutputValueCache());
    }
}
