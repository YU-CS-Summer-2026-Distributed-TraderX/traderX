---
title: Feature and evidence map
---

# Feature and evidence map

Reviewed against integration revision `4c2c4eb2` on 2026-09-30. The entries describe source behavior and existing test entrypoints. They do not claim that those suites were rerun during this documentation refresh, or that a running installation uses this revision.

## Upstream numbered states

These 14 catalog entries explain the FINOS application's progression. They are separate from the YU lineage; a larger number is not necessarily a successor on the same branch. Follow the generated learning graph for parent and optional branches.

| State | Purpose | Source and learning |
|---|---|---|
| 001 | Simple App - Base Uncontainerized App | [Guide](/docs/learning/state-001-baseline-uncontainerized-parity) · [Specification](/specs/baseline-uncontainerized-parity/spec) |
| 002 | Edge Proxy Uncontainerized | [Guide](/docs/learning/state-002-edge-proxy-uncontainerized) · [Specification](/specs/edge-proxy-uncontainerized/spec) |
| 003 | Agentic Harness Foundation | [Guide](/docs/learning/state-003-agentic-harness-foundation) · [Specification](/specs/agentic-harness-foundation/spec) |
| 004 | Containerized Compose Runtime (NGINX Ingress) | [Guide](/docs/learning/state-004-containerized-compose-runtime) · [Specification](/specs/containerized-compose-runtime/spec) |
| 005 | PostgreSQL Database Replacement | [Guide](/docs/learning/state-005-postgres-database-replacement) · [Specification](/specs/postgres-database-replacement/spec) |
| 006 | Messaging Layer Replacement with NATS | [Guide](/docs/learning/state-006-messaging-nats-replacement) · [Specification](/specs/messaging-nats-replacement/spec) |
| 007 | Observability with LGTM on Compose | [Guide](/docs/learning/state-007-observability-lgtm-compose) · [Specification](/specs/observability-lgtm-compose/spec) |
| 008 | Pricing Awareness and Market Data Streaming | [Guide](/docs/learning/state-008-pricing-awareness-market-data) · [Specification](/specs/pricing-awareness-market-data/spec) |
| 009 | Order Management and Matcher | [Guide](/docs/learning/state-009-order-management-matcher) · [Specification](/specs/order-management-matcher/spec) |
| 010 | Kubernetes Runtime on C2 | [Guide](/docs/learning/state-010-kubernetes-runtime) · [Specification](/specs/kubernetes-runtime/spec) |
| 011 | Tilt Local Dev on Kubernetes | [Guide](/docs/learning/state-011-tilt-kubernetes-dev-loop) · [Specification](/specs/tilt-kubernetes-dev-loop/spec) |
| 012 | Platform Convergence C3 | [Guide](/docs/learning/state-012-platform-convergence-c3) · [Specification](/specs/platform-convergence-c3/spec) |
| 013 | Radius Platform on Kubernetes (Optional) | [Guide](/docs/learning/state-013-radius-kubernetes-platform) · [Specification](/specs/radius-kubernetes-platform/spec) |
| 014 | FDC3 Intent Interoperability on C3 | [Guide](/docs/learning/state-014-fdc3-intent-interoperability) · [Specification](/specs/fdc3-intent-interoperability/spec) |

The docs-portal specification is a website feature pack, not an additional catalogued trading state.

## YU states

Each state inherits the earlier layers selected by its catalog lineage. Later overrides may change earlier behavior; generate the intended tip before testing it. Source filenames below are evidence entrypoints, not test totals.

### YU01: LMAX Sequencer (Trading Hot Path)

Sequences orders on one in-memory thread with a Disruptor ring buffer and a replayable journal. SQL is a read model.

**Evidence:** `Journal and replay tests; test-state-YU01-lmax-sequencer.sh`.

**Boundary:** Local journal recovery is distinct from later replicated consensus.

[Learning guide](/docs/learning/state-YU01-lmax-sequencer) · [Spec pack](/specs/YU01-lmax-sequencer)

### YU02: LMAX Kubernetes

Packages the sequencer for Kubernetes, with snapshots, startup replay and readiness gates.

**Evidence:** `Snapshot tests; test-state-YU02-lmax-kubernetes.sh`.

**Boundary:** The historical single-member tier is retained for study; current cluster operation follows YU12.

[Learning guide](/docs/learning/state-YU02-lmax-kubernetes) · [Spec pack](/specs/YU02-lmax-kubernetes)

### YU03: In-Memory Risk Gateway

Checks credit, size, notional, restricted securities and price collars in memory before admission.

**Evidence:** `BlpRiskStateTest; RiskControlControllerTest`.

**Boundary:** These controls implement specific admission rules, not regulatory certification.

[Learning guide](/docs/learning/state-YU03-in-memory-risk-gateway) · [Spec pack](/specs/YU03-in-memory-risk-gateway)

### YU04: Durable Control Feeds

Publishes account, limit and security changes through transactional outboxes and versioned control feeds.

**Evidence:** `AccountOutboxAtomicityIT; ControlFeedSubscriberTest`.

**Boundary:** Persistence, publication and engine acknowledgement are separate events.

[Learning guide](/docs/learning/state-YU04-durable-control-feeds) · [Spec pack](/specs/YU04-durable-control-feeds)

### YU05: Post-Trade Compliance Bundle

Adds settlement states, journal-to-SQL reconciliation, regulatory export, transaction-cost analysis and scoped post-trade access.

**Evidence:** `SettlementServiceTest; ReconciliationServiceTest; RegulatoryReportDeterminismTest`.

**Boundary:** Post-trade JWT checks do not establish production authentication for the new Desk.

[Learning guide](/docs/learning/state-YU05-post-trade-compliance) · [Spec pack](/specs/YU05-post-trade-compliance)

### YU06: EOD Price Production + Overnight Batch Chain

Closes a versioned EOD price snapshot, checks mark quality and publishes an overnight P&L chain.

**Evidence:** `EodStreamRepairIT; EodSnapshotAndPnlIT`.

**Boundary:** Stale or missing marks require an explicit decision; arrival freshness is not market observation time.

[Learning guide](/docs/learning/state-YU06-eod-price-production) · [Spec pack](/specs/YU06-eod-price-production)

### YU07: Historical Tick Store

Stores historical ticks, queries symbol/time windows and supports replay. Later kdb+/q capture adds engine order and trade history.

**Evidence:** `selfcheck.q; txselfcheck.q; test-state-YU07-historical-tick-store.sh`.

**Boundary:** Historical datasets and licensed raw data are not distributed with the documentation.

[Learning guide](/docs/learning/state-YU07-historical-tick-store) · [Spec pack](/specs/YU07-historical-tick-store)

### YU08: Execution Algo Engine

Slices TWAP parent orders into scheduled children through ordinary risk-gated order ingress.

**Evidence:** `AlgoOrderServiceTest; AlgoEventStoreReplayTest`.

**Boundary:** Scheduling and child execution are separate; a submitted child need not fill.

[Learning guide](/docs/learning/state-YU08-execution-algo-engine) · [Spec pack](/specs/YU08-execution-algo-engine)

### YU09: Ops Hardening

Adds operational configuration for secrets, probes, resource limits and service delivery.

**Evidence:** `test-state-YU09-ops-hardening.sh; Kubernetes manifests`.

**Boundary:** Configuration support does not establish the health or security of a deployed installation.

[Learning guide](/docs/learning/state-YU09-ops-hardening) · [Spec pack](/specs/YU09-ops-hardening)

### YU10: FIX Order-Entry Ingress

Maps FIX 4.4 sessions, order entry, cancellation and status onto the sequenced trading path.

**Evidence:** `FixSessionIntegrationTest; FixGatewayStatusTest`.

**Boundary:** Typed order fields and accepted units follow the current order-type contract.

[Learning guide](/docs/learning/state-YU10-fix-ingress) · [Spec pack](/specs/YU10-fix-ingress)

### YU11: Aeron SBE BLP Replication

Replicates encoded events using Aeron transport and SBE messages, with replay and epoch recovery.

**Evidence:** `test-aeron-loss-replay.sh; test-state-YU11-aeron-replication.sh`.

**Boundary:** Replication alone does not provide the consensus model introduced by YU12.

[Learning guide](/docs/learning/state-YU11-aeron-replication) · [Spec pack](/specs/YU11-aeron-replication)

### YU12: Aeron Cluster BLP Consensus

Runs matching on a three-member Aeron Raft cluster, with leader election, snapshots and archive recovery.

**Evidence:** `ThreeMemberClusterTest; SnapshotRoundTripTest`.

**Boundary:** A successful local recovery case is not an unbounded HA guarantee. Preserve compatible core versions and retained history.

[Learning guide](/docs/learning/state-YU12-aeron-cluster) · [Spec pack](/specs/YU12-aeron-cluster)

### YU13: Crossing Limit-Order Book

Matches by price and time, fills at resting prices, supports cancel and atomic replace, and snapshots the resting book.

**Evidence:** `LimitOrderBookTest; ClOrdIdLedgerTest; OrderTraceTest`.

**Boundary:** Current YU18 adds typed orders and cross-account self-match groups. Tracing drops observations rather than blocking trading.

[Learning guide](/docs/learning/state-YU13-limit-order-book) · [Spec pack](/specs/YU13-limit-order-book)

### YU14: Listed Equity Options

Trades listed equity options through the same book and applies contract multipliers to exposure.

**Evidence:** `test-state-YU14-listed-equity-options.sh; option persistence proof`.

**Boundary:** Trading support is distinct from option valuation, Greeks or exercise processing.

[Learning guide](/docs/learning/state-YU14-listed-equity-options) · [Spec pack](/specs/YU14-listed-equity-options)

### YU15: EOD Risk Extract

Exports un-netted positions and counterparties at one consensus cut with reproducible bytes and receipt identity.

**Evidence:** `RiskExtractTest; RiskReplayDeterminismTest; SharedEodExamplesTest`.

**Boundary:** An extract is an input to a risk engine, not a computed portfolio-risk result.

[Learning guide](/docs/learning/state-YU15-eod-risk-extract) · [Spec pack](/specs/YU15-eod-risk-extract)

### YU16: CDM Instruments

Adds CDM instrument representation and Treasury/corporate-bond terms and pricing inputs.

**Evidence:** `test-state-YU16-cdm-instruments.sh; price-publisher tests`.

**Boundary:** Instrument-level failures do not justify substituting invented marks; per-instrument support differs.

[Learning guide](/docs/learning/state-YU16-cdm-instruments) · [Spec pack](/specs/YU16-cdm-instruments)

### YU17: OTC Interest-Rate Swaps

Books OTC swaps and swaptions beside the matching book in the same consensus log. Adds reference-anchored price bands, a price-derived grid, sequenced CLOSED/PRE_OPEN/OPEN phases, historical tape replay and operator-scoped counters.

**Evidence:** `test-state-YU17-otc-rates.sh; OTC contract tests`.

**Boundary:** Contracts export terms without valuation. Historical replay is not live data or a backtest; tick-rule sides are inferred. Active configuration may instead use an explicitly synthetic offline feed.

[Learning guide](/docs/learning/state-YU17-otc-rates) · [Spec pack](/specs/YU17-otc-rates)

### YU18: Risk Integration

Builds immutable risk bundles, validates dated market inputs and external results, and displays job status and coverage. Components also extend order lifecycle and projection recovery.

**Evidence:** `test-state-YU18-risk-integration.sh; check-yu18-composition.py; component tests`.

**Boundary:** The accepted container path prices a closed synthetic Treasury-bill profile. Production risk remains unavailable.

[Learning guide](/docs/learning/state-YU18-risk-integration) · [Spec pack](/specs/YU18-risk-integration)

## YU18 components

Components extend YU18 without creating another numbered state. Their contracts remain in the parent pack; generation still happens at state level.

| Component | Implemented behavior | Evidence entrypoint | Boundary |
|---|---|---|---|
| eod-integration | Immutable bundles, terms, market-input envelopes, mock/W0 and provisional pricing intake, read-only job status | `scripts/test-state-YU18-risk-integration.sh` | Structural validation, pricing validation and production risk eligibility are distinct |
| risk-service | Consumer schema checks and explicit-input external image smoke | `scripts/ci/risk-container-smoke.py` | External engine source and hosted build wiring remain separately owned |
| risk-pipeline | Synchronous `/eod/price`, private staging, immutable response custody and restart reconciliation | `scripts/ri03-acceptance.py`, `scripts/ri03-recovery.py` | Closed synthetic bill profile; ambiguous submission is UNCERTAIN and is not automatically retried |
| order-types | MARKET, LIMIT, STOP, STOP_LIMIT, ICEBERG, PEGGED and TRAILING_STOP; eligible DAY/GTC/IOC/FOK, sequenced business day | `OrderTypesEngineTest`, `OrderTypesServiceTest`, `OrderTypesBoundaryTest`; `scripts/ri07/order-types-live.py` | Local correctness evidence is separate from the still-open SC-OT35 deployment-profile latency acceptance |
| recovery-identity | Authoritative epoch/run identity and explicit SQL migration/transition | generated `recovery-identity/` tests and migration CLI | Legacy data is not silently attributed to a managed run |
| event-recovery | Explicit archive catch-up with identity, ordering, economics and retained-state checks | generated `recovery-identity/` SQL/live proof | Complete archive and a suitable boundary are required; unexplained balances refuse |
| managed-run-ui | Active/historical scope, pointer rechecks, scoped reads/subscriptions and read-only history | console `managed-run*.spec.ts` and Node reader tests | A selected run is not proof of projection completeness |
| automatic-projection-recovery | Lease-fenced catch-up on startup, reconnect and periodic cadence, with transactional cursor updates | generated `recovery-identity/test-live-automatic-recovery.sh` | Default off; genesis replay per page, below-cursor corruption and transition witness size remain limits |
| demo-acceptance | Reserved-account readiness probes and effect-end lifecycle checks | `scripts/ri07/rig-ready.sh`, `scripts/ri07/order-types-live.py` | These submit orders; use an explicitly reserved disposable environment |
| trader-workspace-combined | Account workspace, Markets, Orders, Positions and Risk; inline ticket and separate admin panels | combined prototype Angular tests; `test-desk-session.mjs`, `test-server.mjs` | Username demo sessions are not production identity; secondary tools remain in Demo console |

Cross-account self-match protection is also present: workspace accounts receive a committed self-match group before admission; same-group resting orders are canceled instead of crossed. FOK excludes same-group liquidity. Group assignments survive snapshots and replay. Public usernames are not proof of beneficial ownership.

[UI workflows](ui-workflows.md) explain what each UI exposes. [Verification commands](testing-strategy.md) distinguish local tests, disposable-container checks and live proofs. The [EOD and recovery guide](integration-and-recovery.md) describes refusal and availability states.
