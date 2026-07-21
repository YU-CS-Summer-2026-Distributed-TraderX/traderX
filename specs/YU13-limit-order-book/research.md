# Research: YU13-limit-order-book

## Why a crossing book replaces the price-triggered policy

The inherited matcher filled an order unilaterally whenever the security's last market price
satisfied its limit (`isInTheMoney`), with a threshold policy half-filling large orders. That
policy has no counterparty: quantity appears from nowhere, the book only ever grows on one side,
and depth means nothing. Every downstream consumer this system has grown — the risk engine
marking positions, the trade bridge feeding the DB and UI, the throughput bench counting booked
trades — becomes more meaningful when a fill is two orders agreeing on a price. Crossing is also
the only policy under which "book depth" and "best bid/ask" are real quantities the edge can
serve.

## Why array-indexed levels with intrusive lists

The book's hot operations are: find the best opposite level, take the FIFO head, reduce or
unlink it, append a new resting order, and unlink a canceled order. With price levels as array
slots on a fixed grid and orders as intrusive doubly-linked nodes (links live inside the pooled
`RestingOrder`), every one of these is O(1) with zero allocation — the same discipline the
engine's dense `ordersByRef` index and pooled entries already follow. Tree- or map-based books
put a comparison structure and its allocation churn on the hottest path; the array trades a
bounded, lazily-allocated band of memory for constant-time access and cache-friendly scans.
Best-price maintenance uses per-side occupancy bitmaps: clearing the last order at the best
level scans word-wise toward the next occupied level.

## Why a 0.001 grid and a banded window

Prices travel the system as long ticks at the x1e6 `Px` scale, rounded to 3dp HALF_UP at every
edge. The book grid is therefore 0.001 — every price an edge can produce is exactly on-grid, and
grid rejection (`INVALID`) only ever fires for raw sub-0.001 ingress that no edge emits. With
level price == resting limit price exactly, execution at the level price can never violate
either side's limit.

A full-range array at 0.001 granularity would be gigabytes; the band makes it megabytes. Each
security's window (default `1<<17` ticks = $131.07) anchors mid-band on its first limit price —
the warm-start seed data and the bench flow both trade each security in a narrow range around
one price, and a limit outside ±$65.5 of the anchor is exactly what an exchange price collar
rejects. The band width and grid are config-identity values (like the output ring size): read
once at engine construction, identical on every member, and carried in the snapshot header so a
restored member adopts the geometry its state was built with.

## Why time priority needs no timestamps

Order references are assigned by the cluster service in committed-log order, so ascending
reference IS arrival order IS time priority. This has two consequences the design leans on:
FIFO within a level is maintained by plain tail-append at arrival; and the snapshot needs no
book-structure records at all beyond the band anchors — open rows serialized in ascending
reference order re-append on restore into exactly the original per-level FIFO.

## Why the last price becomes an output

With a crossing book the execution price is discovered by matching, not read from a reference
feed. The mark (`lastPxBySecurity`) is therefore written by trades and only seeded by market-data
ticks while no trade has printed — the seed lets an unpriced security bootstrap (trade-ticket
bookings and market-order risk validation need a reference before the first cross). Risk
price-freshness state continues to consume every tick unchanged, so staleness checks and collar
inputs are unaffected.

## Why egress acks carry a correlation class

The gateway's offer/ack accounting relies on acks being FIFO per session. The inherited design
documented its own limit: async updates for resting orders would interleave and skew the count.
A crossing book makes counterparty updates the common case — every cross emits an update for a
resting order that belongs to no in-flight offer. One spare byte in the fixed 24-byte ack now
classes each order-lifecycle ack as direct or resting-update; correlation and batch accounting
consume only direct acks. Ack length and all existing fields are unchanged, so a client ignoring
the byte sees the previous wire shape.

## What the throughput number means now

A booked-fill event counts each SIDE of a cross: one match step books two trades (two trade
sequence numbers, one per account). The bench's booked/s numerator therefore counts filled-order
events exactly as before, but the flow generating them must be genuinely two-sided and
marketable — numbers measured against the auto-fill engine are not comparable and are not
carried forward (NFR-LOB03).
