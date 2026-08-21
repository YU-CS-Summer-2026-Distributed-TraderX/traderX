package finos.traderx.tradeprocessor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * YU05 (post-trade-compliance, ADR-024, FR-PTC30-32): mirrors price-publisher's {@code
 * pricing.<ticker>} NATS JSON payload shape exactly (see price-publisher's {@code toPayload()}).
 * Consumed by {@link finos.traderx.tradeprocessor.service.PriceTickHandler} to build the price
 * history TCA's benchmark computation reads — the synthetic feed today, real historical tick data
 * later, without changing {@link finos.traderx.tradeprocessor.service.TcaService}'s contract.
 */
/*
 * YU16: the shared NatsJSONSubscriber ObjectMapper is left at Jackson's default
 * FAIL_ON_UNKNOWN_PROPERTIES=true, so a payload that GAINS a field is not additive for this
 * consumer — it is fatal. YU16's Treasury ticks carry cleanPrice/priceSemantics/YTM/maturity
 * alongside the inherited fields, and every one of them was rejected and DROPPED: 10,812
 * deserialization failures, no UST rows in the EOD closing snapshot, and YU06's fail-safe then
 * halted every account holding a bond. Nothing logged the ticker; the only visible symptom was
 * three halted accounts in a proof two states away.
 *
 * Tolerating unknown properties here is what makes "the pricing payload is extended additively"
 * (contract-delta §3) true for a typed consumer rather than only true on the wire. The other two
 * pricing.* consumers (the feed adapter and PricingNatsSubscriberService) already read the tree
 * rather than binding a type, so they were never affected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
