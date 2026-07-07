# Functional Delta: YU05-post-trade-compliance over YU03-in-memory-risk-gateway

New requirement namespace `PTC`.

| Req | Status | Notes |
|---|---|---|
| FR-PTC01 deterministic trade identity | **Done** | Live path: `ProjectorHandler.toTrade()` already used `OrderSnapshot.tradeIdFor(tradeSeq)` correctly. Legacy path: `TradeOrder.fromEvent()` fixed to match (was using `orderIdFor`); trade-processor's `TradeService` no longer mints `UUID.randomUUID()`. See research.md for which path is actually live. |
| FR-PTC02 settlement state machine | **Done** | Fixed in `ProjectorHandler.toTrade()` (the live writer): `Processing` (at booking, real settlementDate computed) `→ Settled` (T+N sweep or forced); `Cancelled` reserved, not yet produced. |
| FR-PTC03 replay-safe trade blotter | **Done** | `TradeBlotterHandler` on the output ring, rebuilt during recovery replay (no snapshot format change needed — see research.md). |
| FR-PTC04 reconciliation classification | **Done** | `MATCHED` / `MISSING_IN_PROJECTION` / `FIELD_MISMATCH` (forward sweep) + `ORPHAN_IN_PROJECTION` (full-history sweep, FR-PTC10). |
| FR-PTC05 recon observability | **Done** | `GET /recon/status` + bounded Prometheus counters; no per-trade labels. |
| FR-PTC06 settlement date default + override | **Done** | Default T+1 business day (`settlement.t-plus-days`, set in both order-matcher and trade-processor); `POST /trades/{id}/settlement/force` operator override. |
| FR-PTC07 recon/settlement never mutate journal/BLP | **Done** | Settlement/recon-sweep writes are MariaDB-side only; full-history reindex and regulatory reports are read-only shadow replays, never touch the live BLP/journal. |
| FR-PTC08 idempotent trade booking | **Done** | Duplicate `TradeOrder.id` delivery is a no-op in trade-processor's legacy path (checked before insert); live path already idempotent via `INSERT IGNORE`. |
| FR-PTC09 recon read API authenticated | **Done** | `/recon/*` requires an `admin` JWT (ADR-025) — superseded the initial token+operator header draft. |
| FR-PTC10 full-history orphan detection | **Done** | `POST /recon/full-history/reindex` (order-matcher, on-demand full journal replay via shadow engine) + `POST /recon/orphan-sweep` (trade-processor, cross-checks every local trade id). |
| FR-PTC20 regulatory audit export | **Done** | `GET /regulatory/report?fromSeq=&toSeq=` — CAT/TRACE-style flat-record export sourced from journal replay (`AuditLogHandler`), not the MariaDB projection. |
| FR-PTC21 audit export reproducibility | **Done** | Pure function of (journal range, seed) — no wall-clock, no external query; same inputs always produce the same records. |
| FR-PTC22 audit export authenticated, off hot path | **Done** | Requires an `admin` JWT (FR-PTC40/41); never on the BLP's admission path. |
| FR-PTC30 TCA execution-quality computation | **Done** | Arrival price + TWAP benchmark + signed slippage-bps per trade, via `GET /tca/report/{tradeId}`. |
| FR-PTC31 TCA is read-side only | **Done** | Entirely in trade-processor; never calls into order-matcher's admission path. |
| FR-PTC32 pluggable historical benchmark source | **Partial** | TWAP implemented against `PriceHistoryStore` (fed by price-publisher's `pricing.*` feed); VWAP deferred — synthetic feed carries no per-tick volume to weight by. Real TAQ data would supply both without changing `TcaService`'s contract. |
| FR-PTC40 principal resolution | **Done (JWT, not OIDC)** | `JwtAuthenticator` — real HS256 signature verification, no live IdP in this environment. Replaces hardcoded `accountId` params on settlement/recon/reporting/TCA APIs. See ADR-025 "Implementation note." |
| FR-PTC41 principal-to-account entitlement gating | **Done** | `JwtPrincipal.isEntitledTo(accountId)` gates settlement-force/TCA (account-scoped) and blotter/full-history/orphan-sweep/regulatory-report (`admin`-only, cross-account). |
| FR-PTC42 feeds risk-gateway entitlement replica | **Deferred** | Needs wiring into order *submission* (`OrderMatcherService`/`GatewayReplicaStore`) — a hot-path-adjacent surface this state deliberately never touched (FR-PTC07). The `principalKey` path is already wired in YU03, still unfed. |
