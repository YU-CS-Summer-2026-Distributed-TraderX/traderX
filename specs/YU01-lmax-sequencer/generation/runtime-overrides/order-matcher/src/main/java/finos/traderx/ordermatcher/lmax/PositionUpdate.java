package finos.traderx.ordermatcher.lmax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 009-compatible `/accounts/{accountId}/positions` payload emitted at the output edge
 * from PositionUpdated. Field names match the existing position-service/web UI contract.
 */
public final class PositionUpdate {
    private Integer accountId;
    private String security;
    private Integer quantity;
    private BigDecimal averageCostBasis;
    private Date updated;

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

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
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
        payload.setAccountId(e.accountId);
        payload.setSecurity(symbols.tickerFor(e.securityId));
        payload.setQuantity(e.positionQty);
        payload.setAverageCostBasis(e.averageCostBasisPx == Px.NONE ? null : Px.toBigDecimal(e.averageCostBasisPx));
        payload.setUpdated(new Date(e.updatedAtMillis));
        return payload;
    }
}
