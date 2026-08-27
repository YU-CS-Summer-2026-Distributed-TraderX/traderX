# The members were left on the STP proof's revert build while everything else moved on

**Found 2026-08-27** during recovery from a host-disk-full event, and **it is independent of that
event** — the rig had been in this state for some time and nothing reported it.

## What was measured

    StatefulSet order-matcher-cluster  ->  traderx/cluster-node:stp-boundary-pre
    cluster-gateway                    ->  traderx/cluster-node:yu17-adr072
    risk-extract                       ->  traderx/cluster-node:yu17-adr072
    feed-adapter                       ->  traderx/cluster-node:yu17-adr072

`stp-boundary-pre` is the **deliberately reverted "before" build** — `scripts/yu15/stp-boundary-revert.patch`
applied to a throwaway copy, built by `scripts/yu15/build-stp-boundary-images.sh` so
`yu13-stp-and-replace` can show its red half against an engine that predates the STP boundary fix.

**It is a build that exists only to fail.** It has no business running as the rig's engine, and it was
running as the rig's engine against a gateway three states newer.

## Why this is worse than a stale pin

**This is a mixed-version cluster**, which is the one configuration this project treats as
unrecoverable: `~/dev/lmax/CLAUDE.md` states that a change to the deterministic core *cannot be rolled
gradually — a mixed-version window diverges members permanently*. The members were adjudicating orders
on pre-STP-fix matching logic while every client of theirs spoke the ADR-072 contract.

**And nothing noticed.** All three members were Ready, consensus was forming, the suite had run. **The
image a member runs is not part of any assertion**, so a rig can be wrong in the single most expensive
way available to it and still present as healthy.

## What is not established

Whether `yu13-stp-and-replace` failed to restore the members, or something else repinned them, was not
determined — the evidence is the end state, not a trace. The proof swaps builds deliberately and in
view (`rebuild_fresh_epoch`'s own comment names its two call sites as the legitimate ones), so it is
the obvious suspect, but **suspicion is not the finding.** The finding is that the rig sat mixed-version
and no check said so.

## Resolved for now, not fixed

The recovery from the disk event wiped the epoch and repinned the members to `yu17-adr072`, so the rig
is consistent today. **The gap is unchanged**: nothing asserts that the members' image matches the
image its clients were built against.

`rebuild_fresh_epoch` already refuses to wipe an epoch *and* change the build in one motion, and that
guard is what surfaced this — it forced the running image to be read before the wipe. **A guard that
made someone look is what found a defect nobody was looking for.** The missing half is the same check
outside a wipe: assert engine and client builds agree, on bring-up and in the suite's preflight.
