# ADR-056: Marks come from the published close where one exists, and the cut's last trade otherwise

Status: Accepted

## Context

The consumer reconciles its base NPV against our P&L, so the extract has to carry *our official*
closing mark — not a mark computed some other way that happens to be close.

Our official close is the YU06 chain's: `trade-processor` classifies the session's last-trade
prints and publishes an immutable `eod_price_snapshot` version, `position-service` marks positions
against exactly that version and writes `eod_position_pnl`, then emits `eod.pnl.done`. Reading that
snapshot is safe in a way reading `positions` is not: it is addressed by `(session_date, version)`
and never updated — a correction is a new version — so the read is a lookup in a frozen table
rather than a race, and it is reproducible forever.

That chain covers equities and not options. `PriceHistoryStore` is fed only by the synthetic
`pricing.<ticker>` feed the price-publisher broadcasts, which carries no option contracts, so an
option is `MISSING` in every snapshot. Two further consequences follow: `eod_price_snapshot.security`
is `VARCHAR(16)` and an unpadded OCC symbol is 19 characters, and YU06's fail-safe halts an
**entire account** if any holding is unpriced — so under the existing chain an account holding one
option produces no `eod_position_pnl` rows at all.

Meanwhile the cluster already knows an option's last trade price. YU13's engine keeps
`lastPxBySecurity` as replicated state, and the cut reads it at the same sequence N as the
positions themselves.

## Decision

A row is marked from the **published close** when `eod_price_snapshot` has a usable price for its
security at the stamped `(sessionDate, version)`. Otherwise it is marked from the **cut's own last
trade price at N**. Every row records which, in a `markSource` column
(`EOD_SNAPSHOT` / `CLUSTER_LAST_TRADE_AT_N`) alongside a `markQuality` column carrying either
YU06's quality classification or `LAST_TRADE`.

A row with neither a published close nor a trade at N is not defensibly markable, and the producer
**refuses to emit the whole extract** rather than ship a zero or a gap.

Two conventions travel in the fixture's own header, so a tie-out discrepancy has a starting point
without anyone reading this file:

- `marketValue = quantity × closingMark × contractMultiplier`
- `unrealizedPnl = (closingMark − costBasis) × quantity × contractMultiplier`

`costBasis` is the engine's weighted average trade price per contract or share, excluding fees and
excluding the multiplier. Arithmetic is `BigDecimal` over integer ticks throughout — exact, and it
cannot overflow the way `quantity × priceTicks × multiplier` would in a `long`.

## Consequences

For an equity in the normal EOD flow, the mark is YU06's published close and `marketValue` is
`quantity × closing_price × 1` — the same number `eod_position_pnl.market_value` holds, so the
tie-out the consumer asked for is exact by construction.

For a listed option, the mark is the last price the contract traded at on our own book at the cut
sequence. It is cut-consistent by construction (same N as the position), it is the number the risk
gate itself used, and it is stamped so nothing is hidden. It is not a settlement price from a
listed-options feed, which is what a production system would eventually reconcile against.

The extract does not read `eod_position_pnl`. It recomputes market value from the same published
price version using the same formula, which reproduces that table's value exactly for equities
while remaining correct for a portfolio the async read model cannot represent — and it removes a
dependency on a table that is empty for any account holding an option.

Where the two disagree, the extract is the one computed on a consistent cut and `eod_position_pnl`
is the async approximation.
