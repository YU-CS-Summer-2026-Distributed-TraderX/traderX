package finos.traderx.positionservice.model;

import java.math.BigDecimal;
import java.util.Date;

public class Trade {
  private String id;
  private Integer accountId;
  private String security;
  private String side;
  private String state;
  private Integer quantity;
  private BigDecimal price;
  private Date updated;
  private Date created;
  // YU16 (FR-CDM23): populated only for Rejected trades; surfaced by the trade blotter.
  private String rejectionReason;
  private String sourceOrderId;

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

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public Date getUpdated() {
    return updated;
  }

  public void setUpdated(Date updated) {
    this.updated = updated;
  }

  public Date getCreated() {
    return created;
  }

  public void setCreated(Date created) {
    this.created = created;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public void setRejectionReason(String rejectionReason) {
    this.rejectionReason = rejectionReason;
  }

  public String getSourceOrderId() {
    return sourceOrderId;
  }

  public void setSourceOrderId(String sourceOrderId) {
    this.sourceOrderId = sourceOrderId;
  }
}
