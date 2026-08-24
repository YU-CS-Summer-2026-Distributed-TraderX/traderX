# The prune step that never runs leaves a stale test that breaks the composed tree

**Found 2026-08-23** by the coordinator, while running the composed suite on YU13 as the forcing
function for an unrelated carry (`propagate-spec-fix` §"force the exercise"). The carry was clean;
this was already there.

Generation's **prune step has never run for the YU-prefixed states** — the id is parsed as a number,
a non-numeric `YUxx` yields null, and the step exits 0 with a reassuring log line. That was known.
What was missing is a measured consequence, because "a deletion did not happen" sounds harmless.

## The consequence, measured

`generated/code/target-generated/order-matcher` **does not compile its test sources on YU13**:

```
> Task :compileTestJava FAILED
NatsReplicationPhase0Test.java:46: error: cannot find symbol
    assertEquals(ReplicationFollower.AckMode.ONRING, ReplicationFollower.AckMode.parse(null));
  symbol: variable AckMode   location: class ReplicationFollower
28 errors
```

The mechanism is exactly the one the prune manifest exists to prevent:

- **YU11** replaced the nested `ReplicationFollower.AckMode` with a standalone `ReplicationAckMode`
  enum (`026bb9ba`, "YU11: add Aeron replication transport core").
- YU11 **correctly** listed the now-stale test for deletion — it is the **only** entry in
  `specs/YU11-aeron-replication/generation/prune-manifest.json`.
- The test itself lives at the **YU02** layer, which YU11 does not and cannot overwrite: the two
  files are in different layers, so an overlay can never fix this. **Deletion is the only mechanism,
  and deletion is the step that does not run.**
- So every branch from YU11 up composes YU11's refactored main class with YU02's stale test, and the
  test tree cannot build.

Verified pre-existing, not caused by the carry that found it: with the carried files reverted to
their pre-change versions and the tree regenerated, the build fails **identically** — 28 errors, all
`ReplicationFollower.AckMode`.

## Why this is worse than a broken test

**It is not a failing test. It is a test tree that cannot be compiled**, so `./gradlew test` on the
composed tree fails before running anything — including tests that have nothing to do with
replication. Any suite invoked against a composed YU11–YU16 tree reports a build failure rather than
a result, which is indistinguishable from "the suite is red" and invites a hunt in the wrong place.
It also means **the composed suite cannot be used as the forcing function** `propagate-spec-fix`
prescribes for carries onto those branches, which is how it was found.

## What is NOT established

- **The tip may be unaffected.** `NatsReplicationPhase0Test.java` is absent from YU17's generated
  tree — either pruned there, or that tree was composed by a different path. Not chased.
- **YU05 is fine**: its composed tree builds and runs (`AuditLogHandlerTest` 6/6, 0 failures). The
  break begins at YU11, where the rename happened.
- Which of YU11/12/13/14/15/16 are individually affected was inferred from the layer layout (the
  test is at YU02 on all of them and `ReplicationAckMode` at YU11), **measured only on YU13**.
- Whether anything in CI or `run-proofs.sh` actually builds a composed test tree on these branches.
  If nothing does, this is latent rather than active — but latent is exactly why it survived.

## The cheap fix, and the real one

**Cheap:** delete `NatsReplicationPhase0Test.java` from the YU02 layer on YU11 and up, by hand, on
each branch. Restores the composed build immediately. Does nothing for the next manifest entry
anyone writes.

**Real:** make the prune step run for `YUxx` ids. It is fixed on YU01 only; propagation was
deliberately deferred. This issue is the argument for undeferring it — the manifests are being
written correctly and silently ignored, so the project is accruing prune debt it believes it has
already paid.

**Before fixing, check what else is being ignored**: sweep every `specs/*/generation/prune-manifest.json`
across every branch and confirm which listed paths still exist in a composed tree. YU11's manifest has
exactly one entry and that entry breaks the build; nobody has checked the others.
