# Data Model: YU08 — Execution Algo Engine

## In-memory model (rebuilt from the JetStream event log, `research.md` Decision 4)

### `ParentOrder`

| Field | Type | Notes |
|---|---|---|
| `parentOrderId` | UUID string | Generated on creation; the correlation id for every child/event. |
| `accountId` | Integer | Same field shape as `OrderCreateRequest.accountId`. |
| `security` | String | Ticker. |
| `side` | `Buy`/`Sell` | Same enum as `order-matcher`'s `OrderSide`. |
| `quantity` | Integer | Total parent quantity. |
| `algoType` | `TWAP`/`VWAP` | Selects the bucket-weighting strategy. |
| `durationSeconds` | Integer | Total schedule window. |
| `bucketSeconds` | Integer | Per-bucket duration; default 10. |
| `status` | `RUNNING`/`COMPLETED` | `COMPLETED` once every bucket is submitted and filled. |
| `createdAt` | Instant | |
| `buckets` | `List<Bucket>` | Ordered by `index`. |

### `Bucket`

| Field | Type | Notes |
|---|---|---|
| `index` | int | 0-based position in the schedule. |
| `startEpochMs` | long | When this bucket becomes due for submission. |
| `targetQuantity` | int | From TWAP's equal split or VWAP's weighted split. |
| `childOrderId` | String, nullable | `order-matcher`'s `orderId`, set once submitted. |
| `clientOrderId` | String | `<parentOrderId>:<index>` — sent on the child request, not read back. |
| `limitPrice` | BigDecimal, nullable | Set at submission time (last price ± 10bps). |
| `submittedAt` | Instant, nullable | |
| `remainingQuantity` | Integer, nullable | Last value observed from `/accounts/*/orders`. |
| `lastExecutionPrice` | BigDecimal, nullable | Last value observed from `/accounts/*/orders`. |
| `filled` | boolean | `true` once `remainingQuantity` reaches 0. |

## JetStream event log (`TRADERX_ALGO_ENGINE` stream, subject `algo.events.>`)

One JSON message per event, in the order applied. Every message carries a `type` discriminator.

| `type` | Payload fields | Applied as |
|---|---|---|
| `ParentOrderCreated` | `parentOrderId`, `accountId`, `security`, `side`, `quantity`, `algoType`, `durationSeconds`, `bucketSeconds`, `buckets:[{index,startEpochMs,targetQuantity}]`, `createdAt` | Inserts a new `ParentOrder` (status `RUNNING`). |
| `ChildOrderSubmitted` | `parentOrderId`, `bucketIndex`, `childOrderId`, `clientOrderId`, `limitPrice`, `submittedAt` | Sets the named bucket's submission fields. |
| `ChildOrderFillObserved` | `parentOrderId`, `bucketIndex`, `remainingQuantity`, `lastExecutionPrice`, `observedAt` | Updates the named bucket's fill fields; sets `filled=true` when `remainingQuantity=0`. |
| `ParentOrderCompleted` | `parentOrderId`, `completedAt` | Sets `ParentOrder.status=COMPLETED`. |

## Config (namespace `algo.*`, environment variables)

| Key | Default | Meaning |
|---|---|---|
| `ALGO_ENGINE_PORT` | `18120` | HTTP port. |
| `NATS_ADDRESS` | `nats://nats-broker:4222` | Broker connection for JetStream events and the `/accounts/*/orders` subscription. |
| `ORDER_MATCHER_URL` | `http://order-matcher:18110` | Child-order submission target (`POST /orders`). |
| `PRICE_SERVICE_URL` | `http://price-publisher:18100` | Reference price for child limit-price derivation. |
| `ALGO_BUCKET_SECONDS_DEFAULT` | `10` | Default `bucketSeconds` when a parent-order request omits it. |
| `ALGO_LIMIT_OFFSET_BPS` | `10` | Aggressive offset applied to the reference price, in basis points. |
| `ALGO_VOLUME_PROFILE_SOURCE` | `synthetic` | `synthetic` or `duckdb` (VWAP only; TWAP ignores this). |
| `ALGO_VOLUME_PROFILE_DUCKDB_PATH` | `gs://traderx-501015-tick-store/ticks` | Parquet root queried by `DuckDbVolumeProfileSource`; only read when the source above is `duckdb`. |

## Reused, unchanged

- `order-matcher`'s `POST /orders` (`OrderCreateRequest`/`OrderResponse`) — no schema change.
- `price-publisher`'s `GET /prices/{ticker}`.
- `/accounts/<accountId>/orders` NATS subject — no publisher change, one new subscriber.
- NATS broker (`nats-broker`) — same connection every other JVM service uses.
