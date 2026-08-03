package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 009-compatible `/trades` payload emitted at the output edge from TradeBooked.
 * The class name is intentionally `TradeOrder`: NatsEnvelope.type is derived from
 * the payload simple class name, and the existing trade processor filters for that
 * legacy type before deserializing.
 */
public final class TradeOrder {
    private static final OrderSide[] SIDES = OrderSide.values();

    private String id;
    private String state;
    private String security;
    private int quantity;
    private BigDecimal price;
    private int accountId;
    private OrderSide side;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getSecurity() {
        return security;
    }

    public void setSecurity(String security) {
        this.security = security;
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

    public Integer getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public static TradeOrder fromEvent(OutputEvent e, SymbolTable symbols) {
        TradeOrder payload = new TradeOrder();
        payload.copyFromEvent(e, symbols);
        return payload;
    }

    void copyFromEvent(OutputEvent e, SymbolTable symbols, OutputValueCache values) {
        setId(values.orderIdFor(e.orderRef));
        setSecurity(symbols.tickerFor(e.securityId));
        setQuantity(e.tradeQty);
        price = values.priceFor(e.lastExecPx);
        setAccountId(e.accountId);
        setSide(SIDES[e.side]);
    }

    void copyFromEvent(OutputEvent e, SymbolTable symbols) {
        copyFromEvent(e, symbols, new OutputValueCache());
    }
}
