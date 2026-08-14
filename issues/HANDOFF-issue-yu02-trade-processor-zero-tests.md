# Issue: YU02's trade-processor `test` task discovers zero tests and fails the build

**Status: RESOLVED.** Fixed by `eb1144f0` — *"YU02 trade-processor: a real unit test, so the unit
tier has something to discover"* — and `3e4f8979`, which took the third and recommended option
below. Verified 2026-08-14 on a generated YU02 tree: `./gradlew test --offline --rerun-tasks` on
trade-processor runs **5 tests, 0 failed**, where this file recorded
`No tests found for given includes`. `TradeServiceBookingTest` carries no `@Tag`, so the
`excludeTags 'integration'` filter leaves it in scope, and both signals the issue wanted preserved
are intact: `failOnNoDiscoveredTests` was not disabled and the integration tag still excludes
`TradeProcessorContextIT`.

**Still unverified:** the original symptom was a publish-walk stop, and the publish walk has not
been re-run. See `HANDOFF-issue-yu-vacuous-pipeline-guards.md` for the separate reason the publisher
still refuses YU states upstream of this point.

Original report follows.

**Status when filed:** open, not fixed. Blocks `publish-generated-state-branch.sh
YU02-lmax-kubernetes` at the compile preflight. Pre-existing; first reachable 2026-08-03, when the
YU publish wiring let the publisher get that far for the first time.
**Related:** `HANDOFF-issue-yu-vacuous-pipeline-guards.md` — same family, opposite sign. That file is
about checks that pass without running; this is a check that *correctly refuses* to pass without
running, and is therefore load-bearing.

---

## Symptom

```
[info] gradle preflight: trade-processor
> Task :test FAILED
Execution failed for task ':test'.
> No tests found for given includes:
```

## Cause

Three facts that only collide on the YU02 generated tree:

1. YU02 contributes exactly one trade-processor test —
   `specs/YU02-lmax-kubernetes/generation/runtime-overrides/trade-processor/src/test/java/finos/traderx/tradeprocessor/TradeProcessorContextIT.java`.
2. That class is annotated `@Tag("integration")` (line 34).
3. The same layer's `build.gradle` gives the `test` task
   `useJUnitPlatform { excludeTags 'integration' }`.

So the only test in scope is excluded by tag, the filter matches nothing, and Gradle 8.14 treats an
empty discovered set as a build failure. The `excludeTestsMatching
'finos.traderx.tradeprocessor.TradeProcessorApplicationTests'` on the adjacent line is a red herring
— no class by that name exists at this layer.

## Why it has never been seen

`YU05-post-trade-compliance` is the first state to add non-integration trade-processor tests
(`TradeServiceIdempotencyTest`, `ReconciliationServiceTest`, and five more). Every YU state from
YU05 up therefore has something for `test` to run, and the same preflight passes: the YU07 publish
run cleared the entire prepublish gate minutes earlier on the same machine. Only YU02, YU03 and YU04
have the empty window — and none of them had ever reached a compile preflight, because
`publish-generated-state-branch.sh` refused every YU state at the `generation.mode` gate until
`8032f629`.

## Not fixed here, and why

The fix is a judgment call, not a mechanical edit, and it lands in a `specs/` layer on branches other
lanes are actively working:

- `failOnNoDiscoveredTests = false` on the `test` task makes it pass, but that is the exact
  "zero tests ran and nothing said so" shape the sibling issue documents. It would silence a real
  signal everywhere else in the module.
- Dropping `excludeTags 'integration'` makes `test` run an integration test, which is what the tag
  exists to prevent.
- Giving trade-processor one genuine unit test at the YU02 layer fixes it honestly and is the only
  option that leaves both signals intact — but it is new test authoring against a contended layer.

The third is the recommendation. Whoever takes it should check YU03 and YU04 at the same time: they
inherit YU02's `build.gradle` and add no trade-processor tests of their own, so they will fail
identically the moment publish reaches their preflight.

## What this does not block

Everything upstream of it is proven. The YU07 run cleared generation for all states, the
branch-consistency bootstrap, compile preflight, license scan, container builds and the full
prepublish gate (`[ok] prepublish generated-state gate passed for YU07-historical-tick-store`). YU07
then stopped only because its parent's generated branch does not exist yet, which is the publisher
working as designed — a generated branch is cut from its parent's. Publishing the lineage is a
topological walk from YU02 up, which is what `publish-generated-state-tree.sh` does; this issue is
the first thing standing in that walk's way.
