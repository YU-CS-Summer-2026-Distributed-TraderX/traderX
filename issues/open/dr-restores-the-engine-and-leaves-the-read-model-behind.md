# DR restores the engine and leaves the read model behind — knowingly, and nothing says so outside one proof

**Status:** open — a SYSTEM gap, not a proof defect
**Surfaced:** 2026-08-27, auditing `yu12-gke-restore-from-gcs` for unnamed limits
**Never exercised anywhere.** See "why nobody has hit it" below.

## The gap

The DR path destroys the whole cluster and restores member-0 from `gs://`, rebuilding 1 and 2 from
nothing. **`trade-processor`'s database is not restored with it.** The proof says so itself:

    :53   WRONG INSTRUMENT for this proof specifically: trade-processor's database is NOT restored
    :342  NOTHING about the read model after DR. trade-processor's database is not restored
    :344  Engine and read model are knowingly divergent at this point

So after a successful DR restore:

- the **engine** resumes from the snapshot and has dropped every order after the snapshot offset;
- the **read model** still holds those orders, showing them **open**;
- **both components are behaving correctly**, and nothing reconciles them.

A client querying `/accounts/{id}/orders` after DR is told about resting orders the venue does not
have. There is no error, no log line, and no counter that disagrees — the read model is a projection
and it is faithfully projecting a log the engine no longer shares.

## Why nobody has hit it

`yu12-gke-restore-from-gcs` is **the only one of the five GKE proofs never run anywhere**, and it now
has two things stacked behind it:

1. **`yu12-snapshot-backup` is not deployed** on the bench cluster (checked 2026-08-27), and the path
   also needs the GCS HMAC secret and bucket from the 2026-07-19 drill.
2. **This divergence**, which a real DR run would expose for the first time.

The proof refuses at step 0 naming the missing CronJob as a **missing prerequisite, not a restore
failure** — correct, and it means the gap stays invisible until someone stands the backup path up.

## Why this is a system question rather than a proof one

The proof's job is to show the engine recovers, and it does that. **Whether the read model is expected
to be reconciled after DR is a design decision nobody has recorded**, and the three plausible answers
are all real work:

- **Rebuild the projection from the restored log** — correct, and the most expensive.
- **Wipe the projection at restore** and let it re-derive — cheap, loses history the log no longer has.
- **Declare the divergence acceptable** and document the window — free, and then the client-facing
  contract has to say so.

**The trap is that doing nothing looks identical to the third option** while nobody has agreed to it.

## What is already true and worth not re-deriving

- The engine's counters ARE snapshotted (`externalOrderRefs` offset 52, `externalTradeLegs` offset 60 —
  `MatchingEngineClusteredService:1499-1500` writer, `:1631-1632` reader), which is what lets the proof
  make a scoped equality claim across the destroy-and-restore at all.
- **The read model is the wrong instrument for that proof** specifically, and the proof says so.
  This issue is not an argument for using it there — it is about the state the system is left in.

Related: [`nothing-enforces-that-an-epoch-bump-and-a-db-wipe-happen-together`](nothing-enforces-that-an-epoch-bump-and-a-db-wipe-happen-together.md)
— same seam, opposite direction, and its sharpening applies here too: **only one direction is silent.**
