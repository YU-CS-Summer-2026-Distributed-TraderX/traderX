# Nothing enforces that an epoch bump and a DB wipe happen together

**Filed 2026-08-27**, found by the lane fixing `yu12-gke-cross-epoch-idreuse` while establishing why
that proof does *not* cover the case its name claims. Not attempted on a rig: reproducing it means
minting on a shared cluster, which is exactly the destruction this work spent the day gating.

## The invariant, and the fact that it is not one

Order refs restart at **1** on a fresh cluster incarnation (`MatchingEngineClusteredService:502`,
`:716`), so an id from epoch N collides with the same id from epoch N+1. The read model's defence is
to key on **`epoch + "-" + orderRef`**, and the engine says so plainly at `:1278`:

> *"It is unique WITHIN an epoch, exactly as an orderRef is. **Nothing here makes ids unique ACROSS a
> wiped epoch**."*

`OrderNatsPublisher:20` states the operating rule: the qualifier is *"stable across FAILOVER"* and
*"bumped together with wiping the DB on a fresh incarnation."*

**"Bumped together with" is the whole invariant, and nothing checks it.** Verified 2026-08-27:

```
CLUSTER_EPOCH   System.getenv("CLUSTER_EPOCH")   :656, :675   -- an env var, from the manifest
                snapshot-path hits: 0                          -- not replicated, not snapshotted
```

It is **off-consensus configuration**. The members do not agree it, the log does not carry it, and no
code compares it against the state of the database it is supposed to be paired with.

## The two failure directions, and only one is loud

- **Wipe the PVCs, forget to bump the epoch** → the new incarnation mints `1, 2, 3…` under the *same*
  qualifier as rows already in the DB. This is the 2026-07-22 collision — trade-processor's dedup
  treats a genuinely new trade as one it has already seen and **eats it**. Silent.
- **Bump the epoch, forget to wipe the DB** → no collision; old rows are simply orphaned under a
  qualifier nothing writes to any more. Harmless to correctness, confusing to read.

**Some sinks are already defended and it is worth knowing which**: the risk-extract's write-once sink
*"refuses a colliding key loudly rather than silently mixing two epochs' contracts, which is the same
posture the trade table has"* (`:1278`). **The exposure is the paths that DEDUP rather than refuse** —
a dedup treats a collision as a duplicate and drops it, which is the failure with no message.

## Why no proof covers it

`yu12-gke-cross-epoch-idreuse` is named for this and does not test it: it kills a **leader**, which is
a failover, not a wiped incarnation. Measured across four runs the operator ref counter ran
`9→19→29→30`, monotonic, no reset — correct for a failover and evidence that no mint occurred. Its
assertions were always sound; the **claim wrapped around them** was not, and a reader cites the banner.
That proof has since been corrected to say *failover*, to name what it does not show, and to assert
the epoch qualifier is unchanged across the kill.

**So the gap is a proof that does not exist**, not a proof that is wrong.

## What such a proof has to solve first

**A mint wipes sequenced control state.** Accounts and instruments live in the DB and vanish from the
state machine; the tape replay self-heals its own accounts, and **nothing heals operator accounts
except `seed_fixtures`.** So the first operator order after any mint is `UNKNOWN_ACCOUNT`, and — the
part that makes this an anti-vacuity problem rather than a nuisance — **an operator counter reading 0
in that state is indistinguishable from a twin that is correctly excluding replayed flow.** Any mint
proof must show its own writes landing *before* it reads a zero as evidence of anything.

Related: `specs/YU17-otc-rates/system/adr-072-...md` (a restart and a mint are different events; all
five twins reset at a mint), `issues/resolved/...cross-epoch...` if one exists for the 2026-07-22
incident.
