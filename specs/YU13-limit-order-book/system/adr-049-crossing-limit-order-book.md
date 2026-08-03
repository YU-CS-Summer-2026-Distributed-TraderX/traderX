# ADR-049: Genuine crossing limit-order book with price-time priority

## Status

Accepted

## Context

The inherited matching policy filled an order unilaterally whenever the security's last market
price satisfied its limit, half-filling above a size threshold. No opposing order was consumed:
executed quantity had no counterparty, one-sided flow grew the book without bound, and depth,
best bid/ask, and spread were not meaningful quantities. The platform's consumers — pre-trade
risk reserving exposure, the trade bridge persisting executions, position keeping, and the
throughput bench — all treat a fill as a real transfer, which the policy could not provide.

## Decision

Match by genuine crossing. Each security carries a two-sided book; an accepted limit order rests
at its price level's FIFO tail unless marketable. A marketable order executes against resting
opposite-side orders best price first, FIFO within a level, filling
min(aggressor remaining, resting remaining) at the RESTING order's limit price per step. Both
sides of every match receive an order update, a booked trade with its own trade sequence, and a
position update through the existing output pipeline; the resting side's update carries a
dedicated flag. Market orders execute immediately and cancel any unfilled remainder — they never
rest. Price ticks no longer trigger fills.

Time priority derives from the consensus log: order references are assigned in committed-log
order, so arrival order, reference order, and FIFO queue order are the same order on every
member and on every replay. Matching adds no clocks, no comparators, and no new inputs.

## Consequences

- An execution is always two orders agreeing on a price; executed quantity is conserved.
- Book depth per side is a real, servable quantity (aggregate open quantity per level).
- One match step books two trades — throughput and trade-count metrics count each side, and
  bench flow must be genuinely two-sided to produce fills at all.
- The threshold half-fill policy and the tick-driven fill scan are removed; every inherited test
  that drove fills with ticks restates its proof through crossing flow.
- ~~Self-crossing (an account matching its own resting order) is permitted; the book has no
  self-trade prevention.~~ **Superseded by [ADR-057](adr-057-self-trade-prevention.md)**, which is
  implemented: an aggressor meeting a resting order of its own account cancels that resting order
  (cancel-oldest) and continues. Self-crossing is no longer possible. Edited here rather than left
  to be contradicted elsewhere, because this line is the one a reader checks.
