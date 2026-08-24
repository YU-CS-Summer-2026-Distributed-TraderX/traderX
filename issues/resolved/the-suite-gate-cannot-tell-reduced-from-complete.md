# The suite gate cannot tell a reduced run from a complete one

> A record, not a rig you can query. Counts come from the day this was found; re-derive them.

Found 2026-08-21 by the algo-engine lane, while auditing its own session. It caught the case in
flight and re-ran; the gate would not have caught it.

## What the gate asserts

`scripts/ci/assert-suites-executed.sh` walks every module with test sources, globs
`{module}/build/test-results/{test,integrationTest}/*.xml`, sums the `tests` attribute, and fails
the module when the total is **zero**:

```
if [[ "${ran}" -eq 0 ]]; then
  echo "::error::${module} has test sources but EXECUTED NO TESTS ..."
```

That is a good guard and it has earned its keep — it is what catches a NO-SOURCE `sourceSets`, an
uncompilable test tree, or a module no list invokes.

## What it cannot see

**It reads a directory, and any filtered run rewrites that directory.** A `cleanTest test --tests
'*Something*'` deletes the module's existing XMLs and leaves only the filtered ones. The gate then
sums those, gets a small non-zero number, and **passes** — reporting a module's suite as executed
when almost all of it was deleted moments earlier.

The failure is not that the gate goes quiet. **The gate still answers, confidently, with a number
that is now wrong in the direction of confidence.** A lane that runs a focused probe and then reports
"N tests / M modules" is reporting a corrupted count, and nothing in the output says so.

Zero it catches. **Reduced it cannot.** Those are different failures and only one is guarded.

## Why this bites here specifically

Suite counts are load-bearing in this project. Lanes report them to the coordinator as evidence, the
coordinator banks them, and they are compared across sessions to attribute deltas — on one day a
figure was only trustworthy because two lanes ran it independently from opposite sides of a
contamination and agreed. A silently-reduced count entering that chain is worse than a missing one.

## Directions

1. **Freshness.** Require the XMLs to postdate the run that is being asserted about — a partial run
   leaves a directory whose mtimes are uniform and recent, but whose *population* shrank.
2. **A floor per module**, recorded and updated deliberately. Turns "reduced" into a loud failure at
   the cost of a number that must be maintained — and a stale floor is its own trap.
3. **Detect the filtered run instead of the artifact.** Refuse to read results at all if the last
   invocation carried `--tests`. Narrow, and it puts the check where the cause is.
4. **Accept it, and make the discipline explicit** in the gate's own output: print the per-module
   counts it summed, so a human reporting them sees `1` where they expected `46`.

Direction 4 is the cheapest and is not nothing — the count is currently summed and discarded, so
today the gate knows the answer and does not say it.

**Break it first, whichever is taken:** run a filtered subset, then assert the gate fails. A check
that passes on both a complete and a reduced run is asserting nothing — the exact shape this file is
about. See `.claude/skills/vacuous-pass-audit`.

## Not established

Whether CI has ever actually reported a reduced count as complete. The mechanism is confirmed by
reading the gate; no historical run was audited for it. Nobody has looked at whether the same
directory-globbing pattern backs any other gate in `scripts/ci/`.

---

## RESOLVED 2026-08-24 — the gate asserts the CLASS SET, not a count

`scripts/ci/assert-suites-executed.sh` now requires that **every test class in a module's sources
produced a result XML**. The zero-check it already had is kept; the new assertion sits beside it.

### Why a set and not a number

Every count-shaped option in "Directions" above fails the same way: it is a number a human must
maintain, it moves on every added `@Test` **method**, and it is therefore stale within a week — at
which point somebody deletes the gate rather than the staleness. A per-module floor, a ratchet, and
a pinned expected count are all this shape.

The class set is derived from the tree on **both** sides — sources on one, result XMLs on the other
— so adding or deleting a test class costs this file nothing. It moves only when a test *file*
appears or disappears, which is roughly an order of magnitude rarer than a method changing, and it
is not a number anybody has to keep in their head. Only a class that deliberately never runs costs
an entry, and an entry has to name the mechanism that excludes it.

### What it now asserts

1. a module with test sources executed >0 tests — unchanged; still the NO-SOURCE / uncompilable /
   no-list-invokes catch, and still the reason this file exists;
2. every `*Test | *Tests | *IT` source class produced a result *somewhere*;
3. results are read from **every** tier (`build/test-results/*/`), not just `{test,integrationTest}`;
4. a standing exemption naming a class the tree no longer has **fails** the build;
5. the summary line on a red run reads `INCOMPLETE: … do NOT report this as a suite count`;
6. the reader's own output is shape-tested, so a crashed reader cannot read as zero (bash `-eq`
   evaluates `""` as `0`, which is how "unread" becomes "ran nothing" becomes a number).

**Point 3 closed a hole nobody had filed.** order-matcher runs its four isolated allocation gates as
their own Test tasks, which write to `build/test-results/allocationGateTest/` and siblings. The
two-tier glob never looked there, so `AllocationGateTest`, `AeronTransportAllocationGateTest` and
`ClusterServiceAllocationGateTest` were invisible to the one gate whose founding story is YU01's
allocation gates silently not existing.

### The legitimately-reduced runs, and which is which

There are three, and they are handled three different ways on purpose:

- **`@Tag("integration")` classes** — `EodSnapshotAndPnlIT`, `EodStreamRepairIT`,
  `TradeProcessorContextIT`. CI runs `cleanTest test`; `service-tests.sh` never invokes the
  `integrationTest` task, so these legitimately produce nothing. Handled as a **conditional**
  exemption: such a class is exempt only while the module's `integrationTest` tier produced nothing
  *at all*, and becomes required the moment that tier is invoked. Derived, so a new integration test
  costs the gate nothing — this is the highest-churn family, which is why it is not a list.
- **Hosted-hostile classes** — `ThreeMemberClusterTest`, `SnapshotBarrierPerformanceTest`, excluded
  by `.github/ci/exclude-heavy.gradle`, run on the dedicated leg. These are the only two standing
  `EXEMPT_CLASSES` entries. Declared rather than parsed out of the init script: that file is
  leg-specific and lives outside the rendered tree the gate reads, and adding a permanent exclusion
  should be a deliberate two-file act. The cost is real and accepted — a third exclusion added to
  the init script reds CI until someone adds it here, and the error message says exactly that.
- **`engine-tests.sh dedicated`** — runs only those two classes plus the Epsilon gates. Running the
  gate after a dedicated-only run in a clean workspace goes **red, correctly**: that workspace did
  not run the suite. The workflow only invokes the gate after the hosted leg.

Two things that were assumed to need exemptions and, measured, do not: a class-level
`@EnabledIfSystemProperty` (`ReplicationThroughputBenchmarkTest`) still emits XML with
`tests="1" skipped="1"`, so no annotation heuristic is needed; and
`TradeProcessorApplicationTests` / `TradeServiceApplicationTests` sit at `src/main/test/java`, which
the source walk never sees.

### Broken first, on real artifacts

`cleanTest test --tests '*TwapScheduleBuilderTest*'` against `execution-algo-engine` — a genuine
filtered gradle run, not a simulation — reports `BUILD SUCCESSFUL` and takes the module's result
directory from 8 XMLs to 1.

```
OLD gate:  execution-algo-engine: 5 tests executed
           ==== 551 tests executed across 6 module(s) with test sources ====   rc=0
NEW gate:  ::error::execution-algo-engine executed a REDUCED suite: 5 tests from 1 class(es), but
           these test classes produced no result at all — AlgoEventStoreReplayTest
           AlgoOrderServiceTest AlgoOrderStateTest DuckDbVolumeProfileSourceTest
           OrderUpdateSubscriberTest SyntheticVolumeProfileSourceTest VwapScheduleBuilderTest
                                                                                 rc=1
```

That is this file's claim reproduced end to end: the old gate does not go quiet, it answers `551`
with the module silently down from 48 to 5.

Full run, same tree, after restoring: **598 tests / 6 modules, rc=0**. order-matcher's 85 executed
classes is exactly 87 sources − 2 exemptions.

`--selftest` went from 3 cases to **7**, all passing: `good`, `bad` (a report that exists and says
`tests="0"`), `notests`, **`reduced`** (the detonator, now permanent in the repo), `integ-skipped` /
`integ-required` (a two-arm harness — same subject, only the presence of an `integrationTest` tier
differing — so the conditional exemption cannot silently degrade into an unconditional one), and
`stale-exempt`. The selftest caught a real bug in the first draft of this change.

### What this still cannot tell apart

- **A suite that lost METHODS.** The assertion is over classes, so `--tests 'Foo.someMethod'`, or a
  class whose `@Test` methods were deleted, still emits that class's XML and passes. Method-level
  reduction is not guarded.
- **A STALE tier.** There is no reference clock. `cleanTest` cleans only the `test` task's output,
  so an `allocationGateTest` directory left by an earlier build satisfies the class requirement
  without that gate having run in *this* build.

Both are direction 1 (freshness), and it is **not implemented** — deliberately. It needs a
trustworthy "when did the run being asserted about start", which this script is not given, and
inferring it from mtimes is arbitrary. Whoever adds it should pass that timestamp in rather than
infer it. The gate's header says so at the point of use.

### "Not established", now established

- **The same pattern does back another gate, and it is the same defect at three different
  strengths.** Filed as its own issue —
  `issues/open/three-ci-scripts-assert-execution-at-three-different-strengths.md`. In short:

  | script | asserts | blind to |
  |---|---|---|
  | `assert-suites-executed.sh` | summed count, zero-check | reduced runs — **fixed here** |
  | `service-tests.sh:73` | counts result **files**, zero-check, `test` tier only | reduced runs, `tests="0"`, every non-default tier |
  | `baseline-tests.sh` | nothing — gradle exit code only | all of it |

  `service-tests.sh` is the weakest of the three, not the middle: counting files cannot catch the
  `tests="0"` report at all, and globbing `build/test-results/test` alone makes it blind to the
  allocation-gate tiers in exactly the way point 3 above closes for this gate.
- **Whether CI ever actually reported a reduced count as complete is still unknown.** No historical
  run was audited. The mechanism is now confirmed by execution rather than by reading, which is a
  different and stronger claim than this file could make when it was written, but it is still not a
  claim about history.

### One correction to this file as written

Direction 4 said the per-module count is "summed and discarded, so today the gate knows the answer
and does not say it." That was already false when it was written: the shipped gate printed
`${module}: ${ran} tests executed` per module. What was missing was per-**class** visibility, not
per-module. Direction 4 is therefore not "the cheapest thing not yet done" — it was done.

### Carry

Confirmed by hash sweep rather than by the worktree table: this script exists on exactly **YU15,
YU16 and YU17**, byte-identical at `043e9c6f036a`. All fifteen other worktrees return the empty-blob
sha — **`main` included**, and `main` is not a propagation target regardless. YU13/YU14 are in the
CI matrix but do not carry the script, so the workflow's gate step is a no-op on their legs today;
unchanged by this work.

---

### Forward pointer (2026-08-24, same day): RESOLVED here does not mean the gate was sound

This issue closed the reduced-run hole and the result-tier hole. Hours later a **third hole of the
same shape** was found in the same script: source discovery looked at `src/test` /
`src/integrationTest` only, ignoring the module's own `sourceSets { test { java.srcDirs = … } }`, so
the gate was silently blind to `generated/…/account-service` — eight test classes, no `src/test` at
all, never mentioned in the gate's output. Fixed under
`issues/resolved/three-ci-scripts-assert-execution-at-three-different-strengths.md`, which is the
current home for this script's state. Read that one before trusting this one's "RESOLVED".

Two corrections to the Carry section above while it is being read:

- The workflow's gate step is **not** "a no-op on their legs" — `bash` on a path that does not exist
  exits 127, so those YU13/YU14 matrix legs fail that step rather than skipping it.
- YU13 and YU14 now carry `assert-suites-executed.sh`, added by the issue named above, because their
  `service-tests.sh` calls it.
