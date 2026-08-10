package finos.traderx.tradeprocessor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

@Entity
@Table(name = "TRADES")
public class Trade implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  @Id
  // YU16: narrowed to the schema's VARCHAR(50) (source pack FR-01713's width check).
  @Column(length = 50, name = "ID")
  private String id;

  @Column(name = "ACCOUNTID")
  private Integer accountId;

  @Column(length = 50, name = "SECURITY")
  private String security;

  @Enumerated(EnumType.STRING)
  @Column(length = 4, name = "SIDE")
  private TradeSide side;

  @Enumerated(EnumType.STRING)
  @Column(length = 20, name = "STATE")
  private TradeState state = TradeState.New;

  @Column(name = "QUANTITY")
  private Integer quantity;

  // YU16 (ADR-057/FR-CDM15): a bond price is a fraction of par; 3dp on a fraction is 1dp
  // of percentage, so every bond-price carrier holds six decimals.
  @Column(name = "PRICE", precision = 18, scale = 6)
  private BigDecimal price;

  @Column(name = "UPDATED")
  private Date updated;

  @Column(name = "CREATED")
  private Date created;

  // YU05 (post-trade-compliance, FR-PTC02/06): set at booking (created + settlement.t-plus-days
  // business days), advanced to actual by SettlementService's sweep once state == Settled.
  @Column(name = "SETTLEMENTDATE")
  private Date settlementDate;

  // YU16 (FR-CDM23): populated only on state == Rejected; a rejected trade never touches a position.
  @Column(length = 255, name = "REJECTIONREASON")
  private String rejectionReason;

  @Column(length = 50, name = "SOURCEORDERID")
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

  public TradeSide getSide() {
    return side;
  }

  public void setSide(TradeSide side) {
    this.side = side;
  }

  public TradeState getState() {
    return state;
  }

  public void setState(TradeState state) {
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
    this.price = price == null ? null : price.setScale(6, RoundingMode.HALF_UP);
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

  public Date getSettlementDate() {
    return settlementDate;
  }

  public void setSettlementDate(Date settlementDate) {
    this.settlementDate = settlementDate;
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
