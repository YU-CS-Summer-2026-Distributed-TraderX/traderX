# Functional Delta: YU03-in-memory-risk-gateway over YU02-lmax-kubernetes

Requirement ids inherited from the original `in-memory-risk-gateway` spec (readable via
`git show in-memory-risk-gateway:specs/in-memory-risk-gateway/spec.md`). Status is for slice 1.

| Req | Status | Notes |
|---|---|---|
| FR-IMRG01 no sync lookup on admission | **Done** | Screening + BLP decision are memory-only; the startup bootstrap fetch is cold-path, before readiness. |
| FR-IMRG02 gateway replicas | **Partial** | Security/account/restriction/kill-switch/limits/price-freshness replicas in-process; entitlement replica deferred to the auth roadmap item. |
| FR-IMRG03 versioned replica records | **Partial** | Monotonic versions assigned internally at the single control plane; per-source epochs/gap invalidation arrive with the durable feeds. |
| FR-IMRG04 subscribe-buffer-snapshot bootstrap | **Deferred** | Slice 1 uses one-shot snapshot fetch sequenced through the journal (single co-located control plane; no delta stream yet to race). |
| FR-IMRG05 readiness at high watermark | **Partial** | Fail-closed until id alignment + bootstrap complete; watermark semantics come with the durable feeds. |
| FR-IMRG06 local preliminary screening | **Done** | Account/security/restriction/kill-switch/size/notional/price-freshness/collar at the edge. |
| FR-IMRG07 gateway pass is preliminary | **Done** | BLP repeats every mutable/aggregate check in sequence order. |
| FR-IMRG08 authoritative security ids | **Done** | Unknown tickers reject at the edge (UNKNOWN_SECURITY); order flow can no longer mint symbol-table entries — ids come from reference-data via the bootstrap/control plane. |
| FR-IMRG09 price freshness state | **Done** | Fixed-point price + source time; missing price is distinguishable from zero; staleness rejects. |
| FR-IMRG10 commands journaled before decision | **Done** | Inherited journal-before-BLP gate unchanged. |
| FR-IMRG11 control events in global sequence | **Done** | TYPE_ACCOUNT/SECURITY/POLICY/RESTRICTION_CONTROL (7–10), versioned, journaled, replicated. |
| FR-IMRG12 ordered pipeline, stable precedence | **Done** | `BlpRiskState.decideAndReserve` / `decideMarketTrade`. |
| FR-IMRG13 check+reserve single-threaded | **Done** | Reservation applied on the BLP thread before book entry. |
| FR-IMRG14 clientOrderId idempotency | **Done (key optional)** | Bounded retention with eviction frontier; absent key = no retry mapping (contract tightening deferred). |
| FR-IMRG15 one decision per key, reason carried | **Done** | riskReason on order lifecycle events + trade decision acks. |
| FR-IMRG16 reserve→consume/release exactly once | **Done** | Pro-rata consume on fill; release on cancel; never negative. |
| FR-IMRG17 sequenced price for decisions | **Done** | BLP uses last sequenced price + event-carried time; no zero substitution. |
| FR-IMRG18 fail closed | **Partial** | Edge fails closed pre-bootstrap (CONTROL_STATE_STALE→503) and on stale price; BLP rejects unknown/disabled state. Feed-loss detection arrives with durable feeds. |
| FR-IMRG19 BLP wins disagreements | **Done** | Mismatch counter + stable rejection surfaced. |
| FR-IMRG20 success only after BLP accept | **Done** | Orders always acked by decision; market trades now block for the sequenced decision (contract delta). |
| FR-IMRG21 risk state in snapshot | **Done** | Snapshot v3: policy, account control/executed, security control/prices, idempotency, per-order reservations. |
| FR-IMRG22 replay reproduces decisions | **Done** | Control events + commands replay through the same pipeline; no external query. |
| FR-IMRG23 rejections journaled, never market-facing | **Done** | Rejected order emits status only (no trade/position events); rejected trade emits only its correlation ack. |
| FR-IMRG24 policy change vs resting orders | **Done (restrictions)** | Restriction cancels resting orders via sequenced cancels; other policy changes retain. |
| FR-IMRG25 multi-gateway concurrency | **Deferred** | Single co-located gateway in slice 1; BLP authority already guarantees no overshoot. |
| FR-IMRG26 bounded capacities | **Done** | All risk tables preallocated/bounded; exhaustion rejects (CAPACITY) or evicts (idempotency frontier). |
| FR-IMRG27 BLP side effects only via output ring | **Done** | Rejections/decisions are output events; no direct NATS/DB. |
| FR-IMRG30 authenticated, attributable control admin | **Partial** | Token + operator provenance (logged); real OIDC is the auth roadmap item. |
| FR-IMRG31 control plane loss ≠ lookup/erase | **Done** | Command path consumes only installed local state. |
| FR-IMRG32/33 durable source feeds | **Deferred** | Slice 1: journal-sequenced bootstrap + `/risk/control` deltas. |
| FR-IMRG34 quarantine invalid control updates | **Deferred** | Arrives with the external feeds. |
| FR-IMRG35 startup/degraded matrix | **Partial** | Fail-closed startup implemented; full matrix doc deferred. |
| FR-IMRG40 inherited topology unchanged | **Done** | Rings, journal gate, matching policy, subjects unchanged beyond specified fields. |
| FR-IMRG41 read model stays a projection | **Done** | No admission state read from MariaDB. |
| FR-IMRG42 API parity except admission delta | **Done** | Optional clientOrderId + rejection body + synchronous trade decision are the only deltas. |
| FR-IMRG43 observability | **Partial** | Metrics exported (readiness, versions, rejections by reason, decisions, duplicates, mismatches, decision latency, reserved notional, control events); Grafana panels/alerts deferred. |
| FR-IMRG44 state identity in UI metadata | **Deferred** | UI untouched in slice 1. |
| FR-IMRG45 no output-ring redesign | **Done** | Two new event kinds only. |
