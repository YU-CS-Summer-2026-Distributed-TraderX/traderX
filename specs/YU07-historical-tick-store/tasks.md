# Tasks: YU07-historical-tick-store

Build order is bottom-up (schema → capture → TAQ ingestion → query recipe → packaging → tests).

## Schema
- [x] T01 Unified `ticks` Parquet schema + `source/dt/symbol` partitioning (`data-model.md`).

## Capture (`tick-store/capture.py`)
- [x] T10 NATS subscriber on `pricing.*` and `/accounts/*/trades`.
- [x] T11 Message-to-row mapping for both subjects (`price_tick`, `trade`).
- [x] T12 Batched flush (row-count or interval trigger) to partitioned Parquet via DuckDB
  `COPY ... (FORMAT PARQUET, COMPRESSION ZSTD)`.

## TAQ ingestion (`tick-store/ingest_taq_quotes.py`)
- [x] T20 Confirmed TAQ CQ CSV column mapping against the real sample file.
- [x] T21 Stream from stdin (`/dev/stdin`) — no disk extraction.
- [x] T22 Row-level error tolerance (skip malformed rows, fail only on zero valid rows).

## Query recipe
- [x] T30 `duckdb_query_examples.sql` — VWAP-style and return-series queries spanning both
  `source` values.

## Packaging
- [x] T40 Dockerfile + `requirements.txt` for `tick-store`.
- [x] T41 `tick-store-deployment.yaml` + `tick-store-data-pvc.yaml`; `kustomization.yaml` extended
  from YU06's copy (append-only).
- [x] T42 Generation hook + render script; wire `generate-state.sh YU07-historical-tick-store`.
- [x] T43 `scripts/{start,stop,status,test}-state-YU07-historical-tick-store*.sh`.

## Tests
- [x] T50 Unit tests: `pricing.*` and `/accounts/*/trades` message-to-row mapping.
- [x] T51 Unit tests: TAQ CQ CSV row mapping against the real sample data.
- [x] T52 End-to-end self-check: synthetic capture batch + real TAQ sample → Parquet → one DuckDB
  query reading both sources.

## Doc sync
- [x] T60 Verify generation propagation empirically (`kustomization.yaml` keeps every ancestor
  entry alongside the two new ones).
- [x] T61 Doc sync (root `CLAUDE.md`, `specs/README.md`, `HANDOFF-FOR-TEAMMATE.md`, catalog).
- [x] T62 `generation/implementation-status.md` with verification evidence.
