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
| `/orders` | broadcast | Fill/status updates for known child orders are correlated to a child order and folded into the owning parent order's bucket state. |
| `/accounts/*/orders` | broadcast, wildcard | The same updates, on the tier that publishes this form as well. |

No subject is published to core NATS by `execution-algo-engine`; no existing publisher or consumer
of either subject is modified.

**Both forms are accepted, and the id is read under either name (amended 2026-08-19).** The
single-BLP tier publishes each order update twice — on `/accounts/<id>/orders` and on `/orders` —
with the id in `orderId`. The Aeron cluster tier publishes on `/orders` only, with the id in `id`
and epoch-qualified as `<epoch>-<orderRef>` while this engine holds the bare `orderRef` the
gateway's response returned. This consumer therefore accepts either subject, reads either field
name, and strips a numeric epoch prefix on comparison. Consuming only the first tier's shape is
what made every TWAP parent on the cluster tier stay `RUNNING` for ever; which forms exist is a
property of the tier, not of this engine.

## 4. JetStream stream (new, `execution-algo-engine`)

| Stream | Subject | Storage | Producer | Consumer |
|---|---|---|---|---|
| `TRADERX_ALGO_ENGINE` | `algo.events.>` | File | `execution-algo-engine` (itself) | `execution-algo-engine` (durable consumer `algo-engine-state`) |

## Not changed

Order/trade/position/risk/EOD/tick-store payload shapes and subjects, matching policy, YU05's
settlement/recon/TCA/regulatory APIs, YU06's EOD chain, YU07's tick-store capture/ingestion, the BLP
hot path, UI journeys (no front-end algo panel in v1 — parent orders are REST-only).
