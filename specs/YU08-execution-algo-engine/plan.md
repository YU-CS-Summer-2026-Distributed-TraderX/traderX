# Implementation Plan: YU08-execution-algo-engine

## Goal

Add an execution algo engine that slices a parent order into TWAP (time-weighted) or VWAP
(volume-weighted) child orders, submits every child through the existing order-entry and risk-gateway
path unchanged, and event-sources its own schedule/fill state so a crash resumes from its log.

## Workstreams

1. New component: `execution-algo-engine`
   - Java 21, Spring Boot (same shape as `account-service`/`trade-processor`), `io.nats:jnats` for
     NATS pub/sub and JetStream, `org.duckdb:duckdb_jdbc` for the VWAP volume-profile query.
   - `AlgoOrderService`: parent-order creation, TWAP/VWAP bucket scheduling, progress queries.
   - `AlgoScheduler`: `@Scheduled` loop submitting due buckets to `order-matcher`'s `POST /orders`.
   - `OrderUpdateSubscriber`: `/accounts/*/orders` broadcast subscriber correlating fills by `orderId`.
   - `AlgoEventStore`: JetStream-backed append log + durable-consumer replay for crash recovery.
   - `VolumeProfileSource`: `SyntheticVolumeProfileSource` (default) and `DuckDbVolumeProfileSource`.
2. Packaging
   - Dockerfile, k8s Deployment + Service (`kubernetes-runtime` overlay), generation hook + render
     script, `scripts/{start,stop,status,test}-state-YU08-execution-algo-engine*.sh`.
3. Validation
   - Unit tests: TWAP bucket math, VWAP weighting + synthetic fallback, event-store replay
     rebuilding in-memory state, order-update correlation by `orderId`.
   - End-to-end: TWAP parent order run against a local kind cluster (`run-state-kind`), children
     observed accepted by `order-matcher`, progress visible via the status endpoint.
   - `bench-compare` against the `YU07-historical-tick-store` baseline (mandatory — children flow
     through the same order-submission path as every other order).

## Key decisions

- Separate warm-path Spring Boot service, not a BLP feature — see `research.md` Decision 1.
- Children submit through `order-matcher`'s existing `POST /orders`, no bypass — Decision 2.
- Child limit price: last price ± 10bps aggressive offset — Decision 3.
- Own state event-sourced over a new JetStream stream (`TRADERX_ALGO_ENGINE`), reusing the existing
  `io.nats:jnats` client and stream-bootstrap idiom from YU04 — Decision 4.
- Fill tracking via the existing `/accounts/*/orders` broadcast subject, correlated by `orderId` —
  Decision 5.
- TWAP: equal-quantity time buckets, default 10s bucket — Decision 6.
- VWAP: pluggable volume-profile source, synthetic default with automatic DuckDB fallback —
  Decision 7.
- REST-only parent-order ingress (`POST /algo/orders`), no front-end panel — Decision 8.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts and exits successfully.
- Unit tests pass for TWAP/VWAP scheduling, event-store replay, and order-update correlation.
- A TWAP parent order run end to end on kind produces accepted child orders and visible progress.
- `bench-compare` shows no regression against the `YU07-historical-tick-store` baseline.
- Generated shared file (`kustomization.yaml`) retains every ancestor state's content alongside this
  state's two additions.
- State can be published to `code/generated-state-YU08-execution-algo-engine`.
