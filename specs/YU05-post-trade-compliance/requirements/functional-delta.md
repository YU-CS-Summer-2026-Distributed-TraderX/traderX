# Functional Delta: YU05-post-trade-compliance over YU03-in-memory-risk-gateway

New requirement namespace `PTC`. Status is for slice 1 (settlement + reconciliation).

| Req | Status | Notes |
|---|---|---|
| FR-PTC01 deterministic trade identity | **Done** | `TradeOrder.fromEvent()` uses `OrderSnapshot.tradeIdFor(tradeSeq)`; MariaDB `TRADES.ID` set from it verbatim, no more `UUID.randomUUID()`. |
| FR-PTC02 settlement state machine | **Done** | `New → Processing` (at booking) `→ Settled` (T+N sweep or forced); `Cancelled` reserved, not yet produced. |
| FR-PTC03 replay-safe trade blotter | **Done** | `TradeBlotterHandler` on the output ring, rebuilt during recovery replay (no snapshot format change needed — see research.md). |
| FR-PTC04 reconciliation classification | **Partial** | `MATCHED` / `MISSING_IN_PROJECTION` / `FIELD_MISMATCH` implemented; `ORPHAN_IN_PROJECTION` deferred (needs full-history blotter, FR-PTC10). |
| FR-PTC05 recon observability | **Done** | `GET /recon/status` + bounded Prometheus counters; no per-trade labels. |
| FR-PTC06 settlement date default + override | **Done** | Default T+1 business day (`settlement.t-plus-days`); `POST /trades/{id}/settlement/force` operator override. |
| FR-PTC07 recon/settlement never mutate journal/BLP | **Done** | Both are trade-processor/MariaDB-side only; no synchronous call into order-matcher's decision path. |
| FR-PTC08 idempotent trade booking | **Done** | Duplicate `TradeOrder.id` delivery is a no-op (checked before insert, same transaction). |
| FR-PTC09 recon read API authenticated | **Done** | `/recon/trades/blotter` requires token + operator header, same pattern as `/risk/control/*`. |
| FR-PTC10 full-history orphan detection | **Deferred** | Needs unbounded/spillover blotter retention or a journal-replay-based comparator; not in slice 1 (documented limitation). |
| FR-PTC20 regulatory audit export | **Deferred** | CAT/TRACE-style flat-record export sourced from the input journal + trade blotter, date-range windowed. |
| FR-PTC21 audit export reproducibility | **Deferred** | Must be byte-for-byte reproducible from journal replay. |
| FR-PTC22 audit export authenticated, off hot path | **Deferred** | Depends on FR-PTC40 (real auth). |
| FR-PTC30 TCA execution-quality computation | **Deferred** | Arrival price / VWAP / TWAP benchmark per settled trade. |
| FR-PTC31 TCA is read-side only | **Deferred** | Never on the order admission path. |
| FR-PTC32 pluggable historical benchmark source | **Deferred** | Synthetic price-publisher history today; real TAQ data when available, same computation contract. |
| FR-PTC40 OIDC principal resolution | **Deferred** | Replaces hardcoded `accountId` params on settlement/recon/reporting/TCA APIs. |
| FR-PTC41 principal-to-account entitlement gating | **Deferred** | Gates which accounts' data a caller may view/act on. |
| FR-PTC42 feeds risk-gateway entitlement replica | **Deferred** | Closes YU03's FR-IMRG02 (partial)/FR-IMRG30 (partial) — the `principalKey` path is already wired, unfed. |
