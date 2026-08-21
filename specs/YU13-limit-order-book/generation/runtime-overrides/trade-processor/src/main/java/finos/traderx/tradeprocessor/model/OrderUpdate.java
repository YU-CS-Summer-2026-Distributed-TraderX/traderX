package finos.traderx.tradeprocessor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Order-lifecycle update off the cluster's leader-side {@code /orders} bridge (YU13), the order-state
 * sibling of {@link TradeOrder}. {@code id} is epoch-qualified ({@code epoch-orderRef}) so it never
 * collides across cluster incarnations. Deserialized from the NATS envelope payload by
 * {@code OrderFeedHandler}; unknown fields are ignored so the wire format can grow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderUpdate {

  private String id;
  private Integer accountId;
  private String security;
  private String side;
  private Integer quantity;
  private Integer remainingQuantity;
  private BigDecimal limitPrice;
  private String status;
  private BigDecimal lastExecutionPrice;
  private Integer lastFillQuantity;
  private Long createdAt;
  private Long updatedAt;
  /**
   * The order's 32-hex W3C trace id, present ONLY on the order's own NEW update (brief 07). A
   * trace id is derived from the client order id, which stops at the gateway, so this is the only
   * carriage of it into the read model — and it is what lets a client name the trace of an order
   * it did not submit. Null on every later update, and null for an order that has no derivable
   * id; {@code OrderFeedHandler} therefore PRESERVES the persisted value rather than overwriting.
   */
  private String traceId;

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

  public Long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Long createdAt) {
    this.createdAt = createdAt;
  }

  public Long getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Long updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  @Override
  public String toString() {
    return "OrderUpdate{id=" + id + ", account=" + accountId + ", security=" + security
        + ", status=" + status + ", remaining=" + remainingQuantity + "}";
  }
}
