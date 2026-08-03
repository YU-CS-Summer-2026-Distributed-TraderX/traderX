# Contract Delta: YU03 over YU02-lmax-kubernetes

The external `YU01`/`YU02` order/trade/position REST + NATS + UI contracts are retained
(FR-IMRG42). The intentional admission deltas are below; everything else is unchanged.

## 1. Optional `clientOrderId` (idempotency, FR-IMRG14)

`POST /orders` (`OrderCreateRequest`) and `POST /trades` (`MarketTradeRequest`) accept an optional
`clientOrderId` string. Hashed at the edge (FNV-1a → 64-bit key; 0 = absent). A duplicate key
returns the original decision without creating/reserving a second order or booking a second trade.
Absent key = no retry mapping. Existing clients that omit it are unaffected.

## 2. Rejection response body (`RiskRejectionBody`)

A risk rejection returns a stable body instead of a generic error:

```json
{ "clientOrderId": "abc-123", "decision": "REJECTED",
  "reason": "CREDIT_LIMIT", "policyVersion": 7, "commandSequence": -1 }
```

Status codes (`RiskExceptionHandler`):
- **422 Unprocessable Entity** — a policy/limit/state rejection (e.g. `CREDIT_LIMIT`, `RESTRICTED`,
  `KILL_SWITCH`, `POSITION_LIMIT`, `ORDER_SIZE`, `PRICE_STALE`).
- **503 Service Unavailable** — control state not ready/stale (`CONTROL_STATE_STALE`); retryable.

`reason` is one of the stable `RiskReason` codes (see `data-model.md`).

## 3. Market trade becomes synchronous (FR-IMRG20)

`POST /trades` previously booked fire-and-forget (echoed the request without waiting). It now
**blocks for the BLP's sequenced accept/reject** and returns the request on accept or a
`RiskRejectionBody` (422) on reject. This is the one behavioral tightening: a rejected market trade
is no longer silently dropped. Any future asynchronous mode must return `202` with a command id
rather than a premature `200`.

## 4. Risk control-plane API (new, `/risk/control`)

Authenticated (`X-Risk-Control-Token` + `X-Risk-Operator` headers), never on the command path.
Each mutation is applied to the replica AND sequenced as a journaled control event.

| Method | Path | Body | Effect |
|---|---|---|---|
| `GET` | `/risk/control/snapshot` | — | current replica image |
| `POST` | `/risk/control/account` | `{accountId, enabled}` | enable/disable account |
| `POST` | `/risk/control/security` | `{ticker, enabled, halted}` | security trading status |
| `POST` | `/risk/control/policy` | `{policyVersion, killSwitch, maxPositionQuantity?, maxConcentrationNotionalTicks?}` | policy + kill switch (+ optional limits) |
| `POST` | `/risk/control/restriction` | `{ticker, restricted}` | restrict; cancels resting orders via sequenced CANCEL (FR-IMRG24) |

Unauthorized → **401**. Unknown security on restriction → **400**.

## 5. Metrics (Prometheus, `/metrics`, FR-IMRG43)

Added (bounded cardinality — no account/security/principal labels): `traderx_replica_ready`,
`traderx_replica_source_version`, `traderx_replica_high_watermark`, `traderx_gateway_rejections_total{reason}`,
`traderx_risk_decisions_total{reason}`, `traderx_idempotency_duplicate_total`,
`traderx_gateway_blp_mismatch_total`, `traderx_gateway_validation_latency_seconds`,
`traderx_risk_decision_latency_seconds`, `traderx_risk_reserved_notional_total`,
`traderx_risk_control_events_total`, `traderx_risk_policy_version`. All inherited metrics retained.

## Not changed

Order/trade/position payload shapes and subjects, matching policy, output-ring topology, projector,
UI journeys (beyond surfacing rejection reasons, deferred).
