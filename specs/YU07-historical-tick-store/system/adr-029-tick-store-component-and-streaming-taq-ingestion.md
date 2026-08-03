# ADR-029: New `tick-store` Component, Streamed TAQ Ingestion, GCS Storage

**Status:** Accepted, implemented
**Date:** 2026-07-09
**State:** `YU07-historical-tick-store` (parent `YU06-eod-price-production`)

## Context

The project needs a historical tick store: TraderX's own live ticks plus a normalized slice of NYSE
TAQ data, in one queryable schema, to unblock real VWAP for the execution algo engine and
return/scenario data for this project's own future VaR/ES work. Three sub-decisions had to be made
together: where the new capability lives, how to move a large multi-file TAQ CSV corpus (tens to
hundreds of GB) without needing terabytes of scratch disk, and where the output actually gets
stored.

## Decision

1. **New standalone `tick-store` component** (Python, `duckdb` + `nats-py`), not an addition to an
   existing JVM service. None of the existing services have a Parquet writer or an embeddable
   analytical query engine; adding DuckDB/Parquet support to a Spring service would mean a JVM
   Parquet library plus a JDBC/JNI path into DuckDB, for no benefit over DuckDB's native Python
   package. `price-publisher` already established that this project tolerates a polyglot component
   when the tech fit is better elsewhere.
2. **Stream TAQ CSVs through a shell pipe, never extract to disk**: `unzip -p <zip> <entry> |
   python3 ingest_taq_quotes.py`, with the normalizer reading `/dev/stdin` via DuckDB's CSV reader.
   A single TAQ quotes day can decompress to tens of GB (confirmed from the zip's own central
   directory); extracting before parsing would need multiple terabytes of scratch space across a
   multi-month corpus with no query benefit.
3. **GCS Standard tier, `traderx-501015` project, bucket-scoped HMAC credential.** Confirmed by the
   user for a multi-hundred-GB corpus. Standard was chosen over Nearline/Coldline because
   this is actively-queried research data — a colder tier's per-GB retrieval fee would eat its own
   storage-cost savings against repeated VWAP/return-series range queries. Auth is a dedicated
   service account (`tick-store-gcs@traderx-501015.iam.gserviceaccount.com`) with
   `storage.objectAdmin` scoped to only this bucket (not project-wide), via an HMAC key — DuckDB's
   native GCS credential shape, not GKE Workload Identity, which DuckDB has no concept of. The key
   reaches `tick-store` through a Kubernetes Secret created out-of-band, never committed to this
   repo.

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
- **Nearline/Coldline storage class** — rejected; this dataset is queried unpredictably as
  VWAP/VaR-ES work iterates, not archived. Retrieval fees on a colder tier scale with every scan of
  a Parquet partition, unlike Standard's flat per-GB storage cost.
- **GKE Workload Identity instead of an HMAC key** — rejected for this state; DuckDB's GCS
  integration authenticates via an HMAC key/secret (the S3-compatible credential shape), not
  Workload Identity. Revisit only if DuckDB adds native Workload Identity support.
- **A local PersistentVolumeClaim** (this state's original v1 design, before the user confirmed
  GCS tier/budget) — superseded once GCS was confirmed; removed rather than kept as a fallback,
  since the schema/partitioning are identical either way and an unused PVC is dead infrastructure.

## Consequences

Positive: capture and TAQ-quotes ingestion both write directly to durable, region-matched object
storage with no local PVC to manage or run out of space; ingestion of the full Feb/March dataset
needs no scratch disk beyond each day's own network write; the bucket-scoped IAM binding means a
compromised HMAC key can only touch this one bucket, not the rest of the project.

Costs: DuckDB's HMAC-based GCS auth means the credential is a static secret (rotatable, but not
short-lived like Workload Identity tokens) — acceptable for a research-data store with no PII, not
acceptable if this pattern were reused for anything handling sensitive data. TAQ **trades**
ingestion is not covered by this ADR or this state — the trades file's exact column layout was not
confirmed at writing time.
