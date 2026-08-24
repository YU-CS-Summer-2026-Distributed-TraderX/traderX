# The prune fix reached the tip and never came back, and four branches could not compile their tests

**Found 2026-08-23** by the coordinator, while running the composed suite on YU13 as
`propagate-spec-fix`'s forcing function for an unrelated carry. **Fixed the same night on
YU11–YU14; still open for `main`.**

## What was actually wrong

Not "the prune step never runs" — that was the first diagnosis and it was wrong. Commit `a7bd36f2`
(2026-08-03, *"YU11: retire the inherited NATS phase-0 test, and make prune actually run for YU
states"*) **did** fix it. It changed two things together:

- `pipeline/prune-generated-state-removed-assets.sh` — YU ids carry no leading digits, so the
  numeric parse returned null, the script exited 0, and the wrapper printed *"no prune manifests
  apply"*. A parse failure that reads exactly like a legitimate outcome. Now `YU(\d{2})` ranks as
  `100 + N`, above the whole numbered lineage and order-preserving within YU.
- Added `specs/YU11-aeron-replication/generation/prune-manifest.json` — **the only YUxx prune
  manifest that exists in the repo.**

**That commit reached YU15, YU16, YU17 — and nothing else.** Not YU12/13/14, and not YU11, the
branch that *owns* the manifest. `pipeline/` is a per-branch copy, so nothing carries it implicitly.

This is the ancestor-miss shape `propagate-spec-fix` documents: the carry ran *forward* from where it
was authored rather than across every branch holding the pack. The habit runs downward because a
missing spec layer is loud on a descendant; a `pipeline/` miss on an ancestor is silent until someone
runs the thing.

## What it cost

YU11 replaced the nested `ReplicationFollower.AckMode` with a standalone `ReplicationAckMode` and
**correctly** marked the now-stale YU02-layer test for deletion. Without the manifest that test
survives into the composed tree — and because the test is at the **YU02** layer while the rename is
at **YU11**, no overlay can ever fix it. **Deletion is the only mechanism.**

```
> Task :compileTestJava FAILED
NatsReplicationPhase0Test.java:46: error: cannot find symbol
    assertEquals(ReplicationFollower.AckMode.ONRING, ...)
  symbol: variable AckMode   location: class ReplicationFollower
28 errors
```

Not a failing test — **a test tree that cannot be compiled**, so the suite fails before running
anything, including tests unrelated to replication. That is indistinguishable from "the suite is red"
and sends you hunting in the wrong place. It also means the composed suite could not serve as
`propagate-spec-fix`'s forcing function on those branches, which is how it was found.

## Measured, before and after

| branch | before | after |
|---|---|---|
| YU11 | 28 errors | prune line present, `compileTestJava` **exit 0, 0 errors** |
| YU12 | 28 errors | **exit 0, 0 errors** |
| YU13 | 28 errors | **exit 0, 0 errors** |
| YU14 | 28 errors | **exit 0, 0 errors** |

Confirmed pre-existing rather than caused by the carry that found it: with the carried files reverted
and the tree regenerated, YU13 failed **identically**.

Carried in `53e7013e` / `f689058a` / `27f02902` / `83fd5ee4`. Every target was byte-identical to the
pre-fix source beforehand, so the copy carried exactly this change.

## The entry-point trap, which cost an hour and looked exactly like a second bug

**Run `bash pipeline/generate-state.sh <state-id>`, never `bash pipeline/generate-state-<state-id>.sh`.**

The per-state script is an **internal hook**. `generate-state.sh` dispatches to it and *then* runs the
prune and validation tail. Invoking the hook directly renders the layer and silently skips the prune —
which presents as "prune ran for YU02…YU10 but not for YU11", i.e. as a plausible second defect in
which a state's own manifest is never applied. It isn't. The same tree pruned correctly the moment it
was generated through the real entry point:

```
[info] pruned target artifact (nats-replication-phase0-test): .../NatsReplicationPhase0Test.java
[ok] pruned removed artifacts and verified post-prune invariants for YU11-aeron-replication
```

## The full manifest sweep — clean

Every `prune-manifest.json` on every branch. Three exist, no divergence (one hash each across all
branches carrying them), and on a properly generated tree **all three apply completely**:

| manifest | artifact | status on a composed tree |
|---|---|---|
| `004-containerized-compose-runtime` | `legacy-node-edge-proxy` | all paths gone |
| `006-messaging-nats-replacement` | `legacy-trade-feed` | all paths gone |
| `YU11-aeron-replication` | `nats-replication-phase0-test` | gone (after this fix) |

Two things that look like findings and are not, recorded so nobody re-derives them:

- **`forbiddenScriptPatterns` are scoped to the GENERATED tree.** The check scans
  `${TARGET_ROOT}/scripts` plus a handful of generated top-level files — **not** the repo's own
  `scripts/`. The three `start|stop|status-state-002-edge-proxy-generated.sh` in the repo are source
  scripts and correctly out of scope. Scanning wider than the tool produces six false violations.
- **The manifest schema is `removedArtifacts[].{targetPaths, componentsPaths, forbiddenScriptPatterns}`.**
  A naive "collect every string containing a dot" walk misses `006` entirely (its paths have no dots)
  and mistakes `004`'s regex patterns for literal paths. Both errors read as "nothing to prune".

## Still open

**`main` is affected and was not carried.** It has the YU11 generate/render scripts, a
`YU11-aeron-replication` catalog entry, both the rename and the stale test, and **neither** the fixed
prune script nor the manifest. Per `propagate-spec-fix`, main is carried by **PR**, not by copy — so
it is deliberately excluded here and named rather than left silently different. main is also where CI
runs, so if any gate there composes a YU11+ tree and compiles tests, it fails for this reason.

`YU02-lmax-kubernetes-blp-ha`, `YU03`–`YU10` and `YU01` are unaffected: the rename is at YU11, so
earlier branches pair YU02's test with a `ReplicationFollower` that still has the nested enum. YU05
was verified building and running (`AuditLogHandlerTest` 6/6).

## Worth fixing while nearby

The fixed script's comment says *"Rank YU01..YU15 as 101..115"* and *"a manifest declared at YU11
reaches YU11..YU15"*. The **code** is generic (`/^YU(\d{2})/` → `100 + N`, so YU16→116, YU17→117);
only the prose is dated. Harmless today, misleading to the next reader deciding whether the mechanism
covers the tip.
