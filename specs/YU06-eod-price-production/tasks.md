# Tasks: YU06-eod-price-production

Build order is bottom-up (schema → producer → event → consumer → trigger → observability → tests →
packaging). Status reflects slice 1.

## Schema
- [x] T01 Add `eod_price_session`, `eod_price_snapshot`, `eod_position_pnl` to the k8s
  database-init ConfigMap (start from YU05's copy so `settlementdate` survives).

## Producer (trade-processor)
- [x] T10 `EodPriceSnapshotRepository` — versioned read/append, "next version" for a date, latest
  version, prior published close per security.
- [x] T11 `EodQualityChecker` — `OK`/`STALE`/`SPIKE`/`MISSING` from `eod.quality.*` + prior close.
- [x] T12 `EodPriceService.produce/override/publish` — versioning + fail-safe gate + auto-publish.
- [x] T13 `EodEventPublisher` — durable JetStream `EOD_PRICES_READY` (reuse YU04 pattern).
- [x] T14 `EodController` — `/eod/session/close`, `/eod/prices/{date}`, `/override`, `/publish` (admin JWT).
- [x] T15 `eod.*` config in `application.properties` (append to YU05's copy).

## Consumer (position-service)
- [x] T20 NATS client dep + `org.json` in `build.gradle` + `eod.*` config (NATS connection folded
  into `EodPnlConsumer`, self-contained — no separate `PubSubConfig` bean needed).
- [x] T21 `EodPriceSnapshotReader` — read-only `(date, version)` snapshot access.
- [x] T22 `EodPnlRepository` — idempotent upsert into `eod_position_pnl`.
- [x] T23 `EodPnlConsumer` — durable subscriber; per-account fail-safe marking; emit `eod.pnl.done`.

## Trigger + observability
- [x] T30 `eod-session-close` CronJob manifest (demo hourly, service-account admin JWT).
- [x] T40 Micrometer metrics (both services) + `traderx-eod-batch-chain.json` Grafana dashboard.

## Tests
- [x] T50 Producer: quality classification, versioning/immutability, override→new version,
  publish fail-safe (409 on flags), auto-publish on clean.
- [x] T51 Consumer: marking math, fail-safe halt on missing/flagged, idempotent redelivery.

## Packaging
- [x] T60 Generation hook + render script; wire `generate-state.sh YU06-eod-price-production`.
- [x] T61 Verify generation propagation empirically (ancestor + YU06 markers in shared files).
- [x] T62 Doc sync (root `CLAUDE.md`, `specs/README.md`, teammate/cloud-arch handoffs, index).
- [x] T63 `generation/implementation-status.md` with verification evidence.
