package finos.traderx.tradeprocessor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Read-model projection of a single order's current state (YU13), mapped to the {@code orderbook}
 * table that ships empty in the base schema. One row per epoch-qualified order id; the
 * {@code OrderFeedHandler} upserts it as lifecycle updates arrive off {@code /orders}, so the row
 * always holds the order's latest known state. This is the effect-end that order-level proofs
 * (cancel/replace/STP) and client-restart enumeration were missing.
 */
@Entity
@Table(name = "orderbook")
public class OrderRow implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  @Id
  @Column(length = 32, name = "orderid")
  private String id;

  @Column(name = "accountid")
  private Integer accountId;

  @Column(length = 16, name = "security")
  private String security;

  @Column(length = 16, name = "side")
  private String side;

  @Column(name = "quantity")
  private Integer quantity;

  @Column(name = "remainingquantity")
  private Integer remainingQuantity;

  @Column(name = "limitprice", precision = 18, scale = 3)
  private BigDecimal limitPrice;

  @Column(length = 24, name = "status")
  private String status;

  @Column(name = "createdat")
  private Date createdAt;

  @Column(name = "updatedat")
  private Date updatedAt;

  @Column(name = "lastexecutionprice", precision = 18, scale = 3)
  private BigDecimal lastExecutionPrice;

  @Column(name = "lastfillquantity")
  private Integer lastFillQuantity;

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

  public String getSide() {
    return side;
  }

  public void setSide(String side) {
    this.side = side;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public Integer getRemainingQuantity() {
    return remainingQuantity;
  }

  public void setRemainingQuantity(Integer remainingQuantity) {
    this.remainingQuantity = remainingQuantity;
  }

  public BigDecimal getLimitPrice() {
    return limitPrice;
  }

  public void setLimitPrice(BigDecimal limitPrice) {
    this.limitPrice = limitPrice;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date createdAt) {
    this.createdAt = createdAt;
  }

  public Date getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Date updatedAt) {
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
}
