package finos.traderx.tradeprocessor.model;

import java.math.BigDecimal;

/**
 * YU05 (post-trade-compliance, ADR-024, FR-PTC30-32): mirrors price-publisher's {@code
 * pricing.<ticker>} NATS JSON payload shape exactly (see price-publisher's {@code toPayload()}).
 * Consumed by {@link finos.traderx.tradeprocessor.service.PriceTickHandler} to build the price
 * history TCA's benchmark computation reads — the synthetic feed today, real historical tick data
 * later, without changing {@link finos.traderx.tradeprocessor.service.TcaService}'s contract.
 */
public class PriceTick {
  private String ticker;
  private BigDecimal price;
  private BigDecimal openPrice;
  private BigDecimal closePrice;
  private String asOf;
  private String source;

  public String getTicker() {
    return ticker;
  }

  public void setTicker(String ticker) {
    this.ticker = ticker;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public BigDecimal getOpenPrice() {
    return openPrice;
  }

  public void setOpenPrice(BigDecimal openPrice) {
    this.openPrice = openPrice;
  }

  public BigDecimal getClosePrice() {
    return closePrice;
  }

  public void setClosePrice(BigDecimal closePrice) {
    this.closePrice = closePrice;
  }

  public String getAsOf() {
    return asOf;
  }

  public void setAsOf(String asOf) {
    this.asOf = asOf;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
