package finos.traderx.ordermatcher.lmax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 009-compatible `/accounts/{accountId}/positions` payload emitted at the output edge
 * from PositionUpdated. Field names match the existing position-service/web UI contract.
 */
public final class PositionUpdate {
    private int accountId;
    private String security;
    private int quantity;
    private BigDecimal averageCostBasis;
    private Date updated;

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

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAverageCostBasis() {
        return averageCostBasis;
    }

    public void setAverageCostBasis(BigDecimal averageCostBasis) {
        this.averageCostBasis = averageCostBasis == null ? null : averageCostBasis.setScale(3, RoundingMode.HALF_UP);
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }

    public static PositionUpdate fromEvent(OutputEvent e, SymbolTable symbols) {
        PositionUpdate payload = new PositionUpdate();
        payload.copyFromEvent(e, symbols);
        return payload;
    }

    void copyFromEvent(OutputEvent e, SymbolTable symbols, OutputValueCache values) {
        setAccountId(e.accountId);
        setSecurity(symbols.tickerFor(e.securityId));
        setQuantity(e.positionQty);
        averageCostBasis = values.priceFor(e.averageCostBasisPx);
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
