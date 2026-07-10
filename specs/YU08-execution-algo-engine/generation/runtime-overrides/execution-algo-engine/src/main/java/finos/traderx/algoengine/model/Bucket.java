package finos.traderx.algoengine.model;

import java.math.BigDecimal;
import java.time.Instant;

/** One time-sliced child order within a {@link ParentOrder}'s schedule (data-model.md). */
public class Bucket {
  private int index;
  private long startEpochMs;
  private int targetQuantity;
  private String childOrderId;
  private String clientOrderId;
  private BigDecimal limitPrice;
  private Instant submittedAt;
  private Integer remainingQuantity;
  private BigDecimal lastExecutionPrice;
  private boolean filled;

  public Bucket() {}

  public Bucket(int index, long startEpochMs, int targetQuantity) {
    this.index = index;
    this.startEpochMs = startEpochMs;
    this.targetQuantity = targetQuantity;
  }

  public boolean isSubmitted() {
    return childOrderId != null;
  }

  public int getIndex() {
    return index;
  }

  public void setIndex(int index) {
    this.index = index;
  }

  public long getStartEpochMs() {
    return startEpochMs;
  }

  public void setStartEpochMs(long startEpochMs) {
    this.startEpochMs = startEpochMs;
  }

  public int getTargetQuantity() {
    return targetQuantity;
  }

  public void setTargetQuantity(int targetQuantity) {
    this.targetQuantity = targetQuantity;
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

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(Instant submittedAt) {
    this.submittedAt = submittedAt;
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

  public boolean isFilled() {
    return filled;
  }

  public void setFilled(boolean filled) {
    this.filled = filled;
  }
}
