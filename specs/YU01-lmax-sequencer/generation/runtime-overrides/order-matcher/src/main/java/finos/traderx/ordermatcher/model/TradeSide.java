package finos.traderx.ordermatcher.model;

/**
 * Trade direction (Buy/Sell). Copied from trade-processor's model so the booked-trade rows
 * and NATS payloads order-matcher now produces (FR-09B08/FR-09B22) match 009 byte-for-byte.
 */
public enum TradeSide {
  Buy,
  Sell
}
