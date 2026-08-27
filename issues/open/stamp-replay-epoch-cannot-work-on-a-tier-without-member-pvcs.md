# `stamp_replay_epoch` cannot work on a tier whose members have no PVC — and it says so by succeeding

**Status:** open
**Measured:** 2026-08-27, GKE bench tier (`traderx-bench`, project `traderx-505400`)

`scripts/yu15/lib-replay-epoch.sh`'s `stamp_replay_epoch` derives the replay anchor from the
**member-0 PersistentVolumeClaim's `creationTimestamp`**:

    ts="$(_rk get pvc data-order-matcher-cluster-0 -o jsonpath='{.metadata.creationTimestamp}')"
    if [[ -z "${ts}" ]]; then
      echo "[epoch] no member-0 PVC to derive the replay epoch from; leaving replay-epoch unstamped"
      return 0                      # <-- deliberate, for a tier with no EOD chain
    fi

**The GKE tier runs its members on an `emptyDir`, not a PVC.** `bring-up-gke.sh` says so in its own
header. So on that tier the function **always** takes the no-PVC path, prints its message, and
**returns 0**.

## Why this is the stale-anchor failure in a new place

Replay position is derived, never stored: `(now - epochStart) x compression`. An unstamped or stale
anchor means the tape reports a **plausible wrong day** — every price real, every `asOf`
self-consistent, nothing logging an error. It is the failure that has no failure mode, and it cost a
morning already on the kind rig (a hand mint that skipped the stamp served **tape day 23 on a rig
minutes old**).

**The `return 0` is correct for its stated purpose** — a tier with no EOD chain is not a failure — and
it is exactly what makes this silent for a tier that *does* want a tape.

## What to do

1. **Fall back to an anchor that exists on every tier** when the member-0 PVC does not: the member-0
   pod's `status.startTime`, or `now` on a freshly minted epoch. Both are correct for a fresh epoch,
   which is the only state in which stamping is meaningful.
2. **Distinguish "no EOD chain here" from "I could not derive an anchor on a tier that has a tape."**
   The first is a legitimate no-op; the second is a defect and should be loud.
3. **Confirm with `taqReplay.position.dayIndex`, never the exit status.** An exit status can only tell
   you the function ran, never that it did the thing it names — and this function has a legitimate
   no-op path, which is precisely when its status stops being evidence.

**Worked around on 2026-08-27** by creating the `replay-epoch` ConfigMap directly from the current
clock. That is correct for a fresh epoch but is not in any script, so the next person to roll that tier
will hit the silent path again.
