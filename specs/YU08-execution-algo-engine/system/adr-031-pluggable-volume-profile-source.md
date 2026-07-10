# ADR-031: Pluggable Volume-Profile Source for VWAP, Synthetic Default with Automatic Fallback

**Status:** Accepted
**Date:** 2026-07-10
**State:** `YU08-execution-algo-engine` (parent `YU07-historical-tick-store`)

## Context

VWAP scheduling needs a per-bucket volume weighting. `YU07-historical-tick-store` already stores a
unified `ticks` Parquet schema at `gs://traderx-501015-tick-store` that could supply this from real
trade history, but bulk OneDrive → GCS TAQ ingestion is still blocked (rclone pending university IT
approval for its Azure OAuth app; CLI-triggered local hydration was confirmed a dead end during
YU07's own work — reading a OneDrive placeholder file never starts a download via a plain filesystem
read), and live-captured trade volume alone is not yet enough to shape a meaningful profile. VWAP
must not sit blocked on data that may take an indeterminate time to arrive.

## Decision

`VolumeProfileSource` is a single-method interface (`bucketWeights(security, bucketCount)`) with two
implementations, selected by `algo.volume-profile.source`:

- `SyntheticVolumeProfileSource` (default): a deterministic U-shaped intraday curve computed with no
  external data — needs nothing to be ingested, ever.
- `DuckDbVolumeProfileSource`: queries YU07's unified `ticks` store via `org.duckdb:duckdb_jdbc`
  (the same DuckDB engine `tick-store` already uses from Python, here from Java) for
  `event_type='trade'` volume by intraday bucket. When the query returns zero matching rows for the
  requested security — the expected state today, given the ingestion blocker above —
  it returns `SyntheticVolumeProfileSource`'s weights instead of failing or blocking the parent
  order, logging that it did so.

Both implementations return the same shape (weights summing to 1, one per bucket), so
`VwapScheduleBuilder` has no branch on which source produced them — the fallback is invisible above
this interface.

## Alternatives Considered

- **Block VWAP until bulk ingestion unblocks:** rejected — explicitly ruled out by the parent
  handoff ("don't block VWAP on this") and would leave VWAP unusable for an indeterminate,
  externally-controlled duration (a university IT approval with no committed timeline).
- **Fail the parent-order request when the DuckDB query returns no rows:** rejected — a caller has
  no way to know in advance whether a given security has enough captured history, and the whole
  point of a pluggable source is that VWAP stays usable regardless; falling back to the synthetic
  curve is strictly more useful than a 4xx the caller cannot act on.
- **A separate "no data yet" error/placeholder profile:** rejected as an unneeded third state —
  `SyntheticVolumeProfileSource`'s curve is already a complete, demo-usable profile, so a second,
  emptier fallback would add a branch for a case the primary fallback already covers.

## Consequences

**Positive:** VWAP is usable today with zero data dependency (synthetic default); switching to real
data later is a one-line config change (`algo.volume-profile.source=duckdb`) with no code change,
and even then degrades gracefully per-security as real data arrives incrementally (a security with
captured history gets a real profile; one without still gets the synthetic curve rather than an
error).

**Costs:** `DuckDbVolumeProfileSource` adds one dependency (`org.duckdb:duckdb_jdbc`) to
`execution-algo-engine` purely for this query; justified the same way YU07 justified DuckDB for
`tick-store` — reimplementing Hive-partitioned Parquet/GCS reading without it would be far more code
than the dependency it replaces.

## Validation

- Unit test: `SyntheticVolumeProfileSource.bucketWeights(n)` returns `n` non-negative weights
  summing to 1 (within floating-point tolerance) for several values of `n`.
- Unit test: `DuckDbVolumeProfileSource` against an empty/no-match query result returns exactly
  `SyntheticVolumeProfileSource`'s weights for the same `bucketCount`.
- Manual/integration check: a VWAP parent order's bucket quantities are not all equal (confirming
  weighting is applied) when the synthetic source is active.
