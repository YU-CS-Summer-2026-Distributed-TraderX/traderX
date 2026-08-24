# Three CI scripts assert "the suite executed" at three different strengths

> A record, not a rig you can query. Line numbers and counts are from 2026-08-24 on
> `YU17-otc-rates`; re-derive them.

Found 2026-08-24 while closing
`issues/resolved/the-suite-gate-cannot-tell-reduced-from-complete.md`, and confirmed independently
by the coordinator against the tree the same day. That issue fixed **one** of these three. This one
records the other two, because filed separately they read as tidy-ups and filed together they read
as what they are: the same blindness, at three strengths, in the three scripts that decide whether
this project's suites ran.

## The class

Every one of these answers "did the suite execute?" from an artifact **directory**, and any filtered
or partial run rewrites that directory. See `.claude/skills/vacuous-pass-audit`.

| script | what it asserts | blind to |
|---|---|---|
| `scripts/ci/assert-suites-executed.sh` | ~~summed `tests`, fails on zero~~ **now: every source test class produced a result, all tiers** | **FIXED 2026-08-24** — residual: method-level reduction, stale tiers |
| `scripts/ci/service-tests.sh:73` | counts result **files**, fails on zero, `test` tier only | reduced runs; `tests="0"`; every non-default tier |
| `scripts/ci/baseline-tests.sh` | nothing — gradle exit code is the whole verdict | all of it |

**`service-tests.sh` is the weakest of the three, not the middle one.** Three weaknesses stacked:

```bash
ran="$(find "${dir}/build/test-results/test" -name '*.xml' 2>/dev/null | wc -l | tr -d ' ')"
if [[ "${ran}" == "0" ]]; then ...
```

1. **Counts files, not tests.** A suite emitting one result XML that reports `tests="0"` is a pass
   here. The fixed gate has a dedicated selftest case (`bad`) for exactly that lie, because it is
   the one that counting files cannot ever catch.
2. **Zero-check only.** Same reduced-run blindness the sibling issue documents: a
   `cleanTest test --tests '…'` leaves one file, `1 != 0`, pass.
3. **Single tier.** It globs `build/test-results/test` alone, so it cannot see results from any
   non-default Test task — the same hole that made the sweeping gate structurally unable to see
   order-matcher's four allocation gates until 2026-08-24.

**`baseline-tests.sh` has no assertion at all.** Its only failure path is
`echo "::error::baseline suite failed: ${svc}"` off a non-zero gradle exit. Its own sibling's header
comment explains why that is not enough — `test` is UP-TO-DATE, NO-SOURCE, or never invoked, and in
all three the exit code is 0 — and the baseline job is where that actually happened: its suites had
ZERO tests running in CI until the `sourceSets` fix, and nothing in the script would have said so.

## The constraint whoever picks this up needs

**`baseline-tests.sh` cannot just call the sweeping gate.** It covers `templates/*-specfirst` —
standalone gradle projects, deliberately not rendered — while `assert-suites-executed.sh` walks
`generated/code/target-generated`. That mismatch is the whole reason the baseline job has no
assertion: the obvious fix does not apply, and nobody wrote the non-obvious one.

The sweeping gate already takes a tree root as `$1` and discovers modules by `build.gradle`, so
pointing it at `templates/` may be most of the answer. Not verified — `templates/*-specfirst` layout
was not checked against the module-discovery walk, and the `.github/workflows/engine-tests.yml`
`baseline` job would need the step added.

## Why this is small

The diagnosis is done; the remaining work is mechanical. The fixed gate is the worked example, it
ships a 7-case `--selftest` including the `reduced` detonator, and both replacement readings
(sum the `tests` attribute; compare the source class set) are already written and exercised there.

**Break it first, as always:** run a filtered subset, then assert each script fails. A check that
passes on both a complete and a reduced run is asserting nothing.

## Not established

- Whether either script has ever actually passed a reduced or empty run in CI. The mechanism is
  confirmed by reading; no historical run was audited.
- Whether `assert-suites-executed.sh` can be pointed at `templates/` unmodified.
- These three are the `scripts/ci/` population. `pipeline/*.sh` was **not** swept for the same
  pattern.

## Carry

`service-tests.sh` and `baseline-tests.sh` are per-branch copies, like everything under `scripts/`.
Fix the branches that carry them, not just the tip —
`.claude/skills/propagate-spec-fix`, "Carriers outside `specs/`". `main` is not a propagation target.

---

## Resolution (2026-08-24)

All three scripts now answer "did the suite run?" with **one** reading, in
`scripts/ci/assert-suites-executed.sh`. The two open scripts stopped carrying their own and became
callers of it, a module at a time.

### The diagnosis above was right about the two open scripts and wrong about the fixed one

**`assert-suites-executed.sh` had a third hole of the same shape, and it was live while this issue
was being written.** It discovered test sources at `src/test` / `src/integrationTest` only, while
gradle is told where they are by the module's own `sourceSets { test { java.srcDirs = … } }`. On the
rendered tree that made it silently blind to a whole module: `generated/…/account-service` has **no
`src/test` at all** — eight test classes at `src/main/test/java` behind an override. The gate did not
fail it, did not exempt it, did not mention it. It was absent from the output, which reads exactly
like "nothing to check here". Both gates on byte-identical artifacts:

```
OLD  ==== 602 tests executed across 6 module(s) with test sources ====   (account-service: 0 mentions)
NEW  ==== 634 tests executed across 7 module(s) with test sources ====   account-service: 32 tests / 8 classes
```

Composed `position-service` and `trade-processor` each hid one further class the same way. So the
2026-08-24 fix closed the reduced-run hole and the result-tier hole and left the **source**-tier hole
— three instances of one defect in one script in one day.

### The issue's proposed shortcut for baseline was false

> "The sweeping gate already takes a tree root as `$1` … so pointing it at `templates/` may be most
> of the answer."

It was none of the answer, for the same reason: every `templates/*-specfirst` keeps its tests at
`src/main/test/java`. `assert-suites-executed.sh templates` found zero modules with test sources and
exited 1 on its own "this gate checked nothing" guard. The issue flagged this as unverified; it is
now verified false. Making the gate reusable here **was** the work, not a routing detail.

### What changed

1. **`assert-suites-executed.sh` derives test source roots from the module's `build.gradle`** instead
   of assuming two. Deriving, rather than adding `src/main/test` as a third hardcoded root, is
   load-bearing in both directions: composed `trade-service` carries
   `src/main/test/java/…/TradeServiceApplicationTests.java` with **no** override, so gradle never
   compiles it and demanding a result would fail the module wrongly — wrong in the direction that
   looks like thoroughness. One derived rule gets `account-service` and `trade-service` right; two
   hardcoded directories get one of them right. Written down at the derivation site.
2. **`EXEMPT_CLASSES` entries are scoped to a module** (`order-matcher/ThreeMemberClusterTest`). This
   makes the stale-exemption check *stricter* (the class must exist in that module, not merely
   somewhere under the root) and makes the gate root-agnostic, which is what lets the runners call it
   per module. Entries whose module is not under the current root are counted and reported as not
   applied rather than passed over silently. One new standing entry:
   `trade-processor/TradeProcessorApplicationTests`, excluded by `excludeTestsMatching` in the
   composed build. Deliberately a standing entry rather than parsing `excludeTestsMatching` out of
   the build file — a derived exclusion would let anyone silence the gate by editing a build file,
   with no stale check and no review. The silencer stays guarded.
3. **`service-tests.sh`** dropped its result-**file** count and calls the gate per module.
   Per module rather than once over the tree so failures still name the module, and so the script
   stays usable standalone (a whole-tree call goes red on `order-matcher` for anyone who has not just
   run `engine-tests.sh`, and a gate that is unusable by hand is a gate people route around).
4. **`baseline-tests.sh`** gained its first assertion, same shape.
5. **Absent gate is a hard error in both runners**, never a skip. A runner that drops its assertion
   because the assertion script is missing is this defect one level up.
6. `--selftest` goes 7 → 10 cases. The three new ones are a three-arm harness: `srcdirs-redirected`
   (override, class ran → green), `srcdirs-reduced` (override, one of two classes ran → red; only
   visible *through* the override), `srcdirs-dormant` (file under `src/main/test`, no override →
   green). Same reason the `integ-*` pair has two arms: one arm alone is satisfied by discovery that
   never fires.

### Red path exercised on real artifacts

A real filtered run of `generated/…/account-service` — `cleanTest test --tests '*AccountServiceTest*'`,
`BUILD SUCCESSFUL`, 8 result XMLs down to 1:

| check | verdict on that reduced run |
|---|---|
| `service-tests.sh`'s old assertion, run verbatim | **passes** — `ran=1`, reports "1 test classes executed" |
| the old sweeping gate | **passes**, exit 0, prints 602 — the same number as the full run |
| `baseline-tests.sh` before this change | no assertion existed to run |
| the new gate | **exit 1**, naming all seven classes that produced no result |

Then green on restored full runs: `--selftest` 10/10; `service-tests.sh` exit 0 (six modules, 209
tests); `baseline-tests.sh` exit 0 — the baseline job's first-ever assertion, 25 tests / 10 classes
across four services. The whole-tree gate is green at 634 tests / 7 modules.

### CONSEQUENCE THAT OUTLIVES THIS ISSUE: `main` is now the weakest branch, and `main` is where CI runs

`main` carries `service-tests.sh` and `baseline-tests.sh` byte-identical to the family and does **not**
carry `assert-suites-executed.sh`. The propagation rule excludes `main` — correctly, it is reached by
PR — so when this change moves across YU13…YU17, **`main` becomes the only branch still running the
weak check**, and it is the branch whose CI matters most. This is a named consequence, not an
oversight: the remedy is a PR carrying all three scripts to `main`, and until that PR lands the
assertion strength on `main` is the pre-2026-08-24 one. Do not read the carry set below as covering
`main`.

### Carry

Hashes re-derived across all eighteen worktrees rather than inherited from the issue text; the carry
set was larger than "the branches that have the gate":

| script | carried to |
|---|---|
| `assert-suites-executed.sh` | YU15, YU16, YU17 — **and newly added to YU13, YU14** |
| `service-tests.sh` | YU13, YU14, YU15, YU16, YU17 |
| `baseline-tests.sh` | YU15, YU16, YU17 (YU13/YU14 do not carry it) |

YU13 and YU14 carry `service-tests.sh` but had no gate, so the gate went with it. That is not a
regression risk: `.github/workflows/engine-tests.yml` (which exists only on YU15+) already runs
`bash scripts/ci/assert-suites-executed.sh` on its YU13 and YU14 matrix legs, against checkouts that
do not contain the file — those legs are failing on a missing path today. The workflow's own matrix
comment reasons explicitly about which scripts a leg's checkout must hold; the gate was added later
without extending that reasoning. All three exempted classes and `.github/ci/exclude-heavy.gradle`
were confirmed present on YU13 and YU14, so the standing exemptions do not read as stale there.
**The gate is not exercised on YU13/YU14** — verifying it would mean two full branch renders into the
shared `generated/` area while other lanes are live in those worktrees.

### Still not closed by this

- **Method-level reduction and stale tiers.** Unchanged from the sibling issue: a class whose
  `@Test` methods were filtered still produces its XML and passes, and `cleanTest` cleans only the
  `test` tier, so a leftover `allocationGateTest` directory satisfies the class requirement without
  having run. Both need a freshness reference the gate is not given.
- **`pipeline/*.sh` was still not swept** for this pattern. The issue said so; it is still true.
