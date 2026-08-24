# The retired 64-capacity images can still be rolled onto a widened epoch, and fail as "corrupt"

**Filed 2026-08-22**, while closing
`issues/resolved/the-fixture-seeder-enables-only-equities-and-options.md`. **Open**, low likelihood,
named because the failure it produces is a false accusation and nobody hitting it would look here.

## What

`traderx/cluster-node:yu15-pre` / `:yu15-stp` — and the explicit provenance tags
`:yu15-pre-orig64` / `:yu15-stp-orig64` that now point at the same two images — hold
`MAX_SECURITIES = 64` and `SNAPSHOT_FORMAT = 3` with **no `MIN_READABLE_SNAPSHOT_FORMAT` field at
all**. The suite no longer uses them: `yu13-stp-and-replace` was repinned to the grafted
`:yu15-pre-1k` / `:yu15-stp-1k` pair, which carry 1024.

Rolling one of the originals onto an epoch written by any 1024-capacity build — which is now every
build the suite runs, and the fixture seeder now enables 68 securities on every epoch — fails during
snapshot restore with

```
snapshot corrupt: symbol id 64
```

which is a **false accusation**: the snapshot is intact and the reader is simply too old. Its
service agent dies on all three members while the pods stay `READY`, so the rig reports healthy with
every engine dead. That is the exact failure recorded in `MatchingEngineClusteredService`'s
`SNAPSHOT_FORMAT` comment as the reason format 4 exists.

## Why it is not fixed by bumping the format

The grafted `-1k` pair deliberately still writes format 3 — see the resolved issue for the full
reasoning. Bumping *them* would make an old build refuse at the header instead of failing deep in
record parsing, which is nicer, but it does not remove the hazard: it just changes the error, and it
costs making a currently-tolerant build strict (these builds have no `MIN_READABLE` field, so their
check is a strict equality). The hazard is the **existence of a rollable 64-capacity image**, not
the message it produces.

## What makes it unlikely rather than dead

Nothing pins these tags any more. Both script defaults (`IMAGE_PRE`/`IMAGE_FIX` in
`scripts/proofs/yu13-stp-and-replace.sh`, `STP_IMAGE_PRE` in `scripts/yu15/run-proofs.sh`) name the
`-1k` pair, and both carry a comment saying the originals must not be used. The remaining routes in
are a hand-run `IMAGE_PRE=traderx/cluster-node:yu15-pre bash scripts/proofs/yu13-stp-and-replace.sh`,
or someone reading an older issue or proof log that names the bare tags.

**The hazard is data-dependent, which is what makes it worth a file.** Below 64 registered securities
a widened build's snapshot restores in a 64-build perfectly. So a rollback rehearsed on a quiet rig
succeeds and the identical rollback fails the moment the 65th security exists — and since the seeder
now enables 68 on every epoch, that condition is no longer occasional. It is always true.

## Options

1. **Delete the originals** (`docker rmi traderx/cluster-node:yu15-pre :yu15-stp :yu15-pre-orig64
   :yu15-stp-orig64`). Removes the hazard entirely. Costs the ability to re-derive the graft or to
   show what the pair was before it — which is the reason they were kept.
2. **Leave them and rely on the comments.** Current state.
3. **Bump the `-1k` pair's `SNAPSHOT_FORMAT` to 4** so an original refuses at the header rather than
   mid-parse. Improves the message, does not remove the hazard, and makes the `-1k` pair strict
   about what it can restore.

Not taken unilaterally because deleting a historical artifact is not this session's call.

---

## Resolved 2026-08-22 — a fifth option: drop the BARE tags, keep the provenance ones

**yaakov's decision.** None of the three options above was taken, because the tags turned out to be
two names for the same artifacts:

```
traderx/cluster-node:yu15-pre        9b104d2f479f
traderx/cluster-node:yu15-pre-orig64 9b104d2f479f
traderx/cluster-node:yu15-stp        d5e6be59cd42
traderx/cluster-node:yu15-stp-orig64 d5e6be59cd42
```

So `docker rmi` on the bare tags drops a *name*, not an image. Done:

```
Untagged: traderx/cluster-node:yu15-pre
Untagged: traderx/cluster-node:yu15-stp
```

No `Deleted:` lines — the layers are intact and both artifacts still resolve under
`:yu15-pre-orig64` / `:yu15-stp-orig64`, verified by id after the removal.

**What this buys.** The route this file named — a stale `IMAGE_PRE=traderx/cluster-node:yu15-pre …`
copied out of an older issue or proof log — now fails immediately with *no such image* instead of
silently rolling a 64-capacity build onto a 68-security epoch and dying as `snapshot corrupt: symbol
id 64` with the pods still `READY`. **A loud failure at the point of the mistake replaces a false
accusation three layers downstream.**

**What it does not buy, stated plainly.** The hazard as this file defines it — *the existence of a
rollable 64-capacity image* — is not removed. `:yu15-pre-orig64` can still be rolled. What changed is
that reaching it now requires typing a name that says what it is, which turns an accident into a
deliberate act. Option 1 (delete the artifacts) remains available if that is ever judged
insufficient; the reason it was not taken is that these two images are the only evidence of what the
pair was before the graft, and re-deriving them is not possible from any tree.

Closing on the accidental route being shut, not on the hazard being gone. If anyone ever needs the
originals rolled deliberately, seed fewer than 64 securities first — the failure is data-dependent
and below the cap the restore is clean.
