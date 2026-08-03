# Implementation Status: YU06-eod-price-production

Slice 1 — EOD price production + one real consumer + observability — **implemented and verified**.

## What is implemented

### Producer (trade-processor)

| Class | Role |
|---|---|
| `model/EodQuality`, `model/EodPrice`, `model/EodReport` | Snapshot value types + quality enum. |
| `repository/EodPriceSnapshotRepository` | JdbcTemplate; versioned append, next/latest version, prior published close, DRAFT→PUBLISHED. |
| `service/EodQualityChecker` | `OK`/`STALE`/`SPIKE`/`MISSING` from `eod.quality.*` + prior close. |
| `service/EodPriceService` | `produce`/`override`/`publish`; versioning, fail-safe gate, auto-publish, Micrometer metrics. |
| `service/EodEventPublisher` | Durable JetStream `EOD_PRICES_READY` on `TRADERX_EOD` (reuses YU04 pattern). |
| `controller/EodController` | `/eod/session/close`, `/eod/prices/{date}[/versions/{v}]`, `/override`, `/publish` — admin JWT. |
| `service/PriceHistoryStore` (extended) | YU05 file + `tickers()` accessor (universe default). YU05's `record`/`twap`/`priceAtOrBefore` preserved. |

### Consumer (position-service)

| Class | Role |
|---|---|
| `eod/EodSnapshotPrice` | Snapshot price + `isUsable()` fail-safe gate. |
| `eod/EodPriceSnapshotReader` | JdbcTemplate read of the `(date, version)` snapshot. |
| `eod/EodPnlRepository` | Idempotent upsert into `eod_position_pnl`. |
| `eod/EodPnlConsumer` | Durable JetStream subscriber; per-account fail-safe marking; emits `eod.pnl.done`; Micrometer metrics. |

### Schema / manifests / observability

- `eod_price_session`, `eod_price_snapshot`, `eod_position_pnl` added to the **real** runtime schema
  (the k8s database-init ConfigMap's single mounted `001-initialSchema.sql` key — a second key would
  not mount; see "Verification"). YU05's `settlementdate` column preserved.
- `eod-session-close` CronJob (hourly demo) wired into `kustomization.yaml`.
- `traderx-eod-batch-chain.json` Grafana dashboard added to the dashboards ConfigMap (YU05's
  post-trade dashboard preserved).

## Verification evidence

- **Generation**: `bash pipeline/generate-state.sh YU06-eod-price-production` exits **0** (recursively
  generates YU05→YU04→YU03→YU02→014, then overlays YU06). Overlay order confirmed in the render log.
- **Shared-file no-clobber** (`scripts/test-state-YU06-eod-price-production.sh`, run against the
  generated tree): every ancestor marker survives alongside the YU06 addition in each shared file —
  - `trade-processor/application.properties`: `eod.*` **and** `settlement.t-plus-days` / `auth.jwt.secret`.
  - `PriceHistoryStore.java`: `tickers()` **and** `twap(...)`.
  - `database-init-configmap.yaml`: `eod_price_session/snapshot/position_pnl` **and** `settlementdate`.
  - `observability-grafana-dashboards-configmap.yaml`: `traderx-eod-batch-chain` **and** `traderx-post-trade-compliance`.
  - `kustomization.yaml`: `eod-session-close-cronjob.yaml` referenced; CronJob manifest generated.
- **Unit/integration tests** (`./gradlew test --offline`, both green):
  - trade-processor: `EodQualityCheckerTest` (7) + `EodPriceServiceTest` (7) — classification,
    versioning/immutability, override→new version, publish fail-safe (409 on flags), auto-publish,
    idempotent republish, NOT_FOUND. Existing YU05 suites still pass.
  - position-service: `EodPnlConsumerTest` (5) — mark-to-close math (incl. shorts), per-account
    fail-safe halt on missing/flagged holding, mixed mark/halt, idempotent reprocessing.
  - **19 new tests, 0 failures, 0 errors.**

## Deferred (out of slice-1 scope — see spec.md)

- **Overnight VaR/ES batch** — teammate-owned; consumes this state's `EOD_PRICES_READY` +
  versioned snapshot. Not built here.
- **Closing auction** price source — stretch; v1 uses last trade price (ADR-026).
- **Front-end data-ops override panel** — v1 override path is REST-only.
- **Wiring YU05 NAV/recon onto `eod.pnl.done`** — the chain link is published + documented; the
  retrofit is a later slice.
- **End-to-end container smoke** (close → prices-ready → marks → pnl-done against real MariaDB +
  JetStream) — deferred to the isolated-staging verification pass, same discipline as YU03/YU05.

## Notes / gotchas recorded

- The k8s database-init ConfigMap mounts **only** `001-initialSchema.sql` via `subPath`, so the EOD
  tables had to be added inside that key, not as a new `002-*.sql` key (which would silently not
  mount). Confirmed against the state-010 deployment patch before writing.
- The state needed its own `scripts/{start,stop,status}-state-YU06-*-generated.sh` +
  `test-state-YU06-*.sh` (thin wrappers over the inherited 014 runtime) — without them the harness
  installer's `write_env_entrypoint_wrappers` fails "missing mandatory runtime scripts". Added and
  wired into `install-generated-runtime-harness.sh`.
