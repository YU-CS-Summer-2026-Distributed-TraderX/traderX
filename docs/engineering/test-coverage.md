# Test coverage

What is tested in this deployment, how much of it runs automatically, and how the numbers were
counted.

## How these numbers were counted

Two different counts appear below and they are not interchangeable.

- **Classes and tests** come from the JUnit XML of an actual run, in the **effective (generated)
  tree** — the code that exists after the spec layers are composed. Counting across the spec
  directories instead double-counts, because ancestor layers hold shadowed copies of files that a
  later layer overrides.
- **Executed** is what a run reports. It is higher than the number of `@Test` annotations in the
  source, because parameterized and repeated cases expand at runtime, and some source files never
  execute at all.

Where the two disagree, the executed number is the real one, and it is the one used here.

## Coverage summary

### Tests that run on every push

| Suite | Tests |
|---|---:|
| Composed engine (`order-matcher`) | 335 |
| Composed service modules | 125 |
| **Total** | **460** |

Zero failures.

Every number in this document is counted on **YU15**, the tip state: the only branch that carries
every ancestor's spec pack, and the one the deployed system is built from. Earlier states remain
buildable and are still exercised in CI (see [What CI runs](#what-ci-runs)), but they are ancestors
of this one rather than separate products, so reporting three sets of numbers described a choice
nobody makes.

### Everything else

| Coverage | Count | Made up of |
|---|---:|---|
| Baseline inherited services | 48 | Java 25 · NestJS 7 · .NET 16 |
| Cross-service integration | 5 suites | real MariaDB, and a real JetStream broker |
| Allocation and no-GC gates | 6 | 4 allocation + 2 Epsilon-GC |
| Market-data gates | 35 | 17 historical store + 18 live capture |
| End-to-end proof scripts | 26 | operator-run against a live cluster |
| Composed Node and Python suites | 44 | reference-data 9 · price-publisher 11 · tick-store 24 |
| Java test classes in the unit tier | 101 | the composed tree |

## Java — the composed tree

Counted on the YU15 effective tree — the code that exists once every ancestor's spec layers are
composed and the shadowed copies are resolved.

| Module | Test classes | Tests executed | In CI |
|---|---|---|---|
| **order-matcher** (engine, book, journal, Aeron replication, cluster, gateways, risk, reporting, risk extract, tracing) | **74** | **335** | ✅ |
| trade-processor (settlement, reconciliation, end-of-day P&L, projection) | 11 | 69 | ✅ |
| execution-algo-engine | 8 | 29 | ✅ |
| position-service | 2 | 11 | ✅ |
| account-service | 4 | 7 | ✅ |
| aeron-replication-sidecar | 1 | 2 | ✅ |
| trade-service (validating edge: ticker and account checks, sequencer forward) | 1 | 7 | ✅ |
| **Total** | **101** | **460** | |


These are the classes the unit task runs. The container-backed tests are tagged out of it and
counted separately under [Cross-service integration](#cross-service-integration).

`order-matcher` is 73% of the test classes and holds the correctness properties the system is built
on: self-trade prevention, atomic replace, client-order-ID idempotency, byte-identical consensus
allocation, deterministic replay, and reproducible regulatory and risk exports.

### Inside order-matcher, by package

| Package | Tests | Covers |
|---|---|---|
| `ordermatcher.lmax` | ~122 | engine, limit order book, journal, Aeron replication |
| `ordermatcher.cluster` | ~69 | consensus, snapshot codec, FIX and binary gateways, risk extract, tracing |
| `ordermatcher.risk`, `.service`, root | remainder | risk state, control feeds, entitlements, projection |

## Baseline inherited services

The plain-vanilla TraderX services this deployment forked. All 48 tests here were added by this
project: the baseline had none running, because the Java test sources sat at a non-default source
path and the build skipped them silently while still reporting success.

| Service | Stack | Tests | In CI |
|---|---|---|---|
| account-service | Java / JUnit | 8 | ✅ |
| trade-processor | Java / JUnit | 7 | ✅ |
| position-service | Java / JUnit | 5 | ✅ |
| trade-service | Java / JUnit | 5 | ✅ |
| reference-data | NestJS / `node:test` | 7 | ✅ |
| people-service | .NET / xUnit | 16 | ✅ |
| **Total** | | **48** | all in CI |

## Cross-service integration

Five suites run against real infrastructure in containers rather than in-memory substitutes. They
cover properties that live in the infrastructure rather than in application code, which is exactly
the set a mock cannot reach: a mock returns what the caller expects, so a test built on one shows
that the code asked for the right thing, never that the database agreed.

| Test | Runs against | Load-bearing case |
|---|---|---|
| `TradeProcessorPersistenceIT` | MariaDB, deployed schema | an order for a non-existent account is rejected by the foreign key and **fails loudly** rather than being silently dropped |
| `AccountOutboxAtomicityIT` | MariaDB, deployed schema and deployed server flags | the account write and its outbox row **commit as one unit or not at all** — asserted from a connection outside the application's transaction |
| `TradeProcessorContextIT` | MariaDB + message broker | the composed context starts with wiring that dials the broker during bean creation |
| `EodStreamRepairIT` (7 cases) | real JetStream | an existing `TRADERX_EOD` missing a required subject is **repaired, not accepted** — an unrepaired stream rejects the completion publish and the overnight chain breaks silently |
| `EodSnapshotAndPnlIT` (11 cases) | MariaDB, **schema read live from the deployed ConfigMap** | the snapshot read returns exactly the `(date, version)` asked for, and the P&L upsert is idempotent under redelivery — idempotency lives in the table's PRIMARY KEY, not in the Java |

`AccountOutboxAtomicityIT` is the counterpart to a unit test that mocks the outbox repository. With
the repository mocked, the assertion is that it was *called* — which holds whether or not the two
writes share a transaction, so the one property the outbox exists to provide is the one that test
cannot see.

Every case here was falsified before it was trusted: the code was deliberately broken to confirm
the test fails, and fails for the stated reason. For `EodSnapshotAndPnlIT` that meant deleting the
PRIMARY KEY from the deployed DDL — exactly the two idempotency cases fail, the other nine pass.
The schema is a declared Gradle input for that reason: without it a DDL-only edit leaves the task
up to date and the previous run's green results stand, which is how the first falsification
attempt appeared to prove the opposite.

All five are isolated by tag into their own task, so the fast unit job needs no container runtime.

## Gates

A gate asks what the code *cost*, not whether it was correct — a method can return the right answer
while allocating on every call, and no assertion about its return value would notice. That matters on
a single-threaded matching engine, where garbage collection stops the thread that processes orders,
so allocation on the hot path turns into latency spikes under load rather than into wrong results.
The rationale is in [Testing strategy](testing-strategy.md).

These run as separately forked JVMs, because they need their own JVM configuration, and are counted
apart from the totals above.

| Gate | Asserts |
|---|---|
| `allocationGateTest` | the hot path allocates exactly zero bytes in steady state |
| `riskAllocationGateTest` | the same, with risk gating engaged |
| `aeronAllocationGateTest` | the Aeron transport claim and encode path |
| `clusterAllocationGateTest` | the cluster apply path |
| `noGcTest` | the above under Epsilon GC — no collector at all, so any allocation is fatal |
| `riskNoGcTest` | the same, risk-gated |

The four allocation gates run in CI. The two Epsilon-GC gates run on demand.

## Market-data gates

| Gate | Checks | Verifies |
|---|---|---|
| `selfcheck.q` | 17 | historical store: per-partition row counts, deduplication, quote and trade split, first trades to the tick, regular-hours VWAP across every symbol-day, replay ordering and pacing |
| `txselfcheck.q` | 18 | live capture: schema, leader-only guard, capture count equal to the cluster's trade count |

Both are cross-implementation checks. Every expected value was computed independently in a second
engine over the same files, so they verify the store against something other than itself.

## End-to-end proof scripts

Twenty-six scripts drive the deployed system end to end: REST, FIX and binary ingress → gateway →
three-member Aeron cluster → asynchronous projection → SQL read model → egress, plus the risk
control plane. Each prints an explicit pass or fail line per step.

| Area | Scripts | Environment |
|---|---|---|
| Risk gateway and control feeds | risk demo, live control delta, offline catch-up | Kubernetes |
| Post-trade and compliance | entitlements, reconciliation, reproducible regulatory export, settlement | Kubernetes |
| End-of-day price chain | quality gate, consumer halt | Kubernetes |
| Execution algo | order slicing | Kubernetes |
| FIX ingress | session, cancel | Kubernetes |
| Order book and lifecycle | cancel ingress, duplicate suppression, self-trade prevention and replace, read model | Kubernetes |
| Options and risk extract | option chain, option persistence, risk extract | Kubernetes |
| Observability | trace join, reject trace and log join | Kubernetes |
| High availability and recovery | cluster recovery, failover transparency, cross-epoch ID reuse, restore from object storage, replace proof, node-clock failover | Kubernetes |

Each script asserts against the system's own record — the committed sequence on a cluster member, the
row in the read model, the message on the egress stream — rather than against the response it got
back, because a request can succeed while the effect it asked for does not. They require a live
cluster, so they are operator-run.

## Front-end and other components

| Component | Tests | Status |
|---|---|---|
| `reference-data` (composed) | 9 | ✅ in CI, alongside the template copy |
| `web-front-end` (Angular) | 49 in 10 specs | **disabled at source** — every suite is `xdescribe`, so wiring the job would run zero tests |
| `price-publisher` | 11 | ✅ in CI |
| `database`, `ingress`, `api-explorer` | 0 | no tests |

## What CI runs

The workflow has 10 job definitions and 11 legs on a push.

| Job | Scope | Trigger |
|---|---|---|
| engine | composed order-matcher suite (335) + 4 allocation gates, then the six other service modules (125) — **460** | push and pull request |
| baseline | 4 Java baseline services | push and pull request |
| baseline (reference-data) | NestJS baseline | push and pull request |
| baseline (people-service) | .NET baseline | push and pull request |
| composed extras | composed reference-data (jest), price-publisher (`node:test`), tick-store (pytest) — 44 tests | push and pull request |
| integration (persistence) | real MariaDB in a container | push and pull request |
| integration (outbox atomicity) | real MariaDB, deployed schema and server flags | push and pull request |
| integration (context) | real MariaDB and message broker | push and pull request |
| integration (EOD stream repair + snapshot/P&L) | real JetStream broker and real MariaDB — 18 cases | push and pull request |
| cluster and timing | three-node cluster, wall-clock budgets, 2 Epsilon gates | manual |

The engine job also runs against YU15's two ancestor branches, which is why 8 job definitions
produce 10 legs. That is not three products being tested; it is one propagation check. Each
branch renders its own effective tree, so the same test name runs against differently composed
code, and a fix that is live on one layer while shadowed on another appears as a single red leg
beside two green ones — otherwise it appears nowhere at all. The ancestors count fewer tests
only because they carry fewer spec layers; **460 is the number that describes what is deployed**.

## Verification tiers

The full rationale for what runs where is in [Testing strategy](testing-strategy.md).

| Layer | What | Where |
|---|---|---|
| In-process tests | 460, plus 48 baseline | CI, every push |
| Cross-service integration | 5 suites against real MariaDB and a real JetStream broker | CI, every push |
| End-to-end proofs | 26 scripts | operator-run against a live cluster |
| Cluster and timing | three-node failover, snapshot and replay, wall-clock budgets | on demand, idle hardware |
| Gates (cut across the rest) | 4 allocation gates, 2 no-GC gates | allocation gates every push; no-GC on demand |

Nearly every end-to-end proof has an in-process test asserting the same property in CI, so the
proofs confirm invariants that are already gated on every commit.
