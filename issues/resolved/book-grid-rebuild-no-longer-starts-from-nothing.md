# The book-grid rebuild no longer starts from nothing — RESOLVED 2026-08-21

> The values below are a record, not a rig you can query. Rig data is rolled deliberately and will
> be rolled again; treat every number here as an example of the shape, and re-derive from the rig in
> front of you before acting on it.

`scripts/proofs/yu16-book-grid.sh` claimed to prove that a wiped member rebuilds and reaches the same
book as its peers. It deleted the victim pod and waited for the StatefulSet to bring it back:

```
${K} delete pod "order-matcher-cluster-${VICTIM}" --wait=true
```

**It did not delete the claim.** The cluster StatefulSet declares a `volumeClaimTemplates` entry, so
the claim outlives the pod. The member came back holding the disk it had a moment ago, read its own
snapshot, and replayed only the tail it missed.

The identity assertion still passed. That was the problem: it passed for a reason weaker than the one
the proof is named after. A from-nothing rebuild and a tail replay are different claims about the
system, and only one of them was being made.

## What changed

Two edits in `scripts/proofs/yu16-book-grid.sh`, both in step 3:

1. **Delete the claim before the pod**, the same order and for the same reason as
   `scripts/proofs/yu17-swap-netting.sh` step 10 — `delete pvc --wait=false` (the `pvc-protection`
   finalizer holds it until the pod is gone), then `delete pod --wait=true`. The message above the
   delete was reworded with it: it read "the claim outlives the pod … replays only the tail", which
   described the old behaviour and would have been a lie about the new one.
2. **Two assertions that can tell a wipe from a tail replay**, taken after the member is Ready:
   the claim's `metadata.uid` must have changed, and the **oldest file on `/data` must postdate the
   wipe**. `WIPE_AT` is read off the member's own clock (`exec … date +%s`) because the mtimes it is
   compared against are written by that clock, not the operator's host. An unreadable disk fails the
   check rather than reading as an empty one.

The identity assertion itself was **not** weakened, retimed, or otherwise touched.

## What was measured (cluster rig `kind-traderx-yu12-cluster`, ns `traderx`, 2026-08-21 21:23–21:33 UTC)

Members `order-matcher-cluster-{0,1,2}`, leader member 0, victim member 1 in every run below.

**The checks discriminate — established before trusting them.** The same harness was run twice
against the same member, differing only in whether the PVC was deleted:

| | claim uid | oldest file on `/data` vs the wipe |
|---|---|---|
| pod delete only (the OLD behaviour) | `df1821bb…` → `df1821bb…` **unchanged** | 2026-08-19 02:55 — **239301s (66h) BEFORE** |
| PVC + pod delete (the fix) | `df1821bb…` → `b05b96f1…` **new** | **+17s AFTER** |

Both readings flip. Neither reads "rebuilt from nothing" under the old behaviour.

**The proof itself, run against the rig — two passes:**

- run 1: claim `b05b96f1…` → `8b6c13a5…` (freshly provisioned, `creationTimestamp` 2026-08-21),
  oldest file on the new disk `+16s` after the wipe; agreed state
  `[-7887530708953065757 5609719231894851530 292 2802]` **identical** across the rebuild; the
  six-decimal UST limit accepted again afterwards.
- run 2: claim `8b6c13a5…` → `75c74fdc…`, oldest file `+15s`; agreed state
  `[7421257962251216113 5609719231894851530 292 2809]` **identical**; six-decimal limit accepted.

In both runs the victim snapshotted before the kill (`snap_count` moved), so the rebuild came through
the `T_SYMBOL` restore branch rather than registration-only replay.

**The new assertions fail on the old behaviour, in the real script.** A copy of the shipped script
with *only* the `delete pvc` line neutered (nothing else changed) was run against the rig and stopped
at the claim-identity assertion:

```
[FAIL] data-order-matcher-cluster-1 still has uid 8b6c13a5-… — the claim survived, so the member
  came back on the same disk and this step measured a tail replay, not a rebuild.
```

So the proof is now falsifiable by the thing it is named after.

## Worth knowing: Ready precedes caught-up by ~90s on a from-nothing rebuild

Observed while validating, not asserted by the proof. After the wiped member reached
`condition=Ready`, it lagged its peers for about 90 seconds — `traderx_book_order_hash`
`4270217215369126872` vs `5569531318944588109`, `next_order_ref` 2795 vs 2799 — then matched exactly.
`identity_consensus()`'s poll budget (60 rounds, ≥2s each) absorbs this comfortably, which is why
both runs passed, but **a rebuild check that reads the hash once at Ready would report a false
divergence.** A tail replay closes the gap fast enough to hide this; the wipe does not. Related in
spirit to `scripts/proofs/yu16-ready-tracks-commit.sh`; not investigated further here.

## What this run did NOT establish

- Only member **1** was wiped, and only as a **follower**. A leader wipe, or a wipe of more than one
  member, was not exercised — the three-member set is what the identity assertion compares against.
- The rebuilt member's recovery path was established **negatively** (nothing on its disk predates the
  wipe, so there was no own state to resume) rather than by reading a restore-from-snapshot log line.
  The member emits none — see the "WHICH PATH RAN" comment already in the script.
- Nothing was propagated to other branches; propagation is held by the coordinator.

## Why it drifted rather than broke

The proof was written when the member was `emptyDir`-backed, where deleting the pod *was* deleting
the state. The backing changed under it. Nothing failed, because the assertion the proof makes is
still true — it simply stopped being evidence for the sentence above it.

## Provenance

Found while fixing `hints-that-name-the-wrong-tier` (resolved 2026-08-21) — the wording fix on the
same line surfaced the behavioural one underneath it.
