# Implementation Plan: YU07-historical-tick-store

## Goal

Persist TraderX's own live ticks and a normalized slice of NYSE TAQ data into one partitioned
Parquet schema, queryable uniformly through DuckDB, without touching the order-matching hot path or
extracting the multi-terabyte TAQ source to disk.

## Workstreams

1. New component: `tick-store`
   - Python 3.12, `duckdb` + `nats-py`; no new microservice pattern beyond `price-publisher`'s
     existing polyglot precedent.
   - `capture.py`: long-running NATS subscriber on `pricing.*` and `/accounts/*/trades`, batched
     writes to partitioned Parquet.
   - `ingest_taq_quotes.py`: one-shot CLI, reads a TAQ CQ CSV from stdin (fed by `unzip -p`),
     normalizes into the same schema, writes partitioned Parquet.
2. Query layer
   - `duckdb_query_examples.sql`: symbol/date-range range queries and aggregations (VWAP-style
     volume-weighted average, simple return series) over the unified store.
3. Packaging
   - Dockerfile, k8s Deployment + PVC (`kubernetes-runtime` overlay), generation hook + render
     script, `scripts/{start,stop,status,test}-state-YU07-historical-tick-store*.sh`.
4. Validation
   - Unit tests for the capture message-to-row mapping and the TAQ CSV-to-row mapping.
   - A runnable end-to-end self-check: synthetic capture batch + the real sample TAQ quotes file →
     Parquet → one DuckDB query reading both.
   - Generation-propagation check confirming the shared `kustomization.yaml` keeps every ancestor
     state's resource entries.

## Key decisions

- `tick-store` is a new standalone component, not an addition to an existing JVM service — see
  `research.md` Decision 1.
- Capture only subscribes to subjects that are already broadcast (`pricing.*`,
  `/accounts/*/trades`); the point-to-point `/trades` link is left untouched.
- One unified Parquet schema for live and TAQ data, partitioned `source/dt/symbol`.
- TAQ ingestion streams via a shell pipe (`unzip -p | ... /dev/stdin`) — never extracts the
  decompressed CSV to disk.
- Storage is a local PVC in this state; GCS is the intended production target but is not wired up
  until the user confirms storage tier and budget (research.md Decision 6).
- Only TAQ **quotes** ingestion is implemented — the trades file's format was not confirmed at
  writing time; no normalizer is written against an unconfirmed layout.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts and exits successfully.
- Unit tests pass for the capture mapping and the TAQ quotes mapping.
- The end-to-end self-check produces Parquet files a single DuckDB query reads back correctly
  across both `source` values.
- Generated shared file (`kustomization.yaml`) retains every ancestor state's content alongside this
  state's two additions.
- State can be published to `code/generated-state-YU07-historical-tick-store`.
