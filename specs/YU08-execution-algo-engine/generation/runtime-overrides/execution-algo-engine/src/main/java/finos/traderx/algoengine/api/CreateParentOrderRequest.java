package finos.traderx.algoengine.api;

import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.OrderSide;

/** FR-AE01: the {@code POST /algo/orders} request body (contracts/contract-delta.md). */
public class CreateParentOrderRequest {
  private Integer accountId;
  private String security;
  private OrderSide side;
  private Integer quantity;
  private AlgoType algoType;
  private Integer durationSeconds;
  private Integer bucketSeconds;

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

  public AlgoType getAlgoType() {
    return algoType;
  }

  public void setAlgoType(AlgoType algoType) {
    this.algoType = algoType;
  }

  public Integer getDurationSeconds() {
    return durationSeconds;
  }

  public void setDurationSeconds(Integer durationSeconds) {
    this.durationSeconds = durationSeconds;
  }

  public Integer getBucketSeconds() {
    return bucketSeconds;
  }

  public void setBucketSeconds(Integer bucketSeconds) {
    this.bucketSeconds = bucketSeconds;
  }
}
