# Implementation Status: YU03-in-memory-risk-gateway

**Status:** Slice 1 implemented, unit/integration-tested, and live-verified in an isolated staging
k8s namespace — one enforced, journaled, replayable pre-trade risk tier (Gateway screening +
authoritative BLP decision/reservation) with sequenced control events, snapshot v3 recovery,
control-plane API, and journaled startup bootstrap. Deliberately kept off the production
`traderx` namespace pending further validation (durable control feeds, entitlement, latency gates).
**Parent:** `YU02-lmax-kubernetes`
**Branch:** `YU03-in-memory-risk-gateway`

## Implemented (slice 1)

- `risk/` package in order-matcher: `BlpRiskState` (ordered decision pipeline + exposure
  reservation + bounded idempotency), `GatewayReplicaStore` (edge screening replica),
  `RiskReason`/`RiskMetrics`/`RiskRejectedException`/`RiskRejectionBody`/`ReservationHolder`,
  `ReplicaBootstrap` (journal-sequenced account/security universe fetch).
- BLP integration: decide+reserve before book entry, consume on fill, release on cancel,
  market-trade decisions with correlation acks, sequenced control events
  (`TYPE_ACCOUNT/SECURITY/POLICY/RESTRICTION_CONTROL` = 7–10), rejection emission
  (`FLAG_REJECT`/`KIND_ORDER_REJECTED`, `KIND_TRADE_ACCEPTED/REJECTED`).
- No journal/replication wire-format change (type-discriminated payload slots; old journals
  replay unchanged). Snapshot v3 with risk sections + per-order reservations; v1/v2 still load.
- Edge: screening in `OrderMatcherService` (order, batch, market trade), optional
  `clientOrderId`, 422/503 rejection bodies via `RiskExceptionHandler`, `/risk/control/*` admin
  API (token + operator provenance), price-freshness feed into the replica, Prometheus metric
  set (readiness, versions, rejections by reason, decisions, duplicates, mismatch, decision
  latency, reserved notional, control events).
- State packaging: `specs/YU03-in-memory-risk-gateway` (this pack), pipeline hook + render
  scripts, catalog/state-index registration.

## Verification evidence (2026-07-06, local)

- Full order-matcher suite: 48 tests, 47 pass (1 skipped). All correctness tests green,
  including `BlpRiskStateTest`, `GatewayReplicaStoreTest`, `RiskReplayDeterminismTest`,
  `LmaxHotPathParityTest` (all cases, including the binary-tick parity test), and
  `OrderMatcherApplicationTests`. The two H2/projector failures previously seen here
  (`marketTradeBooksAndUpdatesPosition`, `.buyFillIncreasesNetPositionInTheBlp`) were a real
  bug, not environmental — fixed: the test datasource's stale `MODE=PostgreSQL` (pre-dating
  the Postgres->MariaDB migration) plus `src/test/resources/application.properties` silently
  shadowing the main naming-strategy override. See `risk-gateway-prod-hardening`
  history for the root-cause writeup.
- `AllocationGateTest.hotPathIsAllocationFreeInSteadyStateWithRiskGating()`: real
  `BlpRiskState` wired into the BLP, every ORDER_NEW runs `decideAndReserve` — zero
  steady-state allocation holds with risk gating on. `noGcTest` (Epsilon-GC, never
  reclaims — any steady-state allocation crashes the run) passes with risk gating on. The
  known intermittent 72-byte producer-thread allocation (JIT/GC warmup noise, ~1-in-3 to
  1-in-6 runs on this machine) is unchanged by risk gating — same signature with or
  without it; still environmental, not a regression.
- Live end-to-end: deployed to an isolated `traderx-yu03-staging` k8s namespace (separate
  Cloud Build trigger + Cloud Deploy pipeline, approval-gated, never touches production) and
  exercised on the real GKE production cluster's order-matcher directly — a submitted order
  with an off-market limit price was correctly rejected with `PRICE_COLLAR`, reflected live in
  `traderx_gateway_rejections_total{reason="price_collar"}`.

## Still open (next commits of this roadmap item)

- Durable account-service / reference-data control feeds (outbox → JetStream) with
  watermarked subscribe-buffer-snapshot bootstrap and gap/epoch invalidation
  (FR-IMRG04/05/32/33/34) — replaces the slice-1 one-shot REST bootstrap.
- Entitlement feeding (blocked on the real-auth roadmap item).
- Alert rules for the risk metric set (NFR-IMRG08) — the dashboard (`traderx-risk-gateway.json`,
  provisioned in the Grafana dashboards ConfigMap) is done; alert thresholds are a paging-policy
  decision, not defined yet.
- p99 latency CI gate over the risk path (NFR-IMRG01/13) — metrics are exported and
  observable, but no automated threshold exists yet (needs a target number + hardware
  baseline decision). The allocation-freedom half of perf-profile acceptance is done: see
  "Verification evidence" below.
- UI: surface rejection reasons + clientOrderId plumbing (FR-IMRG44).
