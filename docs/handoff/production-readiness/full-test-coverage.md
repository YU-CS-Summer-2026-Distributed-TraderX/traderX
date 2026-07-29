# Full test coverage — the whole repo, current state

> Companion to [02-RESULT-coverage-map.md](02-RESULT-coverage-map.md), which is a **frozen
> 2026-07-24 snapshot** taken before CI existed (its `In CI? ✗` column is the finding that
> motivated brief 04 — deliberately preserved, not updated). **This doc is the live picture.**
> Measured 2026-07-28 on the `YU15-eod-risk-extract` tip.

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
| **Composed engine suite (per branch)** | **YU15 330 · YU14 313 · YU13 299** — 0 failures |
| **Baseline inherited services** | **48** (Java 25 · NestJS 7 · .NET 16) |
| **Allocation / no-GC gates** | **6** (4 allocation + 2 Epsilon-GC) |
| **q data gates** | **35** (17 historical + 18 live capture) |
| **Falsifiable proof scripts** | **26** (+2 helpers) |
| **Java test classes in the effective tree** | **105** |

## 2. Java — the composed (deployed) tree

Counted in the YU15 effective tree. "Methods" = `@Test`/`@ParameterizedTest`/`@RepeatedTest`
annotations; the executed total is higher (330) because parameterized cases expand.

| Module | Classes | Methods | In CI? |
|---|---|---|---|
| **order-matcher** (engine, book, journal, Aeron replication, cluster, gateways, risk, reporting, risk extract) | **77** | **308** | ✅ **all 3 branches** |
| trade-processor (settlement, recon, EOD P&L, projection) | 12 | 70 | ✅ **all 3 branches** |
| execution-algo-engine (YU08) | 8 | 29 | ✅ **all 3 branches** |
| account-service (YU04 outbox + inherited) | 4 | 12 | ✅ **all 3 branches** |
| position-service (YU06 EOD) | 2 | 11 | ✅ **all 3 branches** |
| trade-service | 1 | 1 | ✗ |
| aeron-replication-sidecar | 1 | 2 | ✗ |
| **Total** | **105** | **433** | |

**The single most important line:** `order-matcher` is 73% of the classes and holds every
correctness property the system is sold on. As of 2026-07-28 **all five substantial modules run in
CI on all three branches** — order-matcher via `engine-tests.sh`, the other four via
`service-tests.sh`, both steps of the same `hosted` job so the render is paid once.

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

## 4. Gates (separately forked JVMs, not in the 330)

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

## 6. Falsifiable proof scripts — `scripts/proofs/` (26)

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
| Observability | `yu15-otel-trace-join` | kind |
| **HA / DR / consensus** | `yu12-gke-recovery`, `yu12-gke-failover-transparency`, `yu12-gke-cross-epoch-idreuse`, `yu12-gke-restore-from-gcs`, `yu13-gke-replace-proof`, `failover-nodeclock` | **GKE** |

(`yu05-common.sh` is a shared helper, not a proof.)

## 7. Front-end and other

| Component | Tests | Status |
|---|---|---|
| `reference-data` (composed) | 3 `.spec.ts` | not in CI (the **template** copy is) |
| `web-front-end` (Angular) | 10 `.spec.ts` | **inherited scaffolding — never executed here** |
| `price-publisher` (Node) | 1 | not in CI |
| `database`, `ingress`, `api-explorer` | 0 | no tests |

## 8. What CI executes today

Workflow `engine-tests.yml`, green on run #9 (`f6cf4255`), 8 jobs:

| Job | Scope | Trigger |
|---|---|---|
| `hosted` × 3 (YU13, YU14, YU15) | composed **order-matcher** suite + 4 allocation gates, **then the 4 service modules** (108 tests YU13/YU14, 116 YU15) | push + PR |
| `baseline` | 4 Java baseline services | push + PR |
| `baseline-reference-data` | NestJS baseline | push + PR |
| `baseline-people-service` | .NET baseline | push + PR |
| `integration-trade-processor` | Testcontainers MariaDB | push + PR |
| `dedicated` | 3-node cluster + wall-clock timing + 2 Epsilon gates | **manual** (`workflow_dispatch`) |

The matrix is the point: each branch renders its **own** effective tree, so the same test name runs
against differently-composed code — which is how a dead-layer/propagation regression becomes
visible. That bug class has bitten this project repeatedly (the multer CVE, `PubSubConfig`, the
gateway keepalive, the kdb tap).

## 9. Honest gaps

- ~~Four Java modules no pipeline runs~~ **CLOSED 2026-07-28** (`8fc0bafa`): all four now run in the `hosted` job on every branch — **108 tests on YU13/YU14, 116 on YU15**, 0 failures. The `~12 @SpringBootTest` warning was overstated: only **6 classes** needed Spring, and only **one** turned out to be a genuine blocker.
- **One test is deliberately excluded, and it is worth knowing why.** `TradeProcessorApplicationTests` was inert (no `sourceSets` override in the composed tree). Waking it showed the context **cannot start without a live NATS broker** — `tradePublisher` dials `nats.address` during bean creation with no disable flag. It is an integration test that had been sitting in the unit tier passing by never running. It is now excluded from the unit task **visibly, with the reason in the build file**, and belongs in the Testcontainers tier beside `TradeProcessorPersistenceIT`.
- **The Angular front-end has never been executed here at all.**
- **No proof script runs in CI** — deliberate (they need a live cluster) and documented in the [test strategy](04-RESULT-test-strategy.md), but it means they can rot.
- **q gates aren't wired to anything automated.**

## 10. The three-tier framing

Full rationale in [04-RESULT-test-strategy.md](04-RESULT-test-strategy.md):

| Tier | What | Where |
|---|---|---|
| **1 — in-process** | 330 + 48 tests, 6 gates | CI, every push |
| **2 — end-to-end proofs** | 26 falsifiable scripts | manual, live cluster |
| **3 — cluster + timing** | 3-node, wall-clock, Epsilon | on-demand, idle dedicated hardware |

Nearly every Tier-2 proof has a Tier-1 test asserting the same property in CI — so the proofs are
end-to-end *confirmation* of invariants already gated on every commit, not the only evidence.
