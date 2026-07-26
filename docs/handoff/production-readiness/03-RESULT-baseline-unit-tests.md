# 03 — RESULT: baseline-service unit tests

> Deliverable for [brief 03](03-baseline-unit-tests.md). A green, CI-run unit suite for the
> plain-vanilla TraderX baseline services we forked. Done 2026-07-25.

## Headline

The four inherited Java/Spring baseline services went from **0 executable tests to 25**, all green,
all now run in CI (new `baseline` job in `engine-tests.yml`). The "before" was not "thin coverage" —
it was **zero**: each service shipped a single `@SpringBootTest contextLoads` smoke test at the
non-default `src/main/test/java`, which Gradle silently skipped as NO-SOURCE. Those smoke tests were
dead files. Now they run, plus real behavioural unit tests.

## Before / after (per service)

| Service | Before (executable) | After | New tests | What the new tests cover |
|---|---|---|---|---|
| account-service | 0 (1 inert smoke) | 8 | 7 | service found/not-found-signalled/delegation; controller 404 + 500 error mapping |
| trade-service | 0 (1 inert smoke) | 5 | 4 | order-admission gate: valid→published; **ticker/account unknown → rejected AND never published**; publish failure surfaced |
| position-service | 0 (1 inert smoke) | 5 | 4 | account-scoped vs all-accounts read; controller 200 + 500-on-store-failure |
| trade-processor | 0 (1 inert smoke) | 7 | 6 | **position math** (buy adds / sell subtracts / accumulation / short with no guard); trade ends Settled; per-account publish topics; publish best-effort |
| **Total** | **0** | **25** | **21** | |

(The 4 previously-inert smoke tests now execute too: 21 new + 4 = 25.)

## The one-line root cause (talk content)

Upstream TraderX puts test sources at `src/main/test/java`, **not** Gradle's default `src/test/java`.
Without a `sourceSets` override telling Gradle where to look, `./gradlew test` reports NO-SOURCE and
**every test is inert** — it passes because nothing ran. `account-service`'s *deployed* YU04 layer
already carried the fix; the baseline templates did not. The fix is the same four lines in each
`templates/*-specfirst/build.gradle`:

```gradle
sourceSets { test { java.srcDirs = ['src/main/test/java']; resources.srcDirs = ['src/main/test/resources'] } }
```

This is an inherited-baseline behaviour (a silent-pass test harness), so it is **talk content**, not
our omission — and it is exactly the class of silent failure brief 03 wanted surfaced.

## Design choices

- **Pure unit tests — no Spring context, no DB.** Repositories/publishers are mocked (Mockito);
  controllers use standalone `MockMvc`; the trade-service HTTP validation gate uses
  `MockRestServiceServer`. They run in CI with zero infrastructure, sidestepping the documented
  `@SpringBootTest`-needs-a-DB trap (see 00-INDEX CI radar).
- **The pre-existing `@SpringBootTest contextLoads` smoke tests were kept and made to pass.**
  account/trade-service load an in-memory H2 as-is; position-service and trade-processor point their
  *production* config at a networked H2 (`jdbc:h2:tcp://…`), so each got a **test-only**
  `src/main/test/resources/application.properties` with an in-memory H2 (production config untouched).
- **Tests target failure signalling, not the happy path.** The load-bearing assertions are the ones
  that would have caught bugs this project has actually shipped: an order rejected upstream but still
  published, a not-found returned as a silent empty body, a position sign flip.

## Deliberately NOT covered (and why)

- **`reference-data` (NestJS/TypeScript) and `people-service` (.NET)** — not Java/Gradle, so out of
  this pass. The FINDINGS doc flags front-end/TS as the one area with additive upstream churn; defer
  until after the dependency rebase settles (per brief 01 guidance). Jest/xUnit suites are a separate,
  smaller follow-up.
- **Per-state *composed* trees.** These suites test the **baseline** (pre-YU01) templates directly —
  the code we forked. Running the same tests against each YU branch's generated tree (to catch a
  higher-layer `build.gradle` override that drops `sourceSets` and re-silences them) is **brief 04**'s
  "extend the CI matrix" item, not baseline scope.
- **Repository/JPA query correctness** (e.g. `findByAccountIdAndSecurity` actually filtering) — that
  is a `@DataJpaTest`/integration concern (brief 04), not a unit concern. Unit tests assert the
  service calls the account-scoped method, not that the DB honours it.

## CI wiring

- New `scripts/ci/baseline-tests.sh` — single source of truth for the gradle invocations (mirrors
  `engine-tests.sh`); runs the four suites one at a time.
- New `baseline` job in `.github/workflows/engine-tests.yml` — standalone (no effective-tree render),
  JDK 21, uploads JUnit XML. Runs on push to YU15 and on every PR.
