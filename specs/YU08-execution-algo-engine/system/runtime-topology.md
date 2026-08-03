# Runtime Topology: YU08-execution-algo-engine

Parent state: `YU07-historical-tick-store`

## Entrypoints

- `POST /algo/orders`, `GET /algo/orders/{parentOrderId}`, `GET /algo/orders` — the new HTTP surface
  on `execution-algo-engine` (port 18120).
- No new entrypoint on any existing service — `execution-algo-engine` calls `order-matcher`'s
  existing `POST /orders` and `price-publisher`'s existing `GET /prices/{ticker}` as a client, and
  adds itself as a subscriber on the existing `/accounts/*/orders` broadcast subject.

## Components

- Inherits every Kubernetes/C3/FDC3/EOD/tick-store runtime component already present in
  `YU07-historical-tick-store` unchanged.
- `execution-algo-engine` (new): a single Spring Boot service, one Deployment, one ClusterIP
  Service (port 18120). No PVC — all durable state lives in the `TRADERX_ALGO_ENGINE` JetStream
  stream, not local disk.
- NATS gains one new JetStream stream (`TRADERX_ALGO_ENGINE`, created on first boot if absent, same
  idiom as YU04's `TRADERX_CONTROL_ACCOUNT`/`TRADERX_CONTROL_SECURITY`) and one new core-NATS
  subscriber on `/accounts/*/orders`; no existing subject, stream, or publisher changes.

## Networking

- `execution-algo-engine` connects to the existing `nats-broker` service (JetStream + core NATS on
  the same connection), `order-matcher` (`POST /orders`), and `price-publisher`
  (`GET /prices/{ticker}`) — all in-cluster service DNS names, no new ingress route.
- VWAP's `DuckDbVolumeProfileSource` reads directly from `gs://traderx-501015-tick-store` (the same
  bucket YU07's `tick-store` writes to) via DuckDB's native GCS support — same HMAC-credential
  mechanism as `tick-store` (`quickstart.md`), read-only from this state's side.

## Startup / Health Order

1. Generate and verify the inherited `YU07-historical-tick-store` baseline assets.
2. Start MariaDB, NATS, and inherited support services as in the parent state.
3. `execution-algo-engine` connects to NATS, ensures the `TRADERX_ALGO_ENGINE` stream and its
   durable consumer exist, and replays every unacked event to rebuild in-memory parent-order state
   before serving `GET /algo/orders*` traffic.
4. The scheduler loop and the `/accounts/*/orders` subscriber start once replay completes; buckets
   whose `startEpochMs` has already passed during downtime are submitted immediately on the next
   scheduler tick rather than skipped.

## Degraded Behavior

| Condition | Effect |
|---|---|
| NATS unreachable from `execution-algo-engine` at boot | Startup blocks until the broker is reachable (unlike `tick-store`'s retry-without-blocking capture loop) — this service's own state lives in JetStream, so it cannot safely serve `GET /algo/orders*` before replay completes. |
| `order-matcher` unreachable when a bucket is due | The scheduler retries that bucket on its next tick; no partial/duplicate submission occurs because the bucket is only marked submitted after a successful response. |
| `execution-algo-engine` crashes mid-schedule | On restart, JetStream redelivers every unacked event; already-applied events are idempotent (each fully replaces the affected bucket's fields), so replay reconstructs the exact pre-crash state, and the scheduler resumes from there. |
| `price-publisher` unreachable when a bucket is due | The scheduler retries that bucket on its next tick rather than submitting with a stale or fabricated price. |
| `DuckDbVolumeProfileSource` query returns zero rows | Falls back to `SyntheticVolumeProfileSource`'s weights (research.md Decision 7) — VWAP scheduling still completes. |
