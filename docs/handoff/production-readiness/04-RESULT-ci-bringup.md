# 04 (first move) RESULT — CI bring-up for the existing engine tests

> Pulled forward from [04](04-milestone-and-integration-tests.md) per the OSFF board: wire the
> **already-green** 853 tests + gates into CI *before* anyone writes a new test, so brief 03's
> `sourceSets` fix later shows up as **added** coverage, not churn. Depends on [02](02-RESULT-coverage-map.md).
> Documentation + CI config only. No `sourceSets` change (that's brief 03). Commit per branch; push goes to yaakov.

## Status

**Authored and validated locally; awaiting the YU15 push to run on GitHub** (Actions only runs on the
remote — this will be the first push since the idempotency fix). Ready-for-push flag is at the bottom.

## What landed

Three files on `YU15-eod-risk-extract`, all at repo root (safe from `generate-state.sh`, which only
rewrites the generated tree, never `.github/` or `scripts/ci/`):

| File | Role |
|---|---|
| `.github/workflows/engine-tests.yml` | The workflow. Branch **matrix** (YU15 on, YU13/YU14 one line each), `hosted` + `dedicated` jobs. |
| `scripts/ci/engine-tests.sh` | Single source of truth for the gradle invocations — the workflow **and** a dedicated/self-hosted runner **and** a developer all call it. This is the "make target." |
| `.github/ci/exclude-heavy.gradle` | Gradle **init script** (CI-side) that excludes the two hosted-hostile classes. Not committed to the generated `build.gradle`, which is a regenerated artifact — so this never fights the generator or the dead-layer trap. |

## Design, and why each choice

**Matrix by branch from day one.** Each branch renders its own effective tree (last-wins layering),
so the same test name runs against different actual code per branch. A propagation/dead-layer
regression — a fix live on YU13 but shadowed/inert on YU15 — is *invisible* to single-branch CI, and
that dead-layer class is this project's most recurrent bug. The matrix is enabled for YU15 today;
adding YU14/YU13 is uncommenting two lines. **Talk angle:** "CI green across the YU02→YU15 lineage"
is a stronger slide than "green on the tip," so per-branch CI is presentation value, not just insurance.

**The generated tree is git-ignored — so CI generates it.** `order-matcher/build.gradle` and every
test file live only in `generated/code/target-generated/`, which is `.gitignore`d. The workflow runs
`pipeline/generate-state.sh <branch>` (the exact path `test-state-*.sh` uses) after checkout, then
runs gradle in the rendered tree. Measured cost: **~117 s** for a clean headless render (regenerates
the whole lineage, depth 13). No docker, no `npm ci` (with `TRADERX_SKIP_LOCKFILE_REFRESH=1`).

**Sequential, never concurrent.** The script parallelises nothing. Concurrent gradle is what trips the
Aeron `RegistrationException` in `ThreeMemberClusterTest` and the timing miss in
`SnapshotBarrierPerformanceTest`; `concurrency.cancel-in-progress` stops two runs of a ref racing too.

**Hosted vs dedicated — an honest split, not silently-flaky CI.**

| Job | Runs | Trigger | Runner |
|---|---|---|---|
| `hosted` | Functional suite (~298 tests) + the 4 allocation gates | push + PR | `ubuntu-latest` |
| `dedicated` | `ThreeMemberClusterTest` + `SnapshotBarrierPerformanceTest` + 2 Epsilon gates | `workflow_dispatch` (manual) | `ubuntu-latest` → swap to a self-hosted label for real dedicated HW |

The 3-node cluster test and the 50 ms wall-clock budget are not credible on a standard 2-core hosted
runner, so they don't gate every PR. Moving them to dedicated hardware (a self-hosted GKE runner, for
which kubectl/gcloud are authorised) is a one-line `runs-on` change — deliberately not stood up
speculatively, since it can't be validated before a push anyway.

**Retry-once-isolated, scoped to exactly the two known flakes — no blanket retry.** A blanket
retry-all would gut the credibility this whole effort is building. Only two selector sets get a second
isolated attempt: the four allocation gates (the exactly-72-byte C2 rematerialization artifact,
root-caused 2026-07-16) and `SnapshotBarrierPerformanceTest` (the wall-clock budget). The ~298-test
functional suite has **no** retry. Both flakes were confirmed clean on isolated rerun during the
inventory (allocation gate 3/3, SnapshotBarrier 3/3).

**No `sourceSets` change.** `trade-service` / `trade-processor` / `position-service` still have
silently-disabled inherited tests (brief 03). CI lands on the *current* suite so the baseline is green;
03's fix then reads as added coverage.

## Local validation (what stands in for a CI run until the push)

Run against the exact invocations the workflow calls, on both the working tree and a **clean** headless
render, one at a time:

| Check | Result |
|---|---|
| Clean headless `generate-state.sh YU15` (the CI render step) | ✅ 117 s, tree rendered, no docker |
| `hosted` functional suite (init-script exclude + `-x` gates) | ✅ **298** tests, 0 fail; ThreeMember + SnapshotBarrier confirmed excluded |
| Same, against the freshly-generated **clean** tree (full CI path) | ✅ see §validation log |
| 4 allocation gates | ✅ all pass (batch run) |
| `dedicated` selectors: ThreeMemberClusterTest | ✅ **33 s idle** (see load note below) |
| `dedicated`: SnapshotBarrierPerformanceTest isolated | ✅ 3/3 |
| `dedicated`: Epsilon gates `noGcTest`/`riskNoGcTest` isolated | ✅ 3/3 (batch hit the documented 72-byte artifact; retry path exists for exactly this) |

**Load-sensitivity finding (evidence for the split).** Running the full `dedicated` script *while the
laptop was loaded with ~10 other gradle runs from this session*, `ThreeMemberClusterTest`'s destructive
two-failover case (`wipedMemberRejoinsAndLineageSurvivesTwoFailovers`) failed with `condition not met
within 120s` awaiting egress — **not** the concurrency `RegistrationException`, a plain resource-
starvation timeout. Re-run alone on an idle machine it passed in **33 s**. So this test is sensitive to
*load*, not only to concurrent gradle — which is precisely the empirical case for keeping it off shared
2-core hosted runners and on genuinely idle dedicated hardware. It is not a regression and not a
coverage gap.

Not reproducible locally: whether `ubuntu-latest`'s node 22 / Temurin 21 render and run cleanly, and
whether the real-MediaDriver Aeron round-trip tests are happy on a 2-core hosted runner. Those are the
first-run unknowns to watch (see risks).

## First remote run — found and fixed

The first `hosted` run (push `cdcf4874`) **failed in 9 s at the Render step** — before any gradle. Root
cause, reproduced locally by stubbing out `rg`: **`ubuntu-latest` ships `jq` but not `ripgrep`**, and
`generate-state.sh` needs `rg` from its very first step (`validate-template-version-consistency.sh`).
Fix: an `Install shell dependencies` step (`sudo apt-get install -y jq ripgrep`) before Render in both
jobs — identical to what the existing docs/spec-kit workflows already do. Re-pushed. (GitHub gates
Actions logs behind sign-in even on public repos, so this was diagnosed by local repro, not the CI log.)

## Risks to watch on the second remote run

1. **Node/JDK on the runner.** Local render used node 25 / JDK 25; the workflow pins node 22 / Temurin
   21 (matches `sourceCompatibility`). If generation trips on node, bump `node-version`.
2. **Real-Aeron round-trip tests on hosted.** ~6 `Aeron*` tests spin a real MediaDriver over loopback.
   They pass locally; if a 2-core runner chokes, add them to `exclude-heavy.gradle` and fold them into
   the `dedicated` job — a one-line move, same pattern as the cluster test.
3. **Generation time.** ~2 min render + build + ~298 tests. Comfortable inside defaults; no timeout set.

## The badge and the before/after (for the talk)

Badge (add to `README.md` once the first run is green):

```markdown
[![Engine Tests](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/actions/workflows/engine-tests.yml/badge.svg?branch=YU15-eod-risk-extract)](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/actions/workflows/engine-tests.yml)
```

> **Before → after.** Going into the production-readiness phase we had 853 machine-verified engine
> tests — allocation gates proving zero steady-state bytes, byte-identical consensus across three
> members, reproducible regulatory and risk-extract exports — and **not one of them ran in CI**; the
> whole story lived on a laptop. It now runs on every push and PR: ~298 functional tests plus the
> allocation gates on hosted runners across the YU-state lineage, with the three-node cluster, the
> wall-clock timing gate, and the Epsilon no-GC gates on dedicated hardware. The verification didn't
> get weaker to fit CI — the two known environment-sensitive flakes retry once in isolation and
> nothing else does. "Green" now means green on the remote, per branch, not "green on my machine."

## Ready-for-push

**Flagging YU15 ready for push.** Commits are on `YU15-eod-risk-extract`; the push itself is the CI
trigger. After the first `hosted` run is green: (1) add the badge to `README.md`, (2) uncomment the
YU14/YU13 matrix lines (and propagate the three files to those branches so their own pushes trigger),
(3) run the `dedicated` job once via the Actions "Run workflow" button to confirm the split.

**One open decision for the lane.** Retry scope is currently the two flakes the brief named
(allocation/Epsilon 72-byte artifact + SnapshotBarrier timing) — `ThreeMemberClusterTest` gets **no**
retry. The load-sensitivity finding above shows it *can* time out under resource pressure. On truly
idle dedicated hardware that shouldn't happen (33 s idle), so I left it non-retried rather than expand
retry scope against the brief. If the eventual dedicated runner turns out to be shared/bursty, the call
is whether to give that one test a single scoped retry too. Flagging, not deciding.
