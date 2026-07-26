# 03 — RESULT: baseline-service unit tests

> Deliverable for [brief 03](03-baseline-unit-tests.md). A green, CI-run unit suite for the
> plain-vanilla TraderX baseline services we forked. Done 2026-07-25 (Java + TS + .NET).

## Headline

All six inherited baseline services — four Java/Spring, one NestJS/TypeScript, one .NET — went from
**0 executable tests to 48**, all green, all now run in CI (`baseline`, `baseline-reference-data`,
and `baseline-people-service` jobs in `engine-tests.yml`). For the four Java services the "before"
was not "thin coverage" — it was **zero**: each shipped a single `@SpringBootTest contextLoads` smoke
at the non-default `src/main/test/java`, which Gradle silently skipped as NO-SOURCE (dead files). The
TS and .NET services had no test setup at all.

## Before / after (per service)

| Service | Stack | Before | After | What the new tests cover |
|---|---|---|---|---|
| account-service | Java/Spring | 0 (1 inert smoke) | 8 | service found/not-found-signalled/delegation; controller 404 + 500 error mapping |
| trade-service | Java/Spring | 0 (1 inert smoke) | 5 | order-admission gate: valid→published; **ticker/account unknown → rejected AND never published**; publish failure surfaced |
| position-service | Java/Spring | 0 (1 inert smoke) | 5 | account-scoped vs all-accounts read; controller 200 + 500-on-store-failure |
| trade-processor | Java/Spring | 0 (1 inert smoke) | 7 | **position math** (buy adds / sell subtracts / accumulation / short with no guard); trade ends Settled; per-account publish topics; publish best-effort |
| reference-data | NestJS/TS | 0 | 7 | ticker lookup miss → NotFoundException (not silent 200); **CSV loader** FB→META rename, dedup, supported-tickers allow-list, max-tickers cap |
| people-service | .NET/xUnit | 0 | 16 | controller 400/404/200 validation boundaries (missing-id, short-search, not-found); directory logonId-preferred/employeeId-fallback lookup, take cap, loud FileNotFound |
| **Total** | | **0** | **48** | |

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
- **No new test frameworks where the platform already had one.** reference-data uses **Node's
  built-in test runner** (`node:test` + `node:assert`) via the already-present `ts-node` — no jest /
  ts-jest added. people-service uses xUnit (the .NET standard) with **hand-written fakes** for
  `IDirectoryService` / `IWebHostEnvironment` — no Moq dependency.

## Deliberately NOT covered (and why)

- **Per-state *composed* trees.** These suites test the **baseline** (pre-YU01) templates directly —
  the code we forked. Running the same tests against each YU branch's generated tree (to catch a
  higher-layer `build.gradle` override that drops `sourceSets` and re-silences them) is **brief 04**'s
  "extend the CI matrix" item, not baseline scope.
- **Repository/JPA query correctness** (e.g. `findByAccountIdAndSecurity` actually filtering) — that
  is a `@DataJpaTest`/integration concern (brief 04), not a unit concern. Unit tests assert the
  service calls the account-scoped method, not that the DB honours it.

## CI wiring

Three standalone jobs in `.github/workflows/engine-tests.yml` (no effective-tree render), each on
push to YU15 and every PR:

- **`baseline`** — the four Java services via `scripts/ci/baseline-tests.sh` (single source of truth
  for the gradle invocations, mirrors `engine-tests.sh`; runs the suites one at a time). JDK 21,
  uploads JUnit XML.
- **`baseline-reference-data`** — `npm ci && npm test` in `templates/reference-data-specfirst`
  (Node 22).
- **`baseline-people-service`** — `dotnet test` on the xUnit project (.NET 9).
