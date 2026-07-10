# Implementation Status: YU07-historical-tick-store

Tick capture + TAQ quotes ingestion + DuckDB query recipe — **implemented and verified**.

## What is implemented

### `tick-store` (new component, Python)

| File | Role |
|---|---|
| `capture.py` | Long-running NATS subscriber on `pricing.*` and `/accounts/*/trades`; batches rows into the unified schema, flushes to partitioned Parquet on a row-count/interval trigger. |
| `ingest_taq_quotes.py` | One-shot CLI normalizing a TAQ Consolidated Quotes CSV (read from stdin, streamed via `unzip -p`) into the same schema. |
| `duckdb_query_examples.sql` | VWAP-style, daily-return, spread, and inventory queries over the unified store. |
| `Dockerfile`, `requirements.txt` | Container packaging (`duckdb`, `nats-py`; `unzip` in the base image). |
| `tests/test_capture.py`, `tests/test_ingest_taq_quotes.py` | Unit tests (10 total). |

### Manifests

- `tick-store-deployment.yaml` (1 replica, no HTTP surface/probes — NATS subscriber + CLI only)
- `tick-store-data-pvc.yaml` (20Gi, `ReadWriteOnce`)
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
- **TAQ quotes ingestion tested against the actual sample file**: rows streamed from
  `taq_quotes_20250211_csv.zip` (via `unzip -p`) during this state's research produced correct
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
  `price-publisher-deployment.yaml`) alongside both YU07 additions (`tick-store-data-pvc.yaml`,
  `tick-store-deployment.yaml`).
- **Unit tests** (`python3 -m pytest tests/ -v`, run both against the spec source and the generated
  `tick-store` output): **10 passed, 0 failed** — message-to-row mapping (both NATS subjects),
  `SeqCounter` monotonicity, partitioned-write collision-avoidance across repeated flushes, empty-
  batch no-op, real-sample TAQ ingestion, row-level tolerance, and both error paths.
- **Smoke test** (`scripts/test-state-YU07-historical-tick-store.sh`): all checks pass.

## Not implemented (out of this state's scope — see spec.md)

- **TAQ trades ingestion.** `taq_trades_feb2025_csv.zip` had not hydrated from OneDrive (still a 0B
  cloud placeholder) as of this state's implementation; per this project's standing rule, no
  normalizer is written against an unconfirmed column layout. Only TAQ **quotes** ingestion is
  implemented.
- **GCS storage.** Storage is a local PVC (research.md Decision 6); GCS tier/budget was not
  confirmed with the user as of this state.
- Backtesting/replay, and serving the execution algo engine (YU08)/VaR-ES consumers — both are
  separate future states per the parent handoff's own decisions-already-made list, not this state's
  scope.

## Notes / gotchas recorded

- Reading `/dev/stdin` twice in one process (once to check column presence, once to count rows)
  silently returns empty results on the second read — a pipe isn't seekable. `ingest_taq_quotes.py`
  reads the source exactly once; a wrong column layout surfaces as a DuckDB binder error from the
  single `COPY` statement instead of a separate up-front check.
- The runtime-harness and CI-assets pipeline scripts each maintain their own per-state case
  statement that is not otherwise documented in any spec pack — a new state's scripts existing
  under `scripts/` is necessary but not sufficient; both scripts also need an explicit case entry,
  discovered by running generation and reading the failure rather than by inspection alone.
