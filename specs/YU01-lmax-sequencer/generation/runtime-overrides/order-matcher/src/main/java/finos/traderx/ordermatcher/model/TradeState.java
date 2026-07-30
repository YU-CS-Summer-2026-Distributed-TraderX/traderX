package finos.traderx.ordermatcher.model;

/**
 * Trade lifecycle state. Copied from trade-processor's model for read-model contract parity
 * (the projector writes booked trades as {@code Settled}, matching 009's terminal state).
 */
public enum TradeState {
  New,
  Processing,
  Settled,
  Cancelled
}
