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
