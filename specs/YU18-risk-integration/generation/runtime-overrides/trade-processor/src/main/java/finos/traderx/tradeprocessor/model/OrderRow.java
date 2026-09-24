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

  /**
   * The order's derived W3C trace id (brief 07) — persisted so a trace can be named for ANY order,
   * not only one the asking client submitted. Written once off the NEW update and preserved by
   * {@link finos.traderx.tradeprocessor.OrderFeedHandler} across later updates, which carry none.
   * Null is the honest answer for an order with no derivable id; nothing fabricates one.
   */
  @Column(length = 32, name = "traceid")
  private String traceId;

  // YU18 order types (FR-OT33). Declared in the shipped DDL and added to existing databases by
  // the 900-migrations key, so ddl-auto=validate refuses a database that lacks them.
  @Column(length = 16, name = "ordertype")
  private String orderType;

  @Column(length = 8, name = "timeinforce")
  private String timeInForce;

  @Column(precision = 18, scale = 3, name = "stopprice")
  private BigDecimal stopPrice;

  @Column(name = "displayquantity")
  private Integer displayQuantity;

  @Column(length = 16, name = "pegreference")
  private String pegReference;

  @Column(name = "pegoffset")
  private Integer pegOffset;

  @Column(precision = 18, scale = 3, name = "pegcap")
  private BigDecimal pegCap;

  @Column(precision = 18, scale = 3, name = "trailamount")
  private BigDecimal trailAmount;

  @Column(name = "trailpercentbps")
  private Integer trailPercentBps;

  @Column(name = "triggered")
  private Boolean triggered;

  @Column(name = "sessiondate")
  private Integer sessionDate;

  @Column(length = 16, name = "suspendreason")
  private String suspendReason;

  @Column(length = 32, name = "reason")
  private String reason;

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

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }
  public String getOrderType() {
    return orderType;
  }

  public void setOrderType(String orderType) {
    this.orderType = orderType;
  }

  public String getTimeInForce() {
    return timeInForce;
  }

  public void setTimeInForce(String timeInForce) {
    this.timeInForce = timeInForce;
  }

  public BigDecimal getStopPrice() {
    return stopPrice;
  }

  public void setStopPrice(BigDecimal stopPrice) {
    this.stopPrice = stopPrice;
  }

  public Integer getDisplayQuantity() {
    return displayQuantity;
  }

  public void setDisplayQuantity(Integer displayQuantity) {
    this.displayQuantity = displayQuantity;
  }

  public String getPegReference() {
    return pegReference;
  }

  public void setPegReference(String pegReference) {
    this.pegReference = pegReference;
  }

  public Integer getPegOffset() {
    return pegOffset;
  }

  public void setPegOffset(Integer pegOffset) {
    this.pegOffset = pegOffset;
  }

  public BigDecimal getPegCap() {
    return pegCap;
  }

  public void setPegCap(BigDecimal pegCap) {
    this.pegCap = pegCap;
  }

  public BigDecimal getTrailAmount() {
    return trailAmount;
  }

  public void setTrailAmount(BigDecimal trailAmount) {
    this.trailAmount = trailAmount;
  }

  public Integer getTrailPercentBps() {
    return trailPercentBps;
  }

  public void setTrailPercentBps(Integer trailPercentBps) {
    this.trailPercentBps = trailPercentBps;
  }

  public Boolean getTriggered() {
    return triggered;
  }

  public void setTriggered(Boolean triggered) {
    this.triggered = triggered;
  }

  public Integer getSessionDate() {
    return sessionDate;
  }

  public void setSessionDate(Integer sessionDate) {
    this.sessionDate = sessionDate;
  }

  public String getSuspendReason() {
    return suspendReason;
  }

  public void setSuspendReason(String suspendReason) {
    this.suspendReason = suspendReason;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
