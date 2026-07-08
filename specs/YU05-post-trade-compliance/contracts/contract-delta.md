# Contract Delta: YU05 over YU03-in-memory-risk-gateway

All existing order/trade/position/risk-control REST + NATS + UI contracts are retained. The
intentional deltas below are additive except item 1, which is a behavioral tightening of an id that
was previously random and is now deterministic (no client depended on the old randomness — trade
ids were opaque strings already).

## 1. `TradeOrder.id` (NATS `/trade-feed` payload) is now deterministic

Previously `id` carried the *order's* id (`ord-013-NNNN`). Now carries `trd-09b-<tradeSeq>` — the
BLP's deterministic global trade number. `TRADES.ID` in MariaDB is set from this value verbatim
(previously a fresh `UUID.randomUUID()` per delivery). Consumers that treated the id as an opaque
string are unaffected; consumers that parsed the old `ord-013-` prefix (none identified in this
codebase) would need updating.

## 2. Reconciliation read API (new, order-matcher, `/recon/trades/blotter`)

Authenticated (`X-Recon-Control-Token` + `X-Recon-Operator` headers, same pattern as
`/risk/control/*`), never on the command path.

| Method | Path | Query | Effect |
|---|---|---|---|
| `GET` | `/recon/trades/blotter` | `sinceSeq` (long, optional, default 0) | Returns blotter entries with `tradeSeq > sinceSeq`, ascending, capped at `recon.blotter.page-size` (default 1000) per call. |

Unauthorized → **401**.

## 3. Reconciliation status API (new, trade-processor, `/recon/status`)

| Method | Path | Effect |
|---|---|---|
| `GET` | `/recon/status` | Last-sweep summary: cursor position, counts by classification (`matched`, `missingInProjection`, `fieldMismatch`), timestamp of last sweep. |

## 4. Settlement override API (new, trade-processor, `/trades/{id}/settlement`)

Authenticated the same way as recon/risk control.

| Method | Path | Body | Effect |
|---|---|---|---|
| `POST` | `/trades/{id}/settlement/force` | — | Forces `state=Settled`, `settlementdate=now()` immediately, regardless of the T+N schedule. Operator override for ops, not a normal path. |

Unknown trade id → **404**. Already-`Settled` → no-op, **200**.

## 5. Metrics (Prometheus, `/metrics`)

Added (bounded cardinality — no account/security/trade-id labels): `traderx_recon_matched_total`,
`traderx_recon_missing_in_projection_total`, `traderx_recon_field_mismatch_total`,
`traderx_recon_sweep_duration_seconds`, `traderx_settlement_swept_total`,
`traderx_trade_blotter_size`, `traderx_trade_blotter_evictions_total`. All inherited metrics
retained.

## Not changed

Order/position payload shapes and subjects, matching policy, risk-gateway control-plane API,
output-ring topology, projector, UI journeys (settlement/recon UI surfacing is not part of slice 1).
