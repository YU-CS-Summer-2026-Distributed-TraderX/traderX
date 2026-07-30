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

## Headline

### Tests that run on every push, per branch

| Suite | YU13 | YU14 | YU15 |
|---|---:|---:|---:|
| Composed engine (`order-matcher`) | 304 | 318 | 335 |
| Composed service modules | 110 | 110 | 118 |
| **Total per branch** | **414** | **428** | **453** |

Zero failures on all three.

Each branch renders its own effective tree, so the same test name runs against differently composed
code depending on which state you build. That is why the totals differ per branch, and it is the
reason the suite is run against three trees.

### Everything else

| Coverage | Count | Made up of |
|---|---:|---|
| Baseline inherited services | 48 | Java 25 · NestJS 7 · .NET 16 |
| Allocation and no-GC gates | 6 | 4 allocation + 2 Epsilon-GC |
| Market-data gates | 35 | 17 historical store + 18 live capture |
| End-to-end proof scripts | 26 | operator-run against a live cluster |
| Java test classes in the effective tree | 100 | counted on YU15 |

## Java — the composed tree

Counted on the YU15 effective tree.

| Module | Test classes | Tests executed | In CI |
|---|---|---|---|
| **order-matcher** (engine, book, journal, Aeron replication, cluster, gateways, risk, reporting, risk extract, tracing) | **74** | **335** | ✅ all 3 branches |
| trade-processor (settlement, reconciliation, end-of-day P&L, projection) | 11 | 69 | ✅ all 3 branches |
| execution-algo-engine | 8 | 29 | ✅ all 3 branches |
| position-service | 2 | 11 | ✅ all 3 branches |
| account-service | 4 | 7 | ✅ all 3 branches |
| aeron-replication-sidecar | 1 | 2 | ✅ all 3 branches |
| trade-service | 0 | 0 | no runnable unit test |
| **Total (YU15)** | **100** | **453** | |

YU14 renders 98 classes and 428 tests; YU13 renders 97 and 414.

`order-matcher` is 74% of the test classes and holds the correctness properties the system is built
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

Two integration tests run alongside them against real infrastructure in containers rather than
in-memory substitutes:

- **`TradeProcessorPersistenceIT`** drives the booking path against a real MariaDB loaded with the
  deployed schema, so the enum-to-constraint mapping and the foreign keys are exercised as deployed.
  Its load-bearing case is that an order for a non-existent account is rejected by the foreign key
  and fails loudly rather than being silently dropped.
- **`TradeProcessorContextIT`** starts the composed application context against a real MariaDB and a
  real message broker, covering wiring that a mocked test cannot reach.

Both are isolated by tag into their own task, so the fast unit job needs no container runtime.

## Gates

These run as separately forked JVMs and are counted apart from the totals above.

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

Several of these were falsified before they passed — an HTTP 200 that booked nothing, an order the
risk gate rejected while the response still looked successful. That history is why they are trusted.
They require a live cluster, so they are operator-run.

## Front-end and other components

| Component | Tests | Status |
|---|---|---|
| `reference-data` (composed) | 3 | not in CI; the template copy is |
| `web-front-end` (Angular) | 10 | inherited scaffolding |
| `price-publisher` | 1 | not in CI |
| `database`, `ingress`, `api-explorer` | 0 | no tests |

## What CI runs

The workflow has 7 job definitions and 9 legs on a push, because the main engine job is a
three-branch matrix.

| Job | Scope | Trigger |
|---|---|---|
| engine × 3 (YU13, YU14, YU15) | composed order-matcher suite (304 / 318 / 335) + 4 allocation gates, then the five other service modules (110 / 110 / 118) — **414 / 428 / 453 per branch** | push and pull request |
| baseline | 4 Java baseline services | push and pull request |
| baseline (reference-data) | NestJS baseline | push and pull request |
| baseline (people-service) | .NET baseline | push and pull request |
| integration (persistence) | real MariaDB in a container | push and pull request |
| integration (context) | real MariaDB and message broker | push and pull request |
| cluster and timing | three-node cluster, wall-clock budgets, 2 Epsilon gates | manual |

The matrix is the point. Each branch renders its **own** effective tree, so the same test name runs
against differently composed code, which is how a propagation regression becomes visible — a fix
that is live on one branch and shadowed on another shows up as one red leg beside two green ones.

## Verification tiers

The full rationale for what runs where is in [Testing strategy](testing-strategy.md).

| Tier | What | Where |
|---|---|---|
| In-process | 453 / 428 / 414 per branch, plus 48 baseline and 4 allocation gates | CI, every push |
| End-to-end proofs | 26 scripts | operator-run against a live cluster |
| Cluster and timing | three-node failover, wall-clock budgets, Epsilon GC | on demand, idle hardware |

Nearly every end-to-end proof has an in-process test asserting the same property in CI, so the
proofs confirm invariants that are already gated on every commit.
