# Contract Delta: YU05 over YU03-in-memory-risk-gateway

All existing order/trade/position/risk-control REST + NATS + UI contracts are retained. The
intentional deltas below are additive except item 1, which is a behavioral tightening of an id that
was previously random and is now deterministic (no client depended on the old randomness — trade
ids were opaque strings already).

Every new endpoint below (items 2-7) is gated by a real HS256 JWT (ADR-025, FR-PTC40/41) —
`Authorization: Bearer <token>` — not a shared static token, superseding an earlier in-session
draft of this state that used `X-*-Control-Token`/`X-*-Operator` headers. Account-scoped endpoints
(settlement force, TCA report) require the caller be entitled to the trade's account, or hold the
`admin` claim; cross-account endpoints (blotter/full-history/orphan-sweep/regulatory-report)
require `admin` unconditionally. `POST /auth/dev-token` (trade-processor) mints tokens for local
development/testing — see ADR-025.

## 1. `TradeOrder.id` (NATS `/trade-feed` payload) is now deterministic

Previously `id` carried the *order's* id (`ord-013-NNNN`). Now carries `trd-09b-<tradeSeq>` — the
BLP's deterministic global trade number. **Applies to the optional legacy `/trades` NATS path**
(`output.legacy-trades.enabled`, default `false`); the live write path (`ProjectorHandler` →
direct MariaDB insert) already used the correct id and needed no change here — see research.md.
Consumers that treated the id as an opaque string are unaffected; consumers that parsed the old
`ord-013-` prefix (none identified in this codebase) would need updating.

## 2. Reconciliation read API (new, order-matcher, `/recon/trades/blotter`)

Requires an `admin` JWT (cross-account data). Never on the command path.

| Method | Path | Query | Effect |
|---|---|---|---|
| `GET` | `/recon/trades/blotter` | `sinceSeq` (long, optional, default 0) | Returns blotter entries with `tradeSeq > sinceSeq`, ascending, capped at `recon.blotter.page-size` (default 1000) per call. |

Missing/invalid/non-admin JWT → **401**.

## 3. Reconciliation status API (new, trade-processor, `/recon/status`)

Open — bounded aggregate counters only, not account-scoped.

| Method | Path | Effect |
|---|---|---|
| `GET` | `/recon/status` | Last-sweep summary: cursor position, counts by classification (`matched`, `missingInProjection`, `fieldMismatch`), timestamp of last sweep. |

## 4. Settlement override API (new, trade-processor, `/trades/{id}/settlement`)

Requires a JWT entitled to the trade's account, or `admin`.

| Method | Path | Body | Effect |
|---|---|---|---|
| `POST` | `/trades/{id}/settlement/force` | — | Forces `state=Settled`, `settlementdate=now()` immediately, regardless of the T+N schedule. Operator override for ops, not a normal path. |

Unknown trade id → **404**. Already-`Settled` → no-op, **200**. Not entitled → **403**.

## 5. Full-history reconciliation API (new, `/recon/full-history/*` and `/recon/orphan-sweep`)

`admin` JWT required (cross-account). See data-model.md for the classification set.

| Method | Path | Where | Effect |
|---|---|---|---|
| `POST` | `/recon/full-history/reindex` | order-matcher | Triggers a full journal replay (expensive, synchronous). Returns `{indexedTrades, evictions}`. |
| `GET` | `/recon/full-history/trades` | order-matcher | Forward-paginated read of the most recent reindex, same shape as the live blotter. |
| `POST` | `/recon/orphan-sweep` | trade-processor | Triggers the reindex above (using its own service-account JWT to call order-matcher), then diffs every local trade id against it. Returns `{sweptAt, localTradeCount, fullHistoryTradeCount, orphanCount, orphanIds}` (ids capped at 500). |
| `GET` | `/recon/orphan-sweep/last` | trade-processor | Last orphan-sweep result, or **404** if none has run yet. |

## 6. Regulatory report API (new, order-matcher, `/regulatory/report`)

`admin` JWT required (an audit trail is never scoped to one account).

| Method | Path | Query | Effect |
|---|---|---|---|
| `GET` | `/regulatory/report` | `fromSeq`, `toSeq` (long, both optional; `toSeq<=0` = to the end) | Journal-replay audit export: one record per order/trade lifecycle event in range. |

## 7. TCA report API (new, trade-processor, `/tca/report/{tradeId}`)

Requires a JWT entitled to the trade's account, or `admin`.

| Method | Path | Effect |
|---|---|---|
| `GET` | `/tca/report/{tradeId}` | Arrival price, TWAP benchmark, signed slippage-bps for one trade. `benchmarkPrice`/`slippageBps` are `null` (not a fabricated 0) when no price history covers the window. Unknown trade id → **404**; not entitled → **403**. |

## 8. Auth token minting API (new, trade-processor, `/auth/dev-token`)

Local development/testing only — no live OIDC provider in this environment. Gated by its own
`auth.dev-token.master-secret` (distinct from `auth.jwt.secret`, the token-verification secret).
A token minted here validates against both order-matcher and trade-processor (shared
`auth.jwt.secret`).

| Method | Path | Body | Effect |
|---|---|---|---|
| `POST` | `/auth/dev-token` | `{subject, accounts: [int], admin: bool, ttlSeconds: long}` | Mints and returns an HS256 JWT. `ttlSeconds<=0` (other than exactly `0`, which means non-expiring) produces an already-expired token. |

## 9. Metrics (Prometheus, trade-processor `/actuator/prometheus`)

Added (bounded cardinality — no account/security/trade-id labels): `traderx_recon_matched_total`,
`traderx_recon_missing_in_projection_total`, `traderx_recon_field_mismatch_total`,
`traderx_recon_cursor`, `traderx_recon_orphan_total`, `traderx_settlement_swept_total`. All via
standard Micrometer `Gauge` registration (mirrors the existing `SystemController` pattern), not a
new mechanism. Order-matcher's blotter size/evictions remain JSON-only (the reindex response) —
order-matcher's own metrics use a separate hand-rolled hot-path Prometheus exporter (see
`RiskMetrics`), and adding a second, parallel Micrometer-based mechanism there for two simple
counters wasn't judged worth the inconsistency. Grafana dashboard: `traderx-post-trade-compliance.json`
(provisioned via the same ConfigMap as every other dashboard in this lineage).

## Not changed

Order/position payload shapes and subjects, matching policy, risk-gateway control-plane API
(`/risk/control/*` still uses the pre-existing shared-token scheme — out of scope for this state,
per ADR-025), output-ring topology, projector's write mechanics (`INSERT IGNORE` unchanged), UI
journeys (settlement/recon/TCA/regulatory UI surfacing is not part of this state).
