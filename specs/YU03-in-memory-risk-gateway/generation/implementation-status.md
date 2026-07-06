# Implementation Status: YU03-in-memory-risk-gateway

**Status:** Slice 1 implemented and unit/integration-tested — one enforced, journaled, replayable
pre-trade risk tier (Gateway screening + authoritative BLP decision/reservation) with sequenced
control events, snapshot v3 recovery, control-plane API, and journaled startup bootstrap.
**Parent:** `YU02-lmax-kubernetes`
**Branch:** `risk-gateway-forward-port`

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

- Full order-matcher suite: 47 tests, 45 pass. New suites all green:
  `BlpRiskStateTest` (precedence, reserve/consume/release exactly-once, idempotency,
  snapshot-tuple restore), `GatewayReplicaStoreTest` (screening, fail-closed, collar),
  `RiskReplayDeterminismTest` (identical replay + snapshot-v3-plus-tail identical state).
- The two failures (`LmaxHotPathParityTest.marketTradeBooksAndUpdatesPosition`,
  `.buyFillIncreasesNetPositionInTheBlp`) are PRE-EXISTING on this machine: they fail
  identically on pristine parent code (H2 projector persistence timing/environment; the
  in-BLP assertions — including the new risk-gated paths — pass). `AllocationGateTest`
  intermittently reports a constant 72-byte producer allocation on this machine on pristine
  code too (2/6 pristine-base runs); treat as environmental until reproduced in CI.

## Still open (next commits of this roadmap item)

- Durable account-service / reference-data control feeds (outbox → JetStream) with
  watermarked subscribe-buffer-snapshot bootstrap and gap/epoch invalidation
  (FR-IMRG04/05/32/33/34) — replaces the slice-1 one-shot REST bootstrap.
- Entitlement feeding (blocked on the real-auth roadmap item).
- Grafana dashboard/alert assets for the new metric set (NFR-IMRG08).
- Perf-profile acceptance: `noGcTest` + latency gates over the risk path (NFR-IMRG01/02/13).
- k8s manifest env plumbing for `RISK_*` knobs (defaults are live-safe; overrides optional).
- UI: surface rejection reasons + clientOrderId plumbing (FR-IMRG44).
