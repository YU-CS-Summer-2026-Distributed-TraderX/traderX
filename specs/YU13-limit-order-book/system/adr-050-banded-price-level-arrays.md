# ADR-050: Array-indexed price levels on a 0.001 grid inside a banded window

## Status

Accepted

## Context

The book's hot operations — best-level lookup, FIFO head take, reduce, unlink, append — run
inside the cluster apply path under the zero-allocation and nanosecond-latency disciplines.
Comparison-ordered structures (trees, skip lists, hash maps of levels) allocate, chase pointers,
and put a comparator on the hottest loop. Prices in this system are long ticks at the x1e6 scale
and every edge rounds to 3dp HALF_UP, so the price domain is already a discrete grid.

## Decision

Represent each side of a security's book as arrays indexed by price tick on a fixed 0.001 grid
(`BOOK_TICK_PX` = 1_000 Px units): per-level FIFO head/tail references into intrusive
doubly-linked pooled orders, an aggregate open-quantity long per level, and a level-occupancy
bitmap per side. Best-price maintenance advances word-wise through the bitmap when a best level
empties. All arrays for a security allocate once, lazily, at the security's first order —
log-driven and therefore replica-identical.

Bound memory with a banded window per security: `BOOK_LEVELS` (default 1<<17) consecutive ticks
anchored so the security's first limit price sits mid-band (clamped at zero). Limits off the
grid reject INVALID; limits outside the band reject PRICE_COLLAR — both before any reservation.
Band width and grid are config-identity values, identical on every member, and the snapshot
header carries them so a restored member adopts the geometry its state was built with.

## Consequences

- Add, cancel, reduce, and match-head operations are O(1); the only scan is the bitmap walk when
  a best level empties (word-wise over levels/64 longs, worst case ~1μs at 1<<17 levels when a
  side empties across the whole band).
- Per-security memory is `levels`-proportional (~6.5 MB at defaults), lazily paid only for
  securities that actually trade.
- Every price an edge can produce is exactly on-grid, so a level's price equals every resting
  order's limit at that level, and execution at the level price can never violate a limit.
- A limit more than half a band from the anchor rejects PRICE_COLLAR — the same protective
  behavior as an exchange price collar, and the deterministic price-band contract of this state.
