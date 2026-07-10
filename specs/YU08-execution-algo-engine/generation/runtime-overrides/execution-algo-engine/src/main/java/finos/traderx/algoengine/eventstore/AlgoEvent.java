package finos.traderx.algoengine.eventstore;

import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One entry in the {@code TRADERX_ALGO_ENGINE} JetStream event log (data-model.md, ADR-030). A
 * single flat, plain-Jackson-serializable shape covers every event type rather than a polymorphic
 * class hierarchy — {@code type} discriminates which fields are populated, and {@link
 * AlgoOrderState#apply(AlgoEvent)} is the only place that interprets them.
 */
public class AlgoEvent {
  public enum Type {
    ParentOrderCreated,
    ChildOrderSubmitted,
    ChildOrderFillObserved,
    ParentOrderCompleted
  }

  public static class BucketSeed {
    public int index;
    public long startEpochMs;
    public int targetQuantity;

    public BucketSeed() {}

    public BucketSeed(int index, long startEpochMs, int targetQuantity) {
      this.index = index;
      this.startEpochMs = startEpochMs;
      this.targetQuantity = targetQuantity;
    }
  }

  private Type type;
  private String parentOrderId;
  private Instant occurredAt;

  // ParentOrderCreated
  private Integer accountId;
  private String security;
  private OrderSide side;
  private Integer quantity;
  private AlgoType algoType;
  private Integer durationSeconds;
  private Integer bucketSeconds;
  private List<BucketSeed> buckets = new ArrayList<>();

  // ChildOrderSubmitted / ChildOrderFillObserved
  private Integer bucketIndex;
  private String childOrderId;
  private String clientOrderId;
  private BigDecimal limitPrice;
  private Integer remainingQuantity;
  private BigDecimal lastExecutionPrice;

  public static AlgoEvent parentOrderCreated(String parentOrderId, Integer accountId, String security,
      OrderSide side, int quantity, AlgoType algoType, int durationSeconds, int bucketSeconds,
      List<BucketSeed> buckets, Instant occurredAt) {
    AlgoEvent e = new AlgoEvent();
    e.type = Type.ParentOrderCreated;
    e.parentOrderId = parentOrderId;
    e.accountId = accountId;
    e.security = security;
    e.side = side;
    e.quantity = quantity;
    e.algoType = algoType;
    e.durationSeconds = durationSeconds;
    e.bucketSeconds = bucketSeconds;
    e.buckets = buckets;
    e.occurredAt = occurredAt;
    return e;
  }

  public static AlgoEvent childOrderSubmitted(String parentOrderId, int bucketIndex, String childOrderId,
      String clientOrderId, BigDecimal limitPrice, Instant occurredAt) {
    AlgoEvent e = new AlgoEvent();
    e.type = Type.ChildOrderSubmitted;
    e.parentOrderId = parentOrderId;
    e.bucketIndex = bucketIndex;
    e.childOrderId = childOrderId;
    e.clientOrderId = clientOrderId;
    e.limitPrice = limitPrice;
    e.occurredAt = occurredAt;
    return e;
  }

  public static AlgoEvent childOrderFillObserved(String parentOrderId, int bucketIndex,
      Integer remainingQuantity, BigDecimal lastExecutionPrice, Instant occurredAt) {
    AlgoEvent e = new AlgoEvent();
    e.type = Type.ChildOrderFillObserved;
    e.parentOrderId = parentOrderId;
    e.bucketIndex = bucketIndex;
    e.remainingQuantity = remainingQuantity;
    e.lastExecutionPrice = lastExecutionPrice;
    e.occurredAt = occurredAt;
    return e;
  }

  public static AlgoEvent parentOrderCompleted(String parentOrderId, Instant occurredAt) {
    AlgoEvent e = new AlgoEvent();
    e.type = Type.ParentOrderCompleted;
    e.parentOrderId = parentOrderId;
    e.occurredAt = occurredAt;
    return e;
  }

  public Type getType() {
    return type;
  }

  public void setType(Type type) {
    this.type = type;
  }

  public String getParentOrderId() {
    return parentOrderId;
  }

  public void setParentOrderId(String parentOrderId) {
    this.parentOrderId = parentOrderId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant occurredAt) {
    this.occurredAt = occurredAt;
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

  public List<BucketSeed> getBuckets() {
    return buckets;
  }

  public void setBuckets(List<BucketSeed> buckets) {
    this.buckets = buckets;
  }

  public Integer getBucketIndex() {
    return bucketIndex;
  }

  public void setBucketIndex(Integer bucketIndex) {
    this.bucketIndex = bucketIndex;
  }

  public String getChildOrderId() {
    return childOrderId;
  }

  public void setChildOrderId(String childOrderId) {
    this.childOrderId = childOrderId;
  }

  public String getClientOrderId() {
    return clientOrderId;
  }

  public void setClientOrderId(String clientOrderId) {
    this.clientOrderId = clientOrderId;
  }

  public BigDecimal getLimitPrice() {
    return limitPrice;
  }

  public void setLimitPrice(BigDecimal limitPrice) {
    this.limitPrice = limitPrice;
  }

  public Integer getRemainingQuantity() {
    return remainingQuantity;
  }

  public void setRemainingQuantity(Integer remainingQuantity) {
    this.remainingQuantity = remainingQuantity;
  }

  public BigDecimal getLastExecutionPrice() {
    return lastExecutionPrice;
  }

  public void setLastExecutionPrice(BigDecimal lastExecutionPrice) {
    this.lastExecutionPrice = lastExecutionPrice;
  }
}
