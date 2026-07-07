# ADR-024: TCA With a Pluggable Historical Benchmark Source

**Status:** Accepted for specification (not yet implemented — deferred, see plan.md sequencing)
**Date:** 2026-07-06
**State:** `YU05-post-trade-compliance` (parent `YU03-in-memory-risk-gateway`)

## Context

Transaction Cost Analysis needs a benchmark price series (arrival price, VWAP, TWAP) to compare a
settled trade's execution price against. Today the only price source in the system is
`price-publisher`'s synthetic random walk. Separately, the user's professor has provided ~3TB of
real historical NYSE TAQ data (trades + NBBO quotes) that would be a much more realistic benchmark
source — but its transfer/staging logistics are not resolved yet (see
`HANDOFF-combined-yu05-state.md`), and per explicit user direction, TCA's scope must not depend on
that dataset landing.

## Decision

Define the TCA computation against a benchmark-source interface, not a concrete data source.
Slice-1-and-later implementation computes benchmarks from whatever price history is available
(initially: `price-publisher`'s synthetic feed, recorded over the trade's execution window) through
that interface; a TAQ-backed implementation can be swapped in later without changing the
computation contract (arrival price capture point, VWAP/TWAP windowing, slippage-in-bps formula).

## Alternatives Considered

- **Block TCA on the TAQ dataset landing:** rejected per explicit user direction — the dataset is a
  pluggable input, not a scope gate, for every sub-capability in this bundle.
- **Hard-code the synthetic price-publisher as TCA's only source:** rejected — would require a
  rewrite (not a plug-in swap) once real historical data is available, defeating the purpose of
  scoping this ADR now.

## Consequences

Positive: TCA can be built and demoed immediately with synthetic data; swapping in TAQ later is an
implementation detail behind an existing interface, not a redesign.

Costs: the benchmark-source interface must be designed before either implementation is built,
adding a small amount of up-front abstraction — justified here (unlike the project's general
anti-premature-abstraction bias) because a second concrete implementation is a near-certain,
already-named future requirement (the professor's dataset), not a hypothetical one.

## Status in YU05

**Implemented, TWAP only** (FR-PTC30/31, FR-PTC32 partial). `PriceHistoryStore` (fed by
price-publisher's existing `pricing.*` feed) + `TcaService` compute arrival price, TWAP, and
signed slippage-bps via `GET /tca/report/{tradeId}`. VWAP genuinely deferred — the synthetic feed
carries no per-tick volume; the benchmark-source interface this ADR called for is
`PriceHistoryStore`'s `record`/`twap`/`priceAtOrBefore` contract, which a real-volume source (the
TAQ dataset) would feed identically.

## Validation (future)

TCA computation must be a pure function of (trade, benchmark price series) — no hidden dependency
on which concrete benchmark source is wired in, verified by running the same computation against
both a synthetic fixture and a (once available) real-data fixture and comparing formula behavior,
not values.
