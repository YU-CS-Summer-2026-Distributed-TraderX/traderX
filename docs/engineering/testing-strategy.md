# Testing strategy

Which checks are automated, which are operator-run, and why each one sits where it does.

**The short version:** every correctness property is asserted by a fast in-process test that gates
every push; the end-to-end scripts demonstrate those same properties on the deployed stack and stay
operator-run; the three-member cluster and the wall-clock timing budgets run on demand on idle
hardware. Nothing was weakened to make it fit a pipeline.

## Tier 1 — in-process tests, in CI on every push

The engine, cluster, gateway, risk and post-trade logic are covered by **484 machine-verified
tests**, plus **48 baseline-service tests**. They need no cluster, no network and no
database server, using an in-memory database where a datasource is required.

They assert the correctness properties directly: self-trade prevention, atomic replace, client-order-ID
idempotency, byte-identical consensus allocation, deterministic replay, and reproducible regulatory
and risk-extract exports.

This is the tier that makes a green build mean something, because it gates merges.

## Tier 1.5 — cross-service integration, in CI with containers

Five suites run against real infrastructure rather than in-memory substitutes, isolated by tag into
their own task so the fast unit job needs no container runtime. Two of them cover the end-of-day
chain: the JetStream stream contract, and the snapshot read and P&L write against a MariaDB running
the schema read live from the deployed ConfigMap rather than a copied fixture that could drift.

This tier exists for properties that are enforced by infrastructure rather than by application
code. A mock can be made to return whatever the code under test expects, so a test built on one can
only confirm that the code asked for the right thing — never that the database agreed. Constraints,
foreign keys, transaction boundaries and startup wiring all live on the other side of that line.

`TradeProcessorPersistenceIT` drives the real booking path against a **real MariaDB** initialised
with the **deployed schema**, run the same way production runs it. It proves the persistence
contract a mocked unit test cannot:

- a buy books a position row and a trade row against the deployed DDL, including the
  enum-to-constraint mapping surviving a real round trip;
- subsequent trades accumulate onto the same position row;
- an order for an account that does not exist is **rejected by the foreign key and fails loudly**.
  Trades disappearing into a foreign-key rejection is a documented failure class here, so this test
  turns that silence into an assertion.

`AccountOutboxAtomicityIT` asserts the guarantee the **transactional outbox** exists to provide:
the business write and the outbox row commit as one unit, or neither does. That guarantee is
enforced by the database, so it is only observable against one — the unit-tier test in the same
package mocks the outbox repository, which makes it pass whether or not the two writes share a
transaction at all. Every assertion here reads back through an **independent connection**, outside
the application's pool and its transaction, because reading through the connection that did the
writing says nothing about what was committed. It covers:

- both rows present after the call, with the outbox row carrying its generated version;
- **neither row visible to an outside reader while the transaction is still open**, which is what
  rules out the two writes landing on separate connections;
- a **database-rejected** outbox insert — a real constraint violation, not a stubbed failure —
  leaving no account row behind.

It also runs MariaDB with the **same server flags the deployed database uses**, which turned out to
matter: the account path depends on one of them, and nothing else checks that it is still set.

`TradeProcessorContextIT` starts the composed application context against a **real MariaDB and a
real message broker**, covering startup wiring that dials the broker during bean creation and so
cannot be exercised without one.

Each case in this tier was **falsified before it was trusted** — the code was deliberately broken
to confirm the test fails, and fails for the stated reason. A test that has never failed is a test
whose failure mode is unknown, and on this tier that risk is real: infrastructure tests can pass by
never reaching the assertion they claim to make.

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

## Gates — the properties a test cannot see

Cutting across all three tiers are six **gates**: four allocation gates and two no-GC gates. They sit
outside the tiers, and are counted separately, because they are a different kind of check. A test
asks whether the code produced the right answer. A gate asks what the code *cost* to produce it — and
those are independent. A method can return a perfectly correct result while allocating on every call,
and no assertion about its return value will ever notice.

That distinction matters here because of how the matching engine runs: a single thread, processing
one order at a time, on the path every order takes. Garbage collection stops that thread. The damage
is not a wrong answer, it is an unpredictable pause — and pauses arrive under load, which is exactly
when the system is least able to absorb them. Allocation on the hot path therefore converts, quietly
and later, into latency spikes that no correctness test would have flagged.

So the gates run the hot path in steady state and assert that it allocates **exactly zero bytes**.
Zero rather than a budget, because a threshold is negotiable — each change that adds "just a little"
stays under the bar until one day it doesn't, and there is no principled place to draw the line. Zero
is the only number that cannot be argued down.

The two no-GC gates then run the same paths under a JVM configured with **no garbage collector at
all**. With nothing to reclaim memory, any allocation is no longer absorbed and forgotten — it
accumulates until the process dies. That converts a slow, invisible degradation into an immediate,
unmissable failure, which is the whole point: it removes the possibility of a small regression
sitting undetected because the collector was quietly cleaning up after it.

Together the gates cover the engine's own hot path, the same path with risk checks engaged, the
transport encode path, and the cluster apply path — the four places where an allocation would sit
inside the per-order critical section. Because they need their own JVM configuration, they run as
separately forked JVMs rather than inside the main suite, which is why their count is kept apart from
the test totals.

They are best understood as a ratchet rather than a performance claim. They do not assert that the
system is fast. They assert that a property the system already has cannot be lost by accident, which
is the kind of guarantee that is very cheap to keep and very expensive to recover once it has drifted.
The four allocation gates run on every push; the two no-GC gates run on demand with the cluster tier.

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

## Which tree the numbers describe

Every figure here is counted on **YU15**, the tip state — the only branch carrying every ancestor's
spec pack, and the tree the deployed system is built from. On the CI path that is engine 335 plus
service modules 149, for **484 with zero failures**.

The engine job additionally runs against YU15's two ancestor branches. That is a propagation check
rather than three products under test: each renders its own effective tree, so a fix that is live on
one spec layer while shadowed on another surfaces as a single red leg — and surfaces nowhere at all
without it. The ancestors report fewer tests only because they compose fewer layers.
