# Full test coverage — the whole repo, current state

> Companion to [02-RESULT-coverage-map.md](02-RESULT-coverage-map.md), which is a **frozen
> 2026-07-24 snapshot** taken before CI existed (its `In CI? ✗` column is the finding that
> motivated brief 04 — deliberately preserved, not updated). **This doc is the live picture.**
> **Measured 2026-07-29**, every branch rendered and run fresh (not extrapolated from one branch).

## How these numbers were measured

Two different counts appear below and they are not interchangeable:

- **Classes / methods** — counted directly in the **effective (generated) tree**
  (`generated/code/target-generated/`), i.e. after layer composition. This excludes the shadowed
  duplicate copies that live in ancestor spec layers. Counting across `specs/` instead inflates
  order-matcher from 77 classes to 99.
- **Executed** — what a run actually reports. This is **higher** than the `@Test` count where
  parameterized/repeated tests expand, and gate tasks run as separate forked JVMs outside the
  functional suite.

Where the two disagree, the executed number is the real one.

## 1. Headline

| | |
|---|---|
| **Composed engine suite (per branch)** | **YU15 335 · YU14 318 · YU13 304** — 0 failures |
| **Composed service modules (per branch)** | **YU15 118 · YU14 110 · YU13 110** — 0 failures |
| **Every composed test, per branch** | **YU15 453 · YU14 428 · YU13 414** — 0 failures |
| **Baseline inherited services** | **48** (Java 25 · NestJS 7 · .NET 16) |
| **Allocation / no-GC gates** | **6** (4 allocation + 2 Epsilon-GC) |
| **q data gates** | **35** (17 historical + 18 live capture) |
| **Falsifiable proof scripts** | **27 catalogued** (26 standalone + `seed-option-chain.sh` setup; `yu05-common.sh` is a library) |
| **Java test classes in the effective tree** | **100 on YU15** (98 YU14 · 97 YU13) |

**How to read the three suite rows.** The engine row is `order-matcher` alone — the module holding
every correctness property. The service row is the other five composed modules. Both run in the same
`hosted` CI job on all three branches, so the per-branch total is what a push actually gates. The
numbers differ per branch because each renders its own effective tree, which is the entire point of
the matrix.

## 2. Java — the composed (deployed) tree

Counted in the YU15 effective tree from the **JUnit XML of an actual run** — classes and tests that
executed, not annotations counted in source. Those two disagree (parameterized cases expand; some
source files never run), and where they do, this is the one that is true.

| Module | Test classes | Tests executed | In CI? |
|---|---|---|---|
| **order-matcher** (engine, book, journal, Aeron replication, cluster, gateways, risk, reporting, risk extract, OTel) | **74** | **335** | ✅ **all 3 branches** |
| trade-processor (settlement, recon, EOD P&L, projection) | 11 | 69 | ✅ **all 3 branches** |
| execution-algo-engine (YU08) | 8 | 29 | ✅ **all 3 branches** |
| position-service (YU06 EOD) | 2 | 11 | ✅ **all 3 branches** |
| account-service (YU04 outbox + inherited) | 4 | 7 | ✅ **all 3 branches** |
| aeron-replication-sidecar | 1 | 2 | ✅ **all 3 branches** |
| trade-service | 0 | 0 | ✗ — **no runnable unit test** (see §9) |
| **Total (YU15)** | **100** | **453** | |

YU14 renders 98 classes / 428 tests and YU13 97 / 414 — same names, differently composed code.

**The single most important line:** `order-matcher` is 74% of the classes and holds every
correctness property the system is sold on. **All six substantial modules run in CI on all three
branches** — order-matcher via `engine-tests.sh`, the rest via `service-tests.sh`, both steps of the
same `hosted` job so the ~117s render is paid once.

### Inside order-matcher, by package

| Package | Tests | What it covers |
|---|---|---|
| `ordermatcher.lmax` | ~122 | engine, limit order book, journal, Aeron replication |
| `ordermatcher.cluster` | ~69 | consensus, snapshot codec, FIX/binary gateway, risk extract, OTel |
| `ordermatcher.risk` / `.service` / root | rest | risk state, control feeds, entitlements, projection |

## 3. Baseline inherited services (`templates/*-specfirst`)

The plain-vanilla FINOS TraderX we forked. **All 48 were added by brief 03** — the "before" was
zero, because Java test sources sat at the non-default `src/main/test/java` and Gradle skipped them
as NO-SOURCE (a green build running nothing).

| Service | Stack | Tests | In CI? |
|---|---|---|---|
| account-service | Java / JUnit | 8 | ✅ `baseline` |
| trade-processor | Java / JUnit | 7 | ✅ `baseline` |
| position-service | Java / JUnit | 5 | ✅ `baseline` |
| trade-service | Java / JUnit | 5 | ✅ `baseline` |
| reference-data | NestJS / `node:test` | 7 | ✅ `baseline-reference-data` |
| people-service | .NET / xUnit | 16 | ✅ `baseline-people-service` |
| **Total** | | **48** | **all in CI** |

Plus **1 integration test** — `TradeProcessorPersistenceIT` (3 cases) against a **real MariaDB via
Testcontainers** loaded with the deployed configmap schema (`ddl-auto=none`, real FK + CHECK
constraints). Runs as `integration-trade-processor`; tag-isolated so the fast unit job stays
Docker-free.

## 4. Gates (separately forked JVMs — NOT counted in the per-branch totals above)

| Gate | Asserts |
|---|---|
| `allocationGateTest` | hot path allocates exactly zero bytes in steady state |
| `riskAllocationGateTest` | same, with risk gating engaged |
| `aeronAllocationGateTest` | Aeron transport claim/encode path |
| `clusterAllocationGateTest` | cluster apply path |
| `noGcTest` | the above under **Epsilon GC** (no collector at all — any allocation is fatal) |
| `riskNoGcTest` | same, risk-gated |

Allocation gates run in CI (`hosted`); the two Epsilon gates run in the manual `dedicated` job.

## 5. q data gates (KDB-X, brief 06)

| Gate | Checks | Verifies |
|---|---|---|
| `selfcheck.q` | **17** | historical TAQ store: per-partition row counts, dedup collapse, quote/trade split, first-two-trades to the tick, regular-hours VWAP for all 8 symbol-days, replay ordering + pacing |
| `txselfcheck.q` | **18** | live-capture store: `txOrder`/`txTrade` schema, leader-only guard, capture count == cluster trade count |

**Both are cross-implementation checks** — every expected value was computed independently in
**DuckDB** over the same files, so they verify kdb against a second engine rather than against
itself. Neither runs in CI (they need the data sample / a fixture).

## 6. Falsifiable proof scripts — `scripts/proofs/` (27 catalogued)

End-to-end proofs against a *deployed* system, printing explicit ✔/✘ per step. Several were
genuinely falsified before they passed. **None run in CI** — they need a live cluster, which is an
infrastructure limit, not a flakiness one.

| Area | Scripts | Tier |
|---|---|---|
| Risk gateway + control feeds | `yu03-risk-demo`, `yu04-live-delta`, `yu04-offline-catchup` | kind |
| Post-trade / compliance | `yu05-auth-entitlements`, `yu05-recon`, `yu05-regulatory-reproducible`, `yu05-settlement` | kind |
| EOD price chain | `yu06-quality-gate`, `yu06-consumer-halt` | kind |
| Execution algo | `yu08-algo-slicing` | kind |
| FIX ingress | `yu10-fix-session`, `yu13-fix-cancel.mjs` | kind |
| Order book / lifecycle | `yu13-cancel-ingress`, `yu13-clordid-suppression`, `yu13-stp-and-replace`, `yu13-readmodel-effect-end` | kind |
| Options / risk extract | `seed-option-chain`, `yu15-option-persistence`, `yu15-risk-extract` | kind |
| Observability | `yu15-otel-trace-join`, `yu15-otel-reject-trace-log-join` | kind |
| **HA / DR / consensus** | `yu12-gke-recovery`, `yu12-gke-failover-transparency`, `yu12-gke-cross-epoch-idreuse`, `yu12-gke-restore-from-gcs`, `yu13-gke-replace-proof`, `failover-nodeclock` | **GKE** |

(`yu05-common.sh` is a shared library, not a proof. `seed-option-chain.sh` is catalogued but is setup
for the two proofs beneath it, so the standalone-proof count is 26.)

**The two observability proofs are the strongest-built in the set, and worth reading as a template.**
Each predicts the trace id **and** the parent span id in Python from the ClOrdID alone — no input
from any server — then demands Tempo return exactly that. The reject proof additionally runs with
head sampling genuinely on and submits two orders that **both fail the head verdict**: the rejected
one must return a whole 5-span trace, and the accepted one must **404**. That negative case is what
stops a build which quietly started tracing everything from passing — an assertion most proof scripts
in this repo do not have and arguably should.

## 7. Front-end and other

| Component | Tests | Status |
|---|---|---|
| `reference-data` (composed) | 3 `.spec.ts` | not in CI (the **template** copy is) |
| `web-front-end` (Angular) | 10 `.spec.ts` | **inherited scaffolding — never executed here** |
| `price-publisher` (Node) | 1 | not in CI |
| `database`, `ingress`, `api-explorer` | 0 | no tests |

## 8. What CI executes today

Workflow `engine-tests.yml` — 7 job definitions, 9 legs on a push (the `hosted` job is a 3-branch
matrix). Last green CI run recorded: #9 (`f6cf4255`); the counts below were re-measured locally on
2026-07-29 against the current tips, which carry commits CI has not run yet (nothing is pushed).

| Job | Scope | Trigger |
|---|---|---|
| `hosted` × 3 (YU13, YU14, YU15) | composed **order-matcher** suite (304 / 318 / 335) + 4 allocation gates, **then the five other service modules** (110 / 110 / 118) — **414 / 428 / 453 per branch** | push + PR |
| `baseline` | 4 Java baseline services | push + PR |
| `baseline-reference-data` | NestJS baseline | push + PR |
| `baseline-people-service` | .NET baseline | push + PR |
| `integration-trade-processor` | Testcontainers MariaDB | push + PR |
| `integration-trade-processor-context` | composed context vs real MariaDB **and** real NATS | push + PR |
| `dedicated` | 3-node cluster + wall-clock timing + 2 Epsilon gates | **manual** (`workflow_dispatch`) |

The matrix is the point: each branch renders its **own** effective tree, so the same test name runs
against differently-composed code — which is how a dead-layer/propagation regression becomes
visible. That bug class has bitten this project repeatedly (the multer CVE, `PubSubConfig`, the
gateway keepalive, the kdb tap).

## 9. Honest gaps

- ~~Four Java modules no pipeline runs~~ **CLOSED 2026-07-28** (`8fc0bafa`, sidecar added `0e0c7917`): all now run in the `hosted` job on every branch — **110 tests on YU13/YU14, 118 on YU15**, 0 failures. The `~12 @SpringBootTest` warning was overstated: only **6 classes** needed Spring, and only **one** turned out to be a genuine blocker.
- **One test is deliberately excluded, and it is worth knowing why.** `TradeProcessorApplicationTests` was inert (no `sourceSets` override in the composed tree). Waking it showed the context **cannot start without a live NATS broker** — `tradePublisher` dials `nats.address` during bean creation with no disable flag. It is an integration test that had been sitting in the unit tier passing by never running. It is now excluded from the unit task **visibly, with the reason in the build file**, and belongs in the Testcontainers tier beside `TradeProcessorPersistenceIT`.
- ~~trade-processor context-load unverified~~ **CLOSED 2026-07-28** (`d4b9460d`):
  `TradeProcessorContextIT` starts the composed context against a **real MariaDB + real NATS**
  (Testcontainers) and asserts the Publisher bean is wired. Tagged `integration` with its own task,
  so the unit job stays Docker-free (verified green with the Docker daemon down). New
  `integration-trade-processor-context` CI job.
- **`trade-service` still has no runnable unit test, and I deliberately did not fix it.** Same root
  cause (`tradePublisher` dials `nats.address` at bean creation), but its `build.gradle` is produced
  by `specs/006-messaging-nats-replacement/generation/patches/0001-state-overlay.patch` — a
  `git apply` artifact whose context lines are the baseline files, i.e. the same class of artifact
  that broke YU02 rendering during the rebase. There is no layer `build.gradle` to add test
  dependencies to. Editing the patch would risk generation for **every state from 006 onward** to
  recover **one** context-load test. If that patch is ever restructured for another reason, add the
  container test then.
- **The Angular front-end has never been executed here at all.**
- **No proof script runs in CI** — deliberate (they need a live cluster) and documented in the [test strategy](04-RESULT-test-strategy.md), but it means they can rot.
- **q gates aren't wired to anything automated.**

## 10. The three-tier framing

Full rationale in [04-RESULT-test-strategy.md](04-RESULT-test-strategy.md):

| Tier | What | Where |
|---|---|---|
| **1 — in-process** | 453 / 428 / 414 per branch + 48 baseline, 4 allocation gates | CI, every push |
| **2 — end-to-end proofs** | 26 falsifiable scripts | manual, live cluster |
| **3 — cluster + timing** | 3-node failover, wall-clock budgets, 2 Epsilon gates | on-demand, idle hardware |

Nearly every Tier-2 proof has a Tier-1 test asserting the same property in CI — so the proofs are
end-to-end *confirmation* of invariants already gated on every commit, not the only evidence.

**Tier 3 was run on all three branches on 2026-07-29 and is green** — `ThreeMemberClusterTest`
**23.7s (YU13) · 23.8s (YU14) · 28s (YU15)** against a 120s bar, `SnapshotBarrierPerformanceTest`
inside budget on all three, both Epsilon gates green. Two notes that matter more than the pass:

- **The 50 ms `SnapshotBarrierPerformanceTest` marginality on YU13 did not reproduce** — it came in at
  0.23s. It remains a contention artifact, not a regression, consistent with the earlier A/B.
- **`ThreeMemberClusterTest` is contention-sensitive in a cliff-edged way, and this was measured, not
  assumed.** On a box carrying six kind containers it fails at the **120s timeout**; the same commit
  on a quiet box finishes in 24s. It does not degrade gradually to 60s or 90s — it either completes
  in ~24s or starves out entirely (`condition not met within 120s` in `awaitEgress`, always after the
  second failover). A failure here under load is inconclusive, not a regression; re-run it quiet
  before believing it. See the `applied: -1` starvation note in the run-state-kind skill.
