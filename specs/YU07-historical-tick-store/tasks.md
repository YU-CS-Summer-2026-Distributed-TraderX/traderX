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

## TAQ trades ingestion (`tick-store/ingest_taq_trades.py`)
- [x] T23 Confirmed TAQ CT CSV column mapping against the real sample file.
- [x] T24 Stream from stdin (`/dev/stdin`) — no disk extraction, same pattern as T21.
- [x] T25 Row-level error tolerance, same pattern as T22.

## Bulk backfill (`tick-store/stage2_ingest.sh`, GCS-native)
- [x] T90 Container-bundled driver: lists `_raw-taq/**/*.zip`, dispatches by filename pattern to
  the quotes/trades normalizer, streams `gcloud storage cat | funzip | python3` (no local disk).
- [x] T91 Kubernetes Indexed Job (`tick-store-stage2`, `completionMode: Indexed`) — coordination-
  free per-file parallelism via `JOB_COMPLETION_INDEX`.
- [x] T92 Fixed a `pipefail`/`funzip` false-failure bug that caused retry-driven duplicate writes
  (research.md Decision 8) — success now keyed on the normalizer's own exit code.
- [x] T93 Pinned the Job to an immutable image digest (`imagePullPolicy: Always`) after a stale-
  cached-image incident from reusing a tag across the T92 rebuild.
- [ ] T94 Bulk-scale verification: run the pipeline end-to-end against a real multi-file, multi-GB
  batch — proves the driver and Indexed Job pattern handle production scale, not that any specific
  dataset is a permanent requirement of the store. Tracked live in
  `generation/implementation-status.md`.

## Query recipe
- [x] T30 `duckdb_query_examples.sql` — VWAP-style and return-series queries spanning both
  `source` values.

## Packaging
- [x] T40 Dockerfile + `requirements.txt` for `tick-store`.
- [x] T41 `tick-store-deployment.yaml` (no PVC — writes straight to GCS); `kustomization.yaml`
  extended from YU06's copy (append-only).
- [x] T42 Generation hook + render script; wire `generate-state.sh YU07-historical-tick-store`.
- [x] T43 `scripts/{start,stop,status,test}-state-YU07-historical-tick-store*.sh`.

## Tests
- [x] T50 Unit tests: `pricing.*` and `/accounts/*/trades` message-to-row mapping.
- [x] T51 Unit tests: TAQ CQ CSV row mapping against the real sample data.
- [x] T52 End-to-end self-check: synthetic capture batch + real TAQ sample → Parquet → one DuckDB
  query reading both sources.
- [x] T53 `gcs.py` unit tests: `is_gcs_path`, clear-error guard without HMAC env vars, secret
  registration with them.
- [x] T54 Unit tests: TAQ CT CSV row mapping against the real sample data (mirrors T51 for trades).

## GCS storage (research.md Decision 6, confirmed after initial implementation)
- [x] T70 Bucket `gs://traderx-501015-tick-store` (Standard, `us-east1`, uniform bucket-level
  access).
- [x] T71 Bucket-scoped service account + `storage.objectAdmin` IAM binding, HMAC key (IAM/HMAC
  steps done via the Console UI at the user's request).
- [x] T72 `gcs.py`: `CREATE SECRET TYPE gcs` from `GCS_HMAC_KEY_ID`/`GCS_HMAC_SECRET_ACCESS_KEY`,
  wired into both `capture.py` and `ingest_taq_quotes.py`, opt-in on a `gs://` output path.
- [x] T73 Removed the local `tick-store-data` PVC once GCS was confirmed; Deployment updated with
  `TICKSTORE_OUT_DIR=gs://traderx-501015-tick-store/ticks` + Secret-sourced HMAC env vars.
- [x] T74 Live write/read against the real bucket with the real HMAC key (user ran `configure_gcs`
  + a real `COPY ... TO 'gs://...'` locally with the real credential, kept out of chat/tool logs
  throughout; verified independently via a separate gcloud-auth read-back — content matched
  exactly, `[('IBM', 1)]`). Test object cleaned up from the bucket afterward.

## Doc sync
- [x] T60 Verify generation propagation empirically (`kustomization.yaml` keeps every ancestor
  entry alongside the new one).
- [x] T61 Doc sync (root `CLAUDE.md`, `specs/README.md`, `HANDOFF-FOR-TEAMMATE.md`, catalog).
- [x] T62 `generation/implementation-status.md` with verification evidence.
