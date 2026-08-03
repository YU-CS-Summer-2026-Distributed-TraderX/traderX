# Feature Specification: In-Memory Risk Gateway

**Feature Branch**: `YU03-in-memory-risk-gateway`
**Created**: 2026-07-06
**Status**: Implemented
**Input**: Forward-port of the pre-k8s `in-memory-risk-gateway` design, re-based as a delta over the
`YU02-lmax-kubernetes` runtime (HA replication, k8s Lease leader election, MariaDB projection,
snapshot + journal-tail recovery). The `FR-IMRG*`/`NFR-IMRG*` requirement namespace is inherited
verbatim; per-requirement status is tracked in `requirements/nonfunctional-delta.md`, and
`requirements/functional-delta.md` describes the functional delta in prose.

This state adds the pre-trade admission tier the system was missing: before it, an order with a
valid ticker simply matched and filled — no credit, buying-power, exposure, restriction, or
kill-switch control existed anywhere. It keeps the two-tier model of the original design (ADR-018)
and the SEC Rule 15c3-5 Market Access control baseline: an in-process Gateway replica screens
preliminarily against event-fed local state with no synchronous lookup, and the single-writer BLP
makes the authoritative decision and reserves exact aggregate exposure in global sequence order.
The SEC Market Access Rule is a control-requirements baseline only; this state does not claim
regulatory compliance.

## User Stories

- As a risk operator, I want every order screened for credit, size, notional, and price sanity
  before it can match, so a fat-finger or over-limit order is stopped rather than filled.
- As a risk operator, I want to administer account, security, policy, and restriction controls
  (including a kill switch) through an authenticated API and have them take effect deterministically.
- As a risk operator, I want restricting a security to cancel its resting orders, so a control
  change is enforced against orders already in the book, not just new ones.
- As a compliance reviewer, I want every accepted and rejected decision to be journaled and to
  replay identically from snapshot + journal, so any past admission decision can be reproduced for
  audit with no dependence on live external state.
- As a trader, I want a rejected order or trade to come back with a stable, specific reason rather
  than silently failing or filling.
- As a platform engineer, I want admission screening to add no synchronous call and no steady-state
  allocation to the hot path, so risk control does not cost throughput or determinism.
- As a platform engineer, I want the risk tier's health (decisions, rejections by reason, decision
  latency, replica readiness, reserved exposure) visible in Grafana.

## Functional Requirements

- FR-IMRG01: The state SHALL make no synchronous REST or database lookup on the order-admission
  path; edge screening and the BLP decision read only in-memory state. The startup account/security
  universe fetch runs cold-path, before readiness.
- FR-IMRG06: The Gateway replica SHALL screen every order, batch, and market trade against account
  status, security status, restrictions, kill switch, size, notional, price freshness, and price
  collar before the command enters the input sequence.
- FR-IMRG07: Gateway screening SHALL be preliminary; the BLP SHALL repeat every mutable and
  aggregate check in global sequence order, and the BLP decision SHALL be authoritative.
- FR-IMRG08: Security ids SHALL come from reference-data via the control plane. An order for an
  unknown ticker SHALL reject at the edge (`UNKNOWN_SECURITY`); order flow SHALL NOT mint
  symbol-table entries.
- FR-IMRG09: The Gateway SHALL hold a fixed-point price and source time per security, distinguish a
  missing price from a zero price, and reject on staleness.
- FR-IMRG10: Commands SHALL be journaled before the BLP decides (inherited journal-before-BLP gate).
- FR-IMRG11: Control events (account, security, policy, restriction) SHALL enter the same global
  input sequence as commands and prices — versioned, journaled, and replicated (type ids 7–10).
- FR-IMRG12: The BLP decision SHALL run an ordered pipeline with stable precedence: kill switch →
  account known/enabled → security known/enabled → restriction → quantity/size → price present/fresh
  → notional → credit limit (reserve) → position limit → concentration.
- FR-IMRG13: The BLP SHALL perform check-and-reserve as one single-threaded operation before book
  entry.
- FR-IMRG14: A duplicate `clientOrderId` SHALL return the original decision without creating or
  reserving a second order; the key is optional and its absence means no retry mapping. Idempotency
  retention is bounded with an eviction frontier.
- FR-IMRG15: Each decision SHALL carry a stable reason code on the order lifecycle event and the
  trade-decision ack.
- FR-IMRG16: An accepted order SHALL reserve `quantity × limitPx` against the account; the
  reservation SHALL be consumed pro-rata on fill and released on cancel/eviction — exactly once,
  never negative.
- FR-IMRG17: The BLP SHALL decide against the last sequenced price and event-carried time, never
  substituting zero for a missing price.
- FR-IMRG19: When Gateway and BLP disagree, the BLP SHALL win and the disagreement SHALL be counted
  (`traderx_gateway_blp_mismatch_total`).
- FR-IMRG20: An order or market trade SHALL be reported successful only after the BLP accepts it.
  `POST /trades` SHALL block for the sequenced decision and return a stable 422 (or 503 when control
  state is stale/not-ready) rejection body on rejection.
- FR-IMRG21: Risk state — policy, per-account control/executed exposure, per-security
  control/prices, idempotency, and per-order reservations — SHALL be captured in the snapshot
  (format v3; v1/v2 snapshots still load).
- FR-IMRG22: Snapshot + journal replay SHALL reproduce every original acceptance or rejection with
  no external query.
- FR-IMRG23: A rejected command SHALL stay journaled for audit/replay but SHALL NOT enter the book,
  move a position, or emit a market-facing event; a rejected order emits status only, a rejected
  trade emits only its correlation ack.
- FR-IMRG24: Restricting a security SHALL cancel its resting orders via sequenced CANCEL events;
  other policy changes SHALL retain resting orders.
- FR-IMRG26: All risk tables SHALL be preallocated and bounded; exhaustion SHALL reject (`CAPACITY`)
  or evict (idempotency frontier), never grow unbounded.
- FR-IMRG27: The BLP's only side-effect channel SHALL remain the inherited output ring; rejections
  and decisions are output events, with no direct NATS or database write.
- FR-IMRG30: The `/risk/control/*` administration API SHALL require an authentication token and
  record the calling operator's provenance.
- FR-IMRG40: The inherited ring topology, journal-before-BLP gate, matching policy, output handlers,
  projector, and NATS subjects SHALL be unchanged.
- FR-IMRG41: The MariaDB read model SHALL remain a projection; no admission state SHALL be read
  from it.
- FR-IMRG42: The admission API SHALL retain parity except for the optional `clientOrderId` field,
  the rejection body, and the synchronous market-trade decision.
- FR-IMRG43: The state SHALL export a bounded metric set (readiness, control versions, rejections by
  reason, decisions, duplicates, gateway/BLP mismatch, decision latency, reserved notional, control
  events), visualized in a provisioned Grafana dashboard.
- FR-IMRG44: The UI SHALL surface the rejection reason for both BLP-level and edge-level rejections
  and offer an optional Client Order ID field on the order ticket.
- FR-IMRG45: The output ring SHALL gain only two new event kinds (order-rejected, trade decision),
  with no redesign.

## Non-Functional Requirements

- NFR-IMRG01: Gateway screen and BLP decision p99 SHALL stay within a latency budget, asserted by a
  CI gate (5µs threshold on dev hardware, ~5–8× the observed 600–950ns p99).
- NFR-IMRG02: The decision path SHALL make no steady-state heap allocation — preallocated primitive
  arrays, no boxing/iterator/lambda on the BLP decision path — verified under Epsilon-GC.
- NFR-IMRG03: Decisions SHALL be pure functions of sequenced events and fixed config, replaying
  deterministically.
- NFR-IMRG04: The BLP thread SHALL add no lock, atomic, clock read, or randomness; decision time is
  event-carried.
- NFR-IMRG05: Risk state SHALL restore from snapshot v3 + journal tail in one pass over snapshot
  rows.
- NFR-IMRG06: A not-ready replica SHALL reject admission with 503 `CONTROL_STATE_STALE`.
- NFR-IMRG10: Metric cardinality SHALL stay bounded — reason and replica labels only, never
  account, security, or principal labels.
- NFR-IMRG11: The inherited build/publish/deploy harness SHALL be unchanged; this state is
  order-matcher overrides only.
- NFR-IMRG12: No new runtime dependency SHALL be added (JDK HttpClient plus the existing
  Jackson/HdrHistogram).

## Success Criteria

- SC-IMRG01: Generation hook exists and is runnable (`pipeline/generate-state-YU03-in-memory-risk-gateway.sh`).
- SC-IMRG02: State smoke test path is defined (`scripts/test-state-YU03-in-memory-risk-gateway.sh`).
- SC-IMRG03: Unit tests validate the BLP decision pipeline and reservation lifecycle
  (`BlpRiskStateTest`), edge screening (`GatewayReplicaStoreTest`), and replay determinism
  (`RiskReplayDeterminismTest`).
- SC-IMRG04: The allocation gate (`AllocationGateTest`) runs the real `BlpRiskState` on every
  ORDER_NEW under Epsilon-GC and holds zero steady-state allocation with risk gating on.
- SC-IMRG05: The p99 latency CI gate asserts both the BLP `decideAndReserve` path and the edge
  `screen()` path under the budget.
- SC-IMRG06: A Grafana dashboard is provisioned for the risk-gateway metric set
  (`traderx-risk-gateway.json`).
- SC-IMRG07: Generated shared files retain every ancestor state's content alongside this state's
  additions.
