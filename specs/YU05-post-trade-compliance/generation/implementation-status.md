# Implementation Status: YU05-post-trade-compliance

**Status:** All five sub-capabilities implemented and verified locally — deterministic trade
identity (foundational fix), settlement + reconciliation (incl. full-history orphan detection),
regulatory reporting, TCA, and real JWT-based auth/entitlements. Not yet deployed to any staging
namespace (no isolated Cloud Build/Deploy pipeline stood up — requires explicit user go-ahead per
repo convention). FR-PTC42 (entitlement resolution into order admission) is now implemented
(flag-gated, default off — see below); VWAP remains deferred.
**Parent:** `YU03-in-memory-risk-gateway`
**Branch:** `YU05-post-trade-compliance` (isolated worktree — see "Environment notes" below)

## Implemented

### Deterministic trade identity (ADR-022)

- **Live path** (`ProjectorHandler.toTrade()`, order-matcher): already used
  `OrderSnapshot.tradeIdFor(tradeSeq)` correctly and was already idempotent via `INSERT IGNORE` —
  no bug here. The real live gap was settlement (below).
- **Legacy path** (`TradeOrder.fromEvent()` + trade-processor `TradeService`, only active if
  `output.legacy-trades.enabled=true`, default `false`): fixed to match — `tradeIdFor` instead of
  `orderIdFor`; idempotent booking (single + batch) keyed on the id.

### Settlement + reconciliation (ADR-022, FR-PTC01-09)

- **`ProjectorHandler.toTrade()` fix** (the one that matters in production): no longer sets
  `Settled` instantly — sets `Processing` + a real `settlementDate` (`created +
  settlement.t-plus-days` business days, default T+1). `settlementdate` column added to the real
  runtime MariaDB schema (k8s init ConfigMap, not the legacy `database/initialSchema.sql`).
- `SettlementService` (trade-processor): scheduled T+N sweep + `POST /trades/{id}/settlement/force`.
- `TradeBlotter`/`TradeBlotterHandler` (order-matcher): bounded, replay-safe trade record on the
  output ring; `GET /recon/trades/blotter`.
- `ReconciliationService` (trade-processor): scheduled forward sweep, `MATCHED`/
  `MISSING_IN_PROJECTION`/`FIELD_MISMATCH` classification, `GET /recon/status`.

### Full-history orphan detection (FR-PTC10)

- `LmaxEngine.reindexFullHistory()`: on-demand shadow-engine full journal replay (reuses
  `verifyJournalReplay()`'s construction) into an unbounded `TradeBlotter`.
  `POST /recon/full-history/reindex` + `GET /recon/full-history/trades`.
- `ReconciliationService.runOrphanSweep()`: triggers the reindex, diffs every local trade id
  against it, flags `ORPHAN_IN_PROJECTION`. `POST /recon/orphan-sweep` + `GET /recon/orphan-sweep/last`.

### Regulatory reporting (ADR-023, FR-PTC20-22)

- `AuditRecord`/`AuditLogHandler` (order-matcher): captures every reportable output kind (order
  accept/reject/partial-fill/fill/cancel, trade booked), filtered by `OutputEvent.inputSeq` range.
- `LmaxEngine.generateRegulatoryReport(fromSeq, toSeq)` + `GET /regulatory/report`: reuses the
  same shadow-engine replay skeleton as `reindexFullHistory`; reproducible byte-for-byte.

### TCA (ADR-024, FR-PTC30-32)

- `PriceHistoryStore`/`PriceTickHandler` (trade-processor): bounded per-ticker price history fed
  by price-publisher's existing `pricing.*` NATS feed — no new data source, zero BLP involvement.
- `TcaService`: arrival price + TWAP benchmark + signed slippage-bps (positive always means
  "worse than benchmark," regardless of side). `GET /tca/report/{tradeId}`.
- VWAP genuinely deferred (FR-PTC32): the synthetic feed carries no per-tick volume.

### Real auth/entitlements (ADR-025, FR-PTC40/41/42)

- `JwtAuthenticator`/`JwtPrincipal`/`JwtTokenMinter` (order-matcher and trade-processor, separate
  copies — no shared library between the Gradle modules): real HS256 signature verification via
  JDK `javax.crypto` + Jackson. No live OIDC provider in this environment — documented explicitly
  as JWT, not full OIDC.
- Every new endpoint from this state requires a valid JWT: `ReconController`/
  `RegulatoryReportController` (order-matcher, `admin`-only — cross-account data),
  `SettlementController`/`TcaController` (trade-processor, entitled-to-account-or-admin),
  `ReconStatusController`'s orphan-sweep endpoints (trade-processor, `admin`-only). `/risk/control/*`
  (YU03) is unchanged, out of scope for this ADR.
- `ReconciliationService` mints its own long-lived, `admin`-scoped service-account JWT at
  construction for its machine-to-machine calls into order-matcher.
- `POST /auth/dev-token` (trade-processor): local dev/test token minting, own master secret.
- FR-PTC42 (entitlement resolution into order admission) implemented: `EntitlementGate` runs on
  every command entry point (`OrderMatcherService.createOrder`/`createOrderBatch`/`bookMarketTrade`,
  fed the `Authorization` header by `OrderController`/`MarketTradeController`). The resolved JWT
  principal must be entitled to the order's account (401 missing/invalid token, 403 valid-but-
  unentitled, `admin` passes any account). Memory-only check against the token claim — no synchronous
  lookup on the admission path (FR-IMRG01); `GatewayReplicaStore.screen`/`BlpRiskState` untouched.
  Gated by `risk.entitlement.enforced` (default false), so the token-less UI is unaffected until
  enforcement is enabled. Closes FR-IMRG02/FR-IMRG30. Verified: `EntitlementGateTest` 7/7 and the
  full order-matcher suite green (85 tests; the only failure is the pre-existing environmental
  72-byte NGC-01 allocation flake in `AllocationGateTest`, on code this change does not touch —
  the test drives `BlpRiskState`/the disruptor producer directly, not `OrderMatcherService`).

### Observability

- Real Micrometer gauges (trade-processor, mirrors the existing `SystemController` pattern):
  `traderx_recon_matched_total`, `traderx_recon_missing_in_projection_total`,
  `traderx_recon_field_mismatch_total`, `traderx_recon_cursor`, `traderx_recon_orphan_total`,
  `traderx_settlement_swept_total`.
- `traderx-post-trade-compliance.json` Grafana dashboard, added to the same aggregated
  observability ConfigMap every other dashboard in this lineage uses. Order-matcher's blotter
  size/evictions remain JSON-only (order-matcher's own metrics use a separate hand-rolled
  hot-path exporter, not Micrometer — adding a second parallel mechanism there for two counters
  wasn't judged worth the inconsistency).

## State packaging

Spec pack (spec, requirements, ADRs 022-025, architecture, runtime-topology, data-model,
contract-delta, plan, research, tasks, this file); pipeline hooks
(`generate-state-YU05-post-trade-compliance.sh` / `render-state-YU05-post-trade-compliance.sh`);
catalog registration (`state-catalog.json`, `learning-paths.yaml`/`.md`, `specs/README.md`);
runtime-harness wiring (`install-generated-runtime-harness.sh`, `install-generated-ci-assets.sh`,
`scripts/*-state-YU05-*.sh`).

## Verification evidence (2026-07-07, local)

- `bash pipeline/generate-state.sh YU05-post-trade-compliance` exits 0, run repeatedly across every
  change in this state (each new class/endpoint/fix triggered a fresh regenerate+test cycle).
- Every override confirmed live in generated output via grep markers (`tradeIdFor(e.tradeSeq)`,
  `TradeBlotter.java`/`ReconController.java`/`RegulatoryReportController.java`/auth classes present
  under the generated tree, `settlementdate` in the generated database-init ConfigMap, the new
  Grafana dashboard JSON parses and is present in the generated aggregated ConfigMap).
- **order-matcher**: full suite green across every iteration (grew from 54 to 66+ tests as
  `TradeBlotterTest`, `ProjectorHandlerTest`, `AuditLogHandlerTest`, and `JwtAuthenticatorTest`
  were added), 1 pre-existing skip (unrelated). The known intermittent `AllocationGateTest`
  72-byte allocation flake (documented in YU03's own implementation-status.md, ~1-in-3 to 1-in-6
  runs) appeared several times across this session's many regenerate cycles — confirmed
  environmental every time via immediate clean reruns, never a regression.
- **trade-processor**: full suite green (idempotency, settlement, reconciliation, orphan sweep,
  price-history/TWAP math, TCA slippage sign convention, JWT round-trip/tamper/expiry rejection).
- Fixed two real bugs surfaced by the test suite along the way (not masked, fixed at the root):
  (1) two existing `LmaxHotPathParityTest` integration tests asserted instant-`Settled` — updated
  to `Processing`, which is what *proved* the settlement fix landed on the actual live write path,
  not just the code I originally (incorrectly) assumed was live; (2) `JwtTokenMinter`'s TTL
  contract silently treated any non-positive `ttlSeconds` as "non-expiring," which meant an
  intentionally-expired test token was accepted — fixed so `0` means non-expiring and any other
  value (including negative, for tests) computes a real `exp` claim.

## Environment notes (this session)

- **Corrected assumption, not a regression**: initial slice-1 work assumed `TradeOrder.fromEvent`/
  trade-processor's `TradeService` were the live write path. Investigation (confirmed via the
  actual deployed `order-matcher-deployment.yaml` showing `OUTPUT_LEGACY_TRADES_ENABLED=false`,
  and `ProjectorHandler`'s own doc comment) established `ProjectorHandler` is the real writer. The
  fix was relocated there; the original fixes were kept as correct improvements to the legacy path.
- **Isolated worktree**: this state's work was done in a dedicated git worktree
  (`/Users/yaakov/Desktop/Summer 26/lmax/traderX-YU05-post-trade-compliance`, branch
  `YU05-post-trade-compliance`, based off `YU03-in-memory-risk-gateway`) after the shared
  `traderX` working directory was found to be actively branch-switched by a parallel session
  working on `YU04-durable-control-feeds` — generation is single-writer, and an earlier attempt in
  the shared directory had already cost several tracked-file edits when that session checked out
  its own branch mid-session.
- **`~/Desktop` is iCloud-synced**, which caused very slow/occasionally-stalled `git` operations
  (mmap timeouts, stale lock files) during commits earlier in this work — unrelated to the code
  itself, just a filesystem characteristic worth knowing about for future sessions in this repo.

## Still open (next commits of this roadmap item)

- VWAP (FR-PTC32) — needs a real per-tick-volume data source.
- Enable `risk.entitlement.enforced` in a live environment: the gate is wired and tested but
  defaults off; turning it on requires callers (the UI) to send a Bearer token, plus a bench-compare
  on the enforced path (JWT verification per order on the request thread).
- Order-matcher-side Micrometer metrics for the trade blotter (currently JSON-only).
- Full container smoke test and an isolated staging Cloud Build/Deploy pipeline for YU05 — same
  discipline as YU03/YU04, **requires explicit user go-ahead** before touching any live CI/CD
  resource.
