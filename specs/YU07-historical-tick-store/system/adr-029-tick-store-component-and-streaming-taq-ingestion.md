# ADR-029: New `tick-store` Component, Streamed TAQ Ingestion, Local PVC Storage

**Status:** Accepted, implemented
**Date:** 2026-07-09
**State:** `YU07-historical-tick-store` (parent `YU06-eod-price-production`)

## Context

The project needs a historical tick store: TraderX's own live ticks plus a normalized slice of NYSE
TAQ data, in one queryable schema, to unblock real VWAP for the execution algo engine and
return/scenario data for this project's own future VaR/ES work. Three sub-decisions had to be made
together: where the new capability lives, how to move ~650GB of TAQ CSV without needing terabytes
of scratch disk, and where the output actually gets stored.

## Decision

1. **New standalone `tick-store` component** (Python, `duckdb` + `nats-py`), not an addition to an
   existing JVM service. None of the existing services have a Parquet writer or an embeddable
   analytical query engine; adding DuckDB/Parquet support to a Spring service would mean a JVM
   Parquet library plus a JDBC/JNI path into DuckDB, for no benefit over DuckDB's native Python
   package. `price-publisher` already established that this project tolerates a polyglot component
   when the tech fit is better elsewhere.
2. **Stream TAQ CSVs through a shell pipe, never extract to disk**: `unzip -p <zip> <entry> |
   python3 ingest_taq_quotes.py`, with the normalizer reading `/dev/stdin` via DuckDB's CSV reader.
   A single TAQ quotes day decompresses to ~76.5GiB (confirmed from the zip's own central
   directory); extracting before parsing would need multiple terabytes of scratch space across
   Feb+March with no query benefit.
3. **Local PersistentVolumeClaim for storage in v1**, not GCS. The parent handoff explicitly flags
   ~650GB in GCS as a real monthly cost requiring the user's budget/tier decision, which had not
   been made as of this state. A local PVC lets capture and TAQ ingestion work end-to-end without
   pre-committing that decision on the user's behalf.

## Alternatives Considered

- **Extend `trade-processor` or `position-service` with a Parquet writer** — rejected; neither
  service has a reason to own a columnar file format or an embedded analytical engine, and doing so
  would mean adding a JVM Parquet dependency for one feature while a battle-tested Python option
  (DuckDB) already exists.
- **Extract each TAQ zip fully before parsing** — rejected; confirmed via the zip's central
  directory that a single day is ~76.5GiB decompressed, and the user's machine had ~30GB free when
  checked. No architectural benefit over streaming — the source is still read exactly once either
  way.
- **A custom columnar format or bespoke query engine** — rejected per the standing project
  decision (parent handoff): Parquet + DuckDB is the right size, already proven, and needs no new
  infrastructure to operate.
- **Wire up GCS storage now** — rejected for this state; the budget/tier decision belongs to the
  user and has not been made. A local PVC is a truthful description of what is built today; the
  schema and partitioning do not change when the storage backend does.

## Consequences

Positive: capture and TAQ-quotes ingestion both work today with no new infrastructure beyond one
new lightweight component and one new PVC; ingestion of the full Feb/March dataset needs no scratch
disk beyond each day's own output size; the unified schema means one DuckDB query already spans
both live and TAQ data.

Costs: `tick-store`'s local PVC does not scale to the full ~650GB TAQ dataset on its own — moving to
object storage is the expected next step once GCS tier and budget are confirmed, at which point only
the `--out` path changes (DuckDB reads/writes a `gs://` path via its `httpfs` extension the same way
it does a local one). TAQ **trades** ingestion is not covered by this ADR or this state — the trades
file's exact column layout was not confirmed at writing time.
