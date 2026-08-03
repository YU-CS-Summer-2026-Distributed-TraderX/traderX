# Contract Delta: YU08 over YU07-historical-tick-store

All existing order/trade/position/risk/post-trade/EOD/tick-store REST + NATS + UI contracts are
retained. Every delta below is additive.

## 1. New REST API (`execution-algo-engine`, port 18120)

### `POST /algo/orders`

```json
{
  "accountId": 22214,
  "security": "IBM",
  "side": "Buy",
  "quantity": 5000,
  "algoType": "TWAP",
  "durationSeconds": 120,
  "bucketSeconds": 10
}
```

Returns `201` with the created `ParentOrder` (`parentOrderId`, `status=RUNNING`, full `buckets`
schedule per `data-model.md`).

### `GET /algo/orders/{parentOrderId}`

Returns the current `ParentOrder`, including each bucket's submission/fill state.

### `GET /algo/orders`

Returns every known `ParentOrder` (rebuilt from the JetStream event log at boot, per
`research.md` Decision 4).

## 2. `order-matcher` (no change)

`POST /orders` is called by `execution-algo-engine` exactly as it is called by the web front end's
order ticket — same `OrderCreateRequest` shape, same response, same risk-gateway/BLP admission path.
No new field, no new endpoint, no bypass.

## 3. NATS subscriptions (new consumer, `execution-algo-engine`)

| Subject | Delivery | Effect |
|---|---|---|
| `/accounts/*/orders` | broadcast, wildcard | Fill/status updates for known child orders are correlated by `orderId` and folded into the owning parent order's bucket state. |

No subject is published to core NATS by `execution-algo-engine`; no existing publisher or consumer
of `/accounts/*/orders` is modified.

## 4. JetStream stream (new, `execution-algo-engine`)

| Stream | Subject | Storage | Producer | Consumer |
|---|---|---|---|---|
| `TRADERX_ALGO_ENGINE` | `algo.events.>` | File | `execution-algo-engine` (itself) | `execution-algo-engine` (durable consumer `algo-engine-state`) |

## Not changed

Order/trade/position/risk/EOD/tick-store payload shapes and subjects, matching policy, YU05's
settlement/recon/TCA/regulatory APIs, YU06's EOD chain, YU07's tick-store capture/ingestion, the BLP
hot path, UI journeys (no front-end algo panel in v1 — parent orders are REST-only).
