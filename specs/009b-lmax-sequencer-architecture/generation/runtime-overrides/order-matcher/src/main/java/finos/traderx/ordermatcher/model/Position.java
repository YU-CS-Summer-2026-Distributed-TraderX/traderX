package finos.traderx.ordermatcher.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * Net-position read-model row (POSITIONS). State 009b: the BLP is the single position writer
 * (FR-09B10) and the order-matcher Projector persists the result (FR-09B22). Schema and shape
 * are identical to 009 — only the writer changed (the BLP keeps the running net quantity and
 * weighted {@code AVERAGECOSTBASIS} on the single thread, FR-09B40 contract parity).
 */
@Entity
@IdClass(PositionID.class)
@Table(name = "POSITIONS")
public class Position implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  @Id
  @Column(name = "ACCOUNTID")
  private Integer accountId;

  @Id
  @Column(length = 50, name = "SECURITY")
  private String security;

  @Column(name = "QUANTITY")
  private Integer quantity;

  @Column(name = "AVERAGECOSTBASIS", precision = 18, scale = 3)
  private BigDecimal averageCostBasis;

  @Column(name = "UPDATED")
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
}
