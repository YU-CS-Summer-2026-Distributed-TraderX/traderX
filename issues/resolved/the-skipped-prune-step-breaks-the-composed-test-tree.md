# The prune fix reached the tip and never came back, and four branches could not compile their tests

**Found 2026-08-23** by the coordinator, while running the composed suite on YU13 as
`propagate-spec-fix`'s forcing function for an unrelated carry. **Fixed the same night on
YU11–YU14. `main` carried 2026-08-24 in `4be601de` — RESOLVED. See *Resolution* below,
which also corrects three things this issue got wrong.**

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

## Done while nearby

The fixed script carried a dated comment — *"Rank YU01..YU15 as 101..115"*, *"a manifest declared at
YU11 reaches YU11..YU15"* — while the code is generic (`/^YU(\d{2})/` → `100 + nn`, so YU16→116,
YU17→117). Rewritten to state the arithmetic rather than the range that happened to exist, and
carried to all **7** branches holding the fixed script (`76bc6be6` … `ce02c790`). Comment-only, and
prune was re-run afterwards rather than assumed: the block sits inside a command substitution where
the file itself warns that a lone apostrophe breaks the shell parse.

## Resolution — `main`, 2026-08-24, `4be601de`

`main` is now carried and the issue is closed. The fix is on **every branch that needs it**: the
seven that already had it (YU11–YU17) plus `main`. Nine branches still run the unfixed script and
are deliberately excluded — see *What is still not swept*, which is a separate, scoped task and not
a residual of this one.

Two files, copied from the tip worktree rather than cherry-picked from `a7bd36f2`, because `main` is
reached by PR per `propagate-spec-fix` and because the tip carries the later comment rewrite:

- `pipeline/prune-generated-state-removed-assets.sh` (+21/−1)
- `specs/YU11-aeron-replication/generation/prune-manifest.json` (new)

Scope verified by hash, in both directions: before the carry `main`'s prune script was byte-identical
to the unfixed YU02–YU10 copies; after it, byte-identical to the seven branches that already had the
fix. So the copy moved exactly this change and nothing else.

Verified through `pipeline/generate-state.sh`, the real entry point, in both directions:

| state on `main` | before | after |
|---|---|---|
| YU11-aeron-replication | `no prune manifests apply`; `compileTestJava` FAILED, `cannot find symbol: variable AckMode` | `pruned target artifact (nats-replication-phase0-test)`; **exit 0, 0 errors** |
| YU15-eod-risk-extract (covers YU12–YU15 in one composed tree) | — | prune line at the YU11 layer and no earlier one; **exit 0, 0 errors** |

All seven root gates pass, as does `npm --prefix website run build`. `git status` was clean of
anything but the two carried paths before and after every gate — `run-all-conformance-packs` did not
rewrite the 001 packs on this run.

## Three things this issue asserted that did not survive re-checking

1. **"28 errors" is not a signature.** The same break on `main` produces **14** errors — same file,
   same symbol, same root cause, different count under a different layer composition. The table
   above is per-branch. Match on `cannot find symbol: variable AckMode / location: class
   ReplicationFollower`, never on a count.

2. **"YU02–YU10 are unaffected" overstates it.** True for the *compile* break — the rename is at
   YU11, so those branches pair YU02's test with a `ReplicationFollower` that still has the nested
   enum. But the parse failure disabled prune for **every** YU layer, so the `004` and `006` ancestor
   manifests were skipped there too: a pre-fix log reads `[info] no prune manifests apply` for
   YU02 → YU11 without exception. The artifacts themselves are fine (`legacy-node-edge-proxy` and
   `legacy-trade-feed` are already gone by the time the YU layers render, pruned at the 004/006
   layers of the same cumulative run). What those branches actually lose is the **post-prune
   invariant verification at each YU layer**, including the `forbiddenScriptPatterns` scan — a check
   that has never once run on them.

3. **YU01 is not one of the unfixed branches.** It is a **third, divergent** copy of the prune
   script — neither the fixed one nor the byte-identical unfixed one. `a7bd36f2`'s message records
   why: YU01 solves the same parse with a catalog walk to the nearest numbered ancestor. Anyone
   sweeping the unfixed branches by verbatim copy must exclude YU01 or they will destroy that.
   Per `propagate-spec-fix`'s decision table: nine branches are `target == pre-change source`, so a
   verbatim copy is safe; YU01 is `target != source`, so it is a hand-merge.

## What is still not swept, and the one measurement that scopes it

**Nine branches** — YU02–YU10 — still carry the byte-identical unfixed script, plus **YU01** as the
divergent hand-merge case. Not swept here, deliberately: per correction 2 the fix does not merely
enable pruning, it switches on invariant verification that has never run on those branches, and
turning on a check that has never run is not a no-op.

That risk was measured rather than left as a guess. The fixed script was copied into the **YU10**
worktree (working tree only, never committed, reverted afterwards and the original hash re-confirmed)
and `generate-state.sh YU10-fix-ingress` was run:

```
[ok] pruned removed artifacts and verified post-prune invariants for YU02-lmax-kubernetes
...
[ok] pruned removed artifacts and verified post-prune invariants for YU10-fix-ingress
GEN_EXIT=0
```

Every YU layer from YU02 to YU10 ran the previously-skipped invariant verification and **passed**,
generation exit 0. YU10 is the deepest of the nine, so its composed tree exercises all of their
layers at once. **The sweep is nine verbatim copies with no door to open behind it**, not an
unknown-size investigation. It still wants its own task, because each branch should be regenerated
to clear rather than assumed.

## Found while landing this

`main` cannot generate YU16 or YU17 at all — pre-existing, unrelated, and proved so with a stash/pop
control. Filed separately as
[main-presents-31-states-and-can-only-compose-up-to-yu15](../open/main-presents-31-states-and-can-only-compose-up-to-yu15.md).
It is why the composed-tree forcing function above tops out at YU15 on `main` rather than at the tip
state.
