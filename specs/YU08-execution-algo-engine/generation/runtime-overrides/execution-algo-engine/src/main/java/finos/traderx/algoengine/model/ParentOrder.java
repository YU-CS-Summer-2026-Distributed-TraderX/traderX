package finos.traderx.algoengine.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A parent order and its bucket schedule (data-model.md). Mutated only by {@code AlgoOrderState}
 * applying events from the JetStream event log — never directly by the API/scheduler layers. */
public class ParentOrder {
  private String parentOrderId;
  private Integer accountId;
  private String security;
  private OrderSide side;
  private int quantity;
  private AlgoType algoType;
  private int durationSeconds;
  private int bucketSeconds;
  private ParentOrderStatus status = ParentOrderStatus.RUNNING;
  private Instant createdAt;
  private List<Bucket> buckets = new ArrayList<>();

  public String getParentOrderId() {
    return parentOrderId;
  }

  public void setParentOrderId(String parentOrderId) {
    this.parentOrderId = parentOrderId;
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

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  public AlgoType getAlgoType() {
    return algoType;
  }

  public void setAlgoType(AlgoType algoType) {
    this.algoType = algoType;
  }

  public int getDurationSeconds() {
    return durationSeconds;
  }

  public void setDurationSeconds(int durationSeconds) {
    this.durationSeconds = durationSeconds;
  }

  public int getBucketSeconds() {
    return bucketSeconds;
  }

  public void setBucketSeconds(int bucketSeconds) {
    this.bucketSeconds = bucketSeconds;
  }

  public ParentOrderStatus getStatus() {
    return status;
  }

  public void setStatus(ParentOrderStatus status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public List<Bucket> getBuckets() {
    return buckets;
  }

  public void setBuckets(List<Bucket> buckets) {
    this.buckets = buckets;
  }

  public boolean allBucketsFilled() {
    return buckets.stream().allMatch(Bucket::isFilled);
  }
}
