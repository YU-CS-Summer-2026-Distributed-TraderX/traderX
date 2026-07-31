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

What separates this tier from the one above is where it looks for its answer. A unit test can call a
method and inspect what it returns. These scripts cannot, and deliberately do not: they submit real
input to a running system and then read the outcome from the far end of the pipeline — the committed
sequence on a cluster member, the row in the read model, the message on the egress stream. An
acknowledgement is never accepted as evidence that something happened, because in a system that
sequences, replicates and projects asynchronously, a successful response and a completed effect are
different events that can disagree.

Each script is written so that it can fail. It states the outcome it expects before it acts, then
asserts against the system's own record rather than against its own assumptions, and prints an
explicit pass or fail line per step so a run reads as a verdict rather than a log.

They stay operator-run because they need a live cluster. That is an infrastructure constraint rather
than a reliability one, which is a meaningful difference: these scripts are dependable, they simply
require a deployed system to run against.

## Tier 3 — full-cluster and timing, on demand

Three-member failover, snapshot and replay, cold-follower rejoin, and wall-clock budgets run on
demand on idle hardware, and they are kept out of every-push CI deliberately.

The reason is that these tests assert on *time* and on *scheduling*, not only on values. A consensus
test runs three real cluster members with their own transport threads in one JVM; a wall-clock budget
asserts that an operation completes within a fixed number of milliseconds. Both depend on the
machine actually granting CPU when the code asks for it. On a shared runner that assumption does not
hold, and the failure is not a graceful slowdown — a starved member simply stops making progress, and
the test reports a timeout that looks exactly like a broken algorithm.

That gives this tier a different reading rule from the others. **A green result is trustworthy
wherever it runs**, because clearing a timing bar under contention is harder than clearing it on an
idle box. **A red result is only meaningful on quiet hardware**, and needs re-running there before it
is treated as a regression. Keeping these tests out of the push pipeline is what protects that rule:
a suite that cries wolf on a busy runner teaches people to ignore it, which costs more than the
coverage is worth.

Moving this tier onto dedicated hardware is a one-line runner change, at which point it can gate
merges like the others.

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
