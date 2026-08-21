package finos.traderx.tradeprocessor.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "The state of the trade, ie, New, Processing, Settled, Cancelled, Rejected")
public enum TradeState {
  New,
  Processing,
  Settled,
  Cancelled,
  // YU16 (FR-CDM23): the fail-closed landing for booking-time validation failures. A rejected
  // trade is persisted and published; it never mutates a position.
  Rejected
}
