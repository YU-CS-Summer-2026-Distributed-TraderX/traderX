# ADR-060: A Treasury's book grid is one Px tick, derived from the committed ticker

Status: Accepted

## Context

The YU13 crossing book prices levels on a fixed grid — `BOOK_TICK_PX = 1_000`, the exact
3-decimal granularity every edge historically rounded to — and **rejects off-grid limit
prices** (`limitPx % tickTicks == 0` is a placement precondition). A bond price stored as a
fraction of par (ADR-057) needs six decimals: `0.998860` is 998,860 Px ticks, which is not a
multiple of 1,000. Under the decided convention, every bond order was rejected by the book
before matching — discovered when the first end-to-end bond cross produced no position. The
plan's premise that the deterministic core needed *no* change did not survive contact with the
book's grid; among the alternatives (re-quantizing bond prices to 0.1% of par, or redefining
quantity as $100-face units with percent prices and unit/face conversions at two trust
boundaries), the smallest honest change is in the core.

## Decision

Per-security book-grid derivation, exactly the ADR-052 contract-multiplier pattern: at symbol
registration and at `T_SYMBOL` snapshot restore, the service derives
`ticker.startsWith("UST-") ? 1 : 0` and installs it on the engine
(`MatchingEngine.overrideBookTickPx`); book creation uses the per-security grid when one is set,
the global grid otherwise. The value is a **pure function of the committed ticker, stored
nowhere** — `SNAPSHOT_FORMAT` stays 4, no record changes shape, and `T_SYMBOL` restores ahead of
`T_BOOK`, so a restored member rebuilds a bond book on the identical grid it was cut on.

Grid 1 admits the full six-decimal fraction (no bond price is ever off-grid), and the band at
that grid still spans ±6.5 points of par around the anchor — six times the widest configured
Treasury walk (±1.0 for the 30Y).

## Consequences

- The deterministic core changes by exactly one derived function consulted on the cold
  book-creation path. The apply hot path is untouched: `noGcTest` and all three allocation
  gates stay green, and the added lookup sits inside the book-creation branch that runs once
  per security.
- **Roll order matters, once**: old and new code behave identically for every input that does
  not reference a `UST-` symbol, and no such symbol exists on a standing rig until this state
  seeds one — so the image rolls member by member with PVCs and epoch intact, and Treasury
  securities are registered only after all members run the new image. A UST order landing
  inside the mixed window would diverge members; the bring-up seeds fixtures after the roll,
  which makes the window benign by construction.
- Replay and restore identity are proven in-suite: a member restored from a snapshot holding a
  bond book re-derives the grid and renders a byte-identical cut.
- The single-BLP tier's `LmaxEngine` path installs no derivation (nothing registers `UST-`
  tickers with the legacy engine), so that tier's behavior is byte-identical; bonds are a
  cluster-tier feature in this state.
