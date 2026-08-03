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

That chain did not originally cover options. `PriceHistoryStore` is fed only by the
`pricing.<ticker>` feed the price-publisher broadcasts, which carried no option contracts, so an
option was `MISSING` in every snapshot — and YU06's fail-safe halts an **entire account** if any
holding is unpriced, so an account holding one option produced no `eod_position_pnl` rows at all.
A second, independent blocker sat underneath it: `eod_price_snapshot.security` was `VARCHAR(16)`
and an unpadded OCC symbol is 19 characters.

Both are now fixed. The columns are widened, and price-publisher quotes the listed chain off its
underlyings (Black-Scholes at a flat implied vol, derived on every tick from the underlying's
current price rather than walked independently, so a call and a put on the same strike cannot
contradict each other). An option therefore has a published close exactly like an equity, and the
whole chain runs for it end to end.

The cluster also knows an option's last trade price — YU13's engine keeps `lastPxBySecurity` as
replicated state, read at the same sequence N as the positions — which is what the extract used
before the feed covered options, and what it still falls back to.

## Decision

A row is marked from the **published close** when `eod_price_snapshot` has a usable price for its
security at the stamped `(sessionDate, version)`. Otherwise it is marked from the **cut's own last
trade price at N**. With the feed quoting the listed chain, the published close is now the normal
path for options as well as equities, and the last-trade path is a genuine fallback — for an
instrument the feed does not carry — rather than the standing arrangement for a whole asset class.

Every row records which, in a `markSource` column
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

For a listed option, the mark is the published close from the same snapshot version, so the same
tie-out holds — and because both `eod_position_pnl` and the extract apply the contract multiplier,
the two agree exactly rather than by a factor of 100. What our feed publishes is a modelled quote
at a flat implied vol, not a settlement price from a listed-options venue, which is what a
production system would eventually reconcile against; the vol and rate are reported on
price-publisher's `/health` so a consumer can reproduce our marks precisely.

Where the feed does not carry an instrument, the row still falls back to the engine's last trade at
N and says so in `markSource`. That path is cut-consistent by construction and is the number the
risk gate itself used, so a portfolio is never blocked on a missing quote.

The extract does not read `eod_position_pnl`. It recomputes market value from the same published
price version using the same formula, so the two agree for every instrument type — but the extract
is computed on the consistent cut and stays correct when the async read model has not caught up.

Where the two disagree it is a lag artefact, not a methodology difference: the extract is computed
on the consistent cut and `eod_position_pnl` is the async approximation.

One new obligation falls out of quoting options: a 20% day-over-day move is a data-quality alarm
for an equity and unremarkable for a leveraged contract, so the spike gate is instrument-aware
(`eod.quality.max-move-pct` for equities, `eod.quality.max-move-pct-option` for OCC symbols).
Holding options to the equity threshold would have flagged them, and a single flagged instrument
blocks publication of the entire session — so it would have taken the whole EOD chain down,
equities included, rather than merely mis-flagging options.
