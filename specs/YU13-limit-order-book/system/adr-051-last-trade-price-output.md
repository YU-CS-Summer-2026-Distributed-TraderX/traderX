# ADR-051: The last price is an output of matching

## Status

Accepted

## Context

Under the inherited policy the security's last price (`lastPxBySecurity`) was the INPUT that
triggered fills: market-data ticks moved it, and the matcher filled against it. With a crossing
book the execution price is discovered by matching itself, which inverts the relationship.
Several consumers still need a mark before the book's first trade: trade-ticket bookings
(`TYPE_TRADE_NEW`) stamp the current mark, and market-order risk validation needs a reference
price.

## Decision

`lastPxBySecurity` holds the last TRADE price. Every execution — a crossing match step or a
force-fill — writes it; the order-update `marketPx` field and trade-ticket bookings read it.
Market-data price ticks seed it only while it is `Px.NONE` (no trade has printed yet) and
otherwise leave it untouched. Ticks continue to feed risk price-freshness state unchanged, so
staleness checks and collar inputs still track the market-data feed.

## Consequences

- The mark reflects what the venue actually traded at, not an external reference; the emitted
  `marketPx` on a fill equals its execution price.
- An unpriced security still bootstraps: the first tick seeds the mark, enabling trade-ticket
  bookings and market-order validation before the first cross.
- After the first print, the mark and the market-data feed can diverge; risk freshness follows
  the feed while the mark follows executions — deliberate, and visible in the snapshot's price
  records which carry the mark.
