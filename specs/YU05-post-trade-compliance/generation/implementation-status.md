# Implementation Status: YU05-post-trade-compliance

**Status:** Slice 1 (deterministic trade identity, settlement, reconciliation) implemented and
unit/integration-tested locally. Regulatory reporting, TCA, and real auth/entitlements are
specified (requirements, data model, ADRs) but not yet implemented — see "Still open" below.
Not yet deployed to any staging namespace (no isolated Cloud Build/Deploy pipeline stood up for
this state yet — requires explicit user go-ahead per repo convention).
**Parent:** `YU03-in-memory-risk-gateway`
**Branch:** `YU03-in-memory-risk-gateway` (spec-pack authored here; not yet split to its own branch)

## Implemented (slice 1)

- **Deterministic trade identity** (order-matcher): `TradeOrder.fromEvent()` now sets `id =
  OrderSnapshot.tradeIdFor(e.tradeSeq)` instead of `orderIdFor(e.orderRef)` — wiring up a helper
  that already existed but was never called at the one place that mattered.
- **Trade blotter** (order-matcher, `lmax/TradeBlotter` + `lmax/TradeBlotterHandler`): bounded
  (`recon.blotter.capacity`, default 500,000), replay-safe in-memory record of every
  `KIND_TRADE_BOOKED` event, wired into `LmaxEngine`'s output-ring handler chain.
- **Recon read API** (order-matcher, `controller/ReconController`): `GET
  /recon/trades/blotter?sinceSeq=`, authenticated via `X-Recon-Control-Token`/`X-Recon-Operator`
  (same pattern as `/risk/control/*`).
- **Idempotent trade booking** (trade-processor, `TradeService`): `processTrade`/`processTrades`
  now check the deterministic id before booking; a duplicate delivery is a logged no-op instead of
  double-booking a position. Fixed a real, pre-existing shortcut along the way: `bookTrade` had a
  comment reading "Booking is synchronous and always settles immediately in this model" and set
  `TradeState.Settled` directly — there was no real settlement lifecycle at all before this slice.
- **Settlement state machine** (trade-processor, `SettlementService`): a booked trade now starts
  `Processing` with `settlementDate = created + settlement.t-plus-days business days` (default
  T+1); a scheduled sweep (`@Scheduled`, default every 5s) advances due trades to `Settled`;
  `POST /trades/{id}/settlement/force` (via `SettlementController`) is the operator override.
- **`settlementdate` column**: added to the *real* runtime MariaDB schema — the k8s init
  ConfigMap (`kubernetes-runtime/manifests/base/database-init-configmap.yaml`), not the legacy
  `database/initialSchema.sql` at the repo root (confirmed by diffing the two; they differ
  materially — MariaDB vs. Postgres flavor, different column casing, different batch-tuning
  comments — the ConfigMap is what `prepare-state-*-gke-manifests.sh` actually renders).
- **Reconciliation sweep** (trade-processor, `ReconciliationService`): scheduled (default every
  10s) HTTP poll of order-matcher's blotter (JDK `HttpClient` + Jackson, no new dependency, same
  pattern as order-matcher's own `ReplicaBootstrap`), classifying each entry as `MATCHED`,
  `MISSING_IN_PROJECTION`, or `FIELD_MISMATCH` against the local MariaDB row; forward cursor
  persisted in-memory. `GET /recon/status` (via `ReconStatusController`) exposes the summary.
- **Tests**: `TradeBlotterTest` (order-matcher: capture keyed by deterministic id, ascending
  pagination, bounded oldest-first eviction, handler correctness including non-trade-kind
  filtering), `TradeServiceIdempotencyTest` (duplicate-delivery no-op for both single and batch
  booking paths, T+N business-day computation), `SettlementServiceTest` (sweep + force-settle
  transitions and edge cases), `ReconciliationServiceTest` (real HTTP + JSON path against a JDK
  `HttpServer` stub, proving all three classifications, cursor advancement across sweeps, and
  graceful skip-on-unreachable behavior).
- **Fixed one existing test's stale assumption**: `OutputDisruptorHandlersTest.tradeSubmitPublishesTradeBookedOnlyToTrades()`
  asserted the old `ord-013-0042` id format; updated to `trd-09b-7` (the fixture's `tradeSeq`),
  matching the intentional, documented behavioral tightening (contract-delta.md #1) — not a
  masked regression.
- **State packaging**: this spec pack, pipeline hooks (`generate-state-YU05-post-trade-compliance.sh`
  / `render-state-YU05-post-trade-compliance.sh`), catalog registration
  (`state-catalog.json`, `learning-paths.yaml`), runtime-harness wiring
  (`install-generated-runtime-harness.sh`, `install-generated-ci-assets.sh`, `scripts/*-state-YU05-*.sh`).

## Verification evidence (2026-07-06, local)

- `bash pipeline/generate-state.sh YU05-post-trade-compliance` exits 0 (verified against an
  isolated `TRADERX_GENERATED_ROOT` to avoid colliding with a concurrent generation run from a
  parallel session working on `YU04-durable-control-feeds` against the shared `generated/`
  directory — generation is documented as exclusive/single-writer, so this state's own
  verification used a separate output root rather than waiting indefinitely or interrupting the
  other session).
- Confirmed every override is actually live in the generated output (not just present in
  `runtime-overrides/`) by grepping the generated tree directly: `tradeIdFor(e.tradeSeq)` in the
  generated `TradeOrder.java`, `TradeBlotter.java`/`TradeBlotterHandler.java`/`ReconController.java`
  present under the generated `order-matcher` tree, `settlementdate` present in the generated
  database-init ConfigMap.
- **order-matcher**: full suite green — 58 tests (54 pre-existing + 4 new `TradeBlotterTest`
  cases), 1 skipped (pre-existing, unrelated). One transient failure seen on a single run
  (`AllocationGateTest.hotPathIsAllocationFreeInSteadyStateWithRiskGating`, "72 bytes") —
  confirmed environmental, not a regression, by rerunning 3x (all green) and by matching exactly
  the flake YU03's own `implementation-status.md` already documented ("known intermittent 72-byte
  producer-thread allocation... ~1-in-3 to 1-in-6 runs on this machine").
- **trade-processor**: `TradeBlotterTest`-equivalent suite — `TradeServiceIdempotencyTest` (4/4),
  `SettlementServiceTest` (5/5), `ReconciliationServiceTest` (3/3) all green; full `./gradlew test`
  build successful. (Note: `TradeProcessorApplicationTests` lives under the non-standard
  `src/main/test/` path in this codebase, pre-dating this state, and is not picked up by Gradle's
  default `test` source set either with or without this state's changes — not a regression
  introduced here.)

## Still open (next commits of this roadmap item)

- Full-history `ORPHAN_IN_PROJECTION` detection (FR-PTC10) — needs unbounded/spillover blotter
  retention or a direct journal-replay comparator; the current blotter is bounded and forward-only.
- Regulatory reporting (FR-PTC20/21/22, ADR-023) — journal-sourced CAT/TRACE-style audit export.
- TCA (FR-PTC30/31/32, ADR-024) — execution-quality computation behind a pluggable benchmark
  source (synthetic today, real TAQ data later).
- Real auth/entitlements (FR-PTC40/41/42, ADR-025) — OIDC principal resolution gating every
  settlement/recon/reporting/TCA API; currently gated by the same shared-token stopgap as
  `/risk/control/*`.
- Grafana dashboard for the settlement/recon metric set (mirrors YU03's `traderx-risk-gateway.json`).
- Full container smoke (order fill → blotter → recon sweep → settlement sweep against a real
  MariaDB) and isolated staging Cloud Build/Deploy pipeline — **requires explicit user go-ahead**
  before touching any live CI/CD resource, same rule as every prior state.
