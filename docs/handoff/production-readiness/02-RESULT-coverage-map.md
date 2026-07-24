# 02 RESULT — Test-coverage inventory and map

> Deliverable for [02-test-coverage-inventory.md](02-test-coverage-inventory.md). Board: [00-INDEX](00-INDEX.md).
> Documentation-only. Measured 2026-07-24 by running every suite locally, one at a time.
> **The map (§2) is the slide. The ranked gaps (§4) are the work plan for briefs 03 and 04.**

## 1. Headline findings

1. **The engine layer is not under-tested. 853 green tests across the three live branches**
   (YU13 270 / YU14 283 / YU15 300), plus 4 exact-zero allocation gates, 2 Epsilon-GC gates, and
   determinism assertions at four separate layers. The premise of the testing ask is wrong here.
2. **Nothing runs in CI. Not one test — ours or inherited.** The repo's five GitHub Actions
   workflows run docs, spec-kit, OpenAPI and script-parity checks only. `./gradlew test` appears in
   no workflow. `run-all-conformance-packs.sh` runs in CI *without* `--execute-runtime-checks`, so
   it is a static conformance check. **The entire testing story currently lives on a laptop.**
   This is a bigger finding than "the proof scripts aren't in CI" — the JUnit suites aren't either.
3. **Three inherited services have tests that Gradle silently ignores.** Upstream TraderX puts test
   sources at `src/main/test/java`, which is not a Gradle test source dir. `account-service`
   carries an explicit `sourceSets` override (with a comment explaining exactly this); every other
   inherited Java service does not. Net effect: **`trade-service` has zero executable tests**, and
   the inherited smoke tests for `trade-processor` and `position-service` are dead files.
4. **17 falsifiable proof scripts are our strongest correctness evidence and have zero regression
   protection.** Cancel, ClOrdID suppression, STP + atomic replace, FIX session, reconciliation,
   reproducible regulatory export, the EOD risk extract — all proven by scripts that were genuinely
   falsified before passing, none of which run automatically, ever.
5. **There is no cross-service integration test anywhere.** Every cross-service claim is proven by a
   shell script against a live kind/GKE cluster. Nothing in JUnit spans two services.

## 2. The coverage map — component × test type × in CI?

Legend: ✅ substantial · ◐ token/partial · ✗ none · **CI = does any automated pipeline run it**

### Our layers (YU02–YU15)

| Component | Unit | Integration | Gates | Determinism | Proof script | In CI? |
|---|---|---|---|---|---|---|
| Matching engine + limit order book | ✅ deep (`LimitOrderBookTest` 24, `LmaxHotPathParityTest` 12, `OutputDisruptorHandlersTest` 13) | ◐ hot-path parity (Spring) | ✅ alloc + Epsilon | ✅ book-hash | `yu13-stp-and-replace.sh` | ✗ |
| Cluster / consensus (YU12) | ✅ deep (`ClusterSnapshotCodecTest` 18) | ✅ `ThreeMemberClusterTest` (real Aeron) | ✅ cluster apply-path | ✅ 3-member byte-identical | `yu13-gke-replace-proof.sh` | ✗ |
| Aeron replication (YU11) | ✅ 25 | ✅ 6 real-MediaDriver tests | ✅ transport claim/encode | ✅ shadow round-trip | `run-aeron-replication-phase0.sh` | ✗ |
| Journal / durability | ✅ 30 | ◐ | — | ✅ replay | `failover-nodeclock.sh` (timing) | ✗ |
| Gateway — REST | ✅ 36 (`CommandAuthorizationControllerTest` 25) | ◐ contract test | — | — | `yu03-risk-demo.sh` | ✗ |
| Gateway — FIX (YU10/13) | ✅ 12 | ✅ `FixSessionIntegrationTest` | — | — | `yu10-fix-session.sh`, `yu13-fix-cancel.mjs` | ✗ |
| Gateway — binary | ✅ 6 | — | — | — | (bench only) | ✗ |
| Risk gateway (YU03/04) | ✅ 48 (`BlpRiskStateTest` 16, `ControlFeedBootstrapStateTest` 15) | ◐ | ✅ risk alloc gate | ✅ `RiskReplayDeterminismTest` | `yu04-live-delta.sh`, `yu04-offline-catchup.sh` | ✗ |
| Auth / entitlements (YU05) | ✅ 10 | — | — | — | `yu05-auth-entitlements.sh` | ✗ |
| Reporting / audit (YU05) | ✅ 5 | ✅ `RegulatoryReportDeterminismTest` (Spring) | — | ✅ byte-identical export | `yu05-regulatory-reproducible.sh` | ✗ |
| Order read model (YU13) | ◐ feed handler only — **no controller test** | ✗ | — | — | ad-hoc GKE run, **not committed** | ✗ |
| Post-trade / recon / settlement (YU05) | ✅ 11 (trade-processor) | ✗ | — | — | `yu05-recon.sh`, `yu05-settlement.sh` | ✗ |
| EOD price chain (YU06) | ✅ 3 | ✗ | — | — | (inside `yu15-risk-extract.sh`) | ✗ |
| Tick store (YU07) | ✅ 1 | ✗ | — | — | `taq-replay.mjs` (bench) | ✗ |
| Execution algo engine (YU08) | ✅ 8 | ✗ | — | ✅ event-store replay | ✗ | ✗ |
| Listed options (YU14) | ✅ `OccSymbolTest` + risk multiplier | ✗ | — | — | `yu15-option-persistence.sh`, `bs-check-option-quoting.mjs` | ✗ |
| EOD risk extract (YU15) | ✅ 17 | ✅ GCS sink live-proof test | — | ✅ cut identical across members | `yu15-risk-extract.sh` | ✗ |

Where the 300 YU15 tests live, by package (exact, from the results XML):

| Package | Tests |
|---|---|
| `ordermatcher.lmax` (engine, book, journal, Aeron replication) | 122 |
| `ordermatcher.cluster` (consensus, snapshot, FIX/binary gateway, risk extract) | 69 |
| `ordermatcher.risk` (BLP risk state, control feeds, idempotency) | 48 |
| `ordermatcher.controller` (REST) | 36 |
| `ordermatcher.auth` | 10 |
| `ordermatcher.fix` | 7 |
| `ordermatcher.reporting` | 5 |
| `ordermatcher.service`, root | 3 |

Plus 3 gate classes / 4 gate methods that run as separately forked tasks and are **not** in the 300.

### Inherited vanilla TraderX services

| Component | Tests present | Executable? | Ours vs inherited | In CI? |
|---|---|---|---|---|
| `account-service` (Java) | 4 | ✅ — has the `sourceSets` fix | 1 inherited smoke + 3 ours (YU04 outbox) | ✗ |
| `trade-service` (Java) | 1 | **✗ dead** (`src/main/test`, no override) | inherited smoke only | ✗ |
| `trade-processor` (Java) | 12 | ◐ 11 run, 1 dead | 11 ours (YU05/06/07/13), 1 inherited dead | ✗ |
| `position-service` (Java) | 2 | ◐ 1 runs, 1 dead | 1 ours (YU06 EOD P&L), 1 inherited dead | ✗ |
| `reference-data` (NestJS) | 3 | ✅ `jest` configured | **all 3 ours** — vanilla had zero | ✗ |
| `people-service` (.NET) | 0 | — | no test project exists | ✗ |
| `web-front-end` (Angular) | 10 `.spec.ts` | unknown — **never executed here** | all inherited scaffolding | ✗ |
| `database`, `ingress`, `api-explorer` | 0 | — | — | ✗ |
| `price-publisher` (Node) | 1 | ✅ `node --test` | ours (YU14 option quotes) | ✗ |
| `aeron-replication-sidecar` | 1 | ✅ | ours | ✗ |

### What CI actually runs today

| Workflow | Runs | Executes tests? |
|---|---|---|
| `spec-kit-root-gates.yml` | spec-kit integrity, readiness, expressiveness, conformance packs (static), coverage | ✗ |
| `docs-spec-sanity.yml` | same static gates + docusaurus build | ✗ |
| `runtime-script-parity.yml` | lifecycle-script contract, state-doc consistency | ✗ |
| `generate-openapi.yml` | starts compose, regenerates OpenAPI | ✗ |
| `deploy-docusaurus-pages.yml` | website publish | ✗ |

The 31 `scripts/test-state-*.sh` files are **not runtime tests** — they verify generation output and
spec-pack completeness. They contain no `curl`, `kubectl` or `docker`, and CI does not run them either.

## 3. Proof scripts — the falsifiable, non-CI tier

`scripts/bench/` holds 45 files. 17 are falsifiable proofs (assert and exit non-zero); the rest are
benchmarks and utilities. Proofs, and what they establish:

| Script | Proves | Falsified before passing |
|---|---|---|
| `yu03-risk-demo.sh` | two-tier risk gateway, restricted-security toggle | yes |
| `yu04-live-delta.sh` | control-feed delta reaches the BLP replica with no restart | yes |
| `yu04-offline-catchup.sh` | offline replica bootstraps from watermarked snapshot + buffered deltas | yes |
| `yu05-auth-entitlements.sh` | real JWT + per-account entitlement enforcement, both axes | yes |
| `yu05-recon.sh` | CQRS integrity: journal vs projection, incl. orphan detection | yes |
| `yu05-regulatory-reproducible.sh` | regulatory export is a pure function of (journal range, seed) | yes |
| `yu05-settlement.sh` | T+N settlement lifecycle | yes |
| `yu10-fix-session.sh` | FIX ingress E2E through the same ring/journal/risk/DB path as REST | yes |
| `yu13-cancel-ingress.sh` | cancel takes effect identically on every member | yes |
| `yu13-clordid-suppression.sh` | a resent ClOrdID books exactly once (asserted in SQL) | yes — ran against pre-change members |
| `yu13-stp-and-replace.sh` | self-trade prevention (cancel-oldest) + engine-native atomic replace | yes — same scenarios shown failing first |
| `yu13-gke-replace-proof.sh` | replace ack-correlation, 3-member identity, snapshot+rebuild survival | yes |
| `yu13-fix-cancel.mjs` | FIX 4.4 `OrderCancelRequest` against the real acceptor | yes |
| `yu15-option-persistence.sh` | OCC symbols reach SQL; the migration repairs an older DB | yes — found the VARCHAR(15) blocker |
| `yu15-risk-extract.sh` | full EOD chain → byte-identical cut on all 3 members → write-once delivery | yes |
| `bs-check-option-quoting.mjs` | put-call parity on the option quoting path | yes |
| `failover-nodeclock.sh` | node-clock-precise failover timing (measurement, not pass/fail) | n/a |

**A proof exists ≠ CI runs it.** Every row above is hand-run. Worse, the YU13 order-read-model
effect-end proof (NEW → CANCEL → gone from open orders, run live on GKE 2026-07-23) exists only in a
session transcript — **it is not even a committed script.** That one is a gap in both columns.

## 4. Ranked gap list — the work plan for briefs 03 and 04

Ranked by demo centrality × how likely a reviewer is to poke at it.

| # | Gap | Why it ranks here | Fix | Brief |
|---|---|---|---|---|
| 1 | **No CI job runs any test** | A FINOS reviewer's first click is the Actions tab. We have 853 green tests and the badge wall says "docs". Cheapest, highest-visibility win in the whole production-readiness phase. | One workflow: matrix over the Java components, `./gradlew test` per component, one at a time. Gates as a second job. | 04 |
| 2 | **`trade-service` has zero executable tests** | It sits on the demo's order path (web → trade-service → order-matcher) and is the first place a reviewer reads code. | Add the `sourceSets` override (copy `account-service`'s, comment included), then write real tests. | 03 |
| 3 | **Dead inherited smoke tests** in `trade-processor`, `position-service` | Silent `NO-SOURCE`. Looks like coverage, is not. Same one-line cause as #2. | Same `sourceSets` override, ×2. | 03 |
| 4 | **17 proof scripts outside CI** | Our strongest correctness evidence, zero regression protection. A deterministic-core change cannot be rolled gradually — these scripts are what catch that class of bug. | Nightly kind job running the `yu0*`/`yu1*` proofs. Not per-PR: they need a live cluster. | 04 |
| 5 | **No cross-service integration test at all** | Exactly where the FINOS feedback pointed. Every cross-service claim rests on a shell script. | Testcontainers-style integration suite: order-matcher + trade-processor + MariaDB + NATS. | 04 |
| 6 | **`people-service` (.NET): no test project** | Small service, but "zero tests" is an easy reviewer hit and trivially fixable. | `dotnet new xunit`, a handful of controller tests. | 03 |
| 7 | **Angular specs never executed** | 10 inherited spec files of unknown status. If they fail, that is worse than having none. | Run `npm run test:ci` once; record the result; then decide keep/fix/delete. | 03 |
| 8 | **Order read-model `/orders` has no test** and its proof isn't committed | Newest feature, live-demoed, single controller, no coverage at either tier. | Controller test + commit the effect-end proof as `yu13-readmodel-effect-end.sh`. | 03 + 04 |
| 9 | **`reference-data` vanilla service untested** | All 3 specs are ours; the inherited stock/security logic has none. It feeds the risk gateway. | Jest specs for the vanilla controllers/services. | 03 |
| 10 | **`database`, `ingress`, `api-explorer`: no tests** | Thin config-ish components. Lowest priority; note them, don't gold-plate. | Smoke coverage only, if at all. | — |

## 5. Statement of testing posture (for the talk)

> The engine layer is machine-verified. 853 tests run green across the three active branches, and
> the properties that matter are asserted, not asserted-about: the hot path is proven to allocate
> exactly zero bytes in steady state under both C2 and Epsilon GC; the consensus apply path is
> byte-identical across all three cluster members, verified after millions of orders; the regulatory
> export and the EOD risk extract are reproducible pure functions of the journal. A further seventeen
> capabilities — cancel, duplicate-order suppression, atomic replace, FIX session behaviour, CQRS
> reconciliation, the risk extract — are proven by falsifiable scripts that were each shown failing
> against the unfixed system before they were allowed to pass.
>
> Two honest gaps. First, none of it runs in CI today: the verification is real but it is operator-
> driven, and that is the first thing we are fixing. Second, coverage is uneven — it is deep in the
> layers we built and thin in the services we inherited from vanilla TraderX and in the seams between
> services. That is where the next round of test work goes, and it is exactly where our reviewers
> pointed.

## 6. Corrections to the brief

- **YU13 is 270, not 269** (69 classes, 4 skipped). YU14 283 and YU15 300 are exact.
- **The gate count double-counts.** There are **4 allocation-gate tasks** (`allocationGateTest`,
  `riskAllocationGateTest`, `aeronAllocationGateTest`, `clusterAllocationGateTest`) — all wired as
  `test` dependencies, so `./gradlew test` runs them — and **2 Epsilon-GC gates** (`noGcTest`,
  `riskNoGcTest`) which are **not** wired into `test`. `noGcTest` *is* one of the two Epsilon gates,
  not a third thing alongside them.
- **The brief's CI finding understates the problem.** It flags that the proof scripts don't run in
  CI. True — but neither do the JUnit suites, the gates, or anything else. No test of any kind runs
  in CI. That reframes gap #1 from "move the proofs into CI" to "put CI in place at all."
- **A category the brief doesn't name:** upstream's `src/main/test` convention silently disables
  tests unless a component overrides `sourceSets`. That is a *coverage* gap disguised as coverage,
  and it hits three inherited services.

## 7. Method — how these numbers were produced

```bash
cd generated/code/target-generated/order-matcher && ./gradlew cleanTest test --no-daemon
```

Run per branch, **sequentially**, never concurrently. Counts parsed from
`build/test-results/test/*.xml` (`tests`, `failures`, `errors`, `skipped`).

Observed, and **not** coverage gaps:

- **Skipped tests are opt-in benchmark drivers**, gated behind system properties:
  `JournaledBlpBenchmarkDriverTest`, `ReplicationThroughputBenchmarkTest`,
  `AeronReplicationPhase0Test`, and on YU13/YU15 one more. Not missing coverage — deliberate.
- **`SnapshotBarrierPerformanceTest` is a wall-clock threshold test** (50 ms budget) and missed on
  one YU15 run at 86.5 ms while the laptop was otherwise busy. Re-run in isolation **3 times, 3
  passes**. Environment-sensitive, not a coverage gap.
- **`riskNoGcTest` (Epsilon) hit the documented exactly-72-byte artifact** during the batch run.
  Re-run in isolation **3 times, 3 passes** — the C2 rematerialization artifact root-caused
  2026-07-16, not a hot-path allocation and not a coverage gap.
- The other three allocation gates (`allocationGateTest`, `aeronAllocationGateTest`,
  `clusterAllocationGateTest`) passed first time in the batch run.

**Consequence for gap #1:** the CI job must run suites one at a time, and
`SnapshotBarrierPerformanceTest` needs either a runner-calibrated budget or exclusion from the
per-PR job. A timing assertion on shared CI hardware will flap.
