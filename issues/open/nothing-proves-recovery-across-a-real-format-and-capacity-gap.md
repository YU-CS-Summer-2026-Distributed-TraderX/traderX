# Nothing proves the cluster recovers an epoch across a real format-and-capacity gap

**Filed 2026-08-23**, on resolving
`issues/resolved/the-stp-proof-crosses-a-boundary-frozen-in-july.md`. This is the coverage that
resolution deliberately gave up, recorded separately so it does not close along with it.

## What was covered until 2026-08-23, and by accident

`scripts/proofs/yu13-stp-and-replace.sh` rolled the members from `traderx/cluster-node:yu15-pre-1k`
to `:yu15-stp-1k` with their PVCs intact. Both were built 2026-07-22. By the time they were
retired, the gap between those builds and the tip was **four snapshot formats** (3 -> 7) and a
**16x capacity change** (`MAX_SECURITIES` 64 -> 1024, and only because the July binaries had 1024
grafted into them by hand — the originals are still 64).

The proof never claimed to test that gap. It claimed to test self-trade prevention and atomic
replace. But every run also dragged the deterministic core across a genuinely wide version
boundary and required it to come back with its epoch — and that incidental exercise is where three
separate findings actually came from:

- rolling the core with PVCs intact **diverges the members permanently** without a snapshot
  barrier first (measured 2026-08-02: m2 at trades=6/open=1 against m0/m1 at trades=8/open=0, and
  it survived the roll back);
- a too-old reader in front of a newer snapshot **fail-closes with the pods still READY**, so the
  rig looks healthy with every engine dead (2026-08-05, `snapshot corrupt: symbol id 64`);
- `kubectl rollout status` returns while a member is **still on the old image**, which for a
  deterministic core is a permanent divergence window and not a cosmetic race (2026-07-22).

None of those is a statement about STP. All three are statements about upgrading this system.

## What replaced it, and what it cannot say

The pair is now synthesized from the current tree by
`scripts/yu15/build-stp-boundary-images.sh`: `fix` is today's engine, `pre` is today's engine with
`scripts/yu15/stp-boundary-revert.patch` applied. That is a strictly better apparatus for the
proof's own claim — reproducible, rebuildable by a fresh clone, and it tracks the system instead of
receding from it a week per week.

It is also, by construction, a **zero-width** format-and-capacity boundary. Both sides write
`SNAPSHOT_FORMAT` 7 and hold `MAX_SECURITIES` 1024, so the roll is 7 -> 7 across an identical
symbol table. The behavioural boundary is crossed; the *structural* one is not crossed at all.

**Nothing else in the suite covers it.** Every other proof runs on a fresh epoch or on the tip's
own build. `rebuild_fresh_epoch` — the only supported way to move the engine build — wipes the PVCs
first, which is precisely the thing a real upgrade does not get to do.

## Why this is not "fine because nobody upgrades that far"

It is the opposite. A long-delayed upgrade is exactly when the gap is widest, and it is the case
where wiping the epoch is least acceptable. The three findings above are all *silent* failures:
divergence with three READY members, a dead engine behind a healthy-looking rig, a rollout the
controller reports complete. A regression into any of them would not announce itself.

## What a proof of it would need — and the property that actually matters

Two builds spanning a real format change and a real capacity change, rolled PVCs-intact, asserting
that the epoch survives and the three members still agree.

The hard part is not the roll. It is the artifacts: **they must be built on purpose with their
commit recorded**, which is exactly what the July pair was not, and pinning to unrebuildable
binaries is the fault this whole issue arose from. Do not solve it by finding two more old images.
Plausible shapes, unexplored:

- keep a **provenance-recorded archive** build per format bump — a tag plus the commit, the spec
  layer and the `SNAPSHOT_FORMAT` it writes, recorded at the moment the format changes, when that
  information is free. The format bumps are already deliberate acts in
  `MatchingEngineClusteredService`; nothing captures a build at them today.
- or synthesize the structural gap the same way the behavioural one is now synthesized: a patch
  that lowers `SNAPSHOT_FORMAT` and `MAX_SECURITIES` on the `pre` side. Cheaper and rebuildable,
  but it proves the reader tolerates a *synthetic* old format, not the one actually shipped — the
  same weakening this issue exists to record, applied one level down.

Not started. Not scoped. **Do not fold this back into the STP proof** — that proof's boundary is
now behavioural on purpose, and widening it again would re-create the problem that was just fixed.
