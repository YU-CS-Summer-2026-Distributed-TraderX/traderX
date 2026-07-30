# Testing strategy

Which checks are automated, which are operator-run, and why each one sits where it does.

**The short version:** every correctness property is asserted by a fast in-process test that gates
every push; the end-to-end scripts demonstrate those same properties on the deployed stack and stay
operator-run; the three-member cluster and the wall-clock timing budgets run on demand on idle
hardware. Nothing was weakened to make it fit a pipeline.

## Tier 1 — in-process tests, in CI on every push

The engine, cluster, gateway, risk and post-trade logic are covered by **414 to 453 machine-verified
tests per branch**, plus **48 baseline-service tests**. They need no cluster, no network and no
database server, using an in-memory database where a datasource is required.

They assert the correctness properties directly: self-trade prevention, atomic replace, client-order-ID
idempotency, byte-identical consensus allocation, deterministic replay, and reproducible regulatory
and risk-extract exports.

This is the tier that makes a green build mean something, because it gates merges.

## Tier 1.5 — cross-service integration, in CI with containers

Two tests run against real infrastructure rather than in-memory substitutes, isolated by tag into
their own task so the fast unit job needs no container runtime.

`TradeProcessorPersistenceIT` drives the real booking path against a **real MariaDB** initialised
with the **deployed schema**, run the same way production runs it. It proves the persistence
contract a mocked unit test cannot:

- a buy books a position row and a trade row against the deployed DDL, including the
  enum-to-constraint mapping surviving a real round trip;
- subsequent trades accumulate onto the same position row;
- an order for an account that does not exist is **rejected by the foreign key and fails loudly**.
  Trades disappearing into a foreign-key rejection is a documented failure class here, so this test
  turns that silence into an assertion.

`TradeProcessorContextIT` starts the composed application context against a **real MariaDB and a
real message broker**, covering startup wiring that dials the broker during bean creation and so
cannot be exercised without one.

## Tier 2 — end-to-end proofs, operator-run

The **26 proof scripts** drive the deployed system end to end: REST, FIX and binary ingress →
gateway → three-member Aeron cluster → asynchronous projection → SQL read model → egress, plus the
risk control plane.

Several were **genuinely falsified before they passed** — an HTTP 200 that booked nothing; an order
the risk gate rejected while the response still reported success. That history is why they are
trusted.

They stay operator-run because they need a live cluster. That is an infrastructure constraint rather
than a reliability one, which is a meaningful difference: these scripts are dependable, they simply
require a deployed system to run against.

## Tier 3 — full-cluster and timing, on demand

Three-member failover, snapshot and replay, cold-follower rejoin, and wall-clock budgets run on
demand on idle hardware, and they are kept out of every-push CI deliberately: a shared two-core
runner cannot support a credible timing or three-node consensus claim.

Recent run on all three branches: the three-member cluster test completed in **23.7, 23.8 and 28
seconds** against a 120-second bar, the snapshot-barrier budget passed on all three, and both
Epsilon-GC gates were green.

That run also quantified why this tier needs quiet hardware, and the shape matters more than the
pass. On a contended machine the cluster test **hits the 120-second timeout**; the same commit on an
idle machine finishes in 24 seconds. It does not degrade gradually — it either completes in about 24
seconds or starves out entirely, always at the same point after the second failover. This was
established by running the same branch red, then green, with nothing changed but the load on the
box. **A red result on this tier under load is inconclusive and needs a quiet re-run before it means
anything.**

Moving this tier onto dedicated hardware is a one-line runner change.

## Proof-to-test map

Almost every end-to-end proof has an in-process test asserting the same property, already in CI:

| End-to-end proof | Property | In-process test in CI |
|---|---|---|
| self-trade prevention and replace | STP, atomic replace, replay-identical | `LimitOrderBookTest` |
| duplicate client order ID | duplicate suppressed idempotently | `ClOrdIdLedgerTest`, `IdempotencyEvictionDeterminismTest` |
| cancel ingress | cancel unlinks and is skipped by a cross | `LimitOrderBookTest` |
| FIX session and status | FIX 4.4 session, order and mass status | `FixSessionIntegrationTest`, `FixGatewayStatusTest` |
| reproducible regulatory export | journal-sourced export is byte-reproducible | `RegulatoryReportDeterminismTest` |
| reconciliation | journal against projection | `ReconciliationServiceTest` |
| settlement | T+N settlement lifecycle | `SettlementServiceTest` |
| risk gate and control plane | two-tier risk gate, kill switch | `BlpRiskStateTest`, `RiskControlControllerTest`, `ControlPlaneLimitRejectSeamTest` |
| durable control feeds | live delta and bootstrap catch-up | `ControlFeedSubscriberTest`, `ControlFeedBootstrapStateTest` |
| end-of-day risk extract | sequence-addressed, byte-identical cut | `RiskExtractTest`, `RiskReplayDeterminismTest` |
| failover | sub-second failover, zero order loss | `ThreeMemberClusterTest` (Tier 3) |
| order read model | place → new → cancel → canceled at the SQL effect end | `ProjectorHandlerTest` |
| end-of-day price chain | quality gate blocks a flagged publish; consumer halts fail-safe | end-of-day service and quality-checker tests |
| execution algo | a parent order slices into N children, all booked | `AlgoEventStoreReplayTest`, `AlgoOrderServiceTest` |
| cluster recovery | empty-disk rejoin to byte-identity | `ThreeMemberClusterTest`, `SnapshotRoundTripTest` (Tier 3) |
| failover under load | zero lost, zero duplicated | `ClOrdIdLedgerTest`, `InflightCorrelationTest` |
| cross-epoch ID reuse | no order-reference reuse across epochs | `SnapshotRoundTripTest` |
| distributed tracing | one order produces one trace across consensus | `OrderTraceTest`, `SpanSinkTest` |

The shell proofs are the end-to-end confirmation of properties CI already gates in-process. That is
the difference between having a script that shows something works and having the invariant enforced
on every commit **and** demonstrated on a running system.

## What stays manual, and why

The order → match → egress → read-model → REST round trip stays operator-run deliberately.
Automating it means running the Aeron cluster tier inside CI, and a shared two-core runner is a rig
this project has repeatedly measured as unreliable for consensus. Trading a trustworthy manual proof
for an unreliable automated one is a poor exchange. Its properties are gated in-process by the
projector and order-book tests.

## Branch matrix

The engine job runs as a per-branch matrix across **YU13, YU14 and YU15**. Each branch renders its
own effective tree, so the same test name runs against differently composed code — which is how a
propagation regression becomes visible. A fix that is live on one branch and shadowed on another
appears as one red leg beside two green ones.

Current counts on the CI path: engine 304 / 318 / 335, service modules 110 / 110 / 118, for
**414 / 428 / 453 per branch** with zero failures.
