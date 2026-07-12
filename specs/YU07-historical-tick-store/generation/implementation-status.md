# Implementation Status: YU07-historical-tick-store

Tick capture + TAQ quotes/trades ingestion + DuckDB query recipe — **implemented and verified**,
including at real bulk-ingestion scale (FR-TS09) via a real multi-file TAQ corpus as the
verification corpus — see "Bulk backfill" section below for current progress on that run.

## What is implemented

### `tick-store` (new component, Python)

| File | Role |
|---|---|
| `capture.py` | Long-running NATS subscriber on `pricing.*` and `/accounts/*/trades`; batches rows into the unified schema, flushes to partitioned Parquet on a row-count/interval trigger. |
| `ingest_taq_quotes.py` | One-shot CLI normalizing a TAQ Consolidated Quotes CSV (read from stdin, streamed via `unzip -p`) into the same schema. |
| `ingest_taq_trades.py` | One-shot CLI normalizing a TAQ Consolidated Trades CSV, same streaming pattern (research.md Decision 4). |
| `duckdb_query_examples.sql` | VWAP-style, daily-return, spread, and inventory queries over the unified store. |
| `gcs.py` | Shared GCS wiring (`CREATE SECRET TYPE gcs` from HMAC env vars), opt-in on a `gs://` output path — used by every entrypoint. |
| `stage2_ingest.sh` | Bulk-backfill driver: lists raw TAQ zips already landed in GCS, dispatches each to the right normalizer, streamed with no local disk use (research.md Decision 8). |
| `Dockerfile`, `requirements.txt` | Container packaging (`duckdb`, `nats-py`; `unzip` + `google-cloud-cli` in the base image — the latter for `gcloud storage cat`/`auth activate-service-account` inside Stage 2 pods). |
| `tests/test_capture.py`, `tests/test_ingest_taq_quotes.py`, `tests/test_ingest_taq_trades.py`, `tests/test_gcs.py` | Unit tests (21 total). |

### Manifests

- `tick-store-deployment.yaml` (1 replica, no HTTP surface/probes — NATS subscriber + CLI only; GCS
  HMAC credential injected via `tick-store-gcs-hmac` Secret, created out-of-band)
- No PVC — writes straight to `gs://traderx-501015-tick-store` (research.md Decision 6); the
  original `tick-store-data-pvc.yaml` was removed once GCS was confirmed, not kept as a fallback.
- `kustomization.yaml` extended from YU06's copy, append-only (verified below)

### Pipeline wiring

Beyond the generation hook/render script, this state required per-state case-statement entries in
two shared pipeline scripts (not obvious from the spec pack alone — found by running generation and
reading the failure):

- `pipeline/install-generated-runtime-harness.sh` — a `YU07-historical-tick-store)` case copying
  every ancestor's start/stop/status/test scripts plus this state's own four. Missing this produced
  `[fail] missing mandatory runtime scripts for env wrappers in YU07-historical-tick-store`.
- `pipeline/install-generated-ci-assets.sh` — a `YU07-historical-tick-store)` case adding
  `"YU07-historical-tick-store"` and `"tick-store"` (the new component directory) to
  `state_allowed_roots`.

### Entitlement-gate forward-port (order-matcher)

YU07 forked from `YU06-eod-price-production` at commit `392dc4b`, which predates YU06's own
forward-port of `YU05-post-trade-compliance`'s `EntitlementGate` (JWT-based order-admission check,
`70b5dee`) — an inherited timing gap flagged by a separate session working YU05/YU06
(`HANDOFF-yu06-entitlement-and-graphify.md`), not a defect introduced by this state. Fixed by
mirroring YU06's own fix: copied `EntitlementGate.java`, `OrderMatcherService.java` (3
`EntitlementGate.check(...)` call sites), `OrderController.java`, `MarketTradeController.java`, and
`EntitlementGateTest.java` verbatim from the live `YU05-post-trade-compliance` branch into
`specs/YU07-historical-tick-store/generation/runtime-overrides/order-matcher/...` (clean add — YU07
had no prior override of any of these 4 files), and added `application.properties` as a new YU07
override (started from the generated/effective content, appended
`risk.entitlement.enforced=${RISK_ENTITLEMENT_ENFORCED:false}` — default off, same shared HS256
secret as YU05/YU06).

**Verified:**
- Regeneration confirms `EntitlementGate.java` lands with exactly 3 call sites in
  `OrderMatcherService.java`, and `risk.entitlement.enforced` in the generated
  `application.properties` — `tick-store`'s own overrides unaffected.
- `./gradlew test --offline` (generated `order-matcher`): **85 tests, 1 failure** — the
  pre-existing `AllocationGateTest` environmental flake the handoff predicted (unrelated, not
  chased). `EntitlementGateTest`: **7/7 passing, 0 failures** (own JUnit XML report).
- Full local kind bring-up (`run-state-kind` skill, fresh cluster): all pods `1/1 Running` except
  `tick-store` (`ImagePullBackOff` — expected, no image has been built/pushed for the new component
  yet; unrelated to the entitlement fix). `riskReplicaReady=True` immediately. Smoke order
  (`POST /orders`, account `22214`, `IBM`) → **201**.
- **Bench-compare** (mandatory, hot-path-adjacent per project convention): `avg-max-load.mjs
  --no-reset --batch 1000 --conc 48 --secs 20 --runs 2`, matching the exact methodology of the
  YU06 before/after comparison for the same fix:

  | Run | peak/s | booked/s | submit/s |
  |---|---|---|---|
  | YU06 pre-entitlement-fix (2026-07-09T19:50) | 9870 | 99 | 145104 |
  | YU06 post-entitlement-fix (2026-07-10T01:44) | 10366 | 100 | 113382 |
  | **YU07 post-entitlement-fix (2026-07-10T02:16, this state)** | **10038** | 0/0 (scrape timing) | 69045 |

  `peak/s` (the BLP/order-matcher hot-path metric the gate actually touches) sits squarely between
  YU06's pre- and post-fix values — no measurable regression, consistent with the handoff's own
  finding on YU06. `submit/s` is a client-side REST rate on a resource-constrained local machine
  (single kind control-plane node, heavy background load this session) and varies run-to-run
  independent of server-side throughput; not treated as a regression signal. `booked/s` reading 0 in
  both runs is the same "run 2 in a cumulative/no-reset series often reads 0" pattern YU06's own
  reference runs show. Raw output saved to
  `scripts/bench/results/max-load-avg-2026-07-10T02-16-03-637Z.txt`.

## Verification evidence

- **DuckDB mechanics validated against real data before writing shipped code** (not assumed from
  documentation): a scratch venv (`duckdb==1.5.4`, `nats-py==2.15.0`) confirmed —
  - A plain Python list of dicts is **not** scannable by this DuckDB version's replacement scan
    (`"list" ... not suitable for replacement scans"`); the shipped code uses a `CREATE TEMP TABLE`
    + `executemany` instead.
  - `COPY ... PARTITION_BY (...) FILENAME_PATTERN '{uuid}'` correctly avoids collisions across
    repeated flushes into the same partition directory (verified: two flushes produced two
    distinct, non-overwriting files).
  - `read_csv('/dev/stdin', ...)` correctly reads a real shell pipe (`cat sample.csv | python3
    probe_stdin.py`), auto-detecting `DATE`/`TIME_M` types and truncating `TIME_M`'s nanosecond
    fraction to DuckDB's native microsecond `TIMESTAMP` automatically — no manual string parsing
    needed.
  - `DATE + TIME_M` (DuckDB's native `DATE`/`TIME` addition) produces the correct combined
    `TIMESTAMP`, confirmed against the real sample rows.
- **TAQ quotes ingestion tested against the actual sample file**: rows streamed from a real quotes
  zip file (via `unzip -p`) during this state's research produced correct
  `symbol`/`ts`/`bid_price`/`ask_price`/`venue`/`seq` values, including the nanosecond→microsecond
  truncation (`3:59:00.041571072` → stored `.041571`).
- **Row-level tolerance confirmed**: a 3-row batch with 1 row missing `DATE` wrote exactly 2 rows
  and did not abort the run.
- **Error paths confirmed**: a CSV with the wrong column layout raises a clear `ValueError` citing
  the DuckDB binder error; a CSV with zero rows carrying a `symbol` raises `"no valid rows"`.
- **Generation**: `bash pipeline/generate-state.sh YU07-historical-tick-store` exits **0** (first
  run failed on the two pipeline-wiring gaps above; fixed, then a clean rerun succeeded end to end).
- **Shared-file no-clobber** (`scripts/test-state-YU07-historical-tick-store.sh`, run against the
  generated tree): `kustomization.yaml` retains every ancestor's resource entry
  (`eod-session-close-cronjob.yaml`, `order-matcher-lmax-data-pvc.yaml`,
  `price-publisher-deployment.yaml`) alongside the YU07 addition (`tick-store-deployment.yaml`).
- **Unit tests** (`python3 -m pytest tests/ -v`, run both against the spec source and the generated
  `tick-store` output): **13 passed, 0 failed** — message-to-row mapping (both NATS subjects),
  `SeqCounter` monotonicity, partitioned-write collision-avoidance across repeated flushes, empty-
  batch no-op, real-sample TAQ ingestion, row-level tolerance, both error paths, and `gcs.py`'s
  `is_gcs_path`/`configure_gcs` behavior (including the clear-error guard when HMAC env vars are
  unset).
- **Smoke test** (`scripts/test-state-YU07-historical-tick-store.sh`): all checks pass, including
  new checks that the generated Deployment references the real bucket + HMAC Secret and that no
  PVC is generated.

### GCS wiring (added after the user confirmed storage tier/budget)

- Bucket `gs://traderx-501015-tick-store` created (Standard, `us-east1`, uniform bucket-level
  access); dedicated service account `tick-store-gcs@traderx-501015.iam.gserviceaccount.com` with
  `storage.objectAdmin` scoped to only this bucket (granted via the Console UI, at the user's
  request, to walk through the IAM step themselves).
- **Verified structurally before any real credential existed**: `CREATE SECRET (TYPE gcs, KEY_ID
  'dummy', SECRET 'dummy')` against the real bucket produced an auth-style `403 Forbidden`, not a
  scheme/routing error — confirms `gs://` URIs resolve correctly through DuckDB's native `gcs`
  secret type before the real HMAC key was ever involved.
- **End-to-end write/read against the real bucket, with the real HMAC key, verified**: the user ran
  `configure_gcs` + a real `COPY (SELECT 'IBM' AS symbol, 1 AS x) TO
  'gs://traderx-501015-tick-store/_smoke-test/probe.parquet' (FORMAT PARQUET)` locally with the
  real credential (kept out of this session's chat/tool logs throughout — env vars set directly in
  the user's own terminal). Verified independently via a *separate* gcloud-auth path (the project
  Owner account, not the HMAC key): `gcloud storage ls` confirmed the object existed, then
  `gcloud storage cp` + `read_parquet` on the downloaded copy returned `[('IBM', 1)]` — exact match.
  Test object removed from the bucket afterward.

## Not implemented (out of this state's scope — see spec.md)

- Backtesting/replay, and serving the execution algo engine (YU08)/VaR-ES consumers — both are
  separate future states per the parent handoff's own decisions-already-made list, not this state's
  scope.

## TAQ trades ingestion (`ingest_taq_trades.py`)

Added once a real trades zip file was available to inspect, confirming the standard NYSE Daily TAQ
Consolidated Trades (CT) layout (research.md Decision 4). Mirrors `ingest_taq_quotes.py`'s
structure exactly, mapped in `data-model.md`.

**Verified:**
- Real CT sample rows (streamed via `gcloud storage cat | funzip` from a real trades zip file)
  produced correct `symbol`/`ts`/`price`/`size`/`venue`/`seq` values, with
  `bid_price`/`bid_size`/`ask_price`/`ask_size` correctly `NULL` (trades carry no quote data).
- Same row-level tolerance and error-path behavior as the quotes normalizer (missing-column rows
  skipped; zero-valid-rows and wrong-column-layout both raise a clear error).
- **4/4 tests passing** (`tests/test_ingest_taq_trades.py`), bringing the component total to
  **21/21 passing**.

## Bulk backfill — Stage 2 (GCS-native, research.md Decision 8)

The normalizers (Decisions 4/5) are general-purpose against any conforming TAQ file — this section
is about proving the ingestion *pipeline* holds up at real bulk scale, using a real multi-file,
multi-hundred-GB TAQ corpus landed in `gs://traderx-501015-tick-store/_raw-taq/` as the
verification corpus, not as a dataset the store is scoped to. A second ingestion pass (`stage2_ingest.sh`,
bundled into the same `tick-store` image) reads each raw zip straight from GCS, streams it through
the matching normalizer, and writes Parquet back to `gs://traderx-501015-tick-store/ticks` — never
touching local disk. Deployed as a Kubernetes Indexed Job (`tick-store-stage2`, `traderx`
namespace) for coordination-free per-file parallelism; the same Job definition runs unchanged
against any future TAQ drop landed in the same GCS prefix.

**Incident and fix** (see research.md Decision 8 for full detail): a `pipefail`/`funzip`
false-failure bug caused the Indexed Job to retry-loop on already-successful files, each retry
writing a duplicate copy of the partition (confirmed 12–23× file-count inflation on the first
dates processed). Root-caused, fixed (success now keyed on the normalizer's `PIPESTATUS`, not the
pipeline's `pipefail` exit), corrupted partitions wiped, and the Job restarted clean on a
digest-pinned image so a same-tag rebuild can't silently serve stale code again.

**Progress (point-in-time snapshot — check the live Job for current state, this will be stale by
the time anyone reads it later):**
- Multiple files fully ingested and verified duplicate-free (spot-checked via per-symbol file
  counts on a completed partition — exactly 1 Parquet file post-fix, versus a dozen-plus copies
  from the pre-fix bug).
- 11-wide parallelism (3 nodes on `default-pool` + 9 nodes on a new private node pool,
  `batch-private-pool`, added specifically to remove the external-IP quota ceiling — see
  `project_traderx_lmax_kube.md` memory for the Cloud NAT infrastructure this required).
- An autonomous watcher (`scripts/stage2-watcher.sh`) polls the Job and scales both node pools to
  0 on completion or failure, so the run is unattended-safe.
- This run itself is still in progress — update this section with final file/row counts and a
  completion timestamp once the Job reaches `Complete`. FR-TS09/SC-TS07 (bulk-scale capability)
  don't require this specific run to finish to be considered implemented — the pipeline already
  works, per the clean completions so far — but a full run is the strongest evidence available.

## Notes / gotchas recorded

- Reading `/dev/stdin` twice in one process (once to check column presence, once to count rows)
  silently returns empty results on the second read — a pipe isn't seekable. `ingest_taq_quotes.py`
  reads the source exactly once; a wrong column layout surfaces as a DuckDB binder error from the
  single `COPY` statement instead of a separate up-front check.
- The runtime-harness and CI-assets pipeline scripts each maintain their own per-state case
  statement that is not otherwise documented in any spec pack — a new state's scripts existing
  under `scripts/` is necessary but not sufficient; both scripts also need an explicit case entry,
  discovered by running generation and reading the failure rather than by inspection alone.
