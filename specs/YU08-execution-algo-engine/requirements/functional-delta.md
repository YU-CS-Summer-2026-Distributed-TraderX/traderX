# Functional Delta: YU08-execution-algo-engine over YU07-historical-tick-store

New requirement namespace `AE`.

| Req | Status | Notes |
|---|---|---|
| FR-AE01 accept a parent order | **Done** | `POST /algo/orders` on `execution-algo-engine`. |
| FR-AE02 TWAP equal-time buckets | **Done** | `TwapScheduleBuilder` — equal split, remainder on the last bucket. |
| FR-AE03 VWAP volume-weighted buckets | **Done** | `VwapScheduleBuilder` + pluggable `VolumeProfileSource`. |
| FR-AE04 children via `POST /orders` unchanged | **Done** | `OrderMatcherClient` posts `OrderCreateRequest` with a derived positive `limitPrice`; no order-matcher change. |
| FR-AE05 fill tracking via `/accounts/*/orders` | **Done** | `OrderUpdateSubscriber`, correlated by `orderId`. |
| FR-AE06 progress query API | **Done** | `GET /algo/orders/{parentOrderId}`, `GET /algo/orders`. |
| FR-AE07 append-before-apply event log | **Done** | `AlgoEventStore.append(...)` writes to JetStream before `AlgoOrderState.apply(...)`. |
| FR-AE08 startup replay, no synchronous fetch | **Done** | Durable JetStream consumer replay on boot (`AlgoEventStore.replayAndSubscribe`). |
| FR-AE09 VWAP graceful fallback | **Done** | `DuckDbVolumeProfileSource` returns `SyntheticVolumeProfileSource`'s weights on zero matching rows. |
