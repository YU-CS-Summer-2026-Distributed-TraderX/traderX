# Tasks: YU08-execution-algo-engine

Build order is bottom-up (schedule math → event store → HTTP client to order-matcher → fill
tracking → REST API → VWAP volume profile → packaging → tests).

## Schedule
- [x] T01 `TwapScheduleBuilder` — equal-quantity buckets, remainder on the last bucket.
- [x] T02 `VwapScheduleBuilder` — buckets weighted by `VolumeProfileSource.bucketWeights(...)`.

## Event store (`execution-algo-engine/eventstore`)
- [x] T10 `AlgoEventStore`: JetStream stream bootstrap (`TRADERX_ALGO_ENGINE`), append-before-apply.
- [x] T11 Durable pull consumer (`algo-engine-state`, `DeliverPolicy.All`, `AckPolicy.Explicit`),
  replay-on-boot rebuilding `AlgoOrderState`.
- [x] T12 Explicit ack only after in-memory apply succeeds (crash-between-append-and-ack safety).

## Child order submission (`execution-algo-engine/orders`)
- [x] T20 `PriceClient`: `GET /prices/{ticker}` on `price-publisher`.
- [x] T21 `OrderMatcherClient`: `POST /orders` with a derived positive `limitPrice` (last price ±
  `ALGO_LIMIT_OFFSET_BPS`).
- [x] T22 `AlgoScheduler`: `@Scheduled` loop submitting due buckets, retrying on failure without
  double-submitting.

## Fill tracking (`execution-algo-engine/fills`)
- [x] T30 `OrderUpdateSubscriber`: `/accounts/*/orders` wildcard subscription, correlated by
  `orderId` against an in-memory submitted-child index.
- [x] T31 Parent-order completion detection (every bucket submitted and filled).

## REST API (`execution-algo-engine/api`)
- [x] T40 `POST /algo/orders`, `GET /algo/orders/{parentOrderId}`, `GET /algo/orders`.

## Volume profile (`execution-algo-engine/volume`)
- [x] T50 `SyntheticVolumeProfileSource` — deterministic U-shaped curve.
- [x] T51 `DuckDbVolumeProfileSource` — DuckDB JDBC query over the unified `ticks` store, falls back
  to synthetic weights on zero matching rows.

## Packaging
- [x] T60 `build.gradle`, `Dockerfile` for `execution-algo-engine`.
- [x] T61 `execution-algo-engine-deployment.yaml` + `-service.yaml`; `kustomization.yaml` extended
  from YU07's copy (append-only).
- [x] T62 Generation hook + render script; wire `generate-state.sh YU08-execution-algo-engine`.
- [x] T63 `scripts/{start,stop,status,test}-state-YU08-execution-algo-engine*.sh`.

## Tests
- [x] T70 Unit tests: TWAP/VWAP bucket math (including remainder handling and weight normalization).
- [x] T71 Unit tests: `AlgoEventStore` replay rebuilds identical state to the pre-crash model.
- [x] T72 Unit tests: `OrderUpdateSubscriber` correlation by `orderId`, ignoring unrelated orders.
- [x] T73 Unit tests: `DuckDbVolumeProfileSource` empty-result fallback to synthetic weights.
- [x] T74 End-to-end: TWAP parent order on a local kind cluster, children accepted by
  `order-matcher`, progress visible via `GET /algo/orders/{id}` (`run-state-kind`).
- [x] T75 `bench-compare` against the `YU07-historical-tick-store` baseline.

## Doc sync
- [x] T80 Verify generation propagation empirically (`kustomization.yaml` keeps every ancestor
  entry alongside the two new ones).
- [x] T81 Doc sync (root `CLAUDE.md`, `specs/README.md`, `HANDOFF-FOR-TEAMMATE.md`, catalog).
- [x] T82 `generation/implementation-status.md` with verification evidence.
